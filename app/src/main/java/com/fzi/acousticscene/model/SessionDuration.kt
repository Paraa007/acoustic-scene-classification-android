package com.fzi.acousticscene.model

/**
 * Total session duration picked in the wizard. Applies to both Continuous and
 * Interval sessions: when the chosen window elapses, the loop performs a clip-
 * accurate soft stop (finishes the active cycle, no new one is started).
 *
 * `MANUAL` keeps the legacy behavior — runs until the user presses Stop.
 */
enum class SessionDuration(val label: String, val totalMs: Long?) {
    MIN_30("30 min", 30L * 60_000L),
    HOUR_1("1 h", 60L * 60_000L),
    HOUR_3("3 h", 3L * 60L * 60_000L),
    HOUR_6("6 h", 6L * 60L * 60_000L),
    HOUR_12("12 h", 12L * 60L * 60_000L),
    MANUAL("Stop manually", null);

    val isManual: Boolean get() = totalMs == null

    companion object {
        val DEFAULT = MIN_30
    }
}
