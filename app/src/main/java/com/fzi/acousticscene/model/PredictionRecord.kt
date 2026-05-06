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
    val pauseDurationSec: Long? = null
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
     * Konvertiert zu CSV-Zeile
     * Enthält jetzt auch Top 3 Predictions und Batterie-Level.
     *
     * @param allInOneModelNames Wenn nicht-null, wird am Ende für jedes Modell in dieser
     *   Liste eine Zelle `"Class:XX%"` aus [allInOneResults] angehängt (leer falls dieses
     *   Record keinen Eintrag für das Modell hat). So bekommt der CSV-Export dynamische
     *   Spalten pro Modell, ohne dass Records ohne ALL-IN-ONE-Daten einen Defekt erzeugen.
     */
    fun toCsvRow(allInOneModelNames: List<String>? = null): String {
        // Synthetic PAUSE record: classification cells stay empty, only timestamp
        // + mode_label + pause_duration_sec carry data.
        if (isPause) {
            val baseCells = listOf(
                id.toString(),
                getFormattedDateTime(),
                "",                       // battery
                "",                       // class_display_name
                "0",                      // confidence_percent
                "",                       // inference_time_sec
                "PAUSE",                  // recording_mode → "PAUSE" makes filtering trivial
                "", "", "", "", "", "",   // top1..top3
                "",                       // probabilities
                "",                       // user_selected_class
                "",                       // per_second_clips
                "", "", "",               // long_standard / fast / average
                "",                       // long_interval_min
                "",                       // volume_mean
                "",                       // volume_peak
                pauseDurationSec?.toString().orEmpty()
            )
            val emptyAllInOne = allInOneModelNames?.map { "" }.orEmpty()
            return (baseCells + emptyAllInOne).joinToString(",")
        }

        // Probabilities mit Klassennamen (statt Indizes)
        val probHeaders = SceneClass.entries.sortedBy { it.index }.map { it.name }
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

        // User evaluation column (empty if no response)
        val userClassStr = userSelectedClass?.label?.let { "\"$it\"" } ?: ""

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
        val longIntervalStr = longIntervalMinutes?.toString() ?: ""

        val baseCells = listOf(
            id.toString(),
            getFormattedDateTime(),
            batteryString,  // NEU: battery_percent nach timestamp
            "\"${sceneClass.label}\"",  // class_display_name (ohne class_index, class_name)
            confidencePercent.toString(),
            String.format(Locale.US, "%.2f", inferenceTimeMs / 1000.0),
            recordingModeWithTime,  // recording_mode mit Zeit (ohne recording_duration_sec)
            // Top 3 Predictions (ohne Indexe, ohne name)
            "\"${top1.first.label}\"",  // top1_display_name
            String.format(Locale.US, "%.2f", top1.second * 100),  // top1_confidence_percent
            "\"${top2.first.label}\"",  // top2_display_name
            String.format(Locale.US, "%.2f", top2.second * 100),  // top2_confidence_percent
            "\"${top3_entry.first.label}\"",  // top3_display_name
            String.format(Locale.US, "%.2f", top3_entry.second * 100),  // top3_confidence_percent
            probsString,  // probabilities mit Klassennamen im Header
            userClassStr,  // user_selected_class (empty if no evaluation)
            "\"$perSecondStr\"",  // per_second_clips (AVERAGE mode)
            "\"$longStdStr\"",   // long_standard (LONG sub-mode)
            "\"$longFastStr\"",  // long_fast (LONG sub-mode)
            "\"$longAvgStr\"",   // long_average (LONG sub-mode)
            "\"$longIntervalStr\"",  // long_interval_min (LONG mode pause interval, minutes)
            volumeMean?.let { String.format(Locale.US, "%.3f", it) }.orEmpty(),
            volumePeak?.let { String.format(Locale.US, "%.3f", it) }.orEmpty(),
            ""  // pause_duration_sec (empty for non-PAUSE rows)
        )

        // ALL-IN-ONE dynamische Spalten: eine Zelle pro bekanntem Modellnamen
        val allInOneCells: List<String> = if (allInOneModelNames != null && allInOneModelNames.isNotEmpty()) {
            allInOneModelNames.map { name ->
                val r = allInOneResults?.firstOrNull { it.modelName == name }
                if (r != null) {
                    "\"${r.sceneClass.label}:${(r.confidence * 100).toInt()}%\""
                } else ""
            }
        } else emptyList()

        return (baseCells + allInOneCells).joinToString(",")
    }
    
    companion object {
        /**
         * CSV Header mit Top 3 Predictions und Batterie-Level
         * Entfernt: class_index, class_name, recording_duration_sec, top1_index, top1_name, top2_index, top2_name, top3_index, top3_name
         * Geändert: recording_mode enthält jetzt Zeit (z.B. "STANDARD (10s)"), probabilities enthält Klassennamen
         * NEU: battery_percent nach timestamp
         */
        fun getCsvHeader(allInOneModelNames: List<String>? = null): String {
            // Probabilities mit Display-Namen (wie top3_display_name) - mit Anführungszeichen
            val probHeaders = SceneClass.entries.sortedBy { it.index }.joinToString(";") { "\"${it.label}\"" }
            val base = "id,timestamp,battery_percent,class_display_name,confidence_percent,inference_time_sec,recording_mode," +
                    "top1_display_name,top1_confidence_percent," +
                    "top2_display_name,top2_confidence_percent," +
                    "top3_display_name,top3_confidence_percent," +
                    "probabilities[$probHeaders]," +
                    "user_selected_class," +
                    "per_second_clips," +
                    "long_standard,long_fast,long_average,long_interval_min," +
                    "volume_mean,volume_peak,pause_duration_sec"
            val allInOneCols = if (!allInOneModelNames.isNullOrEmpty()) {
                "," + allInOneModelNames.joinToString(",") { "allinone_${it.removeSuffix(".pt")}" }
            } else ""
            return base + allInOneCols
        }
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PredictionRecord
        return id == other.id
    }
    
    override fun hashCode(): Int = id.hashCode()
}
