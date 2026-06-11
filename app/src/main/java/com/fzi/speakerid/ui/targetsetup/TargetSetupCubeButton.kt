package com.fzi.speakerid.ui.targetsetup

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.fzi.acousticscene.R

/**
 * Port von siqas `gui/widgets/cube_button.py` (CubeButton) fuer den
 * Target-Setup-Screen.
 *
 * Ein Button, der wie ein 3D-Quader aussieht:
 *  - FRONT FACE: Rechteck `pos (x, y), size (w-d, h-d)` (Kivy, y nach oben)
 *    -> Android-Rect (0, d, w-d, h)
 *  - TOP FACE (front * 1.2, geklemmt) und RIGHT FACE (front * 0.8) als Quads
 *  - Pressed: Seitenflaechen verschwinden, Front + Text werden um
 *    Kivy (+d, +d) = Android (+d, -d) verschoben ("eingedrueckt")
 *  - Tiefe `d`: target_setup.kv ueberschreibt den Python-Default mit dp(6)
 *  - Text: padding [0, d, d, 0] zentriert den Text auf der Frontflaeche,
 *    Farbe text_primary, front_color im .kv: surface_dim
 */
class TargetSetupCubeButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatButton(context, attrs) {

    /** Kivy `d: dp(6)` (kv-Override des Python-Defaults dp(8)). */
    private val d: Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, 6f, resources.displayMetrics
    )

    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val facePath = Path()

    private var sideColor: Int = 0
    private var topColor: Int = 0

    /** Port von `front_color` inkl. `on_front_color` (side = *0.8, top = *1.2). */
    var frontColor: Int = 0
        set(value) {
            field = value
            sideColor = scaleRgb(value, 0.8f)
            topColor = scaleRgb(value, 1.2f)
            invalidate()
        }

    init {
        // Kivy: background_normal/down '' + background_color transparent
        background = null
        stateListAnimator = null
        isAllCaps = false
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        // cube_button.kv: color app.theme_manager.text_primary_color;
        // target_setup.kv: bold, halign 'center'
        typeface = ResourcesCompat.getFont(context, R.font.speakerid_dejavu_sans_bold)
        setTextColor(ContextCompat.getColor(context, R.color.speakerid_text_primary))
        gravity = Gravity.CENTER
        // padding [0, self.d, self.d, 0] -> Text mittig auf der Frontflaeche
        setPadding(0, d.toInt(), d.toInt(), 0)
        // target_setup.kv: front_color = surface_dim
        frontColor = ContextCompat.getColor(context, R.color.speakerid_surface_dim)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        if (!isPressed) {
            // RIGHT FACE: Kivy-Quad (w-d, 0), (w-d, h-d), (w, h), (w, d)
            // -> Android (y gespiegelt): (w-d, h), (w-d, d), (w, 0), (w, h-d)
            facePaint.color = sideColor
            facePath.reset()
            facePath.moveTo(w - d, h)
            facePath.lineTo(w - d, d)
            facePath.lineTo(w, 0f)
            facePath.lineTo(w, h - d)
            facePath.close()
            canvas.drawPath(facePath, facePaint)

            // TOP FACE: Kivy-Quad (0, h-d), (d, h), (w, h), (w-d, h-d)
            // -> Android: (0, d), (d, 0), (w, 0), (w-d, d)
            facePaint.color = topColor
            facePath.reset()
            facePath.moveTo(0f, d)
            facePath.lineTo(d, 0f)
            facePath.lineTo(w, 0f)
            facePath.lineTo(w - d, d)
            facePath.close()
            canvas.drawPath(facePath, facePaint)
        }

        // PushMatrix/Translate: Front + Text wandern beim Druecken mit
        val saveCount = canvas.save()
        if (isPressed) canvas.translate(d, -d)

        facePaint.color = frontColor
        canvas.drawRect(0f, d, w - d, h, facePaint)

        super.onDraw(canvas) // Text (innerhalb der Translation)
        canvas.restoreToCount(saveCount)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate() // Pressed-Zustand sofort neu zeichnen
    }

    /** Python `on_front_color`: Kanaele skalieren, klemmen, Alpha unveraendert. */
    private fun scaleRgb(color: Int, factor: Float): Int {
        val a = (color ushr 24) and 0xFF
        val r = (((color ushr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val g = (((color ushr 8) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val b = ((color and 0xFF) * factor).toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
