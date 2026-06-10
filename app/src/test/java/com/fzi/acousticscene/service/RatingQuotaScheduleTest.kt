package com.fzi.acousticscene.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Pins the per-block quota contract: every consecutive block of 10 interval
 * cycles prompts exactly ratingPercent/10 times, 100 % prompts always, and
 * the positions inside a block are shuffled rather than front-loaded.
 */
class RatingQuotaScheduleTest {

    private fun drain(schedule: RatingQuotaSchedule, cycles: Int): List<Boolean> =
        List(cycles) { schedule.shouldPrompt() }

    @Test
    fun hundredPercent_promptsEveryCycle() {
        val results = drain(RatingQuotaSchedule(100), 37)
        assertTrue(results.all { it })
    }

    @Test
    fun everyBlockOfTen_carriesExactQuota() {
        for (percent in 10..90 step 10) {
            val schedule = RatingQuotaSchedule(percent, Random(42))
            val results = drain(schedule, 50)
            results.chunked(10).forEachIndexed { blockIdx, block ->
                assertEquals(
                    "percent=$percent block=$blockIdx",
                    percent / 10,
                    block.count { it }
                )
            }
        }
    }

    @Test
    fun promptPositions_areShuffledWithinTheBlock() {
        // With a fixed seed and 5 blocks at 30 %, at least one block must
        // differ from the deterministic "first three cycles" layout.
        val schedule = RatingQuotaSchedule(30, Random(7))
        val blocks = drain(schedule, 50).chunked(10)
        val frontLoaded = List(3) { true } + List(7) { false }
        assertTrue(blocks.any { it != frontLoaded })
    }

    @Test
    fun consecutiveBlocks_getFreshShuffles() {
        // 200 cycles at 50 % with a seeded Random: the chance of all 20 blocks
        // sharing one layout is (1/252)^19 — if these are equal the schedule
        // is not re-shuffling per block.
        val schedule = RatingQuotaSchedule(50, Random(3))
        val blocks = drain(schedule, 200).chunked(10)
        assertNotEquals(1, blocks.distinct().size)
    }

    @Test
    fun offGridPercent_roundsToNearestStep() {
        // 34 → 30 %, 35 → 40 % (round half up).
        val low = drain(RatingQuotaSchedule(34, Random(1)), 10)
        assertEquals(3, low.count { it })
        val high = drain(RatingQuotaSchedule(35, Random(1)), 10)
        assertEquals(4, high.count { it })
    }

    @Test
    fun zeroPercent_neverPrompts() {
        assertTrue(drain(RatingQuotaSchedule(0), 20).none { it })
    }

    @Test
    fun firstCycle_alwaysPrompts_forEveryQuota() {
        // The session's first cycle must prompt no matter the percentage or
        // seed — otherwise low quotas with long pauses look like a broken
        // rating feature right after session start.
        for (percent in 10..90 step 10) {
            for (seed in 0L..19L) {
                val schedule = RatingQuotaSchedule(percent, Random(seed))
                assertTrue(
                    "percent=$percent seed=$seed",
                    schedule.shouldPrompt()
                )
            }
        }
    }

    @Test
    fun firstCyclePin_consumesQuotaInsteadOfAddingToIt() {
        // Block 1 still carries the exact quota: the pinned first prompt is
        // one of its slots, not a bonus on top.
        for (percent in 10..90 step 10) {
            val schedule = RatingQuotaSchedule(percent, Random(11))
            val firstBlock = drain(schedule, 10)
            assertEquals("percent=$percent", percent / 10, firstBlock.count { it })
        }
    }

    @Test
    fun laterBlocks_doNotPinTheFirstPosition() {
        // Only the session start is guaranteed. With 10 % and many blocks, a
        // pinned position 0 would prompt on every block's first cycle — the
        // shuffle must place at least one block's prompt elsewhere.
        val schedule = RatingQuotaSchedule(10, Random(5))
        val blocks = drain(schedule, 100).chunked(10)
        assertTrue(blocks.drop(1).any { !it.first() })
    }
}
