package com.fzi.speakerid.ui.widgets

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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
import kotlin.math.roundToInt

/**
 * Port von siqas `gui/widgets/labeled_slider.py` + `labeled_slider.kv`
 * ("TriangleSlider").
 *
 * Aufbau exakt wie das .kv: Gesamthoehe dp(80), Padding [5,4,5,0], Spacing 2;
 * Zeile 1 (22dp): Label links (FONT_BODY 12sp, text_primary) + Wert rechts
 * (14sp bold, primary, 70dp breit); Zeile 2: Slider mit 3dp-Spur
 * (border-Farbe), gefuelltem Teil (primary) und primary-Dreieck ueber dem
 * Cursor (Basis +-10dp bei 13dp ueber Spur-Mitte, Spitze 45dp darueber).
 *
 * API (Python-Properties): [labelText], [value], [minValue], [maxValue],
 * [step], [fmt] (printf-Stil, Default "%.2f") + [onValueChanged]
 * (Pendant zu Kivys `on_value`; feuert bei jeder Wertaenderung, auch
 * programmatisch — Kivy-Bind-Semantik).
 */
class SpeakerIdLabeledSlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    var labelText: String = ""
        set(value) {
            field = value
            labelView.text = value
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
        // labeled_slider.kv: padding [5,4,5,0], spacing 2 (ueber Margin geloest)
        setPadding(dp(5f), dp(4f), dp(5f), 0)
        clipChildren = false
        clipToPadding = false

        val regular = ResourcesCompat.getFont(context, R.font.speakerid_dejavu_sans)
        val bold = ResourcesCompat.getFont(context, R.font.speakerid_dejavu_sans_bold)

        // Zeile 1: Label links, Wert rechts (Hoehe 22dp)
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(22f))
        }
        labelView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(ContextCompat.getColor(context, R.color.speakerid_text_primary))
            typeface = regular
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
        }
        valueView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ContextCompat.getColor(context, R.color.speakerid_primary))
            typeface = bold
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(dp(70f), LayoutParams.MATCH_PARENT)
        }
        row.addView(labelView)
        row.addView(valueView)
        addView(row)

        // Zeile 2: der eigentliche Slider
        sliderView = SliderView(context)
        sliderView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(2f) // spacing: dp(2)
        }
        addView(sliderView)

        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.SpeakerIdLabeledSlider)
            labelText = a.getString(R.styleable.SpeakerIdLabeledSlider_speakeridSliderLabel) ?: ""
            minValue = a.getFloat(R.styleable.SpeakerIdLabeledSlider_speakeridSliderMin, 0f).toDouble()
            maxValue = a.getFloat(R.styleable.SpeakerIdLabeledSlider_speakeridSliderMax, 1f).toDouble()
            step = a.getFloat(R.styleable.SpeakerIdLabeledSlider_speakeridSliderStep, 0.01f).toDouble()
            fmt = a.getString(R.styleable.SpeakerIdLabeledSlider_speakeridSliderFmt) ?: "%.2f"
            value = a.getFloat(R.styleable.SpeakerIdLabeledSlider_speakeridSliderValue, 0f).toDouble()
            a.recycle()
        }
        updateValueLabel()
    }

    /** Fixe Gesamthoehe dp(80) wie im .kv, sofern das Layout nichts erzwingt. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val forced = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            heightMeasureSpec
        } else {
            MeasureSpec.makeMeasureSpec(dp(80f), MeasureSpec.EXACTLY)
        }
        super.onMeasure(widthMeasureSpec, forced)
    }

    private fun updateValueLabel() {
        valueView.text = try {
            String.format(Locale.US, fmt, value)
        } catch (_: Exception) {
            String.format(Locale.US, "%.2f", value)
        }
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    /** Spur + Fuellung + Dreieck — Pendant zur canvas-Sektion des Kivy-Sliders. */
    private inner class SliderView(context: Context) : View(context) {

        private val density = resources.displayMetrics.density
        // Kivy-Slider: padding '16sp' (hier dp-gleichwertig)
        private val hPad = 16f * density
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.speakerid_border)
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.speakerid_primary)
        }
        private val trianglePath = Path()

        init {
            // Dreieck darf wie Kivys canvas.after ueber die Slider-Flaeche hinausragen
            setLayerType(LAYER_TYPE_NONE, null)
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

            // Gefuellter Teil bis zur Cursor-Position (primary)
            val frac = if (maxValue > minValue) {
                ((value - minValue) / (maxValue - minValue)).toFloat().coerceIn(0f, 1f)
            } else 0f
            val vx = left + frac * (right - left)
            if (vx > left) {
                canvas.drawRoundRect(left, cy - trackHalf, vx, cy + trackHalf, radius, radius, fillPaint)
            }

            // Dreieck: Basis +-10dp auf Hoehe cy-13dp, Spitze bei cy-45dp
            // (Kivy y-Achse zeigt nach oben -> Android nach unten gespiegelt)
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
