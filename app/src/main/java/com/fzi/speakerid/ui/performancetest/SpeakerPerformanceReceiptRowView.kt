package com.fzi.speakerid.ui.performancetest

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.fzi.acousticscene.R
import java.util.Locale

/**
 * Port der `<ReceiptRow@BoxLayout>`-Regel aus
 * siqas `performance_test.kv`: eine Zeile der "Quittung"
 * Label | Ø ms | max ms (beide rechtsbuendig).
 *
 * Geometrie wie das .kv: horizontal, Mindesthoehe dp(24), Padding
 * [12 (is_indent) | 0, 2, 4, 2], Spacing 4; Label flexibel (FONT_SMALL 11sp,
 * mehrzeilig), Wert- und Max-Spalte je dp(80), rechtsbuendig.
 *
 * Farben wie das .kv: Label+Wert text_primary, bei [isInactive]
 * text_secondary; Max-Spalte immer text_secondary. Inaktiv zeigt beide
 * Wertespalten "—", sonst `"{:6.2f} ms"`.
 */
class SpeakerPerformanceReceiptRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    var labelText: String = ""
        set(value) {
            field = value
            labelView.text = value
        }

    var valueMs: Double = 0.0
        set(value) {
            field = value
            refresh()
        }

    var maxMs: Double = 0.0
        set(value) {
            field = value
            refresh()
        }

    var isInactive: Boolean = false
        set(value) {
            field = value
            refresh()
        }

    /** `is_indent` (Default true): 12dp Einzug links, sonst 0. */
    var isIndent: Boolean = true
        set(value) {
            field = value
            setPadding(if (value) dp(12f) else 0, dp(2f), dp(4f), dp(2f))
        }

    private val labelView: TextView
    private val valueView: TextView
    private val maxView: TextView

    private val primaryColor = ContextCompat.getColor(context, R.color.speakerid_text_primary)
    private val secondaryColor = ContextCompat.getColor(context, R.color.speakerid_text_secondary)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(24f)
        setPadding(dp(12f), dp(2f), dp(4f), dp(2f))

        val regular = ResourcesCompat.getFont(context, R.font.speakerid_dejavu_sans)

        // Label: size_hint_x 1, halign left, hoehenflexibel (texture_size)
        labelView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f) // dc.FONT_SMALL
            typeface = regular
            setTextColor(primaryColor)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(4f) // spacing: dp(4)
            }
        }
        addView(labelView)

        // Ø-Wert: width dp(80), halign right
        valueView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = regular
            setTextColor(primaryColor)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(dp(80f), LayoutParams.MATCH_PARENT).apply {
                marginEnd = dp(4f)
            }
        }
        addView(valueView)

        // max-Wert: width dp(80), halign right, immer text_secondary
        maxView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = regular
            setTextColor(secondaryColor)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(dp(80f), LayoutParams.MATCH_PARENT)
        }
        addView(maxView)

        refresh()
    }

    /** `"—" if root.is_inactive else "{:6.2f} ms".format(...)` + Farblogik. */
    private fun refresh() {
        if (isInactive) {
            valueView.text = DASH
            maxView.text = DASH
        } else {
            valueView.text = String.format(Locale.US, "%6.2f ms", valueMs)
            maxView.text = String.format(Locale.US, "%6.2f ms", maxMs)
        }
        val labelColor = if (isInactive) secondaryColor else primaryColor
        labelView.setTextColor(labelColor)
        valueView.setTextColor(labelColor)
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val DASH = "—"
    }
}
