package com.fzi.acousticscene.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pins the ownership contract of the anti-bias blind: a resolving rating
 * (submit, skip, or 5-min expiry) may only lift the blind that belongs to its
 * own prediction id. A late resolution from an OLD cycle must never unblind a
 * NEWER cycle — neither one that is still recording (blindPredictionId == null)
 * nor one already pending under a different id.
 */
class UiStateBlindTest {

    @Test
    fun matchingId_liftsTheBlind() {
        val state = UiState(blindCycleActive = true, blindPredictionId = 42L)
        val resolved = state.withBlindResolved(42L)
        assertFalse(resolved.blindCycleActive)
        assertNull(resolved.blindPredictionId)
    }

    @Test
    fun differentId_keepsTheBlind() {
        val state = UiState(blindCycleActive = true, blindPredictionId = 43L)
        val resolved = state.withBlindResolved(42L)
        assertSame(state, resolved)
        assertEquals(43L, resolved.blindPredictionId)
    }

    @Test
    fun inFlightCycle_isNotUnblindedByAnOldResolution() {
        // blindPredictionId == null means the blinded cycle is still recording
        // and owns no record yet — an expiring old rating must leave it alone.
        val state = UiState(blindCycleActive = true, blindPredictionId = null)
        val resolved = state.withBlindResolved(42L)
        assertSame(state, resolved)
    }

    @Test
    fun noActiveBlind_isANoOp() {
        val state = UiState(blindCycleActive = false, blindPredictionId = null)
        assertSame(state, state.withBlindResolved(42L))
    }
}
