package com.fzi.speakerid.ui.physicsarena

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.fzi.acousticscene.R

/**
 * Port des Anteils-Balkens aus `<ArenaTableRow>` (physics_arena.kv):
 * Track = surface_dark, RoundedRectangle radius RADIUS_CARD dp(8);
 * Fuellung = row_color mit Breite `width * (percentage / 100)`.
 */
class SpeakerIdPercentBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val cornerRadius = 8f * resources.displayMetrics.density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.speakerid_surface_dark)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.speakerid_white)
    }

    /** Fortschritt 0..100 (`root.percentage`). */
    var percentage: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    /** `root.row_color` der Fuellung. */
    fun setData(fillColor: Int, percentage: Float) {
        fillPaint.color = fillColor
        this.percentage = percentage
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        canvas.drawRoundRect(0f, 0f, w, h, cornerRadius, cornerRadius, trackPaint)
        val fillW = w * (percentage.coerceIn(0f, 100f) / 100f)
        if (fillW > 0f) {
            canvas.drawRoundRect(0f, 0f, fillW, h, cornerRadius, cornerRadius, fillPaint)
        }
    }
}
