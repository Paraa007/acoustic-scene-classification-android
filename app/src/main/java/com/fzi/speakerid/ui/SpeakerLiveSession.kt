package com.fzi.speakerid.ui

import android.content.Context
import java.util.concurrent.Executors

/**
 * Pendant zu siqas `gui/app.py`: dort haengt der GUI-LiveProcessor als
 * `app.live_controller` prozessweit an der App und ueberlebt Screen-Wechsel
 * (die Aufnahme laeuft beim Verlassen der Arena im Hintergrund weiter).
 *
 * Dieses Objekt haelt den [SpeakerSessionController] als Prozess-Singleton:
 *  - [get] liefert den Controller (lazy, mit App-Context + Modellverzeichnis).
 *  - [toggleLive] fuehrt `toggle_live` auf einem eigenen Worker aus und stellt
 *    vorher sicher, dass die ONNX-Modelle installiert sind ([AssetModelInstaller]
 *    ist idempotent) — das ONNX-Session-Laden gehoert nicht auf den Main-Thread.
 */
object SpeakerLiveSession {

    @Volatile
    private var controller: SpeakerSessionController? = null

    /** 1-Worker wie Pythons sequenzieller Umgang mit `toggle_live`-Events. */
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SpeakerLiveToggle").apply { isDaemon = true }
    }

    /** `app.live_controller` */
    fun get(context: Context): SpeakerSessionController {
        controller?.let { return it }
        synchronized(this) {
            controller?.let { return it }
            val appContext = context.applicationContext
            val created = SpeakerSessionController(
                SpeakerIdDataManager.getInstance(appContext),
                AssetModelInstaller.modelsDir(appContext),
            )
            controller = created
            return created
        }
    }

    /** Port von `app.live_controller.toggle_live()` (Modelle sicherstellen, dann togglen). */
    fun toggleLive(context: Context) {
        val appContext = context.applicationContext
        executor.execute {
            try {
                AssetModelInstaller.install(appContext)
                get(appContext).toggleLive()
            } catch (_: Exception) {
                // Python loggt Start-Fehler nur ("Fehler beim Start") — Session bleibt aus.
            }
        }
    }

    /** `if app.live_controller and app.live_controller.is_live_processing: stop()` */
    fun stopIfRunning() {
        val current = controller ?: return
        if (current.isLiveProcessing.value) current.stop()
    }
}
