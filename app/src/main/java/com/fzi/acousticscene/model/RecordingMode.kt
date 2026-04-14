package com.fzi.acousticscene.model

/**
 * Enum für Aufnahme-Modi
 *
 * Die FFT-Parameter (nFft, winLength, hopLength, nMels, fMin, fMax) sind für alle Modi gleich
 * und werden zentral in MelSpectrogramProcessor definiert.
 * Nur die Aufnahme-Dauer unterscheidet sich — daraus ergibt sich die Breite des Spektrogramms:
 * nTimeFrames = 1 + (sampleRate * durationSeconds - winLength) / hopLength
 *
 * LONG-Modus: Macht 10s Aufnahme, dann 30 Minuten Pause (für Langzeit-Monitoring)
 */
enum class RecordingMode(
    val durationSeconds: Int,
    val label: String,
    val pauseAfterRecordingMs: Long = 0L  // Pause nach Aufnahme in Millisekunden
) {
    STANDARD(
        durationSeconds = 10,
        label = "Standard (10s)"
    ),
    FAST(
        durationSeconds = 1,
        label = "Fast (1s)"
    ),
    LONG(
        durationSeconds = 10,
        label = "Long (30min)",
        pauseAfterRecordingMs = 30 * 60 * 1000L  // 30 Minuten Pause = 1.800.000 ms
    ),
    /**
     * AVERAGE-Modus (nur Dev Mode):
     * Nimmt 10s auf, teilt in 10x 1s-Clips, führt Inferenz pro Clip aus,
     * und berechnet den Durchschnitt der 10 Vorhersagen als Ergebnis.
     */
    AVERAGE(
        durationSeconds = 10,
        label = "Avg (10s)"
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
