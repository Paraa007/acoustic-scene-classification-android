package com.fzi.acousticscene.model

/**
 * Total session duration picked in the wizard. Applies to both Continuous and
 * Interval sessions: when the chosen window elapses, the loop performs a clip-
 * accurate soft stop (finishes the active cycle, no new one is started).
 *
 * `totalMinutes == null` keeps the legacy "Stop manually" behavior — the
 * session runs until the user presses Stop.
 *
 * Used to be a fixed enum of preset durations (30 min, 1 h, 3 h, 6 h, 12 h,
 * MANUAL). The wizard now drives this with a continuous 15-min-step slider
 * from 0 to 12 h plus an explicit "Stop manually" button, so the type became
 * an open value carrier. The persistence layer serialises [totalMinutes]
 * directly and rehydrates via [fromMinutes] / [manual].
 */
data class SessionDuration(val totalMinutes: Int?) {

    init {
        require(totalMinutes == null || totalMinutes >= 0) {
            "totalMinutes must be >= 0 or null, was $totalMinutes"
        }
    }

    val totalMs: Long? get() = totalMinutes?.let { it * 60_000L }

    val isManual: Boolean get() = totalMinutes == null

    /**
     * Compact label used in summaries, history tiles, slot descriptions:
     * "Stop manually" / "30 min" / "1 h" / "1 h 30 min".
     */
    val label: String
        get() = when {
            totalMinutes == null -> "Stop manually"
            totalMinutes < 60 -> "$totalMinutes min"
            totalMinutes % 60 == 0 -> "${totalMinutes / 60} h"
            else -> "${totalMinutes / 60} h ${totalMinutes % 60} min"
        }

    companion object {
        /** 30 min — matches the historic DEFAULT picked by the wizard. */
        val DEFAULT = SessionDuration(30)

        val MANUAL = SessionDuration(null)

        fun fromMinutes(min: Int): SessionDuration =
            SessionDuration(min.coerceAtLeast(0))

        fun manual(): SessionDuration = MANUAL
    }
}
