package com.fzi.acousticscene.model

import java.text.SimpleDateFormat
import java.util.*

/**
 * Individual 1-second clip result for AVERAGE mode
 */
data class PerSecondClip(
    val clipIndex: Int,         // 0-9
    val sceneClass: SceneClass,
    val confidence: Float,
    val allProbabilities: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PerSecondClip) return false
        return clipIndex == other.clipIndex
    }
    override fun hashCode(): Int = clipIndex
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
    val isDevMode: Boolean = false,  // Whether this was recorded in Dev Mode
    val userSelectedClass: SceneClass? = null,  // User evaluation: selected scene class (null = no response)
    val userComment: String? = null,  // User evaluation: optional comment
    val perSecondClips: List<PerSecondClip>? = null  // AVERAGE mode: individual 1s clip results
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
     * Enthält jetzt auch Top 3 Predictions und Batterie-Level
     */
    fun toCsvRow(): String {
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

        // User evaluation columns (empty if no response)
        val userClassStr = userSelectedClass?.label?.let { "\"$it\"" } ?: ""
        val userCommentStr = userComment?.let { "\"${it.replace("\"", "\"\"")}\"" } ?: ""

        // Per-second clips (AVERAGE mode only)
        val perSecondStr = if (perSecondClips != null && perSecondClips.isNotEmpty()) {
            perSecondClips.sortedBy { it.clipIndex }.joinToString("|") { clip ->
                "${clip.clipIndex + 1}:${clip.sceneClass.label}:${(clip.confidence * 100).toInt()}%"
            }
        } else ""

        return listOf(
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
            userCommentStr,  // user_comment (empty if no comment)
            "\"$perSecondStr\""  // per_second_clips (AVERAGE mode)
        ).joinToString(",")
    }
    
    companion object {
        /**
         * CSV Header mit Top 3 Predictions und Batterie-Level
         * Entfernt: class_index, class_name, recording_duration_sec, top1_index, top1_name, top2_index, top2_name, top3_index, top3_name
         * Geändert: recording_mode enthält jetzt Zeit (z.B. "STANDARD (10s)"), probabilities enthält Klassennamen
         * NEU: battery_percent nach timestamp
         */
        fun getCsvHeader(): String {
            // Probabilities mit Display-Namen (wie top3_display_name) - mit Anführungszeichen
            val probHeaders = SceneClass.entries.sortedBy { it.index }.joinToString(";") { "\"${it.label}\"" }
            return "id,timestamp,battery_percent,class_display_name,confidence_percent,inference_time_sec,recording_mode," +
                    "top1_display_name,top1_confidence_percent," +
                    "top2_display_name,top2_confidence_percent," +
                    "top3_display_name,top3_confidence_percent," +
                    "probabilities[$probHeaders]," +
                    "user_selected_class,user_comment," +
                    "per_second_clips"
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
