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
    CONTINUOUS("Continuous"),
    INTERVAL("Interval")
}

/**
 * LONG-mode evaluation sub-modes: applied to the same 10 s recording.
 * STANDARD is always selected (default, cannot be unchecked).
 *
 * [sliceSeconds] is the audio length each individual inference call sees —
 * STANDARD feeds the full 10 s buffer, FAST takes a 1 s middle slice,
 * AVERAGE slices the buffer into ten 1 s clips and infers per clip. The model's
 * training duration must match this slice for the inference to be valid.
 */
enum class LongSubMode(val label: String, val hint: String, val sliceSeconds: Int) {
    STANDARD("Standard", "full 10 s", 10),
    FAST("Fast", "middle 1 s", 1),
    AVERAGE("Avg", "10 × 1 s → Ø", 1);

    /**
     * Whether this method's slice duration matches the model's training
     * duration. Mismatch either crashes the tensor reshape or returns garbage —
     * the wizard greys out incompatible methods so the user can't pick them.
     */
    fun isCompatibleWith(modelTrainingSeconds: Int): Boolean =
        sliceSeconds == modelTrainingSeconds
}

enum class RecordingMode(
    val durationSeconds: Int,
    val label: String,
    val category: RecordingCategory,
    val pauseAfterRecordingMs: Long = 0L
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
     * Nimmt 10s auf, teilt in 10x 1s-Clips, führt Inferenz pro Clip aus,
     * und berechnet den Durchschnitt der 10 Vorhersagen als Ergebnis.
     */
    AVERAGE(
        durationSeconds = 10,
        label = "Avg (10s)",
        category = RecordingCategory.CONTINUOUS
    );

    fun hasPauseAfterRecording(): Boolean = pauseAfterRecordingMs > 0

    fun getPauseMinutes(): Int = (pauseAfterRecordingMs / 60000L).toInt()

    companion object {
        val DEFAULT = STANDARD
    }
}
