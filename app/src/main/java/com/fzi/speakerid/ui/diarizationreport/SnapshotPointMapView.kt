package com.fzi.speakerid.ui.diarizationreport

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * Port von siqas `src/gui/screens/embeddings/point_map.py::PointMap` fuer den
 * Diarisations-Report: Embeddings als Kreise (r dp(3)), Centroids als
 * Dreiecke (r dp(7), Spitze oben).
 *
 * Auf diesem Screen ist die Map ein eingefrorenes Standbild: `select_item`
 * ist im Screen ein NO-OP ("to keep it frozen/uninteractive"), Hover gibt es
 * auf Android nicht und `is_selected` ist immer False — deshalb sind nur die
 * Basis-Zeichenpfade portiert (keine Auswahl-Ringe/Schatten).
 *
 * Koordinaten: `pos` ist auf [0, 1] x [0, 1] normiert mit Kivy-Ursprung
 * unten links — beim Zeichnen wird die y-Achse gespiegelt.
 */
class SnapshotPointMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Pendant zu einem Eintrag in `points_data` ({"pos", "color", ...}). */
    class MapPoint(val x: Float, val y: Float, val color: Int)

    /** Pendant zu einem Eintrag in `centroids_data`. */
    class MapCentroid(val clusterId: String, val x: Float, val y: Float, val color: Int)

    private var points: List<MapPoint> = emptyList()
    private var centroids: List<MapCentroid> = emptyList()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val trianglePath = Path()

    /** Pendant zu `points_data`/`centroids_data`-Zuweisung (triggert Redraw). */
    fun setData(newPoints: List<MapPoint>, newCentroids: List<MapCentroid>) {
        points = newPoints
        centroids = newCentroids
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 1. Punkte zeichnen (Ellipse, r = dp(3.0); is_selected ist hier immer False)
        val r = dp(3.0f)
        for (p in points) {
            val cx = p.x * w
            val cy = h - p.y * h
            fillPaint.color = p.color
            canvas.drawCircle(cx, cy, r, fillPaint)
        }

        // 2. Centroids zeichnen (Dreiecke: Spitze (cx, cy+r) in Kivy = oben)
        val cr = dp(7f)
        for (c in centroids) {
            val cx = c.x * w
            val cy = h - c.y * h
            trianglePath.reset()
            trianglePath.moveTo(cx, cy - cr)                     // (cx, cy + r) in Kivy
            trianglePath.lineTo(cx - cr, cy + cr * 0.8f)         // (cx - r, cy - r*0.8)
            trianglePath.lineTo(cx + cr, cy + cr * 0.8f)         // (cx + r, cy - r*0.8)
            trianglePath.close()
            fillPaint.color = c.color
            canvas.drawPath(trianglePath, fillPaint)
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
