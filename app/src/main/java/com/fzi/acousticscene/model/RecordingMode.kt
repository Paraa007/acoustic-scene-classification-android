package com.fzi.acousticscene.model

/**
 * Enum für Aufnahme-Modi mit FFT-Parametern
 *
 * LONG-Modus: Macht 10s Aufnahme, dann 30 Minuten Pause (für Langzeit-Monitoring)
 */
enum class RecordingMode(
    val durationSeconds: Int,
    val label: String,
    val nFft: Int,
    val winLength: Int,
    val hopLength: Int,
    val nMels: Int,
    val pauseAfterRecordingMs: Long = 0L  // Pause nach Aufnahme in Millisekunden
) {
    STANDARD(
        durationSeconds = 10,
        label = "Standard (10s)",
        nFft = 4096,
        winLength = 3072,
        hopLength = 500,
        nMels = 256,
        pauseAfterRecordingMs = 0L
    ),
    FAST(
        durationSeconds = 1,
        label = "Fast (1s)",
        nFft = 1024,      // Reduziert für Performance
        winLength = 768,  // 75% von nFft
        hopLength = 256,  // Reduziert
        nMels = 64,       // Reduziert für Performance
        pauseAfterRecordingMs = 0L
    ),
    MEDIUM(
        durationSeconds = 5,
        label = "Medium (5s)",
        nFft = 2048,      // Zwischen FAST und STANDARD
        winLength = 1536, // 75% von nFft
        hopLength = 400,  // Zwischen FAST und STANDARD
        nMels = 128,      // Zwischen FAST und STANDARD
        pauseAfterRecordingMs = 0L
    ),
    LONG(
        durationSeconds = 10,  // 10 Sekunden Aufnahme (wie STANDARD)
        label = "Long (30min)",
        nFft = 4096,      // Wie STANDARD für Detaillierung
        winLength = 3072, // 75% von nFft
        hopLength = 500,  // Wie STANDARD
        nMels = 256,      // Wie STANDARD für volle Auflösung
        pauseAfterRecordingMs = 30 * 60 * 1000L  // 30 Minuten Pause = 1.800.000 ms
    );

    /**
     * Prüft, ob dieser Modus eine Pause nach der Aufnahme hat
     */
    fun hasPauseAfterRecording(): Boolean = pauseAfterRecordingMs > 0

    /**
     * Gibt die Pause in Minuten zurück (für UI-Anzeige)
     */
    fun getPauseMinutes(): Int = (pauseAfterRecordingMs / 60000L).toInt()

    companion object {
        /**
         * Gibt die Standard-Aufnahme-Dauer zurück
         */
        val DEFAULT = STANDARD
    }
}
