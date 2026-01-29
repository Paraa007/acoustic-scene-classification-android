package com.fzi.acousticscene.data

import android.content.Context
import android.util.Log
import com.fzi.acousticscene.model.PredictionRecord
import com.fzi.acousticscene.model.SceneClass
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Repository für alle Vorhersagen
 * Speichert persistent in SharedPreferences und exportiert als CSV
 *
 * @param context Android Context
 * @param modelName Model name for CSV export filename
 */
class PredictionRepository(
    private val context: Context,
    private val modelName: String = "model1"
) {
    companion object {
        private const val TAG = "PredictionRepository"
        private const val PREFS_NAME = "predictions_prefs"
        private const val KEY_PREDICTIONS = "all_predictions"
        private const val MAX_PREDICTIONS = 10000  // Sicherheitslimit
    }
    
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    
    // In-Memory Cache für schnellen Zugriff
    private val predictions = mutableListOf<PredictionRecord>()
    
    init {
        // Lade gespeicherte Vorhersagen beim Start
        loadFromPrefs()
    }
    
    /**
     * Lädt Vorhersagen aus SharedPreferences
     */
    private fun loadFromPrefs() {
        try {
            val json = prefs.getString(KEY_PREDICTIONS, null)
            if (json != null) {
                val type = object : TypeToken<List<PredictionRecord>>() {}.type
                val loaded: List<PredictionRecord> = gson.fromJson(json, type)
                predictions.clear()
                predictions.addAll(loaded)
                Log.d(TAG, "Loaded ${predictions.size} predictions from storage")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading predictions", e)
        }
    }
    
    /**
     * Speichert Vorhersagen in SharedPreferences
     */
    private fun saveToPrefs() {
        try {
            val json = gson.toJson(predictions)
            prefs.edit().putString(KEY_PREDICTIONS, json).apply()
            Log.d(TAG, "Saved ${predictions.size} predictions to storage")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving predictions", e)
        }
    }
    
    /**
     * Fügt eine neue Vorhersage hinzu
     */
    fun addPrediction(record: PredictionRecord) {
        // Sicherheitslimit prüfen
        if (predictions.size >= MAX_PREDICTIONS) {
            // Älteste entfernen
            predictions.removeAt(0)
            Log.w(TAG, "Max predictions reached, removing oldest")
        }
        
        predictions.add(record)
        saveToPrefs()
        Log.d(TAG, "Added prediction: ${record.sceneClass.label} (${(record.confidence * 100).toInt()}%)")
    }
    
    /**
     * Gibt alle Vorhersagen zurück
     */
    fun getAllPredictions(): List<PredictionRecord> = predictions.toList()
    
    /**
     * Gibt Vorhersagen für heute zurück
     */
    fun getTodaysPredictions(): List<PredictionRecord> {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        return predictions.filter { it.getFormattedDate() == today }
    }
    
    /**
     * Gibt Anzahl der Vorhersagen zurück
     */
    fun getCount(): Int = predictions.size
    
    /**
     * Gibt Anzahl der heutigen Vorhersagen zurück
     */
    fun getTodaysCount(): Int = getTodaysPredictions().size
    
    /**
     * Löscht alle Vorhersagen
     */
    fun clearAll() {
        predictions.clear()
        saveToPrefs()
        Log.d(TAG, "All predictions cleared")
    }
    
    /**
     * Löscht Vorhersagen älter als X Tage
     */
    fun clearOlderThan(days: Int) {
        val cutoff = System.currentTimeMillis() - (days * 24 * 60 * 60 * 1000L)
        val before = predictions.size
        predictions.removeAll { it.timestamp < cutoff }
        val removed = before - predictions.size
        saveToPrefs()
        Log.d(TAG, "Removed $removed predictions older than $days days")
    }
    
    /**
     * Löscht alle Vorhersagen mit gegebener sessionStartTime (Package löschen)
     */
    fun deletePackage(sessionStartTime: Long) {
        val before = predictions.size
        predictions.removeAll { it.sessionStartTime == sessionStartTime }
        val removed = before - predictions.size
        saveToPrefs()
        Log.d(TAG, "Deleted package with sessionStartTime=$sessionStartTime, removed $removed predictions")
    }
    
    /**
     * Exportiert alle Vorhersagen als CSV-String
     */
    fun exportToCsvString(): String {
        val sb = StringBuilder()
        sb.appendLine(PredictionRecord.getCsvHeader())
        predictions.forEach { record ->
            sb.appendLine(record.toCsvRow())
        }
        return sb.toString()
    }
    
    /**
     * Exportiert Vorhersagen als CSV-Datei
     * Format: recording_[MODEL_NAME]_[TIMESTAMP].csv
     * Example: recording_model2_2026-01-29_1400.csv
     *
     * @return File object der erstellten Datei
     */
    suspend fun exportToCsvFile(): File = withContext(Dispatchers.IO) {
        // Timestamp format: yyyy-MM-dd_HHmm
        val timestampFormat = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.getDefault())
        val timestamp = timestampFormat.format(Date())

        // Format: recording_[MODEL_NAME]_[TIMESTAMP].csv
        val fileName = "recording_${modelName}_${timestamp}.csv"
        val file = File(context.getExternalFilesDir(null), fileName)

        file.writeText(exportToCsvString())
        Log.d(TAG, "Exported ${predictions.size} predictions to ${file.absolutePath} (model: $modelName)")

        file
    }
    
    /**
     * Gibt Statistiken zurück
     */
    fun getStatistics(): PredictionStatistics {
        if (predictions.isEmpty()) {
            return PredictionStatistics()
        }
        
        val today = getTodaysPredictions()
        val classDistribution = predictions.groupBy { it.sceneClass }
            .mapValues { it.value.size }
        val avgConfidence = predictions.map { it.confidence * 100 }.average()
        val avgInferenceTime = predictions.map { it.inferenceTimeMs }.average()
        
        return PredictionStatistics(
            totalCount = predictions.size,
            todayCount = today.size,
            classDistribution = classDistribution,
            averageConfidence = avgConfidence,
            averageInferenceTimeMs = avgInferenceTime,
            firstPrediction = predictions.firstOrNull()?.timestamp,
            lastPrediction = predictions.lastOrNull()?.timestamp
        )
    }
}

/**
 * Statistiken über alle Vorhersagen
 */
data class PredictionStatistics(
    val totalCount: Int = 0,
    val todayCount: Int = 0,
    val classDistribution: Map<SceneClass, Int> = emptyMap(),
    val averageConfidence: Double = 0.0,
    val averageInferenceTimeMs: Double = 0.0,
    val firstPrediction: Long? = null,
    val lastPrediction: Long? = null
) {
    fun getFormattedFirstPrediction(): String {
        return firstPrediction?.let {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it))
        } ?: "N/A"
    }
    
    fun getFormattedLastPrediction(): String {
        return lastPrediction?.let {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it))
        } ?: "N/A"
    }
}
