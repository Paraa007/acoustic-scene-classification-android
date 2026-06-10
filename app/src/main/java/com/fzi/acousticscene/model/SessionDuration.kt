package com.fzi.acousticscene.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Total session duration picked in the wizard. Applies to both Continuous and
 * Interval sessions: when the chosen window elapses, the loop performs a clip-
 * accurate soft stop (finishes the active cycle, no new one is started).
 *
 * Three mutually exclusive shapes:
 *  - `totalMinutes != null` — fixed window from the slider (15-min steps).
 *  - `endDateMillis != null` — calendar end date (Interval only). The value is
 *    the epoch millisecond of 23:59:59 local time on the chosen date; the
 *    session soft-stops once the wall clock passes it.
 *  - both null — legacy "Stop manually": runs until the user presses Stop.
 *
 * Used to be a fixed enum of preset durations (30 min, 1 h, 3 h, 6 h, 12 h,
 * MANUAL). The wizard now drives this with a continuous 15-min-step slider
 * from 0 to 12 h plus an explicit "Stop manually" button, so the type became
 * an open value carrier. The persistence layer serialises [totalMinutes] (and
 * [endDateMillis] for the date variant) directly and rehydrates via
 * [fromMinutes] / [manual] / [untilDate].
 */
data class SessionDuration(
    val totalMinutes: Int?,
    val endDateMillis: Long? = null
) {

    init {
        require(totalMinutes == null || totalMinutes >= 0) {
            "totalMinutes must be >= 0 or null, was $totalMinutes"
        }
        require(totalMinutes == null || endDateMillis == null) {
            "totalMinutes and endDateMillis are mutually exclusive"
        }
    }

    val totalMs: Long? get() = totalMinutes?.let { it * 60_000L }

    val isManual: Boolean get() = totalMinutes == null && endDateMillis == null

    val hasEndDate: Boolean get() = endDateMillis != null

    /**
     * Compact label used in summaries, history tiles, slot descriptions:
     * "Stop manually" / "30 min" / "1 h" / "1 h 30 min" / "Until Jul 7, 2026".
     */
    val label: String
        get() = when {
            endDateMillis != null -> "Until ${formatEndDate(endDateMillis)}"
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

        /**
         * Date variant. [endDateMillis] must already be the end-of-day instant
         * (23:59:59 local) of the chosen date — the wizard computes that when
         * the picker closes so every consumer can compare plain wall clock.
         */
        fun untilDate(endDateMillis: Long): SessionDuration =
            SessionDuration(totalMinutes = null, endDateMillis = endDateMillis)

        /** "Jul 7, 2026" — date part of the label, English like all UI strings. */
        fun formatEndDate(endDateMillis: Long): String =
            SimpleDateFormat("MMM d, yyyy", Locale.ENGLISH).format(Date(endDateMillis))

        /** "Tue, Jul 7, 2026" — long form for the wizard's end-date chip. */
        fun formatEndDateLong(endDateMillis: Long): String =
            SimpleDateFormat("EEE, MMM d, yyyy", Locale.ENGLISH).format(Date(endDateMillis))
    }
}
