package com.fzi.acousticscene.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.fzi.acousticscene.R
import com.fzi.acousticscene.model.SceneClass

/**
 * Renders a sorted-descending list of class probabilities as horizontal bars.
 *
 * Each row: a colored bar (length proportional to probability, color from
 * [SceneClass.color]) with `<emoji> <className>` on the left and the
 * percentage on the right, both in standard text colors so the bar's color
 * carries the class signal alone.
 */
class BarDistributionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.surface_dark)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        textSize = sp(13f)
        isAntiAlias = true
    }
    private val percentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textSize = sp(12f)
        textAlign = Paint.Align.RIGHT
    }
    private val rect = RectF()

    private var probabilities: FloatArray = FloatArray(0)
    private var sortedClasses: List<SceneClass> = emptyList()

    private val rowHeightPx = dp(24f)
    private val rowGapPx = dp(6f)
    private val labelWidthPx = dp(120f)
    private val percentWidthPx = dp(44f)

    fun setProbabilities(probs: FloatArray) {
        probabilities = probs.copyOf()
        sortedClasses = SceneClass.entries
            .sortedByDescending { probabilities.getOrElse(it.index) { 0f } }
            .take(probabilities.size)
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val rowsToShow = sortedClasses.size.coerceAtLeast(1)
        val height = (rowHeightPx * rowsToShow + rowGapPx * (rowsToShow - 1).coerceAtLeast(0)).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        if (sortedClasses.isEmpty()) return
        val left = paddingLeft + labelWidthPx
        val right = (width - paddingRight - percentWidthPx)
        val barAreaWidth = (right - left).coerceAtLeast(0f)
        var top = paddingTop.toFloat()
        for (sc in sortedClasses) {
            val prob = probabilities.getOrElse(sc.index) { 0f }
            val bottom = top + rowHeightPx

            // Track behind bar
            rect.set(left, top + dp(4f), right, bottom - dp(4f))
            canvas.drawRoundRect(rect, dp(4f), dp(4f), trackPaint)

            // Filled bar
            val barEnd = left + barAreaWidth * prob.coerceIn(0f, 1f)
            barPaint.color = sceneColor(sc)
            rect.set(left, top + dp(4f), barEnd, bottom - dp(4f))
            canvas.drawRoundRect(rect, dp(4f), dp(4f), barPaint)

            // Label (emoji + short label)
            val labelText = "${sc.emoji} ${sc.labelShort}"
            val labelY = top + rowHeightPx / 2f - (labelPaint.descent() + labelPaint.ascent()) / 2f
            canvas.drawText(labelText, paddingLeft.toFloat(), labelY, labelPaint)

            // Percentage
            val pct = (prob * 100).toInt()
            canvas.drawText("$pct%", width.toFloat() - paddingRight, labelY, percentPaint)

            top = bottom + rowGapPx
        }
    }

    private fun sceneColor(sc: SceneClass): Int {
        // SceneClass.color isn't part of the enum — fall back to an emoji-driven palette.
        return classColorPalette[sc.index % classColorPalette.size]
    }

    private val classColorPalette = intArrayOf(
        Color.parseColor("#EF5350"), // Transit/Vehicles → red
        Color.parseColor("#FFA726"), // Urban/Waiting → orange
        Color.parseColor("#66BB6A"), // Nature → green
        Color.parseColor("#42A5F5"), // Social → blue
        Color.parseColor("#AB47BC"), // Work → purple
        Color.parseColor("#FFCA28"), // Commercial → amber
        Color.parseColor("#26A69A"), // Leisure/Sports → teal
        Color.parseColor("#7E57C2"), // Culture/Quiet → deep purple
        Color.parseColor("#8D6E63")  // Living Room → brown
    )

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
}
