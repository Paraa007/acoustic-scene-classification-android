package com.fzi.acousticscene.ui.views

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * One sample of a session-time series: [timeSec] = seconds since session start,
 * [value] in the metric's natural unit (°C, %, …).
 */
data class ChartPoint(val timeSec: Float, val value: Float)

/**
 * Pure axis/decimation math shared by [VolumeLineChartView] and
 * [MetricLineChartView]. No Android dependencies — unit-tested on the JVM
 * (see ChartScalesTest).
 */
object ChartScales {

    /** Never zoom the live volume chart tighter than 0..10 % RMS. */
    const val VOLUME_AXIS_FLOOR = 0.1f

    /** Headroom factor above the loudest sample (~20 %). */
    const val VOLUME_AXIS_HEADROOM = 1.2f

    /**
     * Target top of the volume y-axis (0..1 scale) for a frame whose loudest
     * sample is [frameMax]: max sample + 20 % headroom, but never below the
     * floor and never above 1.
     */
    fun volumeAxisTarget(
        frameMax: Float,
        floor: Float = VOLUME_AXIS_FLOOR,
        headroom: Float = VOLUME_AXIS_HEADROOM
    ): Float = (frameMax * headroom).coerceIn(floor, 1f)

    /**
     * Picks a round gridline step (1-2-5 progression) so the value range
     * [0..range] gets at most [maxLines] gridlines. Returns a step > 0.
     */
    fun niceStep(range: Float, maxLines: Int = 5): Float {
        if (range <= 0f) return 1f
        val rawStep = range / maxLines
        val magnitude = 10.0.pow(floor(log10(rawStep.toDouble()))).toFloat()
        for (m in floatArrayOf(1f, 2f, 5f, 10f)) {
            val step = m * magnitude
            if (range / step <= maxLines) return step
        }
        return 10f * magnitude
    }

    /**
     * Y-range for the battery-temperature chart: data min − 1 to data max + 1 °C,
     * widened symmetrically to at least [minSpan] degrees.
     */
    fun tempRange(dataMin: Float, dataMax: Float, minSpan: Float = 4f): Pair<Float, Float> {
        var lo = dataMin - 1f
        var hi = dataMax + 1f
        val span = hi - lo
        if (span < minSpan) {
            val pad = (minSpan - span) / 2f
            lo -= pad
            hi += pad
        }
        return lo to hi
    }

    /**
     * Top of a zero-based y-axis (CPU %, volume %): data max + 20 % headroom,
     * with a minimum top of [minTop] so near-zero data doesn't over-zoom.
     */
    fun zeroBasedTop(dataMax: Float, headroom: Float = 1.2f, minTop: Float = 10f): Float =
        (dataMax * headroom).coerceAtLeast(minTop)

    /** Candidate x-axis tick steps in seconds: 10 s up to 1 week. */
    private val TIME_STEPS_SEC = intArrayOf(
        10, 30, 60, 120, 300, 600, 900, 1800,
        3600, 7200, 14400, 21600, 43200,
        86400, 172800, 345600, 604800
    )

    /**
     * Picks a tick step (in seconds) so a session of [spanSec] gets at most
     * [maxTicks] ticks after t=0.
     */
    fun timeTickStepSec(spanSec: Float, maxTicks: Int = 6): Int {
        for (step in TIME_STEPS_SEC) {
            if (spanSec / step <= maxTicks) return step
        }
        // Beyond a week per tick: whole multiples of a week.
        val weeks = ceil(spanSec / (604800f * maxTicks)).toInt().coerceAtLeast(1)
        return weeks * 604800
    }

    /** "0", "30s", "15min", "1h", "90min", "2d" — compact tick labels. */
    fun formatTimeTick(sec: Int): String = when {
        sec <= 0 -> "0"
        sec % 86400 == 0 -> "${sec / 86400}d"
        sec % 3600 == 0 -> "${sec / 3600}h"
        sec % 60 == 0 -> "${sec / 60}min"
        else -> "${sec}s"
    }

    /**
     * Min/max bucket decimation. Returns the input unchanged while it fits in
     * 2×[maxBuckets] points; otherwise splits it into [maxBuckets] index
     * buckets and keeps each bucket's min and max point (in x order), so
     * spikes survive. First and last points are always kept.
     */
    fun decimate(points: List<ChartPoint>, maxBuckets: Int): List<ChartPoint> {
        if (maxBuckets <= 0 || points.size <= maxBuckets * 2) return points
        val out = ArrayList<ChartPoint>(maxBuckets * 2 + 2)
        out.add(points.first())
        val bucketSize = points.size.toFloat() / maxBuckets
        for (b in 0 until maxBuckets) {
            val start = (b * bucketSize).toInt().coerceAtLeast(1)
            val end = ((b + 1) * bucketSize).toInt().coerceAtMost(points.size - 1)
            if (start >= end) continue
            var minP = points[start]
            var maxP = points[start]
            for (i in start until end) {
                val p = points[i]
                if (p.value < minP.value) minP = p
                if (p.value > maxP.value) maxP = p
            }
            if (minP.timeSec <= maxP.timeSec) {
                out.add(minP)
                if (maxP !== minP) out.add(maxP)
            } else {
                out.add(maxP)
                out.add(minP)
            }
        }
        out.add(points.last())
        return out
    }
}
