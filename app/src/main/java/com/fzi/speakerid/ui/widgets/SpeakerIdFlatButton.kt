package com.fzi.speakerid.ui.widgets

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.AttributeSet
import android.util.TypedValue
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.fzi.acousticscene.R
import com.fzi.speakerid.ui.SpeakerIdTheme

/**
 * Port von siqas `gui/widgets/flat_button.py` + `flat_button.kv`.
 *
 * Geometrie/Typo aus dem .kv: Hoehe dp(42), Radius dp(5), FONT_BODY 12sp, bold.
 * Farben je `buttonType` (Theme "FZI"):
 *  - [TYPE_PRIMARY]   primary  (#2FAE7A), Text weiss
 *  - [TYPE_SECONDARY] secondary (#0E2356), Text weiss
 *  - [TYPE_DANGER]    #BF4D4D, Text weiss
 *  - [TYPE_DEFAULT]   surface_dark (#F0F1F3), Text text_primary
 * Pressed-Zustand: Hintergrund * 0.8 (Kivy `_update_colors`).
 *
 * XML: `app:speakeridButtonType="primary|secondary|danger|defaultType"`.
 * Hoehe im Layout auf 42dp setzen (minHeight ist vorbelegt).
 */
class SpeakerIdFlatButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatButton(context, attrs) {

    /** "primary" | "secondary" | "danger" | "default" (Python `button_type`). */
    var buttonType: String = TYPE_DEFAULT
        set(value) {
            field = value
            applyType()
        }

    init {
        // flat_button.kv: font_size dc.FONT_BODY ('12sp'), bold: True
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = ResourcesCompat.getFont(context, R.font.speakerid_dejavu_sans_bold)
        isAllCaps = false
        stateListAnimator = null
        minHeight = dp(42)
        minimumHeight = dp(42)
        minWidth = 0
        minimumWidth = 0

        var type = TYPE_DEFAULT
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.SpeakerIdFlatButton)
            type = when (a.getInt(R.styleable.SpeakerIdFlatButton_speakeridButtonType, 0)) {
                1 -> TYPE_PRIMARY
                2 -> TYPE_SECONDARY
                3 -> TYPE_DANGER
                else -> TYPE_DEFAULT
            }
            a.recycle()
        }
        buttonType = type
    }

    /** Port von `FlatButton._on_type_changed` (color_map). */
    private fun applyType() {
        val (bgColor, textColor) = when (buttonType) {
            TYPE_PRIMARY -> color(R.color.speakerid_primary) to color(R.color.speakerid_white)
            TYPE_SECONDARY -> color(R.color.speakerid_secondary) to color(R.color.speakerid_white)
            TYPE_DANGER -> color(R.color.speakerid_danger) to color(R.color.speakerid_white)
            else -> color(R.color.speakerid_surface_dark) to color(R.color.speakerid_text_primary)
        }
        setTextColor(textColor)

        val radius = dp(5).toFloat()
        fun rounded(c: Int) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(c)
        }
        background = StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                rounded(SpeakerIdTheme.pressedVariant(bgColor)),
            )
            addState(intArrayOf(), rounded(bgColor))
        }
    }

    private fun color(resId: Int) = ContextCompat.getColor(context, resId)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        const val TYPE_DEFAULT = "default"
        const val TYPE_PRIMARY = "primary"
        const val TYPE_SECONDARY = "secondary"
        const val TYPE_DANGER = "danger"
    }
}
