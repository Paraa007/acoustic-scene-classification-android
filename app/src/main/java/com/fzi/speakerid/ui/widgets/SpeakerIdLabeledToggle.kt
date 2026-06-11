package com.fzi.speakerid.ui.widgets

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.fzi.acousticscene.R

/**
 * Port von siqas `gui/widgets/labeled_toggle.py` + `labeled_toggle.kv`.
 *
 * Klickbare Zeile (Hoehe 48dp, mit [description] 58dp), Hintergrund surface
 * mit Radius 5dp (gedrueckt: surface_dark), Padding [16,8,16,8]; links Label
 * (14sp bold, text_primary) + optionale Beschreibung (12sp, text_secondary),
 * rechts der animierte Schalter (Spur 50x28dp, Radius 14dp; Knopf 24dp weiss;
 * Spur an = primary, aus = border; Animation 0.2s out_quad).
 *
 * [isActive] feuert [onActiveChanged] bei JEDER Wertaenderung (auch
 * programmatisch) — Kivy-Bind-Semantik von `on_active`.
 */
class SpeakerIdLabeledToggle @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    var labelText: String = ""
        set(value) {
            field = value
            labelView.text = value
        }

    var description: String = ""
        set(value) {
            field = value
            descriptionView.text = value
            descriptionView.visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
            requestLayout()
        }

    /** Pendant zu Kivys `on_active`. */
    var onActiveChanged: ((Boolean) -> Unit)? = null

    var isActive: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                track.setActive(value, animate = true)
                onActiveChanged?.invoke(value)
            }
        }

    private val labelView: TextView
    private val descriptionView: TextView
    private val track: ToggleTrackView

    init {
        orientation = HORIZONTAL
        setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
        isClickable = true
        isFocusable = true

        // canvas.before: surface (down: surface_dark), Radius 5dp
        fun rounded(colorRes: Int) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(5f).toFloat()
            setColor(ContextCompat.getColor(context, colorRes))
        }
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), rounded(R.color.speakerid_surface_dark))
            addState(intArrayOf(), rounded(R.color.speakerid_surface))
        }

        val regular = ResourcesCompat.getFont(context, R.font.speakerid_dejavu_sans)
        val boldFont = ResourcesCompat.getFont(context, R.font.speakerid_dejavu_sans_bold)

        // Linke Seite: Label [+ Beschreibung], vertikal zentriert
        val textColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                marginEnd = dp(12f) // spacing: dp(12)
            }
        }
        labelView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = boldFont
            setTextColor(ContextCompat.getColor(context, R.color.speakerid_text_primary))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END // shorten_from: 'right'
        }
        descriptionView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = regular
            setTextColor(ContextCompat.getColor(context, R.color.speakerid_text_secondary))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(18f)).apply {
                topMargin = dp(2f) // spacing: dp(2)
            }
        }
        textColumn.addView(labelView)
        textColumn.addView(descriptionView)
        addView(textColumn)

        // Rechte Seite: Schalter in 52dp-Container, rechts/zentriert verankert
        val anchor = FrameLayout(context).apply {
            layoutParams = LayoutParams(dp(52f), LayoutParams.MATCH_PARENT)
        }
        track = ToggleTrackView(context).apply {
            layoutParams = FrameLayout.LayoutParams(dp(50f), dp(28f)).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
        }
        anchor.addView(track)
        addView(anchor)

        // ButtonBehavior.on_release: ganze Zeile togglet
        setOnClickListener {
            if (isEnabled) isActive = !isActive
        }

        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.SpeakerIdLabeledToggle)
            labelText = a.getString(R.styleable.SpeakerIdLabeledToggle_speakeridToggleLabel) ?: ""
            description = a.getString(R.styleable.SpeakerIdLabeledToggle_speakeridToggleDescription) ?: ""
            val initial = a.getBoolean(R.styleable.SpeakerIdLabeledToggle_speakeridToggleActive, false)
            a.recycle()
            if (initial) {
                isActive = true
                track.setActive(true, animate = false)
            }
        }
    }

    /** Hoehe dp(58) mit Beschreibung, sonst dp(48) — wie das .kv. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val target = if (description.isNotEmpty()) dp(58f) else dp(48f)
        val forced = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            heightMeasureSpec
        } else {
            MeasureSpec.makeMeasureSpec(target, MeasureSpec.EXACTLY)
        }
        super.onMeasure(widthMeasureSpec, forced)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        // disabled: Label -> text_secondary (wie das .kv), Track grau
        labelView.setTextColor(
            ContextCompat.getColor(
                context,
                if (enabled) R.color.speakerid_text_primary else R.color.speakerid_text_secondary,
            ),
        )
        track.isEnabled = enabled
        track.invalidate()
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    /** Port von `ToggleTrack` (Spur + animierter Knopf + weiche Schatten). */
    private class ToggleTrackView(context: Context) : View(context) {

        private val density = resources.displayMetrics.density
        private val trackOnColor = ContextCompat.getColor(context, R.color.speakerid_primary)
        private val trackOffColor = ContextCompat.getColor(context, R.color.speakerid_border)
        private val knobColor = ContextCompat.getColor(context, R.color.speakerid_white)
        private val disabledTrackColor = 0x804D4D4D.toInt() // [0.3,0.3,0.3,0.5]
        private val disabledKnobColor = 0xFFCCCCCC.toInt()  // [0.8,0.8,0.8,1]

        private var knobX = 0f // 0..1 (Python `_knob_x`)
        private var currentTrackColor = trackOffColor
        private var animator: ValueAnimator? = null
        private val argbEvaluator = ArgbEvaluator()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        fun setActive(active: Boolean, animate: Boolean) {
            val targetX = if (active) 1f else 0f
            val targetColor = if (active) trackOnColor else trackOffColor
            animator?.cancel()
            if (!animate) {
                knobX = targetX
                currentTrackColor = targetColor
                invalidate()
                return
            }
            val startX = knobX
            val startColor = currentTrackColor
            // Animation(duration=0.2, t="out_quad")
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 200
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    val f = anim.animatedValue as Float
                    knobX = startX + (targetX - startX) * f
                    currentTrackColor = argbEvaluator.evaluate(f, startColor, targetColor) as Int
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()

            // Spur (Radius 14dp)
            paint.color = if (isEnabled) currentTrackColor else disabledTrackColor
            rect.set(0f, 0f, w, h)
            val r = 14f * density
            canvas.drawRoundRect(rect, r, r, paint)

            // Knopf-Geometrie: x = 2dp + knobX * (width - 28dp), Durchmesser 24dp
            val knobSize = 24f * density
            val cx = 2f * density + knobX * (w - 28f * density) + knobSize / 2f
            val cyKnob = h - 2f * density - knobSize / 2f // Kivy y+2 von unten

            // Schatten Layer 1 (weit, 5%): Kivy y+0.5 -> 1.5dp unter dem Knopf
            paint.color = 0x0D000000
            canvas.drawCircle(cx, cyKnob + 1.5f * density, knobSize / 2f, paint)
            // Schatten Layer 2 (eng, 10%): Kivy y+1.5 -> 0.5dp unter dem Knopf
            paint.color = 0x1A000000
            canvas.drawCircle(cx, cyKnob + 0.5f * density, knobSize / 2f, paint)

            // Knopf
            paint.color = if (isEnabled) knobColor else disabledKnobColor
            canvas.drawCircle(cx, cyKnob, knobSize / 2f, paint)
        }
    }
}
