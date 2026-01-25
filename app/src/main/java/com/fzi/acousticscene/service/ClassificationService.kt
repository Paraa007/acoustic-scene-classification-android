package com.fzi.acousticscene.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.fzi.acousticscene.MainActivity
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
 */
class ClassificationService : Service() {
    
    companion object {
        private const val TAG = "ClassificationService"
        private const val CHANNEL_ID = "classification_channel"
        private const val NOTIFICATION_ID = 1
        
        const val ACTION_START = "com.fzi.acousticscene.START_CLASSIFICATION"
        const val ACTION_STOP = "com.fzi.acousticscene.STOP_CLASSIFICATION"
    }
    
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    @Volatile
    private var isRunning = false
    
    private var serviceListener: ServiceListener? = null
    
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
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                Log.d(TAG, "Start command received")
                if (!isRunning) {
                    startForegroundClassification()
                }
            }
            ACTION_STOP -> {
                Log.d(TAG, "Stop command received")
                stopForegroundClassification()
            }
        }
        return START_STICKY // Service wird automatisch neu gestartet wenn er beendet wird
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        stopForegroundClassification()
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
        
        // Starte Foreground Service mit Notification
        startForeground(NOTIFICATION_ID, createNotification("Klassifikation läuft..."))
        
        // Benachrichtige Listener
        serviceListener?.onClassificationStarted()
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
        
        // Stoppe Foreground Service
        stopForeground(STOP_FOREGROUND_REMOVE)
        
        // Benachrichtige Listener
        serviceListener?.onClassificationStopped()
        
        // Service beenden
        stopSelf()
    }
    
    /**
     * Prüft, ob der Service läuft
     */
    fun isClassificationRunning(): Boolean = isRunning
    
    /**
     * Erstellt den Notification Channel (Android O+)
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Klassifikation",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Zeigt an, dass die Audio-Klassifikation im Hintergrund läuft"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * Erstellt die Notification für den Foreground Service
     */
    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
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
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)
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