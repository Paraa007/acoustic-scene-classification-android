package com.fzi.acousticscene.model

/**
 * One persisted quickstart slot — a saved [SessionConfig] that the tester can
 * launch in one tap from the Test Welcome screen.
 *
 * The slot's [index] is 1-based and matches the UI label ("Quick start 1"…).
 * Quickstart slots are device-local and capped at 5; see [QuickstartRepository].
 */
data class QuickstartSlot(
    val index: Int,
    val config: SessionConfig
)
