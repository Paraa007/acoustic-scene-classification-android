package com.fzi.acousticscene.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.fzi.acousticscene.R

/**
 * Session-time series line chart: x = seconds since session start (axis grows
 * with the session), one point per cycle record. Shares the visual language of
 * [VolumeLineChartView] (grid, stroke, soft fill, mono axis typography).
 *
 * Configure once via [configure] (unit suffix, y-range mode, line color), then
 * push data with [setPoints]. Callers skip records whose metric is null before
 * handing the list over. Long sessions are decimated to a few hundred drawn
 * points (min/max buckets, spikes survive), so a 4-week interval session
 * renders without breaking a sweat.
 *
 * Y-range modes:
 *  - [YRangeMode.AUTO_PADDED] — data min−1 .. max+1, at least a 4-unit span
 *    (battery temperature).
 *  - [YRangeMode.ZERO_BASED] — 0 .. data max + 20 % headroom, minimum top of
 *    10 units (CPU %, volume %).
 */
class MetricLineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class YRangeMode { AUTO_PADDED, ZERO_BASED }

    companion object {
        private const val PADDING_LEFT_DP = 34f
        private const val PADDING_RIGHT_DP = 12f
        private const val PADDING_TOP_DP = 8f
        private const val PADDING_BOTTOM_DP = 22f

        /** Default x-span so a chart with one early point isn't a single pixel. */
        private const val MIN_SPAN_SEC = 60f

        /** Decimation budget: plenty for any on-screen width. */
        private const val MAX_DRAW_BUCKETS = 360
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ContextCompat.getColor(context, R.color.accent_green_light)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.accent_green_light)
        alpha = 36
    }

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.accent_green_light)
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
        color = ContextCompat.getColor(context, R.color.surface_variant)
    }

    private val axisLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9f * resources.displayMetrics.scaledDensity
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textAlign = Paint.Align.CENTER
    }

    private val yAxisLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9f * resources.displayMetrics.scaledDensity
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textAlign = Paint.Align.RIGHT
    }

    private val noDataPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * resources.displayMetrics.scaledDensity
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textAlign = Paint.Align.CENTER
    }

    private val linePath = Path()
    private val fillPath = Path()

    private var unitSuffix: String = ""
    private var yRangeMode: YRangeMode = YRangeMode.ZERO_BASED
    private var points: List<ChartPoint> = emptyList()
    private var fixedSpanSec: Float? = null

    private val paddingLeftPx: Float get() = PADDING_LEFT_DP * resources.displayMetrics.density
    private val paddingRightPx: Float get() = PADDING_RIGHT_DP * resources.displayMetrics.density
    private val paddingTopPx: Float get() = PADDING_TOP_DP * resources.displayMetrics.density
    private val paddingBottomPx: Float get() = PADDING_BOTTOM_DP * resources.displayMetrics.density

    private val chartLeft: Float get() = paddingLeftPx
    private val chartRight: Float get() = width - paddingRightPx
    private val chartTop: Float get() = paddingTopPx
    private val chartBottom: Float get() = height - paddingBottomPx
    private val chartWidth: Float get() = chartRight - chartLeft
    private val chartHeight: Float get() = chartBottom - chartTop

    /**
     * One-time visual setup. [unitSuffix] tags the topmost y-label (e.g. "°C",
     * "%"); [lineColor] tints stroke, fill and single-point dot.
     */
    fun configure(unitSuffix: String, yRangeMode: YRangeMode, lineColor: Int) {
        this.unitSuffix = unitSuffix
        this.yRangeMode = yRangeMode
        linePaint.color = lineColor
        pointPaint.color = lineColor
        fillPaint.color = lineColor
        fillPaint.alpha = 36
        invalidate()
    }

    /**
     * Replaces the series. Points must already be sorted by [ChartPoint.timeSec]
     * and free of null metrics (callers filter).
     */
    fun setPoints(newPoints: List<ChartPoint>) {
        points = ChartScales.decimate(newPoints, MAX_DRAW_BUCKETS)
        invalidate()
    }

    /**
     * Forces the x-axis to span 0..[spanSec] regardless of the data, so stacked
     * charts of the same session line up. null = derive from the data.
     */
    fun setFixedSpanSeconds(spanSec: Float?) {
        fixedSpanSec = spanSec
        invalidate()
    }

    fun clearData() {
        points = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val spanSec = (fixedSpanSec ?: points.lastOrNull()?.timeSec ?: 0f)
            .coerceAtLeast(MIN_SPAN_SEC)
        val (yMin, yMax) = computeYRange()

        drawGrid(canvas, spanSec, yMin, yMax)
        drawAxisLabels(canvas, spanSec, yMin, yMax)

        when {
            points.isEmpty() ->
                drawNoDataMessage(canvas, context.getString(R.string.metric_chart_waiting))
            points.size == 1 -> drawSinglePoint(canvas, spanSec, yMin, yMax)
            else -> drawLine(canvas, spanSec, yMin, yMax)
        }
    }

    private fun computeYRange(): Pair<Float, Float> {
        if (points.isEmpty()) {
            return when (yRangeMode) {
                YRangeMode.ZERO_BASED -> 0f to ChartScales.zeroBasedTop(0f)
                YRangeMode.AUTO_PADDED -> ChartScales.tempRange(0f, 0f)
            }
        }
        var dataMin = Float.MAX_VALUE
        var dataMax = -Float.MAX_VALUE
        for (p in points) {
            if (p.value < dataMin) dataMin = p.value
            if (p.value > dataMax) dataMax = p.value
        }
        return when (yRangeMode) {
            YRangeMode.ZERO_BASED -> 0f to ChartScales.zeroBasedTop(dataMax)
            YRangeMode.AUTO_PADDED -> ChartScales.tempRange(dataMin, dataMax)
        }
    }

    private fun xFor(timeSec: Float, spanSec: Float): Float =
        chartLeft + chartWidth * (timeSec / spanSec).coerceIn(0f, 1f)

    private fun yFor(value: Float, yMin: Float, yMax: Float): Float {
        val range = (yMax - yMin).coerceAtLeast(0.0001f)
        return (chartBottom - ((value - yMin) / range) * chartHeight)
            .coerceIn(chartTop, chartBottom)
    }

    private fun drawGrid(canvas: Canvas, spanSec: Float, yMin: Float, yMax: Float) {
        // Horizontal gridlines at round value steps.
        val step = ChartScales.niceStep(yMax - yMin, maxLines = 4)
        var v = gridStart(yMin, step)
        while (v < yMax) {
            if (v > yMin) {
                val y = yFor(v, yMin, yMax)
                canvas.drawLine(chartLeft, y, chartRight, y, gridPaint)
            }
            v += step
        }
        // Vertical gridlines at time ticks.
        val tickSec = ChartScales.timeTickStepSec(spanSec)
        var t = tickSec
        while (t < spanSec) {
            val x = xFor(t.toFloat(), spanSec)
            canvas.drawLine(x, chartTop, x, chartBottom, gridPaint)
            t += tickSec
        }
        canvas.drawRect(chartLeft, chartTop, chartRight, chartBottom, gridPaint)
    }

    /** First gridline value at or above yMin, aligned to multiples of step. */
    private fun gridStart(yMin: Float, step: Float): Float {
        if (yMin <= 0f && yMin > -step) return 0f
        return kotlin.math.ceil(yMin / step) * step
    }

    private fun drawAxisLabels(canvas: Canvas, spanSec: Float, yMin: Float, yMax: Float) {
        val labelX = chartLeft - 6 * resources.displayMetrics.density
        val baselineShift = yAxisLabelPaint.textSize / 3

        // Y-labels at the gridlines; the topmost carries the unit suffix.
        val step = ChartScales.niceStep(yMax - yMin, maxLines = 4)
        val values = mutableListOf<Float>()
        var v = gridStart(yMin, step)
        while (v < yMax) {
            if (v >= yMin) values.add(v)
            v += step
        }
        for ((index, value) in values.withIndex()) {
            val isTop = index == values.lastIndex
            val text = formatValueLabel(value) + if (isTop) unitSuffix else ""
            canvas.drawText(text, labelX, yFor(value, yMin, yMax) + baselineShift, yAxisLabelPaint)
        }

        // X-labels: "0", then time ticks; the last in-range tick keeps its unit
        // anyway ("15min", "1h"), so no extra suffix logic needed.
        val yText = chartBottom + 13 * resources.displayMetrics.density
        canvas.drawText("0", chartLeft, yText, axisLabelPaint)
        val tickSec = ChartScales.timeTickStepSec(spanSec)
        var t = tickSec
        while (t <= spanSec) {
            val x = xFor(t.toFloat(), spanSec)
            canvas.drawText(ChartScales.formatTimeTick(t), x, yText, axisLabelPaint)
            t += tickSec
        }
    }

    private fun formatValueLabel(value: Float): String =
        if (value == value.toInt().toFloat()) value.toInt().toString()
        else String.format(java.util.Locale.US, "%.1f", value)

    private fun drawLine(canvas: Canvas, spanSec: Float, yMin: Float, yMax: Float) {
        linePath.reset()
        fillPath.reset()
        var started = false
        var lastX = chartLeft
        for (p in points) {
            val x = xFor(p.timeSec, spanSec)
            val y = yFor(p.value, yMin, yMax)
            if (!started) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, chartBottom)
                fillPath.lineTo(x, y)
                started = true
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
            lastX = x
        }
        if (!started) return
        fillPath.lineTo(lastX, chartBottom)
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)
    }

    private fun drawSinglePoint(canvas: Canvas, spanSec: Float, yMin: Float, yMax: Float) {
        val p = points.first()
        canvas.drawCircle(
            xFor(p.timeSec, spanSec),
            yFor(p.value, yMin, yMax),
            3f * resources.displayMetrics.density,
            pointPaint
        )
    }

    private fun drawNoDataMessage(canvas: Canvas, message: String) {
        canvas.drawText(
            message,
            chartLeft + chartWidth / 2,
            chartTop + chartHeight / 2 + noDataPaint.textSize / 3,
            noDataPaint
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = (300 * resources.displayMetrics.density).toInt()
        val desiredHeight = (100 * resources.displayMetrics.density).toInt()
        val width = resolveSize(desiredWidth, widthMeasureSpec)
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }
}
