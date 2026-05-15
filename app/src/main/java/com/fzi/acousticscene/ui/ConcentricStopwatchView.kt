package com.fzi.acousticscene.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.fzi.acousticscene.R

/**
 * Concentric session/cycle stopwatch for the live recording screen.
 *
 * - Outer ring: session progress (elapsed / total). Greyed out when the session
 *   has no fixed duration (manual stop).
 * - Inner ring: current cycle progress (recording / clip duration). Optionally
 *   subdivided into [cycleSegments] arcs (used for the AVERAGE 10×1 s case).
 * - Center: a `mm:ss` (or `hh:mm:ss`) elapsed-time label, plus a `/ total` hint
 *   when the session has a fixed duration.
 */
class ConcentricStopwatchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val outerTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.surface_dark)
        strokeWidth = dp(8f)
        strokeCap = Paint.Cap.ROUND
    }
    private val outerActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.accent_blue)
        strokeWidth = dp(8f)
        strokeCap = Paint.Cap.ROUND
    }
    private val innerTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.surface_dark)
        strokeWidth = dp(6f)
        strokeCap = Paint.Cap.ROUND
    }
    private val innerActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.accent_green)
        strokeWidth = dp(6f)
        strokeCap = Paint.Cap.ROUND
    }
    private val timeLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        textSize = sp(20f)
        textAlign = Paint.Align.CENTER
    }
    private val totalLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textSize = sp(12f)
        textAlign = Paint.Align.CENTER
    }
    private val rectOuter = RectF()
    private val rectInner = RectF()

    var sessionElapsedMs: Long = 0L
        set(value) { field = value; invalidate() }
    var sessionTotalMs: Long? = null
        set(value) { field = value; invalidate() }

    /** 0.0 – 1.0 */
    var cycleProgress: Float = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }

    /**
     * If > 1 the inner ring renders as N segments; otherwise as a single arc.
     * Set this to 10 when AVERAGE mode is recording.
     */
    var cycleSegments: Int = 1
        set(value) { field = value.coerceAtLeast(1); invalidate() }

    var paused: Boolean = false
        set(value) { field = value; invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(dp(160f).toInt())
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outerStroke = outerActivePaint.strokeWidth
        val innerStroke = innerActivePaint.strokeWidth
        val outerRadius = (width.coerceAtMost(height) / 2f) - outerStroke
        val innerRadius = outerRadius - dp(16f) - innerStroke

        rectOuter.set(cx - outerRadius, cy - outerRadius, cx + outerRadius, cy + outerRadius)
        rectInner.set(cx - innerRadius, cy - innerRadius, cx + innerRadius, cy + innerRadius)

        // Outer ring: session
        canvas.drawArc(rectOuter, 0f, 360f, false, outerTrackPaint)
        val total = sessionTotalMs
        if (total != null && total > 0) {
            val sweep = 360f * (sessionElapsedMs.toFloat() / total).coerceIn(0f, 1f)
            canvas.drawArc(rectOuter, -90f, sweep, false, outerActivePaint)
        }

        // Inner ring: cycle
        if (cycleSegments == 1) {
            canvas.drawArc(rectInner, 0f, 360f, false, innerTrackPaint)
            canvas.drawArc(rectInner, -90f, 360f * cycleProgress, false, innerActivePaint)
        } else {
            val segDeg = 360f / cycleSegments
            val gapDeg = 4f
            val activeSegs = (cycleProgress * cycleSegments).toInt().coerceAtMost(cycleSegments)
            for (i in 0 until cycleSegments) {
                val startAngle = -90f + i * segDeg + gapDeg / 2f
                val sweep = segDeg - gapDeg
                canvas.drawArc(rectInner, startAngle, sweep, false, innerTrackPaint)
                if (i < activeSegs) {
                    canvas.drawArc(rectInner, startAngle, sweep, false, innerActivePaint)
                }
            }
        }

        // Center label
        val timeText = formatElapsed(sessionElapsedMs)
        val baselineY = cy - (timeLabelPaint.descent() + timeLabelPaint.ascent()) / 2f
        canvas.drawText(timeText, cx, baselineY, timeLabelPaint)
        if (total != null && total > 0) {
            canvas.drawText("/ ${formatElapsed(total)}", cx, baselineY + sp(20f), totalLabelPaint)
        } else if (paused) {
            canvas.drawText("⏸", cx, baselineY + sp(20f), totalLabelPaint)
        }
    }

    private fun formatElapsed(ms: Long): String {
        val total = ms / 1000L
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val s = total % 60L
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity
}
