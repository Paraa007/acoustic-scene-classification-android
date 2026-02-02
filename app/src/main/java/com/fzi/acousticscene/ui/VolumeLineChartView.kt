package com.fzi.acousticscene.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.fzi.acousticscene.R
import kotlin.math.max

/**
 * Custom View for real-time volume visualization as a line chart
 *
 * Features:
 * - Displays volume (0-100%) over time as a line graph
 * - X-axis adapts to recording mode duration (1s, 5s, 10s, etc.)
 * - Rolling window behavior: fills left to right, clears at cycle end
 * - Performance optimized: only draws when enabled
 * - Aggregates data at ~50ms intervals (20 FPS)
 *
 * @author FZI
 */
class VolumeLineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "VolumeLineChartView"

        // Data aggregation interval in milliseconds (~20 FPS)
        const val DATA_INTERVAL_MS = 50L

        // Grid line count
        private const val HORIZONTAL_GRID_LINES = 4  // 25%, 50%, 75%, 100%
        private const val VERTICAL_GRID_LINES = 5    // Time markers

        // Padding in dp
        private const val PADDING_LEFT_DP = 32f    // Space for Y-axis labels
        private const val PADDING_RIGHT_DP = 16f
        private const val PADDING_TOP_DP = 16f
        private const val PADDING_BOTTOM_DP = 24f  // Space for X-axis labels
    }

    // Paints for drawing
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
        alpha = 40  // Semi-transparent fill
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
        color = ContextCompat.getColor(context, R.color.surface_variant)
    }

    private val axisLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * resources.displayMetrics.scaledDensity
        color = ContextCompat.getColor(context, R.color.text_secondary)
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

    // Path for drawing the line
    private val linePath = Path()
    private val fillPath = Path()

    // Data points (volume values 0.0-1.0)
    private val dataPoints = mutableListOf<Float>()

    // Maximum duration in seconds (X-axis range)
    private var maxDurationSeconds: Float = 10f

    // Whether the view is enabled and should draw
    private var isDrawingEnabled: Boolean = false

    // Calculated padding in pixels
    private val paddingLeftPx: Float get() = PADDING_LEFT_DP * resources.displayMetrics.density
    private val paddingRightPx: Float get() = PADDING_RIGHT_DP * resources.displayMetrics.density
    private val paddingTopPx: Float get() = PADDING_TOP_DP * resources.displayMetrics.density
    private val paddingBottomPx: Float get() = PADDING_BOTTOM_DP * resources.displayMetrics.density

    // Chart area dimensions
    private val chartLeft: Float get() = paddingLeftPx
    private val chartRight: Float get() = width - paddingRightPx
    private val chartTop: Float get() = paddingTopPx
    private val chartBottom: Float get() = height - paddingBottomPx
    private val chartWidth: Float get() = chartRight - chartLeft
    private val chartHeight: Float get() = chartBottom - chartTop

    /**
     * Maximum number of data points based on duration and interval
     */
    private val maxDataPoints: Int
        get() = ((maxDurationSeconds * 1000) / DATA_INTERVAL_MS).toInt()

    /**
     * Adds a new volume data point (0.0 - 1.0)
     * Called from MainActivity at ~50ms intervals
     */
    fun addDataPoint(volume: Float) {
        if (!isDrawingEnabled) return

        val clampedVolume = volume.coerceIn(0f, 1f)

        // Clear if we've reached max points (oscilloscope sweep behavior)
        if (dataPoints.size >= maxDataPoints) {
            dataPoints.clear()
        }

        dataPoints.add(clampedVolume)

        // Trigger redraw
        invalidate()
    }

    /**
     * Sets the maximum duration for the X-axis
     * Based on the recording mode (1s, 5s, 10s, etc.)
     */
    fun setMaxDuration(seconds: Float) {
        maxDurationSeconds = max(1f, seconds)
        // Clear data when duration changes
        dataPoints.clear()
        invalidate()
    }

    /**
     * Enables or disables drawing
     * When disabled, saves GPU resources
     */
    fun setDrawingEnabled(enabled: Boolean) {
        isDrawingEnabled = enabled
        if (!enabled) {
            dataPoints.clear()
        }
        invalidate()
    }

    /**
     * Checks if drawing is currently enabled
     */
    fun isDrawingEnabled(): Boolean = isDrawingEnabled

    /**
     * Clears all data points
     */
    fun clearData() {
        dataPoints.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw grid and axes
        drawGrid(canvas)
        drawAxisLabels(canvas)

        // Don't draw data if not enabled or no data
        if (!isDrawingEnabled) {
            drawNoDataMessage(canvas, context.getString(R.string.volume_graph_disabled))
            return
        }

        if (dataPoints.isEmpty()) {
            drawNoDataMessage(canvas, context.getString(R.string.volume_graph_waiting))
            return
        }

        // Draw the volume line
        drawVolumeLine(canvas)
    }

    /**
     * Draws the background grid
     */
    private fun drawGrid(canvas: Canvas) {
        // Horizontal grid lines (volume levels)
        for (i in 1..HORIZONTAL_GRID_LINES) {
            val y = chartTop + (chartHeight * i / (HORIZONTAL_GRID_LINES + 1))
            canvas.drawLine(chartLeft, y, chartRight, y, gridPaint)
        }

        // Vertical grid lines (time markers)
        for (i in 1..VERTICAL_GRID_LINES) {
            val x = chartLeft + (chartWidth * i / (VERTICAL_GRID_LINES + 1))
            canvas.drawLine(x, chartTop, x, chartBottom, gridPaint)
        }

        // Draw chart border
        canvas.drawRect(chartLeft, chartTop, chartRight, chartBottom, gridPaint)
    }

    /**
     * Draws axis labels
     */
    private fun drawAxisLabels(canvas: Canvas) {
        // Y-axis labels (0%, 50%, 100%)
        val yLabels = listOf("0", "50", "100")
        val yPositions = listOf(chartBottom, chartTop + chartHeight / 2, chartTop)

        for (i in yLabels.indices) {
            val y = yPositions[i] + axisLabelPaint.textSize / 3
            canvas.drawText(yLabels[i], chartLeft - 8 * resources.displayMetrics.density, y, yAxisLabelPaint)
        }

        // X-axis labels (time in seconds)
        val xLabels = when {
            maxDurationSeconds <= 1f -> listOf("0", "0.5", "1s")
            maxDurationSeconds <= 5f -> listOf("0", "2.5", "5s")
            maxDurationSeconds <= 10f -> listOf("0", "5", "10s")
            else -> listOf("0", "${(maxDurationSeconds / 2).toInt()}", "${maxDurationSeconds.toInt()}s")
        }

        val xPositions = listOf(chartLeft, chartLeft + chartWidth / 2, chartRight)
        val textAligns = listOf(Paint.Align.LEFT, Paint.Align.CENTER, Paint.Align.RIGHT)

        for (i in xLabels.indices) {
            axisLabelPaint.textAlign = textAligns[i]
            val y = chartBottom + 16 * resources.displayMetrics.density
            canvas.drawText(xLabels[i], xPositions[i], y, axisLabelPaint)
        }
    }

    /**
     * Draws the volume line chart
     */
    private fun drawVolumeLine(canvas: Canvas) {
        if (dataPoints.size < 2) return

        linePath.reset()
        fillPath.reset()

        val pointWidth = chartWidth / maxDataPoints

        // Start fill path at bottom left
        fillPath.moveTo(chartLeft, chartBottom)

        for ((index, volume) in dataPoints.withIndex()) {
            val x = chartLeft + (index * pointWidth)
            val y = chartBottom - (volume * chartHeight)

            if (index == 0) {
                linePath.moveTo(x, y)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }

        // Close fill path
        val lastX = chartLeft + ((dataPoints.size - 1) * pointWidth)
        fillPath.lineTo(lastX, chartBottom)
        fillPath.close()

        // Draw fill first (behind line)
        canvas.drawPath(fillPath, fillPaint)

        // Draw line on top
        canvas.drawPath(linePath, linePaint)
    }

    /**
     * Draws a message when there's no data
     */
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

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }
}
