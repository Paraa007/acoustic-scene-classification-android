package com.fzi.acousticscene.ui.views

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartScalesTest {

    // --- volumeAxisTarget ---------------------------------------------------

    @Test
    fun `volume target never drops below the floor`() {
        assertEquals(0.1f, ChartScales.volumeAxisTarget(0.0f), 1e-6f)
        assertEquals(0.1f, ChartScales.volumeAxisTarget(0.05f), 1e-6f)
    }

    @Test
    fun `volume target adds 20 percent headroom above the max sample`() {
        assertEquals(0.36f, ChartScales.volumeAxisTarget(0.3f), 1e-6f)
    }

    @Test
    fun `volume target is capped at 1`() {
        assertEquals(1f, ChartScales.volumeAxisTarget(0.95f), 1e-6f)
        assertEquals(1f, ChartScales.volumeAxisTarget(1f), 1e-6f)
    }

    // --- niceStep -----------------------------------------------------------

    @Test
    fun `niceStep picks round 1-2-5 steps with at most maxLines lines`() {
        assertEquals(20f, ChartScales.niceStep(100f), 1e-6f)   // 5 lines
        assertEquals(5f, ChartScales.niceStep(12f), 1e-6f)     // 2 lines
        assertEquals(2f, ChartScales.niceStep(10f), 1e-6f)     // 5 lines
        assertEquals(10f, ChartScales.niceStep(36f), 1e-6f)    // 3 lines
        assertEquals(0.5f, ChartScales.niceStep(2.4f), 1e-6f)
    }

    @Test
    fun `niceStep handles degenerate range`() {
        assertEquals(1f, ChartScales.niceStep(0f), 1e-6f)
        assertEquals(1f, ChartScales.niceStep(-3f), 1e-6f)
    }

    // --- tempRange ----------------------------------------------------------

    @Test
    fun `temp range pads data by one degree on both sides`() {
        val (lo, hi) = ChartScales.tempRange(30f, 33f)
        assertEquals(29f, lo, 1e-6f)
        assertEquals(34f, hi, 1e-6f)
    }

    @Test
    fun `temp range widens to at least four degrees`() {
        val (lo, hi) = ChartScales.tempRange(30f, 30f)  // single value
        assertEquals(4f, hi - lo, 1e-6f)
        assertEquals(28f, lo, 1e-6f)
        assertEquals(32f, hi, 1e-6f)

        val (lo2, hi2) = ChartScales.tempRange(30f, 31f) // raw span 3 → pad to 4
        assertEquals(4f, hi2 - lo2, 1e-6f)
        assertEquals(28.5f, lo2, 1e-6f)
        assertEquals(32.5f, hi2, 1e-6f)
    }

    // --- zeroBasedTop -------------------------------------------------------

    @Test
    fun `zero based top adds 20 percent headroom`() {
        assertEquals(60f, ChartScales.zeroBasedTop(50f), 1e-4f)
        assertEquals(144f, ChartScales.zeroBasedTop(120f), 1e-4f) // CPU can exceed 100
    }

    @Test
    fun `zero based top never zooms below the minimum top`() {
        assertEquals(10f, ChartScales.zeroBasedTop(0f), 1e-6f)
        assertEquals(10f, ChartScales.zeroBasedTop(3f), 1e-6f)
    }

    // --- timeTickStepSec / formatTimeTick ------------------------------------

    @Test
    fun `time ticks scale with the session span`() {
        assertEquals(10, ChartScales.timeTickStepSec(60f))        // 1 min → 10 s ticks
        assertEquals(120, ChartScales.timeTickStepSec(600f))      // 10 min → 2 min ticks
        assertEquals(600, ChartScales.timeTickStepSec(3600f))     // 1 h → 10 min ticks
        assertEquals(14400, ChartScales.timeTickStepSec(86400f))  // 1 d → 4 h ticks
        // 4-week session → 7-day ticks
        assertEquals(604800, ChartScales.timeTickStepSec(4 * 7 * 86400f))
    }

    @Test
    fun `time tick labels use compact units`() {
        assertEquals("0", ChartScales.formatTimeTick(0))
        assertEquals("30s", ChartScales.formatTimeTick(30))
        assertEquals("15min", ChartScales.formatTimeTick(900))
        assertEquals("90min", ChartScales.formatTimeTick(5400))
        assertEquals("1h", ChartScales.formatTimeTick(3600))
        assertEquals("2d", ChartScales.formatTimeTick(172800))
    }

    // --- decimate -----------------------------------------------------------

    private fun series(n: Int, value: (Int) -> Float): List<ChartPoint> =
        List(n) { ChartPoint(it.toFloat(), value(it)) }

    @Test
    fun `decimate returns small series untouched`() {
        val pts = series(100) { it.toFloat() }
        assertEquals(pts, ChartScales.decimate(pts, 360))
    }

    @Test
    fun `decimate bounds the output size`() {
        val pts = series(5000) { (it % 17).toFloat() }
        val out = ChartScales.decimate(pts, 360)
        assertTrue("size ${out.size}", out.size <= 360 * 2 + 2)
        assertEquals(pts.first(), out.first())
        assertEquals(pts.last(), out.last())
    }

    @Test
    fun `decimate preserves spikes`() {
        val pts = series(5000) { if (it == 2500) 99f else 1f }
        val out = ChartScales.decimate(pts, 100)
        assertTrue(out.any { it.value == 99f })
    }

    @Test
    fun `decimate keeps points sorted by time`() {
        val pts = series(3333) { (it * 31 % 53).toFloat() }
        val out = ChartScales.decimate(pts, 50)
        for (i in 1 until out.size) {
            assertTrue(out[i - 1].timeSec <= out[i].timeSec)
        }
    }
}
