package com.fzi.speakerid.ui.performancetest

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import java.util.Locale
import kotlin.math.max

/**
 * Port von siqas `gui/widgets/chunk_time_chart.py::ChunkTimeChart` —
 * Echtzeit-Liniendiagramm fuer den Performance-Test (Chunk-Index (X) vs.
 * Wandzeit in ms (Y)), komplett selbst gezeichnet.
 *
 * Referenzlinien wie das Original: gestrichelte Durchschnittslinie
 * ([avgColor]), orange Echtzeit-Schwelle (= Chunk-Dauer in ms, nur bei
 * [showThreshold]) und transparente "Fuellung" unter der Kurve (vertikale
 * Linien, max. ~80 Stueck). Geometrie/Farben/Beschriftungen 1:1 aus
 * `_redraw`/`_draw_label` (Kivy zeichnet y-aufwaerts — hier gespiegelt).
 */
class SpeakerPerformanceChunkTimeChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Wandzeiten pro Chunk (ms) — Pendant zur `data`-ListProperty. */
    var data: List<Double> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    /** Chunk-Dauer als Realzeit-Schwelle (`threshold_ms`). */
    var thresholdMs: Double = 1000.0
        set(value) {
            field = value
            invalidate()
        }

    /** `show_threshold` (nur bei Gesamt-Ansicht "wall"). */
    var showThreshold: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    // Default-Farben exakt wie die ColorProperties des Python-Widgets;
    // das Fragment ueberschreibt sie wie das .kv (primary etc.).
    var lineColor: Int = Color.argb(255, 0, 191, 64)          // [0.0,0.75,0.25,1]
        set(value) {
            field = value
            invalidate()
        }
    var avgColor: Int = Color.argb(179, 0, 191, 64)           // [0.0,0.75,0.25,0.7]
        set(value) {
            field = value
            invalidate()
        }
    var thresholdColor: Int = Color.argb(230, 242, 153, 26)   // [0.95,0.6,0.1,0.9]
        set(value) {
            field = value
            invalidate()
        }
    var bgColor: Int = Color.argb(255, 31, 31, 41)            // [0.12,0.12,0.16,1]
        set(value) {
            field = value
            invalidate()
        }
    var gridColor: Int = Color.argb(15, 255, 255, 255)        // [1,1,1,0.06]
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val padL = 52f * density
    private val padR = 12f * density
    private val padT = 14f * density
    private val padB = 32f * density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics,
        )
    }
    private val bgRect = RectF()
    private val linePath = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 10f || h < 10f) return

        val plotW = w - padL - padR
        val plotH = h - padT - padB
        if (plotW < 4f || plotH < 4f) return

        val points = data

        // Y-Achsen-Maximum wie _redraw: bei show_threshold mind. threshold*1.1
        val dataMax = points.maxOrNull() ?: 1.0
        var yMax = if (showThreshold) {
            max(thresholdMs * 1.1, dataMax * 1.15)
        } else {
            max(dataMax * 1.15, 0.1)
        }
        yMax = max(yMax, 0.1)

        // Kivy: y = pb + min(val/y_max, 1) * plot_h (y-aufwaerts) -> gespiegelt
        fun toPxX(i: Int): Float = padL + (i.toFloat() / max(points.size - 1, 1)) * plotW
        fun toPxY(v: Double): Float =
            h - padB - (kotlin.math.min(v / yMax, 1.0).toFloat()) * plotH

        // ── Hintergrund (RoundedRectangle radius dp(12)) ─────────────────────
        fillPaint.color = bgColor
        bgRect.set(0f, 0f, w, h)
        val r = 12f * density
        canvas.drawRoundRect(bgRect, r, r, fillPaint)

        // ── Horizontale Grid-Linien + Y-Achsen-Labels ───────────────────────
        val nGrid = 5
        val yLabelColor = withAlpha(lineColor, 0.55f)
        val yFmt = when {
            yMax < 2 -> "%.2f"
            yMax < 20 -> "%.1f"
            else -> "%.0f"
        }
        linePaint.pathEffect = null
        for (gi in 0..nGrid) {
            val value = yMax * gi / nGrid
            val gy = h - padB - (gi.toFloat() / nGrid) * plotH
            linePaint.color = gridColor
            linePaint.strokeWidth = 0.5f * density
            canvas.drawLine(padL, gy, w - padR, gy, linePaint)
            drawLabel(canvas, String.format(Locale.US, yFmt, value), padL - 4f * density, gy, ALIGN_RIGHT, yLabelColor)
        }

        // ── Echtzeit-Schwelle (nur bei Gesamt-Ansicht) ──────────────────────
        if (showThreshold) {
            val ty = toPxY(thresholdMs)
            linePaint.color = thresholdColor
            linePaint.strokeWidth = 1.2f * density
            linePaint.pathEffect = DashPathEffect(floatArrayOf(8f, 5f), 0f) // dash_length 8 / offset 5
            canvas.drawLine(padL, ty, w - padR, ty, linePaint)
            linePaint.pathEffect = null
            drawLabel(
                canvas,
                String.format(Locale.US, "Limit %.0fms", thresholdMs),
                w - padR, ty - 3f * density, ALIGN_RIGHT, thresholdColor,
            )
        }

        // ── Datenpunkte ─────────────────────────────────────────────────────
        if (points.size >= 2) {
            // Fuellflaeche: vertikale Linien, gleichmaessig verteilt (~80)
            val fillStep = max(1, points.size / 80)
            linePaint.color = withAlpha(lineColor, 0.10f)
            linePaint.strokeWidth = 0.8f * density
            var i = 0
            while (i < points.size) {
                val px = toPxX(i)
                canvas.drawLine(px, h - padB, px, toPxY(points[i]), linePaint)
                i += fillStep
            }

            // Hauptlinie
            linePath.reset()
            for (j in points.indices) {
                val px = toPxX(j)
                val py = toPxY(points[j])
                if (j == 0) linePath.moveTo(px, py) else linePath.lineTo(px, py)
            }
            linePaint.color = lineColor
            linePaint.strokeWidth = 1.5f * density
            canvas.drawPath(linePath, linePaint)

            // Durchschnittslinie (gestrichelt) + Label
            val avg = points.sum() / points.size
            val ay = toPxY(avg)
            linePaint.color = avgColor
            linePaint.strokeWidth = 1f * density
            linePaint.pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f) // dash_length 6 / offset 4
            canvas.drawLine(padL, ay, w - padR, ay, linePaint)
            linePaint.pathEffect = null
            drawLabel(
                canvas,
                String.format(Locale.US, "Ø %.2fms", avg),
                padL + 4f * density, ay - 3f * density, ALIGN_LEFT,
                Color.argb(255, 13, 89, 38), // [0.05, 0.35, 0.15, 1]
            )
        }

        // ── Rahmen-Linie (Plotbereich) ──────────────────────────────────────
        linePaint.color = Color.argb(20, 255, 255, 255) // [1,1,1,0.08]
        linePaint.strokeWidth = 0.5f * density
        canvas.drawRect(padL, padT, padL + plotW, padT + plotH, linePaint)

        // ── X-Achsen-Label ──────────────────────────────────────────────────
        val xLabelColor = Color.argb(89, 255, 255, 255) // [1,1,1,0.35]
        if (points.isNotEmpty()) {
            val labelY = h - padB + 14f * density
            drawLabel(canvas, "0", padL, labelY, ALIGN_CENTER, xLabelColor)
            drawLabel(canvas, points.size.toString(), w - padR, labelY, ALIGN_CENTER, xLabelColor)
            val midI = points.size / 2
            drawLabel(canvas, "Chunk $midI", padL + plotW * 0.5f, labelY, ALIGN_CENTER, xLabelColor)
        }

        // ── Achsenbeschriftung ──────────────────────────────────────────────
        drawLabel(canvas, "ms", padL - 4f * density, padT, ALIGN_RIGHT, xLabelColor)
    }

    /** Port von `_draw_label`: (x, y) = Ankerpunkt, y = vertikale Mitte. */
    private fun drawLabel(canvas: Canvas, text: String, x: Float, y: Float, align: Int, color: Int) {
        textPaint.color = color
        val tw = textPaint.measureText(text)
        val rx = when (align) {
            ALIGN_RIGHT -> x - tw
            ALIGN_CENTER -> x - tw / 2f
            else -> x
        }
        val baseline = y - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(text, rx, baseline, textPaint)
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha * 255 + 0.5f).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    private companion object {
        const val ALIGN_LEFT = 0
        const val ALIGN_RIGHT = 1
        const val ALIGN_CENTER = 2
    }
}
