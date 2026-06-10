package com.fzi.acousticscene.data

import android.content.Context
import android.util.Log
import com.fzi.acousticscene.model.PredictionRecord
import com.fzi.acousticscene.model.RecordingMode
import com.fzi.acousticscene.model.SceneClass
import com.fzi.acousticscene.model.realOnly
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.reflect.TypeToken
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
    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(SceneClass::class.java, JsonDeserializer<SceneClass?> { json, _, _ ->
            try {
                SceneClass.valueOf(json.asString)
            } catch (_: Exception) {
                null
            }
        })
        .create()

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
                // Gson umgeht Kotlins Null-Checks: der lenient SceneClass-Deserializer
                // mappt unbekannte Enum-Namen auf null, und auch recordingMode kann
                // aus einem manipulierten/alten Blob als null ankommen. Solche Records
                // crashen sonst beim ersten Feldzugriff (z. B. getStatistics).
                @Suppress("USELESS_CAST", "SENSELESS_COMPARISON")
                predictions.removeAll {
                    (it.sceneClass as SceneClass?) == null || (it.recordingMode as RecordingMode?) == null
                }
                // Restore-/Legacy-Blobs können größer als das Limit sein — einmalig
                // auf MAX_PREDICTIONS stutzen (älteste zuerst raus), sonst hält und
                // serialisiert die App dauerhaft eine überlange Liste.
                if (predictions.size > MAX_PREDICTIONS) {
                    predictions.subList(0, predictions.size - MAX_PREDICTIONS).clear()
                }
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
     * True wenn ein Record mit dieser ID existiert. EvaluationActivity prüft das,
     * bevor sie ein User-Label schreibt — der Record kann gelöscht worden sein,
     * während die Bewertungs-Notification noch offen war.
     */
    @Synchronized
    fun exists(predictionId: Long): Boolean = predictions.any { it.id == predictionId }

    /**
     * Updates an existing prediction with user evaluation data
     */
    @Synchronized
    fun updatePredictionEvaluation(predictionId: Long, userSelectedClass: SceneClass?) {
        val index = predictions.indexOfFirst { it.id == predictionId }
        if (index >= 0) {
            predictions[index] = predictions[index].copy(
                userSelectedClass = userSelectedClass
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
        // Sicherheitslimit: while statt if — eine über dem Limit geladene Liste
        // würde mit if pro Add nur um eins schrumpfen und nie aufs Limit zurückfallen.
        while (predictions.size >= MAX_PREDICTIONS) {
            // Älteste entfernen
            predictions.removeAt(0)
            Log.w(TAG, "Max predictions reached, removing oldest")
        }

        predictions.add(record)
        saveToPrefs()
        Log.d(TAG, "Added prediction: ${record.sceneClass.label} (${(record.confidence * 100).toInt()}%)")
    }
    
    /**
     * Gibt alle Vorhersagen zurück (thread-safe snapshot)
     */
    @Synchronized
    fun getAllPredictions(): List<PredictionRecord> = predictions.toList()
    
    /**
     * Gibt Vorhersagen für heute zurück
     */
    @Synchronized
    fun getTodaysPredictions(): List<PredictionRecord> {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        return predictions.filter { it.getFormattedDate() == today }
    }

    /**
     * Gibt Anzahl der Vorhersagen zurück
     */
    @Synchronized
    fun getCount(): Int = predictions.size

    /**
     * Gibt Anzahl der heutigen Vorhersagen zurück
     */
    @Synchronized
    fun getTodaysCount(): Int = getTodaysPredictions().size
    
    /**
     * Löscht alle Vorhersagen
     */
    @Synchronized
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
    @Synchronized
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
    @Synchronized
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
    @Synchronized
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
     * Exportiert alle Vorhersagen als CSV-String.
     *
     * Scannt zuerst alle Records nach eindeutigen ALL-IN-ONE-Modellnamen und
     * baut daraus dynamische Spalten `allinone_<model>` am Ende der Zeile.
     * Records ohne ALL-IN-ONE-Daten bekommen leere Zellen in diesen Spalten.
     *
     * Pause-Records bleiben drin: [PredictionRecord.toCsvRow] schreibt für sie
     * eine eigene Gap-Zeile (recording_mode = "PAUSE", nur pause_duration_sec
     * trägt Daten) — Auswertungen filtern sie über genau diese Spalte. Nur der
     * ALL-IN-ONE-Namens-Scan überspringt sie, weil ihre Klassifikations-Felder
     * Platzhalter sind.
     *
     * Kanonischer CSV-Builder der App — der einzige UI-Export (HistoryActivity)
     * soll hierüber laufen, damit dynamische Spalten (allinone_*) nie verloren gehen.
     */
    @Synchronized
    fun exportToCsvString(records: List<PredictionRecord> = predictions): String {
        val allInOneModelNames: List<String> = records.realOnly()
            .mapNotNull { it.allInOneResults }
            .flatten()
            .map { it.modelName }
            .distinct()
            .sorted()

        val sb = StringBuilder()
        sb.appendLine(PredictionRecord.getCsvHeader(allInOneModelNames.takeIf { it.isNotEmpty() }))
        records.forEach { record ->
            sb.appendLine(record.toCsvRow(allInOneModelNames.takeIf { it.isNotEmpty() }))
        }
        return sb.toString()
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
    @Synchronized
    fun setSessionName(sessionStartTime: Long, name: String) {
        sessionNames[sessionStartTime] = name
        saveSessionNames()
        Log.d(TAG, "Session $sessionStartTime renamed to '$name'")
    }

    /**
     * Gibt den benutzerdefinierten Namen einer Session zurück, oder null
     */
    @Synchronized
    fun getSessionName(sessionStartTime: Long): String? {
        return sessionNames[sessionStartTime]
    }

    /**
     * Löst den Anzeigenamen einer Session auf:
     * Custom Name falls vorhanden, sonst "Session #X" (chronologisch, älteste = 1)
     */
    @Synchronized
    fun resolveSessionDisplayName(sessionStartTime: Long, allSessionStartTimes: List<Long>): String {
        sessionNames[sessionStartTime]?.let { return it }
        val sorted = allSessionStartTimes.sorted()
        val index = sorted.indexOf(sessionStartTime) + 1
        return "Session $index"
    }

    /**
     * Gibt Statistiken zurück
     */
    @Synchronized
    fun getStatistics(): PredictionStatistics {
        // Pause-Records sind synthetisch (sceneClass-Placeholder, confidence=0) und
        // verfälschen sonst Distribution + Avg-Confidence.
        val real = predictions.realOnly()
        if (real.isEmpty()) {
            return PredictionStatistics()
        }

        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val today = real.filter { it.getFormattedDate() == todayKey }
        val classDistribution = real.groupBy { it.sceneClass }
            .mapValues { it.value.size }
        val avgConfidence = real.map { it.confidence * 100 }.average()
        val avgInferenceTime = real.map { it.inferenceTimeMs }.average()

        return PredictionStatistics(
            totalCount = real.size,
            todayCount = today.size,
            classDistribution = classDistribution,
            averageConfidence = avgConfidence,
            averageInferenceTimeMs = avgInferenceTime,
            firstPrediction = real.firstOrNull()?.timestamp,
            lastPrediction = real.lastOrNull()?.timestamp
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
