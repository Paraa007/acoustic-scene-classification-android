package com.fzi.acousticscene.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.fzi.acousticscene.R

/**
 * Real-time volume chart with a fixed 10 s X-axis.
 *
 * The X-axis is hard-wired to 10 seconds regardless of the recording mode —
 * one second per tick (0,1,2,…,10). Samples are placed by `frameElapsedMs`
 * coming from the ViewModel, so the chart stays in lock-step with the inner
 * stopwatch ring (both read the same frame clock). When a new frame begins,
 * `frameElapsedMs` jumps back to 0 and the chart clears.
 *
 * While the session is paused the chart freezes — the last drawn line stays
 * on screen until the next frame starts.
 */
class VolumeLineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val FRAME_DURATION_MS = 10_000L
        private const val SLOT_MS = 50L
        private const val SLOT_COUNT = (FRAME_DURATION_MS / SLOT_MS).toInt() // 200

        private const val PADDING_LEFT_DP = 32f
        private const val PADDING_RIGHT_DP = 16f
        private const val PADDING_TOP_DP = 16f
        private const val PADDING_BOTTOM_DP = 24f
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ContextCompat.getColor(context, R.color.accent_green_light)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.accent_green)
        alpha = 40
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
        textSize = 10f * resources.displayMetrics.scaledDensity
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textAlign = Paint.Align.RIGHT
    }

    private val noDataPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 14f * resources.displayMetrics.scaledDensity
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textAlign = Paint.Align.CENTER
    }

    private val linePath = Path()
    private val fillPath = Path()

    // Sparse buffer: slot index 0..199 → volume (0..1) or NaN if not yet seen.
    // Index = frameElapsedMs / SLOT_MS. Slot index lets the same wall-clock-driven
    // time always map to the same X — that's what keeps the chart in sync with
    // the stopwatch.
    private val slotVolumes = FloatArray(SLOT_COUNT) { Float.NaN }
    private var lastFrameElapsedMs: Long = -1L
    private var highestSlot: Int = -1

    private var isDrawingEnabled: Boolean = false

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
     * Records a volume sample at the given frame-clock position. The chart
     * automatically clears whenever `frameElapsedMs` jumps back (new frame
     * starts).
     */
    fun submitSample(volume: Float, frameElapsedMs: Long) {
        if (!isDrawingEnabled) return
        if (frameElapsedMs < lastFrameElapsedMs) {
            clearData()
        }
        lastFrameElapsedMs = frameElapsedMs
        val slot = (frameElapsedMs / SLOT_MS).toInt().coerceIn(0, SLOT_COUNT - 1)
        slotVolumes[slot] = volume.coerceIn(0f, 1f)
        if (slot > highestSlot) highestSlot = slot
        invalidate()
    }

    fun setDrawingEnabled(enabled: Boolean) {
        isDrawingEnabled = enabled
        if (!enabled) clearData()
        invalidate()
    }

    fun isDrawingEnabled(): Boolean = isDrawingEnabled

    fun clearData() {
        for (i in slotVolumes.indices) slotVolumes[i] = Float.NaN
        highestSlot = -1
        lastFrameElapsedMs = -1L
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        drawGrid(canvas)
        drawAxisLabels(canvas)

        if (!isDrawingEnabled) {
            drawNoDataMessage(canvas, context.getString(R.string.volume_graph_disabled))
            return
        }
        if (highestSlot < 0) {
            drawNoDataMessage(canvas, context.getString(R.string.volume_graph_waiting))
            return
        }
        drawVolumeLine(canvas)
    }

    /**
     * Eleven vertical gridlines (every second 0..10) + four horizontal lines
     * for the Y-axis (25/50/75/100%).
     */
    private fun drawGrid(canvas: Canvas) {
        // Horizontal lines at 25/50/75/100%
        for (i in 1..4) {
            val y = chartTop + (chartHeight * i / 5f)
            canvas.drawLine(chartLeft, y, chartRight, y, gridPaint)
        }
        // Vertical lines every second (1..9, plus 0 and 10 = chart borders)
        for (s in 1..9) {
            val x = chartLeft + chartWidth * (s / 10f)
            canvas.drawLine(x, chartTop, x, chartBottom, gridPaint)
        }
        // Border
        canvas.drawRect(chartLeft, chartTop, chartRight, chartBottom, gridPaint)
    }

    /**
     * X-axis labels: 0,1,2,...,9,10s. Y-axis: 0/50/100.
     */
    private fun drawAxisLabels(canvas: Canvas) {
        // Y-axis
        val yLabels = listOf("0", "50", "100")
        val yPositions = listOf(chartBottom, chartTop + chartHeight / 2, chartTop)
        for (i in yLabels.indices) {
            val y = yPositions[i] + yAxisLabelPaint.textSize / 3
            canvas.drawText(yLabels[i], chartLeft - 8 * resources.displayMetrics.density, y, yAxisLabelPaint)
        }

        // X-axis: 11 labels (0..10). Label at "10" is suffixed with "s" so the
        // user knows the unit; the others stay bare digits to keep the line tidy.
        val yText = chartBottom + 14 * resources.displayMetrics.density
        for (s in 0..10) {
            val x = chartLeft + chartWidth * (s / 10f)
            val text = if (s == 10) "10s" else s.toString()
            canvas.drawText(text, x, yText, axisLabelPaint)
        }
    }

    private fun drawVolumeLine(canvas: Canvas) {
        linePath.reset()
        fillPath.reset()
        var started = false
        var lastX = chartLeft
        for (i in 0..highestSlot) {
            val v = slotVolumes[i]
            if (v.isNaN()) continue
            val x = chartLeft + chartWidth * (i.toFloat() / SLOT_COUNT)
            val y = chartBottom - v * chartHeight
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

    private fun drawNoDataMessage(canvas: Canvas, message: String) {
        val x = chartLeft + chartWidth / 2
        val y = chartTop + chartHeight / 2
        canvas.drawText(message, x, y, noDataPaint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = 300.dpToPx()
        val desiredHeight = 160.dpToPx()
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val width = when (widthMode) {
            MeasureSpec.EXACTLY -> widthSize
            MeasureSpec.AT_MOST -> minOf(desiredWidth, widthSize)
            else -> desiredWidth
        }
        val height = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> minOf(desiredHeight, heightSize)
            else -> desiredHeight
        }
        setMeasuredDimension(width, height)
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()
}
