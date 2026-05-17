package com.fzi.acousticscene.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.fzi.acousticscene.R
import kotlin.math.min

/**
 * Custom View für kreisförmigen Confidence Indicator
 * 
 * Zeigt Konfidenz als animierten Kreis mit Prozent-Anzeige
 * Design: Dark Theme mit grünem Gradient
 */
class ConfidenceCircleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var targetSizeDp: Int = 200
    private var scale: Float = 1f

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 12f
        color = ContextCompat.getColor(context, R.color.surface_variant)
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 12f
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.accent_green)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.text_primary)
        textSize = 48f * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }

    private val percentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textSize = 24f * resources.displayMetrics.scaledDensity
    }

    private var confidence: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private val rect = RectF()

    // Eigener Handler statt eines anonymen Handlers pro setConfidence(animate=true)-Call.
    // So können wir laufende Animations-Runnables in onDetachedFromWindow stoppen und
    // verhindern, dass die View über das Runnable die Activity am Leben hält.
    private val animationHandler = Handler(Looper.getMainLooper())
    private var pendingAnimation: Runnable? = null

    fun setTargetSize(sizeDp: Int) {
        targetSizeDp = sizeDp
        scale = sizeDp / 200f
        backgroundPaint.strokeWidth = 12f * scale
        progressPaint.strokeWidth = 12f * scale
        textPaint.textSize = 48f * scale * resources.displayMetrics.scaledDensity
        percentPaint.textSize = 24f * scale * resources.displayMetrics.scaledDensity
        requestLayout()
        invalidate()
    }

    /**
     * Setzt die Konfidenz (0.0 - 1.0) und animiert die Änderung
     */
    fun setConfidence(value: Float, animate: Boolean = true) {
        // Vorherige Animation stoppen — sonst überlagern sich Step-Sequenzen,
        // wenn setConfidence schneller aufgerufen wird als die 30-Frame-Animation
        // durchläuft.
        pendingAnimation?.let { animationHandler.removeCallbacks(it) }
        pendingAnimation = null

        if (animate) {
            val target = value.coerceIn(0f, 1f)
            val start = confidence
            val steps = 30
            val stepSize = (target - start) / steps

            val runnable = object : Runnable {
                private var currentStep = 0
                override fun run() {
                    if (currentStep < steps) {
                        confidence = start + stepSize * (currentStep + 1)
                        currentStep++
                        animationHandler.postDelayed(this, 16) // ~60 FPS
                    } else {
                        confidence = target
                        pendingAnimation = null
                    }
                }
            }
            pendingAnimation = runnable
            animationHandler.post(runnable)
        } else {
            confidence = value
        }
    }

    override fun onDetachedFromWindow() {
        // Laufende Animation abbrechen, sonst hält das gepostete Runnable
        // die View und die View über ihren Context die Activity am Leben.
        animationHandler.removeCallbacksAndMessages(null)
        pendingAnimation = null
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = targetSizeDp.dpToPx()
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        val centerY = height / 2f
        val radius = min(width, height) / 2f - 12f * scale

        rect.set(
            centerX - radius,
            centerY - radius,
            centerX + radius,
            centerY + radius
        )

        // Hintergrund-Kreis
        canvas.drawArc(rect, 0f, 360f, false, backgroundPaint)

        // Progress-Kreis (von oben, im Uhrzeigersinn)
        val sweepAngle = confidence * 360f
        canvas.drawArc(rect, -90f, sweepAngle, false, progressPaint)

        // Prozent-Text
        val percent = (confidence * 100).toInt()
        val percentText = "$percent"
        val percentY = centerY - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(percentText, centerX, percentY, textPaint)

        // "%" Symbol - nach unten verschoben, damit es die Zahl nicht überschneidet
        val symbolY = centerY + percentPaint.textSize * 1.8f
        canvas.drawText("%", centerX, symbolY, percentPaint)
    }

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }
}
