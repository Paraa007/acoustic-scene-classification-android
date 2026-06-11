package com.fzi.speakerid.ui.statistics

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.fzi.acousticscene.R
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Port von siqas `gui/widgets/pie_chart.py` (`PieChartWidget` + `PieSlice`):
 *
 * - Tortenstuecke absteigend nach Dauer, Winkel im Uhrzeigersinn ab
 *   12 Uhr (Kivy `Ellipse.angle_start`); Name "Target" fuer "1", sonst
 *   "Sp. {cid}".
 * - Desktop-Hover wird auf Touch uebertragen: Antippen faehrt das Stueck
 *   sanft aus (25dp, 0.25s out_quad) und zeigt das Pop-up-Label
 *   ("Name\n{pct:.1f}%", 15sp bold) am Mittelwinkel bei Radius r-15;
 *   Antippen ausserhalb bzw. eines anderen Stuecks faehrt es zurueck
 *   (0.2s in_out_quad).
 * - `center_text_1/2` existieren im Original nur als Properties und werden
 *   nie gezeichnet — daher auch hier nicht gerendert.
 *
 * Winkelkonvention: Kivy zaehlt ab 12 Uhr im Uhrzeigersinn (y-Achse nach
 * oben), Android `drawArc` ab 3 Uhr im Uhrzeigersinn (y nach unten) —
 * androidStart = kivyStart - 90; Offsets: dx = sin(a), dy = -cos(a).
 */
class SpeakerStatisticsPieChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private class Slice(
        val name: String,
        val percentage: Double,
        val angleStart: Double,
        val angleEnd: Double,
        val color: Int,
    ) {
        /** 0 = eingefahren, 1 = ganz ausgefahren (Pendant zu offset_x/y). */
        var offsetFraction: Float = 0f
        var animator: ValueAnimator? = null
    }

    private val slices = mutableListOf<Slice>()
    private var activeSlice: Slice? = null

    private val slicePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.speakerid_text_primary)
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 15f, resources.displayMetrics,
        )
        typeface = ResourcesCompat.getFont(context, R.font.speakerid_dejavu_sans_bold)
    }
    private val arcRect = RectF()

    /** PieSlice: `dist = 25` (Ausfahr-Distanz). */
    private val explodeDistance = dp(25f)

    /** `label_dist = r - 15`. */
    private val labelInset = dp(15f)

    /**
     * Pendant zu `data`/`colors`-Rebuild (`rebuild_chart`): baut alle
     * Tortenstuecke neu und setzt Auswahl + Label zurueck.
     */
    fun setData(data: Map<String, Double>, colors: Map<String, Int>) {
        for (s in slices) s.animator?.cancel()
        slices.clear()
        activeSlice = null

        if (data.isNotEmpty()) {
            val total = data.values.sum().takeIf { it != 0.0 } ?: 1.0
            var angleStart = 0.0
            val sortedItems = data.entries.sortedByDescending { it.value }
            val fallbackColor = 0xFF808080.toInt() // Kivy-Fallback [0.5,0.5,0.5,1]

            for ((cid, value) in sortedItems) {
                if (value <= 0.0) continue
                val color = colors[cid] ?: fallbackColor
                val name = if (cid == "1") {
                    context.getString(R.string.speakerid_statistics_target_name)
                } else {
                    context.getString(R.string.speakerid_statistics_pie_speaker_fmt, cid)
                }
                val percentage = value / total * 100.0
                val angleEnd = angleStart + 3.6 * percentage
                slices.add(Slice(name, percentage, angleStart, angleEnd, color))
                angleStart = angleEnd
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (slices.isEmpty()) return

        val side = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val half = side / 2f

        for (s in slices) {
            val midRad = Math.toRadians(s.angleStart + (s.angleEnd - s.angleStart) / 2.0)
            val dx = (sin(midRad) * explodeDistance * s.offsetFraction).toFloat()
            val dy = (-cos(midRad) * explodeDistance * s.offsetFraction).toFloat()

            arcRect.set(cx - half + dx, cy - half + dy, cx + half + dx, cy + half + dy)
            slicePaint.color = s.color
            canvas.drawArc(
                arcRect,
                (s.angleStart - 90.0).toFloat(),
                (s.angleEnd - s.angleStart).toFloat(),
                true,
                slicePaint,
            )
        }

        // Pop-up-Label des aktiven Stuecks (`_reposition_label`)
        val active = activeSlice ?: return
        val midRad = Math.toRadians(active.angleStart + (active.angleEnd - active.angleStart) / 2.0)
        val labelDist = half - labelInset
        val lx = cx + (sin(midRad) * labelDist).toFloat()
        val ly = cy - (cos(midRad) * labelDist).toFloat()

        // Zweizeiliges Label ("Name\n{pct:.1f}%"), Block am Punkt zentriert
        val fm = labelPaint.fontMetrics
        val lineHeight = fm.descent - fm.ascent
        val line1 = active.name
        val line2 = String.format(java.util.Locale.US, "%.1f%%", active.percentage)
        val baseline1 = ly - lineHeight - fm.ascent
        canvas.drawText(line1, lx, baseline1, labelPaint)
        canvas.drawText(line2, lx, baseline1 + lineHeight, labelPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            selectSlice(hitTest(event.x, event.y))
            return true
        }
        return super.onTouchEvent(event)
    }

    /** Hit-Test wie `on_mouse_pos` (Distanz- + Winkel-Check). */
    private fun hitTest(x: Float, y: Float): Slice? {
        val cx = width / 2f
        val cy = height / 2f
        val dx = x - cx
        val dyUp = -(y - cy) // Kivy-y zeigt nach oben
        val dist = sqrt(dx * dx + dyUp * dyUp)
        val radius = min(width, height) / 2f
        if (dist > radius) return null

        // Kivy: angle = degrees(pi/2 - atan2(dy, dx)); < 0 -> +360
        var angle = Math.toDegrees(Math.PI / 2.0 - atan2(dyUp.toDouble(), dx.toDouble()))
        if (angle < 0) angle += 360.0

        return slices.firstOrNull { angle in it.angleStart..it.angleEnd }
    }

    /** Pendant zur Hover-Logik in `on_mouse_pos` + `PieSlice.set_state`. */
    private fun selectSlice(slice: Slice?) {
        if (slice === activeSlice) return

        activeSlice?.let { animateSlice(it, expanded = false) }
        activeSlice = slice
        slice?.let { animateSlice(it, expanded = true) }
        invalidate()
    }

    private fun animateSlice(slice: Slice, expanded: Boolean) {
        slice.animator?.cancel()
        val target = if (expanded) 1f else 0f
        slice.animator = ValueAnimator.ofFloat(slice.offsetFraction, target).apply {
            // Kivy: 0.25s out_quad (ausfahren) / 0.2s in_out_quad (einfahren)
            duration = if (expanded) 250L else 200L
            interpolator = if (expanded) {
                DecelerateInterpolator()
            } else {
                AccelerateDecelerateInterpolator()
            }
            addUpdateListener {
                slice.offsetFraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        for (s in slices) s.animator?.cancel()
        super.onDetachedFromWindow()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
