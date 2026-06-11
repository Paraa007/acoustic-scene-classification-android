package com.fzi.speakerid.ui.dashboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.fzi.speakerid.library.data.HistoryEntry
import com.fzi.speakerid.library.data.SpeakerManager
import com.fzi.speakerid.ui.SpeakerIdTheme
import kotlin.math.ceil
import kotlin.math.max

/**
 * Port von siqas `gui/widgets/waveform_widget.py::SegmentedWaveform`:
 * Visualisiert die `chunk_history` als Raster farbiger Quadrate.
 *
 * - Spaltenanzahl: `max(10, breite / (24dp + 2dp))` (kontinuierlich)
 * - Quadrate `seg_w = (breite - (cols+1)*pad) / cols`, Hoehe = Breite
 * - Stille -> SILENCE_COLOR, `id == null/"0"` -> UNLABELED_COLOR,
 *   sonst Theme-Sprecherfarbe ([SpeakerIdTheme.speakerColor])
 * - Reassignment (`prev_id` gesetzt) -> diagonal gesplittetes Kaestchen:
 *   alte Farbe unten-links, neue Farbe oben-rechts
 * - Rahmen: aussen (0.2,0.2,0.2,1) 1dp, pro Kaestchen (0,0,0,0.2) 0.5dp
 *
 * Die Hoehe ergibt sich aus der Zeilenanzahl (`rows*seg_h + (rows+1)*pad`),
 * bei leerer Liste 0 (Kivy: `self.height = dp(0)`).
 */
class SpeakerIdSegmentedWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Pendant zur Kivy-`ListProperty segment_data` (chunk_history-Eintraege). */
    var segmentData: List<HistoryEntry> = emptyList()
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val trianglePath = Path()

    private class Grid(val cols: Int, val rows: Int, val segSize: Float)

    /** Kivy `_render`-Geometrie: ideal 24dp pro Segment, 2dp Padding. */
    private fun computeGrid(w: Float, num: Int): Grid {
        val pad = dp(2f)
        val idealSegSize = dp(24f)
        val cols = max(10, (w / (idealSegSize + pad)).toInt())
        val rows = max(1, (num + cols - 1) / cols)
        val segW = (w - (cols + 1) * pad) / cols
        return Grid(cols, rows, segW)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val num = segmentData.size
        if (num == 0 || width <= 0) {
            setMeasuredDimension(width, 0)
            return
        }
        val pad = dp(2f)
        val grid = computeGrid(width.toFloat(), num)
        val requiredH = grid.rows * grid.segSize + (grid.rows + 1) * pad
        setMeasuredDimension(width, ceil(requiredH.toDouble()).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val num = segmentData.size
        if (num == 0) return
        val w = width.toFloat()
        // Kivy: `if w < 100: return` (noch nicht bereit fuer Render)
        if (w < 100f) return

        val pad = dp(2f)
        val grid = computeGrid(w, num)
        val segW = grid.segSize
        val segH = segW

        // Aussenrahmen: Color(0.2, 0.2, 0.2, 1), Line width dp(1)
        strokePaint.color = OUTER_BORDER_COLOR
        strokePaint.strokeWidth = dp(1f)
        canvas.drawRect(0f, 0f, w, height.toFloat(), strokePaint)

        for (i in segmentData.indices) {
            val row = i / grid.cols
            val col = i % grid.cols
            val x = pad + col * (segW + pad)
            val y = pad + row * (segH + pad)

            drawChunk(canvas, segmentData[i], x, y, segW, segH)

            // Kaestchen-Rahmen: Color(0, 0, 0, 0.2), width dp(0.5)
            strokePaint.color = SQUARE_BORDER_COLOR
            strokePaint.strokeWidth = dp(0.5f)
            canvas.drawRect(x, y, x + segW, y + segH, strokePaint)
        }
    }

    /** Kivy `_draw_chunk`: einfarbig oder diagonal gesplittet. */
    private fun drawChunk(
        canvas: Canvas,
        chunk: HistoryEntry,
        x: Float,
        y: Float,
        segW: Float,
        segH: Float,
    ) {
        if (chunk.status == SpeakerManager.STATUS_SILENCE) {
            fillPaint.color = SILENCE_COLOR
            canvas.drawRect(x, y, x + segW, y + segH, fillPaint)
            return
        }

        if (chunk.prevId != null) {
            // Diagonal-Split: prev unten-links, neue Farbe oben-rechts
            fillPaint.color = colorForId(chunk.prevId)
            trianglePath.reset()
            trianglePath.moveTo(x, y + segH)            // unten-links
            trianglePath.lineTo(x + segW, y + segH)     // unten-rechts
            trianglePath.lineTo(x, y)                   // oben-links
            trianglePath.close()
            canvas.drawPath(trianglePath, fillPaint)

            fillPaint.color = colorForId(chunk.id)
            trianglePath.reset()
            trianglePath.moveTo(x + segW, y)            // oben-rechts
            trianglePath.lineTo(x, y)                   // oben-links
            trianglePath.lineTo(x + segW, y + segH)     // unten-rechts
            trianglePath.close()
            canvas.drawPath(trianglePath, fillPaint)
        } else {
            fillPaint.color = colorForId(chunk.id)
            canvas.drawRect(x, y, x + segW, y + segH, fillPaint)
        }
    }

    /** Kivy `_color_for_id`: None/"0" -> unlabeled-grau, sonst Theme-Farbe. */
    private fun colorForId(speakerId: String?): Int {
        if (speakerId == null || speakerId == "0") return UNLABELED_COLOR
        return SpeakerIdTheme.speakerColor(context, speakerId)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    companion object {
        /** waveform_widget.py UNLABELED_COLOR (0.55, 0.55, 0.65, 1) — helles Blaugrau */
        private val UNLABELED_COLOR = 0xFF8C8CA6.toInt()

        /** waveform_widget.py SILENCE_COLOR (0.08, 0.08, 0.1, 1) — fast Schwarz */
        private val SILENCE_COLOR = 0xFF14141A.toInt()

        /** Aussenrahmen Color(0.2, 0.2, 0.2, 1) */
        private val OUTER_BORDER_COLOR = 0xFF333333.toInt()

        /** Kaestchen-Rahmen Color(0, 0, 0, 0.2) */
        private val SQUARE_BORDER_COLOR = 0x33000000
    }
}
