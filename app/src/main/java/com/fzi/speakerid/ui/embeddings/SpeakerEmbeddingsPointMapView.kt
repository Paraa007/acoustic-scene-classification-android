package com.fzi.speakerid.ui.embeddings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import com.fzi.acousticscene.R

/**
 * Port von siqas `gui/screens/embeddings/point_map.py::PointMap` —
 * interaktives 2D-Scatterplot-Widget:
 *
 *  - zeichnet Embeddings als Kreise (r dp(3), selektiert dp(4.5)) und
 *    Centroids als Dreiecke (r dp(7), Spitze oben),
 *  - selektierte Objekte erhalten einen gestrichelten Umriss (width 1.2,
 *    dash 4/4) in zur Haelfte+ aufgehellter Farbe (Blend 0.6 Richtung Weiss),
 *  - Touch-Down waehlt das naechste Objekt im Umkreis dp(20)
 *    (Centroids zuerst geprueft, Punkte gewinnen nur bei echt kleinerer
 *    Distanz — exakt wie `_find_nearest`),
 *  - Maus-/Stylus-Hover (Pendant zu `Window.mouse_pos`) meldet das naechste
 *    Objekt im Umkreis dp(18) via [onHoverChanged]; ohne Treffer `null`.
 *
 * Koordinaten: `pos` ist auf [0,1]x[0,1] normiert mit y nach OBEN (Kivy);
 * beim Zeichnen/Treffen wird y gespiegelt. Der "Schatten" des Originals wird
 * mit Alpha 0.0 gezeichnet (unsichtbar) und daher hier weggelassen.
 * [highlightColor] existiert wie im Original als Property, wird aber in
 * `update_canvas` nicht verwendet.
 */
class SpeakerEmbeddingsPointMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Eintrag aus `points_data` (embeddings.py::_do_refresh_ui). */
    data class MapPoint(
        val index: Int,
        val xNorm: Float,
        val yNorm: Float,
        val color: Int,
        val isSelected: Boolean,
        val clusterLabel: String,
    )

    /** Eintrag aus `centroids_data`. */
    data class MapCentroid(
        val clusterId: String,
        val xNorm: Float,
        val yNorm: Float,
        val color: Int,
        val isSelected: Boolean,
    )

    /** Pendant zu `screen_ref.hovered_data` ({} -> null). */
    data class HoverData(
        val type: String,
        val id: String,
        val cluster: String,
        val color: Int,
    )

    var pointsData: List<MapPoint> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var centroidsData: List<MapCentroid> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    /** Im .kv an accent_color gebunden; im Original-`update_canvas` ungenutzt. */
    var highlightColor: Int = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }

    /** `screen_ref.select_item("point", index)` */
    var onSelectPoint: ((Int) -> Unit)? = null

    /** `screen_ref.select_item("centroid", clusterId)` */
    var onSelectCentroid: ((String) -> Unit)? = null

    /** `screen_ref.hovered_data = {...}` bzw. `{}` (= null). */
    var onHoverChanged: ((HoverData?) -> Unit)? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        // Kivy dash_length=4 / dash_offset=4 (= Luecke zwischen Strichen)
        pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
    }
    private val trianglePath = Path()

    // ── Treffer-Suche (Port von `_find_nearest`) ────────────────────────────

    private sealed class Hit {
        class PointHit(val index: Int) : Hit()
        class CentroidHit(val clusterId: String) : Hit()
    }

    private fun findNearest(x: Float, y: Float, thresholdPx: Float): Hit? {
        if (x < 0f || y < 0f || x > width || y > height) return null

        var best: Hit? = null
        var minDistSq = thresholdPx * thresholdPx + 1f
        val w = width.toFloat()
        val h = height.toFloat()

        for (c in centroidsData) {
            val cx = c.xNorm * w
            val cy = (1f - c.yNorm) * h
            val dSq = (x - cx) * (x - cx) + (y - cy) * (y - cy)
            if (dSq < minDistSq) {
                minDistSq = dSq
                best = Hit.CentroidHit(c.clusterId)
            }
        }
        for (p in pointsData) {
            val px = p.xNorm * w
            val py = (1f - p.yNorm) * h
            val dSq = (x - px) * (x - px) + (y - py) * (y - py)
            if (dSq < minDistSq) {
                minDistSq = dSq
                best = Hit.PointHit(p.index)
            }
        }
        return best
    }

    // ── Touch (Port von `on_touch_down`) ────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val hit = findNearest(event.x, event.y, dp(20f))
            if (hit != null) {
                when (hit) {
                    is Hit.PointHit -> onSelectPoint?.invoke(hit.index)
                    is Hit.CentroidHit -> onSelectCentroid?.invoke(hit.clusterId)
                }
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // ── Hover (Port von `_on_mouse_pos`, nur Maus/Stylus) ───────────────────

    override fun onHoverEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                when (val hit = findNearest(event.x, event.y, dp(18f))) {
                    null -> onHoverChanged?.invoke(null)
                    is Hit.PointHit -> {
                        val p = pointsData.getOrNull(hit.index)
                        if (p != null) {
                            onHoverChanged?.invoke(
                                HoverData(
                                    type = context.getString(
                                        R.string.speakerid_embeddings_hover_type_point,
                                    ),
                                    id = hit.index.toString(),
                                    cluster = p.clusterLabel,
                                    color = p.color,
                                ),
                            )
                        }
                    }
                    is Hit.CentroidHit -> {
                        val c = centroidsData.firstOrNull { it.clusterId == hit.clusterId }
                        if (c != null) {
                            onHoverChanged?.invoke(
                                HoverData(
                                    type = context.getString(
                                        R.string.speakerid_embeddings_hover_type_centroid,
                                    ),
                                    id = c.clusterId,
                                    cluster = c.clusterId,
                                    color = c.color,
                                ),
                            )
                        }
                    }
                }
            }
            MotionEvent.ACTION_HOVER_EXIT -> onHoverChanged?.invoke(null)
        }
        return super.onHoverEvent(event)
    }

    // ── Zeichnen (Port von `update_canvas`) ─────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // 1. Punkte zeichnen
        for (p in pointsData) {
            val r = if (p.isSelected) dp(4.5f) else dp(3f)
            val cx = p.xNorm * w
            val cy = (1f - p.yNorm) * h

            fillPaint.color = p.color
            canvas.drawCircle(cx, cy, r, fillPaint)

            if (p.isSelected) {
                // Echte Aufhellung (Mischen mit Weiss, blend 0.6)
                dashPaint.color = ColorUtils.blendARGB(p.color, Color.WHITE, 0.6f)
                canvas.drawCircle(cx, cy, r + dp(1f), dashPaint)
            }
        }

        // 2. Centroids zeichnen (Dreiecke, Spitze oben)
        for (c in centroidsData) {
            val r = dp(7f)
            val cx = c.xNorm * w
            val cy = (1f - c.yNorm) * h

            trianglePath.rewind()
            trianglePath.moveTo(cx, cy - r)
            trianglePath.lineTo(cx - r, cy + r * 0.8f)
            trianglePath.lineTo(cx + r, cy + r * 0.8f)
            trianglePath.close()
            fillPaint.color = c.color
            canvas.drawPath(trianglePath, fillPaint)

            if (c.isSelected) {
                val o = dp(1.5f)
                trianglePath.rewind()
                trianglePath.moveTo(cx, cy - r - o)
                trianglePath.lineTo(cx - r - o, cy + r * 0.8f + o * 0.6f)
                trianglePath.lineTo(cx + r + o, cy + r * 0.8f + o * 0.6f)
                trianglePath.close()
                dashPaint.color = ColorUtils.blendARGB(c.color, Color.WHITE, 0.6f)
                canvas.drawPath(trianglePath, dashPaint)
            }
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
