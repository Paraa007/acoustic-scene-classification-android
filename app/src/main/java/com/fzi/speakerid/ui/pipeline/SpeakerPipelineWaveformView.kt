package com.fzi.speakerid.ui.pipeline

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.fzi.acousticscene.R
import com.fzi.speakerid.library.data.HistoryEntry
import com.fzi.speakerid.library.data.SpeakerManager
import com.fzi.speakerid.ui.SpeakerIdTheme
import kotlin.math.ceil
import kotlin.math.max

/**
 * Port von siqas `gui/widgets/waveform_widget.py::SegmentedWaveform`:
 * visualisiert die `chunk_history` als farbige Quadrate (1 Kaestchen = 1
 * Chunk). Eintraege mit `prevId` (Re-Zuweisung) werden diagonal gesplittet:
 * alte Farbe unten-links, neue Farbe oben-rechts.
 *
 * Geometrie wie das Original: pad dp(2), Ziel-Kaestchengroesse dp(24),
 * cols = max(10, w / (24dp + pad)), Quadrate, benoetigte Hoehe
 * rows*seg + (rows+1)*pad (erste Reihe sitzt 2*pad unter der Oberkante —
 * exakt die Kivy-Formel `top - pad - (row+1)*(seg+pad)`).
 *
 * Farb-Logik `_color_for_id`: id null/"0" -> UNLABELED-Grau, sonst
 * `ThemeManager.get_speaker_color` = [SpeakerIdTheme.speakerColor];
 * status "silence" -> SILENCE-Anthrazit.
 */
class SpeakerPipelineWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** `segment_data` — neu setzen loest Re-Layout + Repaint aus (Kivy-bind). */
    var segmentData: List<HistoryEntry> = emptyList()
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    private val fillPaint = Paint().apply { style = Paint.Style.FILL }
    private val strokePaint = Paint().apply { style = Paint.Style.STROKE }
    private val trianglePath = Path()

    private val unlabeledColor = ContextCompat.getColor(context, R.color.speakerid_pipeline_wf_unlabeled)
    private val silenceColor = ContextCompat.getColor(context, R.color.speakerid_pipeline_wf_silence)
    private val borderColor = ContextCompat.getColor(context, R.color.speakerid_pipeline_wf_border)
    private val cellFrameColor = ContextCompat.getColor(context, R.color.speakerid_pipeline_wf_cell_frame)

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    /** `_color_for_id`: None/"0" -> unlabeled-grau, sonst Sprecherfarbe. */
    private fun colorForId(speakerId: String?): Int =
        if (speakerId == null || speakerId == SpeakerManager.RESERVED_UNLABELED) {
            unlabeledColor
        } else {
            SpeakerIdTheme.speakerColor(context, speakerId)
        }

    // ── Layout (Hoehenberechnung aus `_render`) ──────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val num = segmentData.size
        if (num == 0 || width <= 0) {
            // `self.height = dp(0)` bei leerer History
            setMeasuredDimension(width, 0)
            return
        }
        val pad = dp(2f)
        val cols = columnsFor(width.toFloat(), pad)
        val rows = (num + cols - 1) / cols
        val segW = (width - (cols + 1) * pad) / cols
        val requiredH = rows * segW + (rows + 1) * pad
        setMeasuredDimension(width, ceil(requiredH.toDouble()).toInt())
    }

    /** `cols = max(10, int(w / (ideal_seg_size + pad)))`, ideal dp(24). */
    private fun columnsFor(w: Float, pad: Float): Int =
        max(10, (w / (dp(24f) + pad)).toInt())

    // ── Render (Port von `_render` + `_draw_chunk`) ──────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val num = segmentData.size
        if (num == 0) return
        val w = width.toFloat()
        if (w < 100f) return // "Nicht bereit für Render"

        val pad = dp(2f)
        val cols = columnsFor(w, pad)
        val segW = (w - (cols + 1) * pad) / cols
        val segH = segW // Quadrate

        // Rahmen um das gesamte Widget: Color(0.2, 0.2, 0.2, 1), width dp(1)
        strokePaint.color = borderColor
        strokePaint.strokeWidth = dp(1f)
        canvas.drawRect(0f, 0f, w, height.toFloat(), strokePaint)

        for ((i, chunk) in segmentData.withIndex()) {
            val row = i / cols
            val col = i % cols
            val x = pad + col * (segW + pad)
            // Kivy: y = top - pad - (row+1)*(seg_h+pad) -> von oben gemessen:
            val yTop = 2 * pad + row * (segH + pad)

            drawChunk(canvas, chunk, x, yTop, segW, segH)

            // Rahmen je Kaestchen: Color(0, 0, 0, 0.2), width dp(0.5)
            strokePaint.color = cellFrameColor
            strokePaint.strokeWidth = dp(0.5f)
            canvas.drawRect(x, yTop, x + segW, yTop + segH, strokePaint)
        }
    }

    /** `_draw_chunk` — einfarbig oder diagonal gesplittet. */
    private fun drawChunk(
        canvas: Canvas,
        chunk: HistoryEntry,
        x: Float,
        yTop: Float,
        segW: Float,
        segH: Float,
    ) {
        if (chunk.status == SpeakerManager.STATUS_SILENCE) {
            fillPaint.color = silenceColor
            canvas.drawRect(x, yTop, x + segW, yTop + segH, fillPaint)
            return
        }

        if (chunk.prevId != null) {
            // Diagonal-Split: prev unten-links, neu oben-rechts
            // (Kivy-Punkte mit y nach oben -> hier unten = yTop + segH)
            fillPaint.color = colorForId(chunk.prevId)
            trianglePath.apply {
                reset()
                moveTo(x, yTop + segH)        // unten-links
                lineTo(x + segW, yTop + segH) // unten-rechts
                lineTo(x, yTop)               // oben-links
                close()
            }
            canvas.drawPath(trianglePath, fillPaint)

            fillPaint.color = colorForId(chunk.id)
            trianglePath.apply {
                reset()
                moveTo(x + segW, yTop)        // oben-rechts
                lineTo(x, yTop)               // oben-links
                lineTo(x + segW, yTop + segH) // unten-rechts
                close()
            }
            canvas.drawPath(trianglePath, fillPaint)
        } else {
            // Einfarbig
            fillPaint.color = colorForId(chunk.id)
            canvas.drawRect(x, yTop, x + segW, yTop + segH, fillPaint)
        }
    }
}
