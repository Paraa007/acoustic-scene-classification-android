package com.fzi.acousticscene.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.fzi.acousticscene.R
import com.fzi.acousticscene.model.RecordingMode

/**
 * Schematic timeline that visualizes how a RecordingMode behaves over time.
 * Recording stretches are drawn as filled blocks, pauses as gaps with a label.
 */
class ModeTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var mode: RecordingMode = RecordingMode.STANDARD

    private val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent_green)
        style = Paint.Style.FILL
    }
    private val gapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.surface_variant)
        style = Paint.Style.FILL
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textSize = dp(11f)
        textAlign = Paint.Align.CENTER
    }
    private val pauseLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textSize = dp(10f)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val smallLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textSize = dp(8f)
        textAlign = Paint.Align.CENTER
    }
    private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent_green)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }

    fun setMode(mode: RecordingMode) {
        if (this.mode != mode) {
            this.mode = mode
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val desiredH = dp(100f).toInt()
        setMeasuredDimension(w, resolveSize(desiredH, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val padH = dp(4f)
        val trackTop = dp(14f)
        val trackBottom = dp(44f)
        val axisY = trackBottom + dp(3f)

        // Segments: list of Pair(widthWeight, isRecording) with optional label
        data class Segment(val weight: Float, val isRecording: Boolean, val label: String? = null, val topLabel: String? = null)

        val segments: List<Segment> = when (mode) {
            RecordingMode.FAST -> List(6) { Segment(1f, true, "1s") }
            RecordingMode.STANDARD -> List(3) { Segment(1f, true, "10s") }
            RecordingMode.AVERAGE -> List(10) { Segment(1f, true, "1s") }
            RecordingMode.LONG -> listOf(
                Segment(1f, true, "10s"),
                Segment(4f, false, null, context.getString(R.string.timeline_pause_30min)),
                Segment(1f, true, "10s"),
                Segment(4f, false, null, context.getString(R.string.timeline_pause_30min)),
                Segment(1f, true, "10s")
            )
        }

        val totalWeight = segments.sumOf { it.weight.toDouble() }.toFloat()
        val usable = w - 2 * padH
        var x = padH

        // Axis line
        canvas.drawLine(padH, axisY, w - padH, axisY, axisPaint)

        val topLabelPaint = if (mode == RecordingMode.AVERAGE) smallLabelPaint else labelPaint
        val firstBlockLeft = padH
        var lastBlockRight = padH

        for (seg in segments) {
            val segW = usable * (seg.weight / totalWeight)
            if (seg.isRecording) {
                val rect = RectF(x + dp(1f), trackTop, x + segW - dp(1f), trackBottom)
                canvas.drawRoundRect(rect, dp(3f), dp(3f), blockPaint)
                seg.label?.let {
                    canvas.drawText(it, x + segW / 2f, trackTop - dp(4f), topLabelPaint)
                }
                lastBlockRight = x + segW
            } else {
                // pause gap — draw subtle dotted/filled thin bar at axis level
                val rect = RectF(x, axisY - dp(1f), x + segW, axisY + dp(1f))
                canvas.drawRect(rect, gapPaint)
                seg.topLabel?.let {
                    canvas.drawText(it, x + segW / 2f, axisY + dp(14f), pauseLabelPaint)
                }
            }
            x += segW
        }

        // AVERAGE: draw a bracket under all 10 blocks with an explanatory label
        if (mode == RecordingMode.AVERAGE) {
            val bracketTop = axisY + dp(6f)
            val bracketBottom = bracketTop + dp(6f)
            val path = Path().apply {
                moveTo(firstBlockLeft, bracketTop)
                lineTo(firstBlockLeft, bracketBottom)
                lineTo(lastBlockRight, bracketBottom)
                lineTo(lastBlockRight, bracketTop)
            }
            canvas.drawPath(path, bracketPaint)
            // tick pointing down to label
            val midX = (firstBlockLeft + lastBlockRight) / 2f
            canvas.drawLine(midX, bracketBottom, midX, bracketBottom + dp(4f), bracketPaint)
            canvas.drawText(
                "10 × 1s → 1 averaged result",
                midX,
                bracketBottom + dp(16f),
                pauseLabelPaint
            )
        }

        // Bottom summary label
        val summary = when (mode) {
            RecordingMode.FAST -> "continuous, 1 s per recording"
            RecordingMode.STANDARD -> "continuous, 10 s per recording"
            RecordingMode.AVERAGE -> "continuous, 10 × 1 s averaged into one result"
            RecordingMode.LONG -> "10 s record · 30 min pause · repeat"
        }
        canvas.drawText(summary, w / 2f, h - dp(6f), labelPaint)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
