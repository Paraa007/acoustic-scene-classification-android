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
 * Per-model evaluation methods. Each method is bound to a single training
 * duration: STANDARD only fits 10 s-trained models, FAST and AVERAGE only fit
 * 1 s-trained ones. The wizard auto-assigns the matching methods when a model
 * is picked — there is no user choice at this layer.
 *
 * [sliceSeconds] is the audio length each individual inference call sees:
 * STANDARD feeds the full 10 s buffer, FAST a 1 s slice, AVERAGE the ten
 * 1 s slices that make up the 10 s frame (averaged after the fact).
 */
enum class LongSubMode(val label: String, val hint: String, val sliceSeconds: Int) {
    STANDARD("Standard", "full 10 s", 10),
    FAST("Fast", "live 1 s", 1),
    AVERAGE("Avg", "10 × 1 s → Ø", 1);

    /**
     * Whether this method can drive a model trained on [modelTrainingSeconds]-long
     * clips. STANDARD is 10 s only; FAST and AVERAGE are 1 s only.
     */
    fun isCompatibleWith(modelTrainingSeconds: Int): Boolean = when (this) {
        STANDARD -> modelTrainingSeconds == 10
        FAST, AVERAGE -> modelTrainingSeconds == 1
    }
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
