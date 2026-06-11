package com.fzi.speakerid.ui.diarizationreport

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.fzi.acousticscene.R
import com.fzi.speakerid.ui.SpeakerIdTheme

/**
 * Port von siqas `src/gui/widgets/diarization_timeline_widget.py::
 * DiarizationTimelineWidget`: zwei ausgerichtete horizontale Track-Gruppen
 * (Ground Truth "Soll" vs. Prediction "Ist") inkl. Zeitachse, Enrollment-
 * Baendern und DER-Berechnung (gesamt + Target).
 *
 * Kivy-Properties -> Kotlin-Setter (jede Aenderung triggert wie
 * `_trigger_rebuild` Neuberechnung + Redraw); die acht Metrik-Properties
 * werden gebuendelt ueber [onMetricsChanged] gemeldet (Pendant zum
 * `bind(der_val=...)` des Screens).
 */
class DiarizationTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Die acht Metrik-Werte (`der_val` ... `conf_target_val`), Raten 0..x. */
    class Metrics(
        val der: Double,
        val miss: Double,
        val fa: Double,
        val conf: Double,
        val derTarget: Double,
        val missTarget: Double,
        val faTarget: Double,
        val confTarget: Double,
    )

    var onMetricsChanged: ((Metrics) -> Unit)? = null

    /** RTTM-Rohzeilen der Referenz (Ground Truth). */
    var refLines: List<String> = emptyList()
        set(value) {
            field = value
            rebuild()
        }

    /** RTTM-Rohzeilen der Hypothese (Prediction). */
    var hypLines: List<String> = emptyList()
        set(value) {
            field = value
            rebuild()
        }

    /** [(start_s, end_s)] — Target-Enrollment, aus der Wertung ausgeschlossen. */
    var enrollmentSegments: List<Pair<Double, Double>> = emptyList()
        set(value) {
            field = value
            rebuild()
        }

    /** Bekannter Target-GT-Sprecher (aus dem Seeding), nicht geraten. */
    var targetRefSpeaker: String = ""
        set(value) {
            field = value
            rebuild()
        }

    // ── Berechneter Zustand (Pendant zu rebuild_timeline-Lokalen) ───────────

    private class Seg(val start: Double, val end: Double, val duration: Double, val speaker: String)

    private class Interval(
        val start: Double,
        val end: Double,
        val duration: Double,
        val refActive: Set<String>,
        val hypActive: Set<String>,
    )

    private class Row(val label: String, val segments: List<Seg>, val color: Int, val isRef: Boolean)

    private var hasData = false
    private var rows: List<Row> = emptyList()
    private var maxTime = 1.0

    // ── Paints / Ressourcen ──────────────────────────────────────────────────

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics
        )
    }

    private val fontRegular: Typeface =
        ResourcesCompat.getFont(context, R.font.speakerid_dejavu_sans) ?: Typeface.DEFAULT
    private val fontBold: Typeface =
        ResourcesCompat.getFont(context, R.font.speakerid_dejavu_sans_bold) ?: Typeface.DEFAULT_BOLD

    private val textPrimaryColor = ContextCompat.getColor(context, R.color.speakerid_text_primary)
    private val textSecondaryColor = ContextCompat.getColor(context, R.color.speakerid_text_secondary)
    private val borderColor = ContextCompat.getColor(context, R.color.speakerid_border)

    // Default-Farben aus dem Widget-Modul
    private val unmappedColor = Color.argb(0.8f, 0.5f, 0.5f, 0.5f)          // UNMAPPED_COLOR
    private val emptyBgColor = Color.argb(1f, 0.1f, 0.1f, 0.12f)            // "Keine Daten"-Hintergrund

    // ── Rebuild (Daten-Teil von `rebuild_timeline`) ─────────────────────────

    private fun rebuild() {
        val refSegments = parseRttm(refLines)
        val hypSegments = parseRttm(hypLines)

        hasData = refSegments.isNotEmpty() || hypSegments.isNotEmpty()
        if (!hasData) {
            rows = emptyList()
            maxTime = 1.0
            onMetricsChanged?.invoke(Metrics(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0))
            invalidate()
            return
        }

        // 2. Intervalle, Mapping & DER
        val intervals = buildIntervals(refSegments, hypSegments)
        val mapping = computeSpeakerMapping(intervals)
        val overall = calculateDerDetails(intervals, mapping)

        // 3. Maximale Timeline-Dauer
        var mt = 1.0
        for (s in refSegments) if (s.end > mt) mt = s.end
        for (s in hypSegments) if (s.end > mt) mt = s.end
        maxTime = mt

        val refSpeakers = refSegments.map { it.speaker }.distinct().sorted()

        // Target-GT-Sprecher bestimmen — Prioritaet: Seeding > Mapping("1") > laengster GT
        var targetRefSpk: String? = if (targetRefSpeaker in refSpeakers) targetRefSpeaker else null
        if (targetRefSpk == null) targetRefSpk = mapping["1"]
        if ((targetRefSpk == null || targetRefSpk.startsWith("UNMAPPED_")) && refSpeakers.isNotEmpty()) {
            val durations = LinkedHashMap<String, Double>()
            for (s in refSegments) durations[s.speaker] = (durations[s.speaker] ?: 0.0) + s.duration
            targetRefSpk = durations.maxByOrNull { it.value }?.key
        }

        val target = calculateTargetDerDetails(intervals, mapping, targetRefSpk)

        // GT-IDs auf das Cluster-Schema remappen: Target -> "1", Rest -> "2", "3", ...
        val refRemap = LinkedHashMap<String, String>()
        if (targetRefSpk != null && targetRefSpk in refSpeakers) refRemap[targetRefSpk] = "1"
        var nextId = 2
        for (spk in refSpeakers) {
            if (refRemap.containsKey(spk)) continue
            refRemap[spk] = nextId.toString()
            nextId += 1
        }

        val speakerColors = HashMap<String, Int>()
        for (spk in refSpeakers) {
            speakerColors[spk] = SpeakerIdTheme.speakerColor(context, refRemap.getValue(spk))
        }

        // ── Zeilen aufbauen ──
        val newRows = ArrayList<Row>()
        if (refSegments.isNotEmpty()) {
            // Vergleichs-Modus: Ref/Hyp je Sprecher paaren, Target zuerst
            val orderedRefs = refSpeakers.sortedWith(
                compareBy({ refRemap.getValue(it) != "1" }, { refRemap.getValue(it).toInt() })
            )

            for (refSpk in orderedRefs) {
                val displayId = refRemap.getValue(refSpk)
                val refColor = speakerColors[refSpk] ?: unmappedColor

                val refSegs = refSegments.filter { it.speaker == refSpk }
                val refLbl = if (displayId == "1") {
                    context.getString(R.string.speakerid_diarization_report_track_ref_target)
                } else {
                    context.getString(R.string.speakerid_diarization_report_track_ref_speaker, displayId)
                }
                newRows.add(Row(refLbl, refSegs, refColor, true))

                val mappedHypSegs = ArrayList<Seg>()
                for ((hypSpk, mappedRef) in mapping) {
                    if (mappedRef == refSpk) {
                        mappedHypSegs.addAll(hypSegments.filter { it.speaker == hypSpk })
                    }
                }
                val hypLbl = if (displayId == "1") {
                    context.getString(R.string.speakerid_diarization_report_track_hyp_target)
                } else {
                    context.getString(R.string.speakerid_diarization_report_track_hyp_speaker, displayId)
                }
                newRows.add(Row(hypLbl, mappedHypSegs, refColor, false))
            }

            // Nicht gemappte Hyp-Sprecher unten anhaengen — IDs ab nextId
            val unmappedHypSpeakers = hypSegments
                .map { it.speaker }
                .distinct()
                .filter { mapping[it] !in refSpeakers }
                .sorted()
            for (hypSpk in unmappedHypSpeakers) {
                val hypSegs = hypSegments.filter { it.speaker == hypSpk }
                val displayId = nextId.toString()
                nextId += 1
                val hypColor = SpeakerIdTheme.speakerColor(context, hypSpk)
                val hypLbl = context.getString(
                    R.string.speakerid_diarization_report_track_hyp_extra, displayId
                )
                newRows.add(Row(hypLbl, hypSegs, hypColor, false))
            }
        } else {
            // Live-Modus: nur vorhergesagte Sprecher — Target zuerst
            val hypSpeakers = hypSegments
                .map { it.speaker }
                .distinct()
                .sortedWith(compareBy({ it != "1" }, { digitsOr999(it) }))
            for (hypSpk in hypSpeakers) {
                val hypColor = SpeakerIdTheme.speakerColor(context, hypSpk)
                val hypSegs = hypSegments.filter { it.speaker == hypSpk }
                val lbl = if (hypSpk == "1") {
                    context.getString(R.string.speakerid_diarization_report_track_live_target)
                } else {
                    context.getString(R.string.speakerid_diarization_report_track_live_speaker, hypSpk)
                }
                newRows.add(Row(lbl, hypSegs, hypColor, false))
            }
        }
        rows = newRows

        onMetricsChanged?.invoke(
            Metrics(
                overall[0], overall[1], overall[2], overall[3],
                target[0], target[1], target[2], target[3],
            )
        )
        invalidate()
    }

    /** Python `int(s) if s.isdigit() else 999` (isdigit: nur Ziffern). */
    private fun digitsOr999(s: String): Int =
        if (s.isNotEmpty() && s.all { it.isDigit() }) s.toInt() else 999

    // ── Zeichnen (Geometrie-Teil von `rebuild_timeline`) ────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < dp(50f) || h < dp(40f)) return

        if (!hasData) {
            fillPaint.color = emptyBgColor
            canvas.drawRect(0f, 0f, w, h, fillPaint)
            drawLabel(
                canvas, context.getString(R.string.speakerid_diarization_report_timeline_no_data),
                w / 2f, h / 2f, Paint.Align.CENTER, textSecondaryColor, bold = false,
            )
            return
        }

        val n = rows.size
        if (n == 0) return

        val padL = dp(75f)
        val padR = dp(25f)
        val padT = dp(15f)
        val padB = dp(35f)
        val plotW = w - padL - padR
        val plotH = h - padT - padB
        if (plotW <= 0f || plotH <= 0f) return

        val gap = dp(4f)
        val availableH = plotH - (n - 1) * gap
        val trackH = maxOf(dp(12f), minOf(dp(28f), availableH / n))
        val totalTracksH = n * trackH + (n - 1) * gap
        // Kivy zeichnet reversed() von unten — identisch: rows[0] beginnt oben
        val topStart = padT + (plotH - totalTracksH) / 2f

        // Container-Hintergrund + Rahmen
        fillPaint.color = Color.argb(0.05f, 1f, 1f, 1f)
        canvas.drawRect(0f, 0f, w, h, fillPaint)
        strokePaint.color = borderColor
        strokePaint.strokeWidth = dp(1.2f)
        canvas.drawRect(0f, 0f, w, h, strokePaint)

        val maxT = maxTime.toFloat()

        for ((idx, row) in rows.withIndex()) {
            val ty = topStart + idx * (trackH + gap)

            // Track-Label: Soll = bold, Ist = regular; Textfarbe text_primary
            drawLabel(
                canvas, row.label, padL - dp(10f), ty + trackH / 2f,
                Paint.Align.RIGHT, textPrimaryColor, bold = row.isRef,
            )

            // Track-Hintergrundbalken
            fillPaint.color = Color.argb(0.45f, 0.08f, 0.08f, 0.1f)
            canvas.drawRect(padL, ty, padL + plotW, ty + trackH, fillPaint)

            // Sprach-Segmente
            for (s in row.segments) {
                val startPx = padL + (s.start.toFloat() / maxT) * plotW
                var durPx = (s.duration.toFloat() / maxT) * plotW
                durPx = maxOf(durPx, dp(1.5f))

                fillPaint.color = row.color
                canvas.drawRect(startPx, ty, startPx + durPx, ty + trackH, fillPaint)

                strokePaint.color = Color.argb(0.3f, 0f, 0f, 0f)
                strokePaint.strokeWidth = dp(0.6f)
                canvas.drawRect(startPx, ty, startPx + durPx, ty + trackH, strokePaint)
            }
        }

        // ── Enrollment-Bereiche markieren (aus der Wertung ausgeschlossen) ──
        for ((segStart, segEnd) in enrollmentSegments) {
            val bandX = padL + (segStart.toFloat() / maxT) * plotW
            val bandW = maxOf(((segEnd - segStart).toFloat() / maxT) * plotW, dp(1.0f))
            fillPaint.color = Color.argb(0.13f, 0.6f, 0.6f, 0.66f)
            canvas.drawRect(bandX, topStart, bandX + bandW, topStart + totalTracksH, fillPaint)
            strokePaint.color = Color.argb(0.35f, 0.7f, 0.7f, 0.78f)
            strokePaint.strokeWidth = dp(0.8f)
            canvas.drawRect(bandX, topStart, bandX + bandW, topStart + totalTracksH, strokePaint)
        }

        // ── Zeitachse / Grid-Ticks unter den Tracks ──
        val rulerY = h - padB + dp(6f)
        strokePaint.color = Color.argb(0.15f, 1f, 1f, 1f)
        strokePaint.strokeWidth = dp(0.8f)
        canvas.drawLine(padL, rulerY, padL + plotW, rulerY, strokePaint)

        var tickStep = 10.0
        if (maxTime > 300) {
            tickStep = 60.0
        } else if (maxTime > 120) {
            tickStep = 30.0
        } else if (maxTime <= 30) {
            tickStep = 5.0
        }

        var currTick = 0.0
        while (currTick <= maxTime) {
            val tx = padL + (currTick.toFloat() / maxT) * plotW
            strokePaint.color = Color.argb(0.25f, 1f, 1f, 1f)
            strokePaint.strokeWidth = dp(0.8f)
            canvas.drawLine(tx, rulerY, tx, rulerY - dp(6f), strokePaint)

            // Vertikale Grid-Linien
            strokePaint.color = Color.argb(0.035f, 1f, 1f, 1f)
            strokePaint.strokeWidth = dp(0.6f)
            canvas.drawLine(tx, topStart - dp(4f), tx, topStart + totalTracksH + dp(4f), strokePaint)

            // Zeitstempel-Label ("m:ss")
            val mins = (currTick / 60.0).toInt()
            val secs = (currTick % 60.0).toInt()
            val labelStr = String.format(java.util.Locale.US, "%d:%02d", mins, secs)
            drawLabel(
                canvas, labelStr, tx, rulerY + dp(10f),
                Paint.Align.CENTER, textSecondaryColor, bold = false,
            )

            currTick += tickStep
        }
    }

    /** Port von `_draw_label` (CoreLabel sp(10), DejaVu, vertikal zentriert). */
    private fun drawLabel(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        align: Paint.Align,
        color: Int,
        bold: Boolean,
    ) {
        textPaint.color = color
        textPaint.typeface = if (bold) fontBold else fontRegular
        textPaint.textAlign = align
        val baseline = y - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(text, x, baseline, textPaint)
    }

    // ── Parsing- und Mathe-Helfer (1:1 aus dem Kivy-Widget) ─────────────────

    /** Port von `_parse_rttm`. */
    private fun parseRttm(rttmLines: List<String>): List<Seg> {
        val segments = ArrayList<Seg>()
        for (raw in rttmLines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 8 || parts[0] != "SPEAKER") continue
            try {
                val start = parts[3].toDouble()
                val duration = parts[4].toDouble()
                val speaker = parts[7]
                segments.add(Seg(start, start + duration, duration, speaker))
            } catch (_: NumberFormatException) {
                // wie Pythons `except ValueError: pass`
            }
        }
        segments.sortBy { it.start }
        return segments
    }

    /** Port von `_build_intervals`. */
    private fun buildIntervals(refSegments: List<Seg>, hypSegments: List<Seg>): List<Interval> {
        val timestamps = sortedSetOf<Double>()
        for (s in refSegments + hypSegments) {
            timestamps.add(s.start)
            timestamps.add(s.end)
        }
        val sortedTimes = timestamps.toList()
        val intervals = ArrayList<Interval>()

        for (i in 0 until sortedTimes.size - 1) {
            val tStart = sortedTimes[i]
            val tEnd = sortedTimes[i + 1]
            val duration = tEnd - tStart
            if (duration < 1e-6) continue

            val refActive = LinkedHashSet<String>()
            for (s in refSegments) {
                if (s.start <= tStart + 1e-6 && s.end >= tEnd - 1e-6) refActive.add(s.speaker)
            }
            val hypActive = LinkedHashSet<String>()
            for (s in hypSegments) {
                if (s.start <= tStart + 1e-6 && s.end >= tEnd - 1e-6) hypActive.add(s.speaker)
            }
            intervals.add(Interval(tStart, tEnd, duration, refActive, hypActive))
        }
        return intervals
    }

    /** Port von `_compute_speaker_mapping` (greedy nach Ueberlappungsdauer). */
    private fun computeSpeakerMapping(intervals: List<Interval>): Map<String, String> {
        val overlap = LinkedHashMap<String, LinkedHashMap<String, Double>>()
        for (inv in intervals) {
            for (r in inv.refActive) {
                for (h in inv.hypActive) {
                    val refs = overlap.getOrPut(h) { LinkedHashMap() }
                    refs[r] = (refs[r] ?: 0.0) + inv.duration
                }
            }
        }

        val mapping = LinkedHashMap<String, String>()
        val matchedRef = HashSet<String>()

        val pairs = ArrayList<Triple<String, String, Double>>()
        for ((h, refs) in overlap) {
            for ((r, dur) in refs) pairs.add(Triple(h, r, dur))
        }
        pairs.sortByDescending { it.third }

        for ((h, r, _) in pairs) {
            if (!mapping.containsKey(h) && r !in matchedRef) {
                mapping[h] = r
                matchedRef.add(r)
            }
        }

        for (inv in intervals) {
            for (h in inv.hypActive) {
                if (!mapping.containsKey(h)) mapping[h] = "UNMAPPED_$h"
            }
        }
        return mapping
    }

    /** Port von `_is_enrollment`. */
    private fun isEnrollment(interval: Interval): Boolean {
        if (enrollmentSegments.isEmpty()) return false
        val mid = (interval.start + interval.end) / 2.0
        return enrollmentSegments.any { (segStart, segEnd) -> mid in segStart..segEnd }
    }

    /** Port von `_calculate_der_details` -> [der, miss, fa, conf]. */
    private fun calculateDerDetails(
        intervals: List<Interval>,
        mapping: Map<String, String>,
    ): DoubleArray {
        var refSpeech = 0.0
        var miss = 0.0
        var fa = 0.0
        var confusion = 0.0

        for (inv in intervals) {
            if (isEnrollment(inv)) continue
            val dur = inv.duration
            val refAct = inv.refActive
            val hypAct = inv.hypActive

            val mappedHypAct = hypAct.mapNotNull { mapping[it] }.toSet()

            val refSpeaks = refAct.isNotEmpty()
            val hypSpeaks = hypAct.isNotEmpty()

            if (refSpeaks) refSpeech += dur

            if (refSpeaks && !hypSpeaks) {
                miss += dur * refAct.size
            } else if (!refSpeaks && hypSpeaks) {
                fa += dur * hypAct.size
            } else if (refSpeaks && hypSpeaks) {
                val correctCount = refAct.count { it in mappedHypAct }

                if (refAct.size > hypAct.size) {
                    miss += dur * (refAct.size - hypAct.size)
                } else if (hypAct.size > refAct.size) {
                    fa += dur * (hypAct.size - refAct.size)
                }

                val confusionCount = minOf(refAct.size, hypAct.size) - correctCount
                if (confusionCount > 0) confusion += dur * confusionCount
            }
        }

        return if (refSpeech > 0) {
            val totalError = miss + fa + confusion
            doubleArrayOf(totalError / refSpeech, miss / refSpeech, fa / refSpeech, confusion / refSpeech)
        } else {
            doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        }
    }

    /** Port von `_calculate_target_der_details` (nur der Target-Sprecher). */
    private fun calculateTargetDerDetails(
        intervals: List<Interval>,
        mapping: Map<String, String>,
        targetRefSpk: String?,
    ): DoubleArray {
        if (targetRefSpk == null) return doubleArrayOf(0.0, 0.0, 0.0, 0.0)

        var refTargetSpeech = 0.0
        var miss = 0.0
        var fa = 0.0
        var confusion = 0.0

        for (inv in intervals) {
            if (isEnrollment(inv)) continue
            val dur = inv.duration
            val refIsTarget = targetRefSpk in inv.refActive
            val mappedHyp = inv.hypActive.map { mapping[it] }.toSet()
            val hypCoversTarget = targetRefSpk in mappedHyp

            if (refIsTarget) {
                refTargetSpeech += dur
                when {
                    hypCoversTarget -> Unit          // korrekt erkannt
                    inv.hypActive.isNotEmpty() -> confusion += dur
                    else -> miss += dur
                }
            } else if (hypCoversTarget) {
                fa += dur
            }
        }

        if (refTargetSpeech <= 0) return doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        val totalError = miss + fa + confusion
        return doubleArrayOf(
            totalError / refTargetSpeech,
            miss / refTargetSpeech,
            fa / refTargetSpeech,
            confusion / refTargetSpeech,
        )
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
