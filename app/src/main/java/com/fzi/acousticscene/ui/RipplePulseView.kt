package com.fzi.acousticscene.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.fzi.acousticscene.R

/**
 * Custom View für Lautstärke-Visualisierung mit Sonar/Ripple-Effekt.
 *
 * Erzeugt konzentrische Kreise die sich vom Zentrum nach außen bewegen.
 * Inspiriert von Google Assistant / Siri Listening-Animation.
 *
 * Verhalten:
 * - Bei Stille: Keine Wellen
 * - Bei Audio: Wellen entstehen und bewegen sich nach außen
 * - Je lauter, desto sichtbarer die Wellen
 *
 * Performance: Nutzt Canvas.drawCircle für 60 FPS Animation.
 */
class RipplePulseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /**
     * Repräsentiert eine einzelne Welle (Ripple)
     */
    private data class Ripple(
        var currentRadius: Float,
        var alpha: Int,
        val maxAlpha: Int
    )

    private val ripples = mutableListOf<Ripple>()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = ContextCompat.getColor(context, R.color.accent_green)
    }

    // Timing für neue Wellen
    private var lastRippleTime = 0L
    private var minTimeBetweenRipples = 200L  // Mindestabstand zwischen Wellen in ms

    // Animations-Parameter
    private val rippleExpansionSpeed = 3f     // Pixel pro Frame
    private val alphaDecreaseRate = 2         // Alpha-Abnahme pro Frame
    private val maxRippleRadius get() = width.coerceAtLeast(height) / 2f

    // Aktueller Lautstärke-Wert (0.0 - 1.0)
    private var currentVolume = 0f

    /**
     * Setzt die aktuelle Lautstärke und erzeugt ggf. neue Wellen.
     *
     * @param volume Normalisierte Lautstärke (0.0 = Stille, 1.0 = Sehr laut)
     */
    fun setVolume(volume: Float) {
        currentVolume = volume.coerceIn(0f, 1f)

        val now = System.currentTimeMillis()
        val timeSinceLastRipple = now - lastRippleTime

        // Je lauter, desto häufiger neue Wellen
        val volumeBasedInterval = (300 - (currentVolume * 200)).toLong().coerceIn(100, 300)

        // Nur neue Welle erzeugen wenn:
        // 1. Lautstärke über Schwelle (0.05 = sehr leise Geräusche ignorieren)
        // 2. Genug Zeit seit letzter Welle
        if (currentVolume > 0.05f && timeSinceLastRipple > volumeBasedInterval) {
            // Initial-Alpha basierend auf Lautstärke (lauter = sichtbarer)
            val initialAlpha = (currentVolume * 180).toInt().coerceIn(40, 200)

            ripples.add(Ripple(
                currentRadius = 20f,  // Startet klein im Zentrum
                alpha = initialAlpha,
                maxAlpha = initialAlpha
            ))

            lastRippleTime = now
        }

        // Trigger Redraw wenn Wellen vorhanden
        if (ripples.isNotEmpty() || currentVolume > 0.05f) {
            invalidate()
        }
    }

    /**
     * Gibt die aktuelle Lautstärke als Prozent (0-100) zurück
     */
    fun getVolumePercent(): Int = (currentVolume * 100).toInt()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f

        // Zeichne alle aktiven Wellen
        val iterator = ripples.iterator()
        while (iterator.hasNext()) {
            val ripple = iterator.next()

            // Setze Alpha und zeichne Kreis
            paint.alpha = ripple.alpha
            canvas.drawCircle(cx, cy, ripple.currentRadius, paint)

            // Animation: Vergrößere Radius und verringere Alpha
            ripple.currentRadius += rippleExpansionSpeed
            ripple.alpha = (ripple.alpha - alphaDecreaseRate).coerceAtLeast(0)

            // Entferne Welle wenn unsichtbar oder zu groß
            if (ripple.alpha <= 0 || ripple.currentRadius > maxRippleRadius) {
                iterator.remove()
            }
        }

        // Kontinuierliche Animation solange Wellen existieren
        if (ripples.isNotEmpty()) {
            postInvalidateOnAnimation()
        }
    }

    /**
     * Stoppt alle Animationen und entfernt alle Wellen
     */
    fun clear() {
        ripples.clear()
        currentVolume = 0f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        clear()
    }
}
