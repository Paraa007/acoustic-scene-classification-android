package com.fzi.speakerid.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import android.widget.LinearLayout
import com.fzi.acousticscene.R

/**
 * Port von siqas `gui/widgets/logo_bar.py` + `logo_bar.kv`: FZI- und
 * Movisens-Logo nebeneinander (je [logoWidth] breit, Default dp(90),
 * 24dp Abstand = Kivy spacing 8 + 8dp-Spacer + spacing 8).
 *
 * [hideNarrow] blendet die Logos aus, wenn der Screen schmaler als 700dp ist
 * (Kivy: `Window.width > dp(700)`).
 */
class SpeakerIdLogoBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    var logoWidth: Int = dp(90f)
        set(value) {
            field = value
            applyLayout()
        }

    var hideNarrow: Boolean = false
        set(value) {
            field = value
            applyLayout()
        }

    private val fziLogo: ImageView
    private val movisensLogo: ImageView

    init {
        orientation = HORIZONTAL

        fziLogo = ImageView(context).apply {
            setImageResource(R.drawable.speakerid_fzi_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = false
        }
        movisensLogo = ImageView(context).apply {
            setImageResource(R.drawable.speakerid_movisens)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = false
        }
        addView(fziLogo)
        addView(movisensLogo)

        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.SpeakerIdLogoBar)
            logoWidth = a.getDimensionPixelSize(
                R.styleable.SpeakerIdLogoBar_speakeridLogoWidth, dp(90f),
            )
            hideNarrow = a.getBoolean(R.styleable.SpeakerIdLogoBar_speakeridHideNarrow, false)
            a.recycle()
        } else {
            applyLayout()
        }
    }

    private fun applyLayout() {
        val visible = !hideNarrow || resources.configuration.screenWidthDp > 700
        val width = if (visible) logoWidth else 0
        fziLogo.layoutParams = LayoutParams(width, LayoutParams.MATCH_PARENT)
        movisensLogo.layoutParams = LayoutParams(width, LayoutParams.MATCH_PARENT).apply {
            marginStart = if (visible) dp(24f) else 0
        }
        fziLogo.visibility = if (visible) VISIBLE else GONE
        movisensLogo.visibility = if (visible) VISIBLE else GONE
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
