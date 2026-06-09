package com.fzi.acousticscene.model

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Covers the calendar-end-date extension of [SessionDuration]: the three
 * mutually exclusive shapes, the "Until …" label, and backward compatibility
 * of Gson payloads persisted before the field existed.
 */
class SessionDurationTest {

    private fun millisFor(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month, day, 23, 59, 59)
        }.timeInMillis

    @Test
    fun minuteVariant_keepsLegacyLabels() {
        assertEquals("30 min", SessionDuration.fromMinutes(30).label)
        assertEquals("1 h", SessionDuration.fromMinutes(60).label)
        assertEquals("1 h 30 min", SessionDuration.fromMinutes(90).label)
        assertEquals("Stop manually", SessionDuration.MANUAL.label)
    }

    @Test
    fun dateVariant_rendersUntilLabel() {
        val d = SessionDuration.untilDate(millisFor(2026, Calendar.JULY, 7))
        assertEquals("Until Jul 7, 2026", d.label)
        assertTrue(d.hasEndDate)
        assertFalse(d.isManual)
        assertNull(d.totalMinutes)
        assertNull(d.totalMs)
    }

    @Test
    fun manual_isNeitherMinutesNorDate() {
        val m = SessionDuration.MANUAL
        assertTrue(m.isManual)
        assertFalse(m.hasEndDate)
        assertNull(m.totalMs)
    }

    @Test
    fun minutesAndEndDate_areMutuallyExclusive() {
        assertThrows(IllegalArgumentException::class.java) {
            SessionDuration(totalMinutes = 30, endDateMillis = 123L)
        }
    }

    @Test
    fun gson_deserializesLegacyPayloadWithoutEndDate() {
        // Records persisted before the date variant carry only totalMinutes.
        val restored = Gson().fromJson("{\"totalMinutes\":45}", SessionDuration::class.java)
        assertEquals(45, restored.totalMinutes)
        assertNull(restored.endDateMillis)
        assertEquals("45 min", restored.label)
    }

    @Test
    fun gson_roundTripsDateVariant() {
        val gson = Gson()
        val original = SessionDuration.untilDate(millisFor(2026, Calendar.AUGUST, 1))
        val restored = gson.fromJson(gson.toJson(original), SessionDuration::class.java)
        assertEquals(original.endDateMillis, restored.endDateMillis)
        assertNull(restored.totalMinutes)
    }
}
