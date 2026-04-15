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
enum class RecordingCategory(val label: String) {
    CONTINUOUS("Durchgehend"),
    INTERVAL("Intervall")
}

/**
 * LONG-mode evaluation sub-modes: applied to the same 10 s recording.
 * STANDARD is always selected (default, cannot be unchecked).
 */
enum class LongSubMode(val label: String, val hint: String) {
    STANDARD("Standard", "full 10 s"),
    FAST("Fast", "middle 1 s"),
    AVERAGE("Avg", "10 × 1 s → Ø")
}

enum class RecordingMode(
    val durationSeconds: Int,
    val label: String,
    val category: RecordingCategory,
    val pauseAfterRecordingMs: Long = 0L,
    val devOnly: Boolean = false
) {
    STANDARD(
        durationSeconds = 10,
        label = "Standard (10s)",
        category = RecordingCategory.CONTINUOUS
    ),
    FAST(
        durationSeconds = 1,
        label = "Fast (1s)",
        category = RecordingCategory.CONTINUOUS
    ),
    LONG(
        durationSeconds = 10,
        label = "every 30min",
        category = RecordingCategory.INTERVAL,
        pauseAfterRecordingMs = 30 * 60 * 1000L
    ),
    /**
     * AVERAGE-Modus (nur Dev Mode):
     * Nimmt 10s auf, teilt in 10x 1s-Clips, führt Inferenz pro Clip aus,
     * und berechnet den Durchschnitt der 10 Vorhersagen als Ergebnis.
     */
    AVERAGE(
        durationSeconds = 10,
        label = "Avg (10s)",
        category = RecordingCategory.CONTINUOUS,
        devOnly = true
    );

    fun hasPauseAfterRecording(): Boolean = pauseAfterRecordingMs > 0

    fun getPauseMinutes(): Int = (pauseAfterRecordingMs / 60000L).toInt()

    companion object {
        val DEFAULT = STANDARD

        fun forCategory(category: RecordingCategory, isDevMode: Boolean): List<RecordingMode> =
            values().filter { it.category == category && (isDevMode || !it.devOnly) }
    }
}
