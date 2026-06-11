package com.fzi.speakerid.ui.statistics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.fzi.acousticscene.R

/**
 * Port des Fortschrittsbalkens aus statistics.kv `<SpeakerStatsItem>`:
 * RoundedRectangle (radius dp(6)) in surface_dark als Hintergrund, darueber
 * die Sprecherfarbe mit Breite `width * (percentage / 100)`.
 */
class SpeakerStatisticsBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Redeanteil in Prozent (0..100). */
    var percentage: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    /** Sprecherfarbe (`root.color_rgba`). */
    var fillColor: Int = ContextCompat.getColor(context, R.color.speakerid_text_secondary)
        set(value) {
            field = value
            invalidate()
        }

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.speakerid_surface_dark)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private val radius = 6f * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, radius, radius, backgroundPaint)

        val fillWidth = w * (percentage / 100f)
        if (fillWidth > 0f) {
            fillPaint.color = fillColor
            rect.set(0f, 0f, fillWidth, h)
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
        }
    }
}
