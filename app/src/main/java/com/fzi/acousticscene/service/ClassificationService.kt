package com.fzi.acousticscene.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.fzi.acousticscene.data.RecordingEngineHolder
import com.fzi.acousticscene.ui.MainActivity
import com.fzi.acousticscene.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground Service für Hintergrund-Klassifikation
 *
 * Ermöglicht kontinuierliche Audio-Klassifikation auch wenn die App
 * im Hintergrund läuft oder der Bildschirm ausgeschaltet ist.
 *
 * WICHTIG: Dieser Service hält einen WakeLock, um sicherzustellen,
 * dass die CPU aktiv bleibt und die Aufnahme nicht unterbrochen wird.
 * Dies ist essentiell für lückenlose Datenerfassung!
 */
class ClassificationService : Service() {

    companion object {
        private const val TAG = "ClassificationService"
        private const val CHANNEL_ID = "classification_channel"
        private const val NOTIFICATION_ID = 1
        private const val WAKELOCK_TAG = "AcousticScene::ClassificationWakeLock"

        // Alarm-Intervall für periodische Aufweckung (alle 30 Sekunden)
        // Dies ist ein Backup falls der WakeLock vom System ignoriert wird
        private const val ALARM_INTERVAL_MS = 30 * 1000L
        private const val ACTION_KEEP_ALIVE = "com.fzi.acousticscene.KEEP_ALIVE"

        // WakeLock-Timeout. Drei mal so groß wie das Alarm-Intervall — so refresht
        // der Keep-Alive-Alarm den Lock zuverlässig, und falls der Service hart
        // gekillt wird, gibt Android den Lock spätestens nach diesem Fenster frei.
        // Früher waren das vier Stunden, was bei einem Crash zu massivem Akku-Drain
        // führen konnte.
        private const val WAKELOCK_TIMEOUT_MS = 90 * 1000L

        const val ACTION_START = "com.fzi.acousticscene.START_CLASSIFICATION"
        const val ACTION_STOP = "com.fzi.acousticscene.STOP_CLASSIFICATION"
        // Tells MainActivity that the notification tap should land on the live
        // recording screen (re-attach), not the nav start destination.
        const val EXTRA_OPEN_LIVE = "com.fzi.acousticscene.OPEN_LIVE"
        // The recording engine lives inside the service now — these actions let the UI
        // drive the loop without ever touching the engine directly. The actual
        // SessionConfig + pause-timer values are handed over via
        // [RecordingEngineHolder] (same process, no need to marshal into the Intent).
        const val ACTION_APPLY_CONFIG = "com.fzi.acousticscene.APPLY_CONFIG"
        const val ACTION_PAUSE = "com.fzi.acousticscene.PAUSE_CLASSIFICATION"
        const val ACTION_RESUME = "com.fzi.acousticscene.RESUME_CLASSIFICATION"
        const val ACTION_CLEAR_RESULTS = "com.fzi.acousticscene.CLEAR_RESULTS"
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // The recording loop lives on [RecordingEngineHolder] (process scope) so it
    // outlives stop/start cycles. The service drives it via intents.
    private val recordingEngine: RecordingEngine
        get() = RecordingEngineHolder.ensureEngine(applicationContext)

    @Volatile
    private var isRunning = false

    private var serviceListener: ServiceListener? = null

    // WakeLock um CPU aktiv zu halten während Aufnahme läuft
    // KRITISCH: Ohne WakeLock geht die CPU in den Schlafmodus wenn der Bildschirm aus ist!
    private var wakeLock: PowerManager.WakeLock? = null

    // AlarmManager für periodische Aufweckung als Backup gegen Doze-Mode
    private var alarmManager: AlarmManager? = null
    private var keepAlivePendingIntent: PendingIntent? = null

    // BroadcastReceiver für Keep-Alive Alarm
    private val keepAliveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_KEEP_ALIVE && isRunning) {
                Log.d(TAG, "Keep-alive alarm received - ensuring WakeLock is held")
                // Stelle sicher, dass WakeLock noch gehalten wird
                ensureWakeLockHeld()
            }
        }
    }
    
    /**
     * Listener für Service-Events
     */
    interface ServiceListener {
        fun onClassificationStarted()
        fun onClassificationStopped()
        fun onError(message: String)
    }
    
    /**
     * Setzt einen Listener für Service-Events
     */
    fun setServiceListener(listener: ServiceListener?) {
        this.serviceListener = listener
    }
    
    /**
     * Binder für Activity-Service Kommunikation
     */
    inner class LocalBinder : Binder() {
        fun getService(): ClassificationService = this@ClassificationService
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()

        // Registriere Keep-Alive BroadcastReceiver
        val filter = IntentFilter(ACTION_KEEP_ALIVE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(keepAliveReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(keepAliveReceiver, filter)
        }

        // Initialisiere AlarmManager
        alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_APPLY_CONFIG -> {
                Log.d(TAG, "Apply-config command received")
                // Promote to foreground immediately — Android requires startForeground()
                // within 5 s of startForegroundService. Notification text updates once
                // the loop actually begins recording.
                ensureForegroundStarted()
                val config = RecordingEngineHolder.pendingConfig
                if (config == null) {
                    Log.w(TAG, "ACTION_APPLY_CONFIG with no pending config")
                } else {
                    RecordingEngineHolder.pendingConfig = null
                    recordingEngine.applyConfig(config)
                }
            }
            ACTION_START -> {
                Log.d(TAG, "Start command received")
                ensureForegroundStarted()
                recordingEngine.start()
            }
            ACTION_PAUSE -> {
                Log.d(TAG, "Pause command received")
                val ms = RecordingEngineHolder.pendingPauseAutoResumeMs
                RecordingEngineHolder.pendingPauseAutoResumeMs = null
                recordingEngine.pause(ms)
            }
            ACTION_RESUME -> {
                Log.d(TAG, "Resume command received")
                recordingEngine.resume()
            }
            ACTION_CLEAR_RESULTS -> {
                Log.d(TAG, "Clear-results command received")
                RecordingEngineHolder.engineOrNull()?.clearResults()
                // If the engine wasn't even live, just stomp the state directly so the
                // UI snaps back to a clean slate.
                if (RecordingEngineHolder.engineOrNull() == null) {
                    RecordingEngineHolder.mutableUiState.value =
                        com.fzi.acousticscene.ui.common.UiState()
                }
            }
            ACTION_STOP -> {
                Log.d(TAG, "Stop command received")
                RecordingEngineHolder.engineOrNull()?.stop()
                stopForegroundClassification()
            }
        }
        // START_NOT_STICKY: Wenn das System den Service unter Memory-Druck killt,
        // soll er NICHT automatisch ohne Intent neu starten — er hätte keinen
        // Recording-State mehr, würde aber Notification + WakeLock-Pfad anlegen
        // (Zombie-Recording). Der User startet die Session bewusst neu vom UI.
        return START_NOT_STICKY
    }

    /**
     * Idempotent foreground-start. Called whenever an action arrives that should
     * be backed by a foreground service (apply-config, start). Doing this here
     * — instead of only from start() — covers the case where the UI applies a
     * config and we sit on the model-loading screen before [ACTION_START] lands.
     */
    private fun ensureForegroundStarted() {
        if (isRunning) return
        startForegroundClassification()
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")

        // Stoppe Keep-Alive Alarm
        stopKeepAliveAlarm()

        // Deregistriere BroadcastReceiver
        try {
            unregisterReceiver(keepAliveReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }

        // Sicherheit: WakeLock freigeben auch wenn stopForegroundClassification nicht aufgerufen wurde
        releaseWakeLock()
        stopForegroundClassification()
        // The recording engine lives on RecordingEngineHolder (process scope) — we
        // intentionally do NOT release it here. The point of moving the loop into
        // the service was to keep recordings alive across activity destruction;
        // if Android tears the service down mid-session we'd rather keep the
        // engine's state intact for the next bind than wipe it.
        serviceScope.cancel()
    }
    
    /**
     * Startet die Foreground-Klassifikation
     */
    private fun startForegroundClassification() {
        if (isRunning) {
            Log.w(TAG, "Classification already running")
            return
        }

        Log.d(TAG, "Starting foreground classification")
        isRunning = true

        // KRITISCH: WakeLock erwerben BEVOR wir starten!
        // Dies verhindert, dass die CPU in den Schlafmodus geht
        acquireWakeLock()

        // Starte periodische Aufweckung als Backup gegen Doze-Mode
        startKeepAliveAlarm()

        // Starte Foreground Service mit Notification (HIGH Priority!)
        startForeground(NOTIFICATION_ID, createNotification("Classification running..."))

        // Benachrichtige Listener
        serviceListener?.onClassificationStarted()

        Log.d(TAG, "Foreground classification started with WakeLock and Keep-Alive Alarm")
    }

    /**
     * Startet einen periodischen Alarm der den Service aufweckt.
     * Dies ist ein Backup falls Android den WakeLock im Doze-Mode ignoriert.
     */
    private fun startKeepAliveAlarm() {
        try {
            val intent = Intent(ACTION_KEEP_ALIVE).apply {
                setPackage(packageName)
            }
            keepAlivePendingIntent = PendingIntent.getBroadcast(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            alarmManager?.let { am ->
                // setExactAndAllowWhileIdle funktioniert auch im Doze-Mode!
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + ALARM_INTERVAL_MS,
                        keepAlivePendingIntent!!
                    )
                } else {
                    am.setExact(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + ALARM_INTERVAL_MS,
                        keepAlivePendingIntent!!
                    )
                }
                Log.d(TAG, "Keep-alive alarm started (interval: ${ALARM_INTERVAL_MS}ms)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start keep-alive alarm", e)
        }
    }

    /**
     * Stoppt den periodischen Keep-Alive Alarm
     */
    private fun stopKeepAliveAlarm() {
        try {
            keepAlivePendingIntent?.let { pending ->
                alarmManager?.cancel(pending)
                pending.cancel()
            }
            keepAlivePendingIntent = null
            Log.d(TAG, "Keep-alive alarm stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping keep-alive alarm", e)
        }
    }

    /**
     * Stellt sicher, dass der WakeLock noch gehalten wird.
     * Wird vom Keep-Alive Alarm aufgerufen.
     */
    private fun ensureWakeLockHeld() {
        // acquireWakeLock() refreshed jetzt unbedingt — egal ob held oder nicht,
        // der Timeout wird wieder auf WAKELOCK_TIMEOUT_MS zurückgesetzt. Das ist
        // genau das, was die periodische Aufweckung tun soll.
        acquireWakeLock()

        // Nächsten Keep-Alive Alarm einplanen. Vorher den alten cancellen, damit
        // wir keinen verwaisten PendingIntent stehen lassen, falls Android setExact
        // mal nicht überschreibt.
        if (isRunning) {
            stopKeepAliveAlarm()
            startKeepAliveAlarm()
        }
    }

    /**
     * Erwirbt einen PARTIAL_WAKE_LOCK um die CPU aktiv zu halten.
     *
     * PARTIAL_WAKE_LOCK: Hält nur die CPU wach, nicht den Bildschirm.
     * Das ist genau was wir brauchen für Hintergrund-Aufnahmen!
     *
     * Ohne diesen WakeLock würde Android die CPU in den Schlafmodus
     * versetzen wenn der Bildschirm ausgeht → Datenlücken!
     *
     * Idempotent: kann beliebig oft aufgerufen werden — wenn der Lock schon
     * gehalten wird, refresht der erneute acquire() lediglich den Timeout.
     */
    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = wakeLock ?: powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKELOCK_TAG
            ).also {
                it.setReferenceCounted(false)
                wakeLock = it
            }
            // Kurzer Timeout als Crash-Safety. Der Keep-Alive Alarm refresht alle
            // ALARM_INTERVAL_MS, also lange vor Ablauf. Falls der Prozess hart
            // stirbt, gibt Android den Lock nach WAKELOCK_TIMEOUT_MS frei statt
            // nach Stunden.
            lock.acquire(WAKELOCK_TIMEOUT_MS)
            Log.d(TAG, "WakeLock acquired/refreshed (timeout: ${WAKELOCK_TIMEOUT_MS}ms)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock", e)
            serviceListener?.onError("WakeLock konnte nicht aktiviert werden: ${e.message}")
        }
    }
    
    /**
     * Stoppt die Foreground-Klassifikation
     */
    private fun stopForegroundClassification() {
        if (!isRunning) {
            return
        }

        Log.d(TAG, "Stopping foreground classification")
        isRunning = false

        // Stoppe Keep-Alive Alarm
        stopKeepAliveAlarm()

        // KRITISCH: WakeLock freigeben um Batterie zu schonen!
        releaseWakeLock()

        // Stoppe Foreground Service
        stopForeground(STOP_FOREGROUND_REMOVE)

        // Benachrichtige Listener
        serviceListener?.onClassificationStopped()

        // Service beenden
        stopSelf()

        Log.d(TAG, "Foreground classification stopped, WakeLock released")
    }

    /**
     * Gibt den WakeLock frei.
     *
     * WICHTIG: Muss immer aufgerufen werden wenn die Aufnahme stoppt!
     * Ansonsten bleibt die CPU aktiv und die Batterie wird schnell leer.
     */
    private fun releaseWakeLock() {
        try {
            wakeLock?.let { lock ->
                if (lock.isHeld) {
                    lock.release()
                    Log.d(TAG, "WakeLock released - CPU can sleep now")
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock", e)
        }
    }
    
    /**
     * Prüft, ob der Service läuft
     */
    fun isClassificationRunning(): Boolean = isRunning
    
    /**
     * Erstellt den Notification Channel (Android O+)
     *
     * WICHTIG: IMPORTANCE_DEFAULT statt IMPORTANCE_LOW!
     * Höhere Priorität hilft gegen Doze-Mode Einschränkungen.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Classification",
                NotificationManager.IMPORTANCE_DEFAULT  // Erhöht von LOW auf DEFAULT
            ).apply {
                description = "Shown while audio classification is running in the background"
                setShowBadge(false)
                // Deaktiviere Sound und Vibration (wir wollen nur höhere Priorität)
                setSound(null, null)
                enableVibration(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * Erstellt die Notification für den Foreground Service
     *
     * WICHTIG: PRIORITY_HIGH für bessere Behandlung im Doze-Mode!
     */
    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            // Re-attach to the live screen instead of the nav start destination.
            // SINGLE_TOP so an already-running MainActivity gets onNewIntent rather
            // than a fresh instance on top of the stack.
            putExtra(EXTRA_OPEN_LIVE, true)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)  // Erhöht von LOW auf HIGH
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setSilent(true)  // Kein Sound/Vibration trotz HIGH Priority
            .build()
    }
    
    /**
     * Aktualisiert die Notification
     */
    fun updateNotification(text: String) {
        if (isRunning) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, createNotification(text))
        }
    }
}