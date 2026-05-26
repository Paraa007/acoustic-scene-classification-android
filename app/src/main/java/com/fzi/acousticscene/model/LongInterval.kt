package com.fzi.acousticscene.model

/**
 * Pause length between two recordings in Interval mode, in whole minutes.
 *
 * Used to be a fixed enum of preset pauses (10 / 15 / 30 / 45 / 60 / 180 min).
 * The wizard now drives this with a continuous 15-min-step slider from 0 to 6 h,
 * so the type became an open value carrier instead. The persistence layer
 * serialises [pauseMinutes] directly and rehydrates via [fromMinutes].
 *
 * `pauseMinutes` is always a non-negative integer. The wizard step uses 0 to
 * mean "no pause" — practically equivalent to Continuous, but the user can
 * still toggle Interval and pick 0.
 */
data class LongInterval(val pauseMinutes: Int) {

    init {
        require(pauseMinutes >= 0) { "pauseMinutes must be >= 0, was $pauseMinutes" }
    }

    val pauseMs: Long get() = pauseMinutes * 60_000L

    /**
     * Compact label used in summaries and history tiles: "30min", "1h", "1h30min".
     * Mirrors the formatting that the old enum's `label` field carried.
     */
    val label: String
        get() = when {
            pauseMinutes <= 0 -> "0min"
            pauseMinutes < 60 -> "${pauseMinutes}min"
            pauseMinutes % 60 == 0 -> "${pauseMinutes / 60}h"
            else -> "${pauseMinutes / 60}h${pauseMinutes % 60}min"
        }

    /** "30 min pause" / "1 h pause" / "3 h pause" — used in the timeline view. */
    val pauseTimelineLabel: String
        get() = when {
            pauseMinutes < 60 -> "$pauseMinutes min pause"
            pauseMinutes % 60 == 0 -> "${pauseMinutes / 60} h pause"
            else -> "${pauseMinutes / 60} h ${pauseMinutes % 60} min pause"
        }

    companion object {
        val DEFAULT = LongInterval(30)

        fun fromMinutes(min: Int): LongInterval = LongInterval(min.coerceAtLeast(0))

        fun fromMinutesOrNull(min: Int?): LongInterval? = min?.let { fromMinutes(it) }
    }
}
