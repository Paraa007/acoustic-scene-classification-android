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

    /** Locked-default sub-mode for a given training duration. */
    fun defaultSubMode(fileName: String): LongSubMode = when (secondsForFilename(fileName)) {
        1 -> LongSubMode.FAST
        else -> LongSubMode.STANDARD
    }
}
