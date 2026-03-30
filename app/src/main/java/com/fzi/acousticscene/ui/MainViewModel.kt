package com.fzi.acousticscene.ui

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.AndroidViewModel
import com.fzi.acousticscene.R
import androidx.lifecycle.viewModelScope
import com.fzi.acousticscene.audio.AudioRecorder
import com.fzi.acousticscene.audio.RecordingState
import com.fzi.acousticscene.data.PredictionRepository
import com.fzi.acousticscene.data.PredictionStatistics
import com.fzi.acousticscene.ml.ComputationDispatcher
import com.fzi.acousticscene.ml.ModelInference
import com.fzi.acousticscene.model.ClassificationResult
import com.fzi.acousticscene.model.PredictionRecord
import com.fzi.acousticscene.model.RecordingMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import java.io.File
import java.util.concurrent.Executors

/**
 * ViewModel für MainActivity
 * 
 * Verwaltet:
 * - Model Loading
 * - Audio Recording
 * - Model Inference
 * - State Management
 * - History
 * 
 * @param application Android Application Context
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "MainViewModel"
        private const val HISTORY_SIZE = 5
        private const val EVALUATION_CHANNEL_ID = "evaluation_channel"
        private const val EVALUATION_NOTIFICATION_ID = 2
    }

    private var modelInference = ModelInference(application.applicationContext)

    // Model configuration
    private var _modelPath: String = "user_model/model1.pt"
    private var _modelName: String = "model1.pt"
    private var _numClasses: Int = 8
    private var _isDevMode: Boolean = false

    val modelName: String get() = _modelName
    val numClasses: Int get() = _numClasses
    val isDevMode: Boolean get() = _isDevMode

    // Repository for all predictions (singleton - shared with HistoryFragment)
    private val predictionRepository = PredictionRepository.getInstance(application)

    // Session start time (set on app start)
    private var sessionStartTime: Long = System.currentTimeMillis()

    /**
     * Sets the model configuration from Intent extras
     */
    fun setModelConfig(modelPath: String, modelName: String, numClasses: Int, isDevMode: Boolean) {
        val pathChanged = _modelPath != modelPath
        _modelPath = modelPath
        _modelName = modelName
        _numClasses = numClasses
        _isDevMode = isDevMode

        // Update the ModelInference with the new path
        modelInference.setModelPath(modelPath)

        Log.d(TAG, "Model config set: $modelName ($numClasses classes, devMode=$isDevMode)")

        // (Re)load model if path changed or model not yet loaded
        if (pathChanged || !_uiState.value.isModelLoaded) {
            loadModel()
        }
    }

    /**
     * Sets the session start time (should be called on app start)
     */
    fun initializeSession() {
        sessionStartTime = System.currentTimeMillis()
        Log.d(TAG, "Session initialized: $sessionStartTime")
    }

    /**
     * Ermittelt den aktuellen Akkustand in Prozent (0-100).
     * Wird bei jeder Vorhersage aufgerufen, um den Verbrauch zu protokollieren.
     *
     * @return Akkustand in % oder -1 wenn nicht verfügbar
     */
    private fun getBatteryLevel(): Int {
        return try {
            val batteryManager = getApplication<Application>()
                .getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
            Log.e(TAG, "Could not get battery level", e)
            -1
        }
    }
    
    // Dedizierter Thread-Pool für ML-Operationen
    private val mlDispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
    
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    
    // Statistiken als StateFlow
    private val _statistics = MutableStateFlow(PredictionStatistics())
    val statistics: StateFlow<PredictionStatistics> = _statistics.asStateFlow()
    
    // Alle Vorhersagen Anzahl
    private val _totalPredictionsCount = MutableStateFlow(0)
    val totalPredictionsCount: StateFlow<Int> = _totalPredictionsCount.asStateFlow()
    
    private var recordingJob: Job? = null
    private var volumeJob: Job? = null
    private var isRecording = false
    private var currentMode: RecordingMode = RecordingMode.STANDARD
    private var audioRecorder: AudioRecorder = AudioRecorder(durationSeconds = currentMode.durationSeconds)

    // Time Analysis: Tracks whether the analysis view was opened BEFORE recording started
    // This flag is set when recording starts and checked to gate time analysis calculations
    private val _isAnalysisViewEnabledAtRecordingStart = MutableStateFlow(false)
    val isAnalysisViewEnabledAtRecordingStart: StateFlow<Boolean> = _isAnalysisViewEnabledAtRecordingStart.asStateFlow()

    /**
     * Called by MainActivity to record whether the analysis view was enabled when recording starts.
     * This gates whether time analysis calculations should run.
     */
    fun setAnalysisViewStateAtRecordingStart(isEnabled: Boolean) {
        _isAnalysisViewEnabledAtRecordingStart.value = isEnabled
        Log.d(TAG, "Analysis view state at recording start: $isEnabled")
    }

    /**
     * Resets the analysis view state when recording stops.
     */
    private fun resetAnalysisViewState() {
        _isAnalysisViewEnabledAtRecordingStart.value = false
    }

    init {
        updateStatistics()
        // Starte Volume-Beobachtung
        startVolumeObservation()
        // Note: loadModel() is called from setModelConfig() when fragments configure the model
    }

    /**
     * Beobachtet den Volume-Flow vom AudioRecorder und aktualisiert den UI-State
     */
    private fun startVolumeObservation() {
        volumeJob?.cancel()
        volumeJob = viewModelScope.launch {
            audioRecorder.volumeFlow.collect { volume ->
                _uiState.update { it.copy(currentVolume = volume) }
            }
        }
    }
    
    private fun updateStatistics() {
        _statistics.value = predictionRepository.getStatistics()
        _totalPredictionsCount.value = predictionRepository.getCount()
    }
    
    /**
     * Lädt das PyTorch Model asynchron
     */
    private fun loadModel() {
        viewModelScope.launch {
            _uiState.update { it.copy(appState = AppState.Loading, isModelLoaded = false) }
            
            try {
                // Model Loading kann blockierend sein, daher auf IO Dispatcher
                val success = withContext(Dispatchers.IO) {
                    modelInference.loadModel()
                }
                
                if (success) {
                    _uiState.update {
                        it.copy(
                            appState = AppState.Ready,
                            isModelLoaded = true,
                            errorMessage = null
                        )
                    }
                    Log.d(TAG, "Model loaded successfully")
                } else {
                    _uiState.update {
                        it.copy(
                            appState = AppState.Error("Failed to load model"),
                            isModelLoaded = false,
                            errorMessage = "Failed to load model"
                        )
                    }
                    Log.e(TAG, "Failed to load model")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading model", e)
                _uiState.update {
                    it.copy(
                        appState = AppState.Error("Error loading model: ${e.message}"),
                        isModelLoaded = false,
                        errorMessage = "Error loading model: ${e.message}"
                    )
                }
            }
        }
    }
    
    /**
     * Startet kontinuierliche Klassifikation
     */
    fun startClassification() {
        if (isRecording) {
            Log.w(TAG, "Classification already running")
            return
        }
        
        if (!modelInference.isModelLoaded()) {
            Log.e(TAG, "Model not loaded")
            _uiState.update {
                it.copy(
                    appState = AppState.Error("Model not loaded"),
                    errorMessage = "Model not loaded"
                )
            }
            return
        }
        
        isRecording = true
        recordingJob = viewModelScope.launch {
            while (isActive && isRecording) {
                try {
                    val durationSeconds = currentMode.durationSeconds
                    
                    // Starte Aufnahme
                    _uiState.update { 
                        it.copy(
                            appState = AppState.Recording(durationSeconds),
                            recordingProgress = 0f
                        ) 
                    }
                    
                    // Audio aufnehmen - Flow läuft auf IO Thread!
                    var audioSamples: FloatArray? = null
                    var recordingError: String? = null
                    
                    try {
                        audioRecorder.startRecording()
                            .catch { e: Throwable ->
                                if (e is CancellationException) {
                                    // Expected when user stops - rethrow to be handled by outer catch
                                    throw e
                                }
                                Log.e(TAG, "Recording error", e)
                                recordingError = e.message ?: "Unknown error"
                                _uiState.update {
                                    it.copy(
                                        appState = AppState.Error("Recording failed: ${recordingError}"),
                                        errorMessage = "Recording failed: ${recordingError}"
                                    )
                                }
                            }
                            .collect { state ->
                                when (state) {
                                    is RecordingState.Started -> {
                                        Log.d(TAG, "Recording started")
                                    }
                                    is RecordingState.Progress -> {
                                        // Progress Update auf Main Thread (UI Update)
                                        val secondsRemaining = (durationSeconds * (1f - state.progress)).toInt()
                                        _uiState.update { 
                                            it.copy(
                                                appState = AppState.Recording(secondsRemaining),
                                                recordingProgress = state.progress
                                            ) 
                                        }
                                    }
                                    is RecordingState.Completed -> {
                                        Log.d(TAG, "Recording completed: ${state.samples.size} samples")
                                        audioSamples = state.samples
                                    }
                                    is RecordingState.Error -> {
                                        Log.e(TAG, "Recording error: ${state.message}")
                                        recordingError = state.message
                                        _uiState.update { 
                                            it.copy(
                                                appState = AppState.Error(state.message),
                                                errorMessage = state.message
                                            ) 
                                        }
                                    }
                                }
                            }
                    } catch (e: CancellationException) {
                        // Expected when user clicks Stop - NOT an error
                        Log.d(TAG, "Recording collection cancelled by user")
                        // Don't set error - this is normal stop behavior
                    } catch (e: Exception) {
                        Log.e(TAG, "Error during recording collection", e)
                        recordingError = e.message ?: "Unknown error"
                        _uiState.update {
                            it.copy(
                                appState = AppState.Error("Recording failed: ${recordingError}"),
                                errorMessage = "Recording failed: ${recordingError}"
                            )
                        }
                    }

                    if (!isRecording) {
                        Log.d(TAG, "Recording stopped, skipping inference")
                        delay(1000) // Kurze Pause vor nächster Iteration
                        continue
                    }
                    
                    // Wenn Fehler oder keine Samples, überspringe Inferenz
                    if (recordingError != null) {
                        Log.e(TAG, "Recording error occurred: $recordingError")
                        delay(1000)
                        continue
                    }
                    
                    if (audioSamples == null || audioSamples!!.isEmpty()) {
                        Log.e(TAG, "No audio samples received")
                        _uiState.update { 
                            it.copy(
                                appState = AppState.Error("No audio samples received"),
                                errorMessage = "No audio samples received"
                            ) 
                        }
                        delay(1000)
                        continue
                    }
                    
                    // Verarbeite Audio - Processing State
                    _uiState.update { it.copy(appState = AppState.Processing, recordingProgress = 0f) }
                    
                    // Führe Inferenz durch auf dediziertem ML Thread-Pool
                    // NICHT auf Main Thread! Mit withContext wechseln wir zu mlDispatcher
                    val result = withContext(mlDispatcher) {
                        modelInference.infer(audioSamples!!, currentMode)
                    }
                    
                    // Prüfe, ob Recording noch aktiv ist (falls Benutzer gestoppt hat)
                    if (result != null) {
                        // Speichere in Repository (ALLE Vorhersagen)
                        // Top 3 Predictions aus ClassificationResult extrahieren
                        val top3 = result.getTopPredictions(3)

                        // Akkustand zum Zeitpunkt der Vorhersage erfassen
                        val currentBatteryLevel = getBatteryLevel()

                        val record = PredictionRecord(
                            sceneClass = result.sceneClass,
                            confidence = result.confidence,
                            allProbabilities = result.allProbabilities,
                            topPredictions = top3,  // Top 3 Predictions for CSV
                            inferenceTimeMs = result.inferenceTimeMs,
                            recordingMode = currentMode,
                            sessionStartTime = sessionStartTime,  // Session start time
                            batteryLevel = currentBatteryLevel,  // Battery level
                            modelName = _modelName,  // Model file name
                            isDevMode = _isDevMode  // Whether Dev Mode is active
                        )
                        predictionRepository.addPrediction(record)
                        updateStatistics()

                        // Normal: Aktualisiere UI State mit Ergebnis
                        updateStateWithResult(result)

                        // LONG-Modus: Pause nach Aufnahme (z.B. 30 Minuten warten)
                        if (currentMode.hasPauseAfterRecording() && isRecording) {
                            // Send evaluation notification
                            sendEvaluationNotification(record)

                            val pauseMs = currentMode.pauseAfterRecordingMs
                            val pauseMinutes = currentMode.getPauseMinutes()
                            Log.d(TAG, "LONG mode: Starting ${pauseMinutes} minute pause")

                            // Zeige Pause-Status in der UI
                            var remainingMinutes = pauseMinutes
                            while (remainingMinutes > 0 && isActive && isRecording) {
                                _uiState.update {
                                    it.copy(appState = AppState.Paused(remainingMinutes))
                                }
                                // Warte 1 Minute
                                delay(60 * 1000L)
                                remainingMinutes--
                            }

                            if (isRecording) {
                                Log.d(TAG, "LONG mode: Pause completed, starting next recording")
                            }
                        }
                    } else {
                        // Fehler
                        if (isRecording) {
                            _uiState.update {
                                it.copy(
                                    appState = AppState.Error("Inference failed"),
                                    errorMessage = "Inference failed"
                                )
                            }
                            delay(1000) // Kurze Pause bei Fehler
                        }
                    }
                } catch (e: CancellationException) {
                    // Expected when user clicks Stop - NOT an error
                    Log.d(TAG, "Recording stopped by user (CancellationException)")
                    // Don't update UI state here - stopClassification() handles it
                } catch (e: Exception) {
                    Log.e(TAG, "Error during classification", e)
                    _uiState.update {
                        it.copy(
                            appState = AppState.Error(e.message ?: "Unknown error"),
                            errorMessage = e.message ?: "Unknown error"
                        )
                    }
                    delay(1000) // Kurze Pause bei Fehler
                }
            }
        }
    }

    /**
     * Stoppt kontinuierliche Klassifikation
     */
    fun stopClassification() {
        isRecording = false
        recordingJob?.cancel()
        audioRecorder.stopRecording()
        resetAnalysisViewState()
        _uiState.update { it.copy(appState = AppState.Ready, errorMessage = null) }
        Log.d(TAG, "Classification stopped")
    }
    
    /**
     * Aktualisiert den UI State mit einem neuen Klassifikations-Ergebnis
     */
    private fun updateStateWithResult(result: ClassificationResult) {
        _uiState.update { currentState ->
            val newHistory = (listOf(result) + currentState.history).take(HISTORY_SIZE)
            val totalCount = currentState.totalClassifications + 1
            val totalTime = currentState.averageInferenceTime * (totalCount - 1) + result.inferenceTimeMs
            val avgTime = totalTime / totalCount
            
            currentState.copy(
                appState = AppState.Ready,
                currentResult = result,
                history = newHistory,
                totalClassifications = totalCount,
                averageInferenceTime = avgTime,
                errorMessage = null
            )
        }
    }
    
    /**
     * Resets the session UI state (history, results, statistics) while keeping the model loaded.
     * Called when the user navigates away from a recording tab without an active recording.
     */
    fun resetSession() {
        _uiState.update {
            it.copy(
                appState = if (it.isModelLoaded) AppState.Ready else AppState.Idle,
                currentResult = null,
                history = emptyList(),
                totalClassifications = 0,
                averageInferenceTime = 0L,
                errorMessage = null,
                recordingProgress = 0f,
                currentVolume = 0f
            )
        }
        Log.d(TAG, "Session reset")
    }

    /**
     * Löscht die History
     */
    fun clearHistory() {
        _uiState.update {
            it.copy(history = emptyList(), totalClassifications = 0, averageInferenceTime = 0L)
        }
    }
    
    /**
     * Löscht die Fehlermeldung
     */
    fun clearError() {
        _uiState.update {
            it.copy(errorMessage = null, appState = AppState.Ready)
        }
    }
    
    /**
     * Prüft, ob aktuell klassifiziert wird
     */
    fun isClassifying(): Boolean = isRecording
    
    /**
     * Setzt den Aufnahme-Modus (Standard 10s oder Fast 1s)
     */
    fun setRecordingMode(mode: RecordingMode) {
        if (currentMode != mode && !isRecording) {
            currentMode = mode
            audioRecorder = AudioRecorder(durationSeconds = mode.durationSeconds)
            // Starte Volume-Beobachtung für neuen AudioRecorder neu
            startVolumeObservation()
            _uiState.update { it.copy(recordingMode = mode) }
            Log.d(TAG, "Recording mode changed to: ${mode.label}")
        }
    }
    
    /**
     * Gibt den aktuellen Aufnahme-Modus zurück
     */
    fun getRecordingMode(): RecordingMode = currentMode
    
    /**
     * Exportiert alle Vorhersagen als CSV
     */
    fun exportPredictions(onComplete: (File?) -> Unit) {
        viewModelScope.launch {
            try {
                val file = predictionRepository.exportToCsvFile()
                onComplete(file)
            } catch (e: Exception) {
                Log.e(TAG, "Export failed", e)
                onComplete(null)
            }
        }
    }
    
    /**
     * Gibt alle Vorhersagen zurück
     */
    fun getAllPredictions(): List<PredictionRecord> {
        return predictionRepository.getAllPredictions()
    }
    
    /**
     * Löscht alle Vorhersagen
     */
    fun clearAllPredictions() {
        predictionRepository.clearAll()
        updateStatistics()
    }
    
    /**
     * Löscht alte Vorhersagen
     */
    fun clearOldPredictions(days: Int) {
        predictionRepository.clearOlderThan(days)
        updateStatistics()
    }
    
    /**
     * Sends an evaluation notification for LONG mode recordings.
     * User has 5 minutes to respond with their own scene classification.
     */
    private fun sendEvaluationNotification(record: PredictionRecord) {
        val context = getApplication<Application>()
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create evaluation notification channel (idempotent)
        val channel = NotificationChannel(
            EVALUATION_CHANNEL_ID,
            context.getString(R.string.evaluation_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.evaluation_channel_description)
        }
        notificationManager.createNotificationChannel(channel)

        // PendingIntent to open EvaluationActivity
        val intent = Intent(context, EvaluationActivity::class.java).apply {
            putExtra(EvaluationActivity.EXTRA_PREDICTION_ID, record.id)
            putExtra(EvaluationActivity.EXTRA_MODEL_PREDICTED_CLASS, record.sceneClass.name)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, record.id.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, EVALUATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.evaluation_title))
            .setContentText("${record.sceneClass.emoji} ${record.sceneClass.labelShort} — ${context.getString(R.string.evaluation_tap_to_rate)}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setTimeoutAfter(EvaluationActivity.EVALUATION_TIMEOUT_MS) // Auto-dismiss after 5 minutes
            .build()

        notificationManager.notify(EVALUATION_NOTIFICATION_ID, notification)
        Log.d(TAG, "Evaluation notification sent for prediction ${record.id}")
    }

    override fun onCleared() {
        super.onCleared()
        stopClassification()
        mlDispatcher.close()  // Thread-Pool aufräumen
    }
}