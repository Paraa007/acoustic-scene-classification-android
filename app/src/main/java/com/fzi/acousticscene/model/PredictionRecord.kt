package com.fzi.acousticscene.model

import java.text.SimpleDateFormat
import java.util.*

/**
 * Individual 1-second clip result for AVERAGE mode.
 *
 * `volumeMean` / `volumePeak` are aggregated over the same 1 s window; null on
 * legacy records persisted before volume tracking landed.
 */
data class PerSecondClip(
    val clipIndex: Int,         // 0-9
    val sceneClass: SceneClass,
    val confidence: Float,
    val allProbabilities: FloatArray,
    val volumeMean: Float? = null,
    val volumePeak: Float? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PerSecondClip) return false
        return clipIndex == other.clipIndex
    }
    override fun hashCode(): Int = clipIndex
}

/**
 * ALL-IN-ONE mode result: one entry per model that was run on the same recording buffer.
 */
data class AllInOneResult(
    val modelName: String,
    val sceneClass: SceneClass,
    val confidence: Float,
    val allProbabilities: FloatArray,
    val inferenceTimeMs: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AllInOneResult) return false
        return modelName == other.modelName
    }
    override fun hashCode(): Int = modelName.hashCode()
}

/**
 * LONG-mode sub-mode result (Standard / Fast / Avg applied to the same 10 s recording).
 *
 * `modelName` was added with the Multi-Model Evaluation feature: each
 * (model, sub-mode) combination produces its own [LongSubResult]. Older records
 * persisted before the migration carry `null` here and are treated as primary-model.
 */
data class LongSubResult(
    val subMode: LongSubMode,
    val sceneClass: SceneClass,
    val confidence: Float,
    val allProbabilities: FloatArray,
    val inferenceTimeMs: Long,
    val perSecondClips: List<PerSecondClip>? = null,  // Only for AVERAGE sub-mode
    val modelName: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LongSubResult) return false
        return subMode == other.subMode && modelName == other.modelName
    }
    override fun hashCode(): Int = 31 * subMode.hashCode() + (modelName?.hashCode() ?: 0)
}

/**
 * Eine einzelne Vorhersage mit allen Details für CSV Export
 *
 * @param topPredictions Top 3 Predictions (Klasse, Konfidenz) für CSV Export
 * @param batteryLevel Akkustand in % (0-100) zum Zeitpunkt der Vorhersage
 */
data class PredictionRecord(
    val id: Long = System.currentTimeMillis(),
    val timestamp: Long = System.currentTimeMillis(),
    val sessionStartTime: Long = System.currentTimeMillis(),  // Start-Zeit der App-Session
    val sceneClass: SceneClass,
    val confidence: Float,  // 0.0 - 1.0
    val allProbabilities: FloatArray,
    val topPredictions: List<Pair<SceneClass, Float>>,  // Top 3 Predictions
    val inferenceTimeMs: Long,
    val recordingMode: RecordingMode,
    val batteryLevel: Int = -1,  // Akkustand in % (0-100), -1 = unbekannt
    val modelName: String = "model1.pt",  // Model file name
    val userSelectedClass: SceneClass? = null,  // User evaluation: selected scene class (null = no response)
    val perSecondClips: List<PerSecondClip>? = null,  // AVERAGE mode: individual 1s clip results
    val longSubResults: List<LongSubResult>? = null,  // LONG mode: per sub-mode evaluation of the same 10s buffer
    val longIntervalMinutes: Int? = null,  // LONG mode (Dev): chosen pause interval between recordings, in minutes
    val allInOneResults: List<AllInOneResult>? = null,  // ALL-IN-ONE mode: one entry per selected model
    // Aggregated audio level over the cycle. Range 0.0 – 1.0. null for legacy records.
    val volumeMean: Float? = null,
    val volumePeak: Float? = null,
    // Synthetic PAUSE records: written whenever the user hits Pause/Resume so the
    // CSV carries an explicit gap row. When true, all classification fields are
    // placeholders and only `pauseDurationSec` is meaningful.
    val isPause: Boolean = false,
    val pauseDurationSec: Long? = null,
    // Wizard-snapshot. All seven nullable on legacy records (persisted before the
    // CSV-completeness migration); new records carry the full SessionConfig 1:1
    // so the CSV export can reproduce the wizard answers without reconstruction.
    val modelsSelected: List<String>? = null,
    val recordingCategory: RecordingCategory? = null,
    val continuousMethodsByModel: Map<String, Set<LongSubMode>>? = null,
    val intervalMethodsByModel: Map<String, Set<LongSubMode>>? = null,
    val sessionDurationPlanned: SessionDuration? = null,
    val pauseAutoResumeMin: Int? = null,
    // Per-second RMS volume mean across the 10 s frame (length 10). For 1 s-long
    // FAST cycles only s1 carries data, s2..s10 = 0.
    val perSecondVolumes: FloatArray? = null,
    /**
     * Which app entry point launched this record's session. TEST = tester ran
     * a quickstart slot; CONFIG = developer ran the wizard. Null on legacy
     * records persisted before the mode tag landed (treated as CONFIG by the
     * History filter).
     */
    val sessionMode: SessionMode? = null
) {
    /**
     * Formatierter Zeitstempel für Anzeige
     */
    fun getFormattedTime(): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
    
    /**
     * Formatiertes Datum für Anzeige
     */
    fun getFormattedDate(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
    
    /**
     * Formatierter DateTime für CSV
     */
    fun getFormattedDateTime(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
    
    /**
     * Formatiert sessionStartTime im selben Stil wie [getFormattedDateTime].
     */
    fun getFormattedSessionStartTime(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(sessionStartTime))
    }

    /**
     * Konvertiert zu CSV-Zeile
     * Enthält jetzt auch Top 3 Predictions und Batterie-Level.
     *
     * @param allInOneModelNames Wenn nicht-null, wird am Ende für jedes Modell in dieser
     *   Liste eine Zelle `"Class:XX%"` aus [allInOneResults] angehängt (leer falls dieses
     *   Record keinen Eintrag für das Modell hat). So bekommt der CSV-Export dynamische
     *   Spalten pro Modell, ohne dass Records ohne ALL-IN-ONE-Daten einen Defekt erzeugen.
     */
    fun toCsvRow(allInOneModelNames: List<String>? = null): String {
        // Session/wizard snapshot cells — present on every row (PAUSE included)
        // so a single session reads as one coherent block in the CSV.
        val sessionStartStr = getFormattedSessionStartTime()
        val sessionDurationStr = sessionDurationPlanned?.label.orEmpty()
        val modelsSelectedStr = modelsSelected?.joinToString("|").orEmpty()
        val categoryStr = recordingCategory?.label.orEmpty()
        fun serializeMethodMap(map: Map<String, Set<LongSubMode>>?): String =
            map?.takeIf { it.isNotEmpty() }
                ?.entries
                ?.sortedBy { it.key }
                ?.joinToString("|") { (m, methods) ->
                    "$m:" + methods.sortedBy { it.ordinal }.joinToString(",") { it.name }
                }
                .orEmpty()
        val continuousMethodsStr = serializeMethodMap(continuousMethodsByModel)
        val intervalMethodsStr = serializeMethodMap(intervalMethodsByModel)
        val pauseAutoResumeStr = pauseAutoResumeMin?.toString().orEmpty()
        val longIntervalStr = longIntervalMinutes?.toString() ?: ""

        // Per-second volume cells — PAUSE rows fill 0.000, regular rows fill the
        // actual buckets, legacy rows (perSecondVolumes == null) stay empty.
        val volumeSecondCells: List<String> = when {
            isPause -> List(10) { "0.000" }
            perSecondVolumes != null -> List(10) { i ->
                String.format(Locale.US, "%.3f", perSecondVolumes.getOrNull(i) ?: 0f)
            }
            else -> List(10) { "" }
        }

        // Mean/peak — same treatment: 0 for PAUSE, formatted for real, blank for legacy.
        val volumeMeanCell = when {
            isPause -> "0.000"
            volumeMean != null -> String.format(Locale.US, "%.3f", volumeMean)
            else -> ""
        }
        val volumePeakCell = when {
            isPause -> "0.000"
            volumePeak != null -> String.format(Locale.US, "%.3f", volumePeak)
            else -> ""
        }

        // Synthetic PAUSE record: classification cells stay empty, only timestamp,
        // session block, mode_label, volume zeros and pause_duration_sec carry data.
        if (isPause) {
            val baseCells = listOf(
                id.toString(),
                getFormattedDateTime(),
                sessionStartStr,
                escapeCsv(sessionDurationStr),
                "",                       // battery
                "",                       // class_display_name
                "0",                      // confidence_percent
                "",                       // inference_time_sec
                "PAUSE",                  // recording_mode → "PAUSE" makes filtering trivial
                "",                       // model_name
                escapeCsv(modelsSelectedStr),
                escapeCsv(categoryStr),
                escapeCsv(continuousMethodsStr),
                escapeCsv(intervalMethodsStr),
                pauseAutoResumeStr,
                "", "", "", "", "", "",   // top1..top3
                "",                       // probabilities
                "",                       // user_selected_class
                "",                       // per_second_clips
                "", "", "",               // long_standard / fast / average
                longIntervalStr,
                volumeMeanCell,
                volumePeakCell
            ) + volumeSecondCells + listOf(
                pauseDurationSec?.toString().orEmpty()
            )
            val emptyAllInOne = allInOneModelNames?.map { "" }.orEmpty()
            return (baseCells + emptyAllInOne).joinToString(",")
        }

        // Probabilities mit Klassennamen (statt Indizes)
        val probsString = allProbabilities.joinToString(";") {
            String.format(Locale.US, "%.4f", it)
        }
        val confidencePercent = (confidence * 100).toInt()

        // Top 3 Predictions (immer 3, mit Platzhaltern falls weniger vorhanden)
        val top3 = topPredictions.take(3)
        val top1 = top3.getOrNull(0) ?: (SceneClass.TRANSIT_VEHICLES to 0f)
        val top2 = top3.getOrNull(1) ?: (SceneClass.TRANSIT_VEHICLES to 0f)
        val top3_entry = top3.getOrNull(2) ?: (SceneClass.TRANSIT_VEHICLES to 0f)

        // Recording mode mit Zeit (z.B. "STANDARD (10s)")
        val recordingModeWithTime = "${recordingMode.name} (${recordingMode.durationSeconds}s)"

        // Batterie-Level (oder "N/A" wenn unbekannt)
        val batteryString = if (batteryLevel >= 0) batteryLevel.toString() else "N/A"

        // User evaluation column (empty if no response). Escaped — user-configurable input.
        val userClassStr = userSelectedClass?.label?.let { escapeCsv(it) } ?: ""

        // Per-second clips (AVERAGE mode + LONG-with-Avg sub-mode).
        // When per-clip volume was captured (new records) the entry carries
        // `:mean=…:peak=…` after the percentage; legacy records omit those.
        val perSecondStr = if (perSecondClips != null && perSecondClips.isNotEmpty()) {
            perSecondClips.sortedBy { it.clipIndex }.joinToString("|") { clip ->
                val base = "${clip.clipIndex + 1}:${clip.sceneClass.label}:${(clip.confidence * 100).toInt()}%"
                val vol = if (clip.volumeMean != null && clip.volumePeak != null) {
                    ":mean=${String.format(Locale.US, "%.3f", clip.volumeMean)}" +
                            ":peak=${String.format(Locale.US, "%.3f", clip.volumePeak)}"
                } else ""
                base + vol
            }
        } else ""

        // LONG sub-mode results.
        // Multi-Model Evaluation: each (model, sub-mode) gets its own LongSubResult.
        // The 3 fixed CSV columns (long_standard / long_fast / long_average) carry pipe-
        // serialised per-model entries: "m1:Park:80%|m2:Park:75%". Records from before
        // the migration (modelName == null) emit a single unprefixed entry.
        fun subCell(sub: LongSubMode): String {
            val matching = longSubResults?.filter { it.subMode == sub }.orEmpty()
            if (matching.isEmpty()) return ""
            return matching.joinToString("|") { r ->
                val name = r.modelName
                val pct = (r.confidence * 100).toInt()
                if (name.isNullOrBlank()) "${r.sceneClass.label}:$pct%"
                else "$name:${r.sceneClass.label}:$pct%"
            }
        }
        val longStdStr = subCell(LongSubMode.STANDARD)
        val longFastStr = subCell(LongSubMode.FAST)
        val longAvgStr = subCell(LongSubMode.AVERAGE)

        val baseCells = listOf(
            id.toString(),
            getFormattedDateTime(),
            sessionStartStr,
            escapeCsv(sessionDurationStr),
            batteryString,
            escapeCsv(sceneClass.label),
            confidencePercent.toString(),
            String.format(Locale.US, "%.2f", inferenceTimeMs / 1000.0),
            escapeCsv(recordingModeWithTime),
            escapeCsv(modelName),
            escapeCsv(modelsSelectedStr),
            escapeCsv(categoryStr),
            escapeCsv(continuousMethodsStr),
            escapeCsv(intervalMethodsStr),
            pauseAutoResumeStr,
            // Top 3 Predictions (ohne Indexe, ohne name)
            escapeCsv(top1.first.label),
            String.format(Locale.US, "%.2f", top1.second * 100),
            escapeCsv(top2.first.label),
            String.format(Locale.US, "%.2f", top2.second * 100),
            escapeCsv(top3_entry.first.label),
            String.format(Locale.US, "%.2f", top3_entry.second * 100),
            probsString,
            userClassStr,
            escapeCsv(perSecondStr),
            escapeCsv(longStdStr),
            escapeCsv(longFastStr),
            escapeCsv(longAvgStr),
            escapeCsv(longIntervalStr),
            volumeMeanCell,
            volumePeakCell
        ) + volumeSecondCells + listOf(
            ""  // pause_duration_sec (empty for non-PAUSE rows)
        )

        // ALL-IN-ONE dynamische Spalten: eine Zelle pro bekanntem Modellnamen
        val allInOneCells: List<String> = if (allInOneModelNames != null && allInOneModelNames.isNotEmpty()) {
            allInOneModelNames.map { name ->
                val r = allInOneResults?.firstOrNull { it.modelName == name }
                if (r != null) {
                    escapeCsv("${r.sceneClass.label}:${(r.confidence * 100).toInt()}%")
                } else ""
            }
        } else emptyList()

        return (baseCells + allInOneCells).joinToString(",")
    }

    companion object {
        /**
         * Escapes a string for safe CSV output.
         *
         * Defends against two classes of attack/corruption:
         *  1. **Formula injection** — Excel/LibreOffice/Numbers interpret cells starting
         *     with `=`, `+`, `-`, `@`, TAB or CR as formulas. We prefix such values with
         *     a single quote (`'`), which spreadsheet apps render as plain text. This is
         *     the OWASP-recommended mitigation.
         *  2. **RFC 4180 structural breakage** — values containing `,`, `"`, or newline
         *     must be wrapped in double quotes with embedded quotes doubled.
         *
         * Numeric fields (Long/Double/Int rendered via toString or String.format) are
         * safe by construction and don't need escaping.
         */
        internal fun escapeCsv(value: String): String {
            if (value.isEmpty()) return ""
            // Formula-injection guard: neutralize leading meta-chars.
            val deFormula = if (value[0] in "=+-@\t\r") "'$value" else value
            // RFC 4180 quoting if the value contains structural chars.
            val needsQuoting = deFormula.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
            return if (needsQuoting) {
                "\"" + deFormula.replace("\"", "\"\"") + "\""
            } else {
                deFormula
            }
        }

        /**
         * CSV Header. Spalten in der Reihenfolge wie [toCsvRow] sie erzeugt —
         * gruppiert in Session-Meta (timestamp + Session-Start/Duration), Predict
         * (battery/class/confidence/inference/mode), Session-Config (model_name +
         * Wizard-Block), Top-N, Probabilities, Aux (user_selected, per_second_clips,
         * long_*), Volume (mean/peak + s1..s10), Pause.
         */
        fun getCsvHeader(allInOneModelNames: List<String>? = null): String {
            // Probabilities mit Display-Namen (wie top3_display_name) - mit Anführungszeichen
            val probHeaders = SceneClass.entries.sortedBy { it.index }.joinToString(";") { "\"${it.label}\"" }
            val volumeSecondCols = (1..10).joinToString(",") { "volume_s$it" }
            val base = "id,timestamp,session_start_time,session_duration_planned," +
                    "battery_percent,class_display_name,confidence_percent,inference_time_sec," +
                    "recording_mode,model_name,models_selected,category,continuous_methods_by_model," +
                    "interval_methods_by_model,pause_auto_resume_min," +
                    "top1_display_name,top1_confidence_percent," +
                    "top2_display_name,top2_confidence_percent," +
                    "top3_display_name,top3_confidence_percent," +
                    "probabilities[$probHeaders]," +
                    "user_selected_class," +
                    "per_second_clips," +
                    "long_standard,long_fast,long_average,long_interval_min," +
                    "volume_mean,volume_peak," +
                    "$volumeSecondCols," +
                    "pause_duration_sec"
            val allInOneCols = if (!allInOneModelNames.isNullOrEmpty()) {
                "," + allInOneModelNames.joinToString(",") { "allinone_${escapeCsv(it.removeSuffix(".pt"))}" }
            } else ""
            return base + allInOneCols
        }
    }
    
}

/**
 * Synthetische Pause-Records tragen Placeholder-Felder (sceneClass = TRANSIT_VEHICLES,
 * confidence = 0). Jede Aggregation über Szenen, Konfidenz, Modell-Accuracy etc. muss
 * sie ausschließen, sonst verfälschen die Placeholder das Ergebnis. Diese Extension
 * ist die Single Source of Truth — bitte überall verwenden statt `filterNot` inline.
 */
fun List<PredictionRecord>.realOnly(): List<PredictionRecord> = filterNot { it.isPause }
