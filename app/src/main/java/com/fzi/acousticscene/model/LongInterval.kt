package com.fzi.acousticscene.model

/**
 * Recording-interval choices for the LONG mode (Dev Mode only).
 * Each value defines how long the loop pauses between two 10 s recordings.
 */
enum class LongInterval(val pauseMinutes: Int, val label: String) {
    TEN_MIN(10, "10min"),
    FIFTEEN_MIN(15, "15min"),
    THIRTY_MIN(30, "30min"),
    FORTY_FIVE_MIN(45, "45min"),
    ONE_HOUR(60, "1h"),
    THREE_HOURS(180, "3h");

    val pauseMs: Long get() = pauseMinutes * 60_000L

    /** "30 min pause" / "1 h pause" / "3 h pause" — used in the timeline view. */
    val pauseTimelineLabel: String
        get() = if (pauseMinutes < 60) "$pauseMinutes min pause"
        else "${pauseMinutes / 60} h pause"

    companion object {
        val DEFAULT = THIRTY_MIN

        fun fromMinutesOrNull(min: Int?): LongInterval? =
            min?.let { values().firstOrNull { v -> v.pauseMinutes == it } }
    }
}
