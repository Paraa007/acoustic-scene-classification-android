package com.fzi.speakerid.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.fzi.acousticscene.R
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Port der Inline-Slider-Cards aus siqas
 * `src/gui/screens/settings/settings.kv` (SharedSettingsPanel) — eine
 * Surface-Karte (RADIUS_CARD 8dp, Padding [15,12], Hoehe dp(100), Spacing 5)
 * mit Kopfzeile (22dp: Label links + Wertanzeige, size_hint 1 : 0.15) und
 * darunter dem "TriangleSlider" aus derselben .kv-Datei.
 *
 * Unterschiede zum Fundament-Widget `SpeakerIdLabeledSlider` (das
 * `labeled_slider.kv` portiert): Karten-Hintergrund, Hoehe 100 statt 80,
 * Wert-Spalte proportional (0.15) statt 70dp und vor allem die pro Karte
 * konfigurierbare Akzentfarbe ([accentColor], settings.kv `triangle_color`:
 * primary fuer Target/Chunk, secondary fuer Cluster).
 *
 * Label: `font_size: min(sp(12), root.width * 0.032)` + `shorten: True`
 * (Kivy shorten_from default 'center' -> ellipsize MIDDLE), halign left,
 * valign Kivy-Default 'bottom'. Wertanzeige: 14sp bold in Akzentfarbe,
 * zentriert (Kivy-Label ohne text_size).
 *
 * [value]/[onValueChanged] folgen der Kivy-`on_value`-Bind-Semantik
 * (feuert bei jeder echten Wertaenderung, auch programmatisch).
 */
class SpeakerSettingsSliderCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    var labelText: String = ""
        set(value) {
            field = value
            labelView.text = value
        }

    /** settings.kv `triangle_color` + Farbe der Wertanzeige (primary/secondary). */
    var accentColor: Int = ContextCompat.getColor(context, R.color.speakerid_primary)
        set(value) {
            field = value
            valueView.setTextColor(value)
            sliderView.setAccent(value)
        }

    var minValue: Double = 0.0
        set(value) {
            field = value
            sliderView.invalidate()
        }

    var maxValue: Double = 1.0
        set(value) {
            field = value
            sliderView.invalidate()
        }

    var step: Double = 0.01

    /** printf-Formatstring der Wertanzeige (Kivy "{:.2f}" -> "%.2f"). */
    var fmt: String = "%.2f"
        set(value) {
            field = value
            updateValueLabel()
        }

    /** Pendant zu Kivys `on_value`-Event. */
    var onValueChanged: ((Double) -> Unit)? = null

    var value: Double = 0.0
        set(newValue) {
            val clamped = newValue.coerceIn(minValue, maxValue)
            if (field != clamped) {
                field = clamped
                updateValueLabel()
                sliderView.invalidate()
                onValueChanged?.invoke(clamped)
            }
        }

    private val labelView: TextView
    private val valueView: TextView
    private val sliderView: SliderView

    init {
        orientation = VERTICAL
        // settings.kv: padding dc.PADDING_CARD = [15, 12], surface + RADIUS_CARD
        setPadding(dp(15f), dp(12f), dp(15f), dp(12f))
        background = ContextCompat.getDrawable(context, R.drawable.speakerid_bg_card)
        // Dreieck ragt wie Kivys canvas.after ueber die Slider-Flaeche hinaus
        clipChildren = false
        clipToPadding = false

        val regular = ResourcesCompat.getFont(context, R.font.speakerid_dejavu_sans)
        val bold = ResourcesCompat.getFont(context, R.font.speakerid_dejavu_sans_bold)

        // ── Kopfzeile (22dp): Label (size_hint_x 1) + Wert (size_hint_x 0.15) ──
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(22f))
        }
        labelView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f) // min() folgt in onSizeChanged
            setTextColor(ContextCompat.getColor(context, R.color.speakerid_text_primary))
            typeface = regular
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MIDDLE // Kivy shorten (shorten_from 'center')
            // halign 'left' + text_size self.size, valign Kivy-Default 'bottom'
            gravity = Gravity.START or Gravity.BOTTOM
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
        }
        valueView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(accentColor)
            typeface = bold
            gravity = Gravity.CENTER // Kivy-Label ohne text_size: Texture zentriert
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 0.15f)
        }
        row.addView(labelView)
        row.addView(valueView)
        addView(row)

        // ── Slider fuellt den Rest der Karte (spacing: dp(5)) ──
        sliderView = SliderView(context)
        sliderView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(5f)
        }
        addView(sliderView)

        updateValueLabel()
    }

    /** Fixe Kartenhoehe dp(100) wie im .kv, sofern das Layout nichts erzwingt. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val forced = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            heightMeasureSpec
        } else {
            MeasureSpec.makeMeasureSpec(dp(100f), MeasureSpec.EXACTLY)
        }
        super.onMeasure(widthMeasureSpec, forced)
    }

    /** Kivy: `font_size: min(sp(12), root.width * 0.032)`. */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0) {
            val sp12 = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics,
            )
            labelView.setTextSize(TypedValue.COMPLEX_UNIT_PX, min(sp12, w * 0.032f))
        }
    }

    private fun updateValueLabel() {
        valueView.text = try {
            String.format(Locale.US, fmt, value)
        } catch (_: Exception) {
            String.format(Locale.US, "%.2f", value)
        }
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    /**
     * "TriangleSlider" aus settings.kv: 3dp-Spur (border), gefuellter Teil und
     * Dreieck (Basis +-10dp bei 13dp ueber Spur-Mitte, Spitze 45dp darueber)
     * in der Akzentfarbe. Kivy-Slider-Padding '16sp'.
     */
    private inner class SliderView(context: Context) : View(context) {

        private val density = resources.displayMetrics.density
        private val hPad = 16f * density
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.speakerid_border)
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
        }
        private val trianglePath = Path()

        fun setAccent(color: Int) {
            fillPaint.color = color
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cy = height / 2f
            val trackHalf = 1.5f * density
            val left = hPad
            val right = width - hPad
            val radius = 1.5f * density

            // Spur (border-Farbe, 3dp hoch, Radius 1.5)
            canvas.drawRoundRect(left, cy - trackHalf, right, cy + trackHalf, radius, radius, trackPaint)

            // Gefuellter Teil bis zur Cursor-Position (Akzentfarbe)
            val frac = if (maxValue > minValue) {
                ((value - minValue) / (maxValue - minValue)).toFloat().coerceIn(0f, 1f)
            } else 0f
            val vx = left + frac * (right - left)
            if (vx > left) {
                canvas.drawRoundRect(left, cy - trackHalf, vx, cy + trackHalf, radius, radius, fillPaint)
            }

            // Dreieck (Kivy y-Achse nach oben -> Android nach unten gespiegelt)
            trianglePath.reset()
            trianglePath.moveTo(vx - 10f * density, cy - 13f * density)
            trianglePath.lineTo(vx + 10f * density, cy - 13f * density)
            trianglePath.lineTo(vx, cy - 45f * density)
            trianglePath.close()
            canvas.drawPath(trianglePath, fillPaint)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    updateFromTouch(event.x)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    updateFromTouch(event.x)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        /** Kivy-Slider-Semantik: Wert auf das Step-Raster ab `min` runden. */
        private fun updateFromTouch(x: Float) {
            val left = hPad
            val right = width - hPad
            if (right <= left) return
            val frac = ((x - left) / (right - left)).coerceIn(0f, 1f)
            var raw = minValue + frac * (maxValue - minValue)
            if (step > 0.0) {
                raw = minValue + ((raw - minValue) / step).roundToInt() * step
            }
            value = raw.coerceIn(minValue, maxValue)
        }
    }
}
