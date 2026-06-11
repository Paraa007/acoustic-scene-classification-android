package com.fzi.speakerid.ui.widgets

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.fzi.acousticscene.R
import kotlin.math.min

/**
 * Port von siqas `gui/widgets/screen_header.py` + `screen_header.kv`:
 * Zeile (50dp, bg_color, Padding [12,6,12,6], Spacing 12) mit Back-Button
 * "← Zurück" (110x42dp, 12sp bold, text_secondary auf surface_dim, Radius
 * 4dp) und Titel (bold, text_primary, Groesse `min(20sp, width*0.055)`).
 *
 * `on_back`-Event -> [onBack]-Lambda.
 */
class SpeakerIdScreenHeader @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    var title: String = ""
        set(value) {
            field = value
            titleView.text = value
        }

    /** Pendant zum Kivy-Event `on_back`. */
    var onBack: (() -> Unit)? = null

    private val titleView: TextView

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
        setBackgroundColor(ContextCompat.getColor(context, R.color.speakerid_bg))

        // Back-Button (normal: surface_dim, down: surface_dark, Radius 4dp)
        fun rounded(colorRes: Int) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(4f).toFloat() // dc.RADIUS_BUTTON
            setColor(ContextCompat.getColor(context, colorRes))
        }
        val backButton = AppCompatButton(context).apply {
            text = context.getString(R.string.speakerid_back_button)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f) // dc.FONT_BODY
            typeface = ResourcesCompat.getFont(context, R.font.speakerid_dejavu_sans_bold)
            isAllCaps = false
            setTextColor(ContextCompat.getColor(context, R.color.speakerid_text_secondary))
            stateListAnimator = null
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            background = StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    rounded(R.color.speakerid_surface_dark),
                )
                addState(intArrayOf(), rounded(R.color.speakerid_surface_dim))
            }
            layoutParams = LayoutParams(dp(110f), dp(42f)).apply {
                marginEnd = dp(12f) // spacing: dp(12)
            }
            setOnClickListener { onBack?.invoke() }
        }
        addView(backButton)

        titleView = TextView(context).apply {
            typeface = ResourcesCompat.getFont(context, R.font.speakerid_dejavu_sans_bold)
            setTextColor(ContextCompat.getColor(context, R.color.speakerid_text_primary))
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            maxLines = 1
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
        }
        addView(titleView)

        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.SpeakerIdScreenHeader)
            title = a.getString(R.styleable.SpeakerIdScreenHeader_speakeridHeaderTitle) ?: ""
            a.recycle()
        }
    }

    /** Hoehe fix dp(50) wie im .kv, sofern das Layout nichts erzwingt. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val forced = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            heightMeasureSpec
        } else {
            MeasureSpec.makeMeasureSpec(dp(50f), MeasureSpec.EXACTLY)
        }
        super.onMeasure(widthMeasureSpec, forced)
    }

    /** Kivy: `font_size: min(sp(20), root.width * 0.055)`. */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val sp20 = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 20f, resources.displayMetrics,
        )
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, min(sp20, w * 0.055f))
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
