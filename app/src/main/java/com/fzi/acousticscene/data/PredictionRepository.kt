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
 * SINGLETON: Use getInstance(context) to ensure all components share the same
 * in-memory cache. Creating separate instances causes data loss when one
 * instance saves stale data that overwrites another instance's deletions.
 */
class PredictionRepository private constructor(private val context: Context) {
    companion object {
        private const val TAG = "PredictionRepository"
        private const val PREFS_NAME = "predictions_prefs"
        private const val KEY_PREDICTIONS = "all_predictions"
        private const val KEY_SESSION_NAMES = "session_names"
        private const val MAX_PREDICTIONS = 10000  // Sicherheitslimit

        @Volatile
        private var instance: PredictionRepository? = null

        fun getInstance(context: Context): PredictionRepository {
            return instance ?: synchronized(this) {
                instance ?: PredictionRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    // Session-Namen Cache
    private val sessionNames = mutableMapOf<Long, String>()
    
    // In-Memory Cache für schnellen Zugriff
    private val predictions = mutableListOf<PredictionRecord>()
    
    init {
        // Lade gespeicherte Vorhersagen beim Start
        loadFromPrefs()
        loadSessionNames()
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
     * Updates an existing prediction with user evaluation data
     */
    @Synchronized
    fun updatePredictionEvaluation(predictionId: Long, userSelectedClass: SceneClass?, userComment: String?) {
        val index = predictions.indexOfFirst { it.id == predictionId }
        if (index >= 0) {
            predictions[index] = predictions[index].copy(
                userSelectedClass = userSelectedClass,
                userComment = userComment
            )
            saveToPrefs()
            Log.d(TAG, "Updated prediction $predictionId with user evaluation: ${userSelectedClass?.labelShort}")
        } else {
            Log.w(TAG, "Prediction $predictionId not found for evaluation update")
        }
    }

    /**
     * Fügt eine neue Vorhersage hinzu
     */
    @Synchronized
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
        sessionNames.clear()
        saveSessionNames()
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
        // Session-Name ebenfalls entfernen
        if (sessionNames.remove(sessionStartTime) != null) {
            saveSessionNames()
        }
        Log.d(TAG, "Deleted package with sessionStartTime=$sessionStartTime, removed $removed predictions")
    }

    /**
     * Löscht mehrere Packages auf einmal (Batch-Delete)
     */
    fun deletePackages(sessionStartTimes: Set<Long>) {
        val before = predictions.size
        predictions.removeAll { it.sessionStartTime in sessionStartTimes }
        val removed = before - predictions.size
        saveToPrefs()
        var namesRemoved = 0
        sessionStartTimes.forEach { sessionStartTime ->
            if (sessionNames.remove(sessionStartTime) != null) {
                namesRemoved++
            }
        }
        if (namesRemoved > 0) saveSessionNames()
        Log.d(TAG, "Batch deleted ${sessionStartTimes.size} packages, removed $removed predictions")
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
     * Exports predictions as CSV file
     * @param modelName Optional model name to include in filename
     * @return File object of the created file
     */
    suspend fun exportToCsvFile(modelName: String? = null): File = withContext(Dispatchers.IO) {
        // Calculate start and end time of predictions
        val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault())
        val timestamp = dateFormat.format(Date())

        // Get model name from first prediction if not provided
        val model = modelName
            ?: predictions.firstOrNull()?.modelName?.replace(".pt", "")
            ?: "model1"

        // Format: recording_[MODEL_NAME]_[TIMESTAMP].csv
        val fileName = "recording_${model}_${timestamp}.csv"
        val file = File(context.getExternalFilesDir(null), fileName)

        file.writeText(exportToCsvString())
        Log.d(TAG, "Exported ${predictions.size} predictions to ${file.absolutePath}")

        file
    }

    /**
     * Exports a specific package as CSV file
     * @param records List of prediction records to export
     * @return File object of the created file
     */
    suspend fun exportPackageToCsvFile(records: List<PredictionRecord>): File = withContext(Dispatchers.IO) {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault())
        val startTime = records.firstOrNull()?.timestamp ?: System.currentTimeMillis()
        val timestamp = dateFormat.format(Date(startTime))

        // Get model name from first prediction
        val model = records.firstOrNull()?.modelName?.replace(".pt", "") ?: "model1"

        // Format: recording_[MODEL_NAME]_[TIMESTAMP].csv
        val fileName = "recording_${model}_${timestamp}.csv"
        val file = File(context.getExternalFilesDir(null), fileName)

        val sb = StringBuilder()
        sb.appendLine(PredictionRecord.getCsvHeader())
        records.forEach { record ->
            sb.appendLine(record.toCsvRow())
        }
        file.writeText(sb.toString())

        Log.d(TAG, "Exported ${records.size} predictions to ${file.absolutePath}")
        file
    }
    
    // --- Session-Namen Persistenz ---

    private fun loadSessionNames() {
        try {
            val json = prefs.getString(KEY_SESSION_NAMES, null)
            if (json != null) {
                val type = object : TypeToken<Map<String, String>>() {}.type
                val loaded: Map<String, String> = gson.fromJson(json, type)
                sessionNames.clear()
                loaded.forEach { (key, value) ->
                    sessionNames[key.toLongOrNull() ?: return@forEach] = value
                }
                Log.d(TAG, "Loaded ${sessionNames.size} session names")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading session names", e)
        }
    }

    private fun saveSessionNames() {
        try {
            val stringKeyMap = sessionNames.mapKeys { it.key.toString() }
            val json = gson.toJson(stringKeyMap)
            prefs.edit().putString(KEY_SESSION_NAMES, json).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving session names", e)
        }
    }

    /**
     * Setzt einen benutzerdefinierten Namen für eine Session
     */
    fun setSessionName(sessionStartTime: Long, name: String) {
        sessionNames[sessionStartTime] = name
        saveSessionNames()
        Log.d(TAG, "Session $sessionStartTime renamed to '$name'")
    }

    /**
     * Gibt den benutzerdefinierten Namen einer Session zurück, oder null
     */
    fun getSessionName(sessionStartTime: Long): String? {
        return sessionNames[sessionStartTime]
    }

    /**
     * Löst den Anzeigenamen einer Session auf:
     * Custom Name falls vorhanden, sonst "Session #X" (chronologisch, älteste = 1)
     */
    fun resolveSessionDisplayName(sessionStartTime: Long, allSessionStartTimes: List<Long>): String {
        sessionNames[sessionStartTime]?.let { return it }
        val sorted = allSessionStartTimes.sorted()
        val index = sorted.indexOf(sessionStartTime) + 1
        return "Session $index"
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
