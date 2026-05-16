package com.fzi.acousticscene.model

/**
 * Detects how long a model's training clips were, based on its filename.
 *
 * Convention: filenames carry `_1s_` or `_10s_` markers (e.g.
 * `dcase2025_1s_04_06_128bt.pt`, `dcase2025_10s_04_29_32bt.pt`).
 * Falls back to 10 s for any model without an explicit marker.
 */
object ModelTrainingDuration {
    private val ONE_SECOND = Regex("(?:^|[_-])1s(?:[_-]|\\.)")
    private val TEN_SECONDS = Regex("(?:^|[_-])10s(?:[_-]|\\.)")

    fun secondsForFilename(fileName: String): Int = when {
        ONE_SECOND.containsMatchIn(fileName) -> 1
        TEN_SECONDS.containsMatchIn(fileName) -> 10
        else -> 10
    }

    /**
     * Methods that always run for a given model. 1 s-models get both FAST (live
     * 1 s-Wert) and AVERAGE (mean of ten 1 s-Inferenzen); 10 s-models get
     * STANDARD (one inference per 10 s buffer). There is no user choice — the
     * wizard derives this directly from the training duration.
     */
    fun requiredMethodsForModel(fileName: String): Set<LongSubMode> =
        when (secondsForFilename(fileName)) {
            1 -> setOf(LongSubMode.FAST, LongSubMode.AVERAGE)
            else -> setOf(LongSubMode.STANDARD)
        }

    /**
     * Headline method for persistence and history rendering — the one that
     * carries the canonical "this is the model's prediction" result.
     */
    fun primaryMethodFor(fileName: String): LongSubMode =
        when (secondsForFilename(fileName)) {
            1 -> LongSubMode.FAST
            else -> LongSubMode.STANDARD
        }
}
