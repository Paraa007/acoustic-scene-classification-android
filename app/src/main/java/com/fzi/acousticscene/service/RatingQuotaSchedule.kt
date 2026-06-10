package com.fzi.acousticscene.service

import kotlin.random.Random

/**
 * Decides which interval cycles ask the subject for a "Rate now" evaluation.
 *
 * Instead of an independent coin flip per cycle (which can cluster badly over
 * a day), the schedule guarantees the quota per block: every consecutive block
 * of [BLOCK_SIZE] cycles contains exactly `ratingPercent / 10` prompts, at
 * random positions within the block. A fresh shuffled block is built whenever
 * the previous one is used up.
 *
 * The very first cycle of a session always prompts: one of the first block's
 * quota slots is pinned to position 0 (the block total stays exact). Without
 * this, low percentages plus long interval pauses can leave hours without a
 * single prompt right after starting — indistinguishable from a broken rating
 * feature when testing a configuration.
 *
 * 100 % short-circuits to "always prompt" so the historic behavior stays
 * bit-identical. State is in-memory only — one instance per session run.
 */
class RatingQuotaSchedule(
    ratingPercent: Int,
    private val random: Random = Random.Default
) {
    companion object {
        const val BLOCK_SIZE = 10
    }

    private val percent = ratingPercent.coerceIn(0, 100)
    private var block: List<Boolean> = emptyList()
    private var position = 0
    private var firstBlock = true

    /**
     * Call exactly once per interval cycle, at cycle start. Returns whether
     * this cycle should surface the rating prompt. The draw happens before
     * recording begins so the engine can blind the live UI for the whole
     * cycle (anti-bias); the same decision is reused after persisting.
     */
    fun shouldPrompt(): Boolean {
        if (percent >= 100) return true
        if (percent <= 0) return false
        if (position >= block.size) {
            block = buildBlock(pinFirst = firstBlock)
            firstBlock = false
            position = 0
        }
        return block[position++]
    }

    private fun buildBlock(pinFirst: Boolean): List<Boolean> {
        // 10–90 % in steps of 10 → 1..9 prompts per block of 10. Percentages
        // off the grid round to the nearest step.
        val prompts = ((percent + 5) / 10).coerceIn(0, BLOCK_SIZE)
        if (pinFirst && prompts > 0) {
            // Session start: spend one quota slot on cycle 1, shuffle the rest.
            val rest = (List(prompts - 1) { true } + List(BLOCK_SIZE - prompts) { false })
                .shuffled(random)
            return listOf(true) + rest
        }
        return (List(prompts) { true } + List(BLOCK_SIZE - prompts) { false })
            .shuffled(random)
    }
}
