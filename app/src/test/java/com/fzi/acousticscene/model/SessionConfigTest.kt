package com.fzi.acousticscene.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the rating-quota default on [SessionConfig]: configs built without an
 * explicit value (wizard pre-quota, legacy persisted payloads mapped through
 * `ratingPercent ?: 100` in the stores) must behave like the historic app and
 * prompt on every cycle.
 */
class SessionConfigTest {

    @Test
    fun ratingPercent_defaultsToHundred() {
        val config = SessionConfig(
            modelNames = listOf("model1.pt"),
            category = RecordingCategory.INTERVAL,
            intervalPause = LongInterval.fromMinutes(30)
        )
        assertEquals(100, config.ratingPercent)
    }

    @Test
    fun copy_keepsRatingPercent() {
        val config = SessionConfig(
            modelNames = listOf("model1.pt"),
            category = RecordingCategory.INTERVAL,
            intervalPause = LongInterval.fromMinutes(30),
            ratingPercent = 40
        )
        assertEquals(40, config.copy(mode = SessionMode.TEST).ratingPercent)
    }
}
