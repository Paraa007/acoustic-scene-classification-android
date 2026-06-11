package com.fzi.speakerid.ui.widgets

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.fzi.acousticscene.R

/**
 * Port von siqas `gui/widgets/segmented_choice.py` + `segmented_choice.kv`.
 *
 * Karte (surface, Radius 8dp, Padding [15,12,15,12], Spacing 8) mit optionalem
 * Header (FONT_SMALL 11sp bold, text_secondary, 20dp) und einem Pill-Raster
 * ([columns] Spalten, Pill-Hoehe 38dp, Spacing 8, Radius 4dp). Aktive Pill:
 * primary-Hintergrund, weisser bold-Text; inaktiv: surface_dark + text_primary.
 *
 * [value] feuert [onValueChanged] bei jeder echten Wertaenderung (auch
 * programmatisch) — Kivy-Bind-Semantik von `on_value`.
 * [collapsed] blendet das ganze Widget aus (Kivy: Hoehe 0 + opacity 0 +
 * disabled == GONE).
 */
class SpeakerIdSegmentedChoice @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    var labelText: String = ""
        set(value) {
            field = value
            headerView.text = value
            headerView.visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
        }

    /** (Label, Value)-Paare — Pendant zur Kivy `options`-Liste. */
    var options: List<Pair<String, String>> = emptyList()
        set(value) {
            field = value
            rebuildPills()
        }

    var columns: Int = 1
        set(value) {
            field = value.coerceAtLeast(1)
            rebuildPills()
        }

    var collapsed: Boolean = false
        set(value) {
            field = value
            visibility = if (value) View.GONE else View.VISIBLE
        }

    /** Pendant zu Kivys `on_value`. */
    var onValueChanged: ((String) -> Unit)? = null

    var value: String = ""
        set(newValue) {
            if (field != newValue) {
                field = newValue
                refreshPillStates()
                onValueChanged?.invoke(newValue)
            }
        }

    private val headerView: TextView
    private val pillContainer: LinearLayout
    private val pills = mutableListOf<TextView>()

    init {
        orientation = VERTICAL
        setPadding(dp(15), dp(12), dp(15), dp(12))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat() // dc.RADIUS_CARD
            setColor(ContextCompat.getColor(context, R.color.speakerid_surface))
        }

        headerView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f) // dc.FONT_SMALL
            typeface = ResourcesCompat.getFont(context, R.font.speakerid_dejavu_sans_bold)
            setTextColor(ContextCompat.getColor(context, R.color.speakerid_text_secondary))
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            visibility = View.GONE
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(20))
        }
        addView(headerView)

        pillContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8) // spacing: dp(8)
            }
        }
        addView(pillContainer)

        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.SpeakerIdSegmentedChoice)
            labelText = a.getString(R.styleable.SpeakerIdSegmentedChoice_speakeridChoiceLabel) ?: ""
            columns = a.getInt(R.styleable.SpeakerIdSegmentedChoice_speakeridChoiceColumns, 1)
            a.recycle()
        }
    }

    /** Convenience fuer flache String-Listen (Kivy: Label == Value). */
    fun setOptionsFromStrings(values: List<String>) {
        options = values.map { it to it }
    }

    /** Port von `_rebuild_pills` (Pills zeilenweise, gleiche Spaltenbreite). */
    private fun rebuildPills() {
        pillContainer.removeAllViews()
        pills.clear()
        if (options.isEmpty()) return

        var row: LinearLayout? = null
        options.forEachIndexed { index, (label, pillValue) ->
            val col = index % columns
            if (col == 0) {
                row = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(38)).apply {
                        if (index > 0) topMargin = dp(8)
                    }
                }
                pillContainer.addView(row)
            }
            val pill = createPill(label, pillValue)
            pill.layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                if (col > 0) marginStart = dp(8)
            }
            row?.addView(pill)
            pills.add(pill)
        }
        // Letzte Zeile mit unsichtbaren Platzhaltern auf Spaltenbreite auffuellen
        val remainder = options.size % columns
        if (remainder != 0) {
            repeat(columns - remainder) {
                val spacer = View(context)
                spacer.layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                    marginStart = dp(8)
                }
                row?.addView(spacer)
            }
        }
        refreshPillStates()
    }

    private fun createPill(label: String, pillValue: String): TextView =
        TextView(context).apply {
            text = label
            tag = pillValue
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            maxLines = 2
            isClickable = true
            isFocusable = true
            setOnClickListener { value = pillValue }
        }

    /** Port von `_ChoicePill._draw`: aktiv = primary/weiss/bold, sonst surface_dark. */
    private fun refreshPillStates() {
        val regular = ResourcesCompat.getFont(context, R.font.speakerid_dejavu_sans)
        val bold = ResourcesCompat.getFont(context, R.font.speakerid_dejavu_sans_bold)
        for (pill in pills) {
            val active = pill.tag == value
            pill.typeface = if (active) bold else regular
            pill.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (active) R.color.speakerid_white else R.color.speakerid_text_primary,
                ),
            )
            pill.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(4).toFloat() // radius [dp(4)]
                setColor(
                    ContextCompat.getColor(
                        context,
                        if (active) R.color.speakerid_primary else R.color.speakerid_surface_dark,
                    ),
                )
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
