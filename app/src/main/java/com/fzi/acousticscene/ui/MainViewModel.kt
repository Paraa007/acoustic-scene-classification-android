package com.fzi.acousticscene.ui

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.fzi.acousticscene.R
import androidx.lifecycle.viewModelScope
import com.fzi.acousticscene.audio.AudioRecorder
import com.fzi.acousticscene.audio.RecordingState
import com.fzi.acousticscene.data.ActiveSessionRegistry
import com.fzi.acousticscene.data.PredictionRepository
import com.fzi.acousticscene.data.PredictionStatistics
import com.fzi.acousticscene.ml.ComputationDispatcher
import com.fzi.acousticscene.ml.ModelInference
import com.fzi.acousticscene.model.AllInOneResult
import com.fzi.acousticscene.model.ClassificationResult
import com.fzi.acousticscene.model.LongSubMode
import com.fzi.acousticscene.model.LongSubResult
import com.fzi.acousticscene.model.ModelConfig
import com.fzi.acousticscene.model.PerSecondClip
import com.fzi.acousticscene.model.PredictionRecord
import com.fzi.acousticscene.model.RecordingMode
import com.fzi.acousticscene.model.SceneClass
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

    // ALL-IN-ONE: additional ModelInference instances, one per selected dev model.
    // The first entry mirrors `modelInference` (primary). When this list has ≥ 2
    // entries, ALL-IN-ONE mode is active and every inference loops over all of them.
    private var _allInOneModelNames: List<String> = emptyList()
    private var allInOneInferences: List<ModelInference> = emptyList()

    val modelName: String get() = _modelName
    val numClasses: Int get() = _numClasses
    val isDevMode: Boolean get() = _isDevMode
    val isAllInOne: Boolean get() = _allInOneModelNames.size >= 2
    val allInOneModelNames: List<String> get() = _allInOneModelNames

    // Repository for all predictions (singleton - shared with HistoryFragment)
    private val predictionRepository = PredictionRepository.getInstance(application)

    // Session start time (set on app start)
    private var sessionStartTime: Long = System.currentTimeMillis()

    /**
     * Sets the model configuration from Intent extras.
     *
     * For single-model sessions [allInOneModels] is null/empty. For ALL-IN-ONE
     * sessions it carries every selected `.pt` filename (primary first); each
     * model gets its own cached [ModelInference] instance for parallel inference.
     */
    fun setModelConfig(
        modelPath: String,
        modelName: String,
        numClasses: Int,
        isDevMode: Boolean,
        allInOneModels: List<String>? = null
    ) {
        val pathChanged = _modelPath != modelPath
        val allInOneChanged = _allInOneModelNames != (allInOneModels ?: emptyList<String>())
        _modelPath = modelPath
        _modelName = modelName
        _numClasses = numClasses
        _isDevMode = isDevMode

        // Update the primary ModelInference with the new path
        modelInference.setModelPath(modelPath)

        // Rebuild all-in-one inference pool when the selection changes
        if (allInOneChanged) {
            _allInOneModelNames = allInOneModels?.toList() ?: emptyList()
            allInOneInferences = if (_allInOneModelNames.size >= 2) {
                _allInOneModelNames.map { name ->
                    val fullPath = "${ModelConfig.DEV_MODELS_DIR}/$name"
                    // The primary model shares its instance with `modelInference`
                    // to avoid loading the same model twice. Others get fresh inferences.
                    if (fullPath == modelPath) {
                        modelInference
                    } else {
                        ModelInference(getApplication<Application>().applicationContext, fullPath)
                    }
                }
            } else emptyList()
            _uiState.update {
                it.copy(
                    allInOneModelNames = _allInOneModelNames,
                    allInOneResults = emptyMap()
                )
            }
        }

        Log.d(TAG, "Model config set: $modelName ($numClasses classes, devMode=$isDevMode, allInOne=${_allInOneModelNames.size})")

        // (Re)load model if path changed or model not yet loaded
        if (pathChanged || allInOneChanged || !_uiState.value.isModelLoaded) {
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
    @Volatile private var isRecording = false
    @Volatile private var isPaused = false
    // If set, the user-triggered pause auto-resumes at this SystemClock.elapsedRealtime()
    // deadline. null = indefinite user pause (resume only via Resume button).
    @Volatile private var userPauseDeadlineElapsedMs: Long? = null
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
        // Observe dismissals of the in-app evaluation prompt (from EvaluationActivity)
        viewModelScope.launch {
            EvaluationPromptBus.dismissals.collect { dismissedId ->
                _uiState.update { state ->
                    if (state.pendingEvaluation?.predictionId == dismissedId) {
                        state.copy(pendingEvaluation = null)
                    } else state
                }
            }
        }
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
                    val primaryLoaded = modelInference.loadModel()
                    // ALL-IN-ONE: also load every extra inference (primary is the same instance
                    // as modelInference when paths match, so loadModel() is idempotent there).
                    val extrasLoaded = allInOneInferences.all { it.loadModel() }
                    primaryLoaded && extrasLoaded
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
        isPaused = false
        // Every Start begins a fresh History session
        sessionStartTime = System.currentTimeMillis()
        _uiState.update { it.copy(isPaused = false) }
        Log.d(TAG, "New session started: $sessionStartTime")

        // Track active session globally so other screens (Welcome, History) can detect it
        ActiveSessionRegistry.register(
            ActiveSessionRegistry.Entry(
                isDevMode = _isDevMode,
                modelPath = _modelPath,
                modelName = _modelName,
                numClasses = _numClasses,
                sessionStartTime = sessionStartTime,
                allInOneModels = _allInOneModelNames.takeIf { it.size >= 2 }
            )
        )

        // AVERAGE mode uses a completely different flow: record 1s, infer, repeat 10x
        if (currentMode == RecordingMode.AVERAGE) {
            recordingJob = viewModelScope.launch {
                startAverageClassification()
            }
            return
        }

        recordingJob = viewModelScope.launch {
            while (isActive && isRecording) {
                try {
                    // Block here if user paused — holds before starting the next recording
                    while (isPaused && isRecording && isActive) {
                        _uiState.update { it.copy(appState = AppState.UserPaused(0)) }
                        delay(500)
                    }
                    if (!isRecording) break

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

                    // Für LONG: mehrere Sub-Modi auf dasselbe 10s-Sample anwenden.
                    // Für STANDARD/FAST: single inference (bisheriges Verhalten).
                    val subResults: List<LongSubResult>
                    val result: ClassificationResult?
                    val allInOneForRecord: List<AllInOneResult>
                    if (currentMode == RecordingMode.LONG) {
                        val (pri, subs) = runLongSubModes(audioSamples!!, _uiState.value.selectedLongSubs)
                        result = pri
                        subResults = subs
                        // LONG + ALL-IN-ONE: run every selected model on the full 10s buffer.
                        // The LONG sub-modes (Standard/Fast/Avg) continue to use the primary
                        // model only to keep the comparison matrix tractable.
                        allInOneForRecord = if (isAllInOne) runAllInOne(audioSamples!!, currentMode) else emptyList()
                    } else {
                        subResults = emptyList()
                        if (isAllInOne) {
                            val allResults = runAllInOne(audioSamples!!, currentMode)
                            allInOneForRecord = allResults
                            // Primary display follows the first model in the selection
                            result = allResults.firstOrNull()?.let {
                                ClassificationResult(
                                    sceneClass = it.sceneClass,
                                    confidence = it.confidence,
                                    allProbabilities = it.allProbabilities,
                                    inferenceTimeMs = it.inferenceTimeMs
                                )
                            }
                        } else {
                            allInOneForRecord = emptyList()
                            result = withContext(mlDispatcher) {
                                modelInference.infer(audioSamples!!, currentMode)
                            }
                        }
                    }

                    // Prüfe, ob Recording noch aktiv ist (falls Benutzer gestoppt hat)
                    if (result != null) {
                        // Speichere in Repository (ALLE Vorhersagen)
                        // Top 3 Predictions aus ClassificationResult extrahieren
                        val top3 = result.getTopPredictions(3)

                        // Akkustand zum Zeitpunkt der Vorhersage erfassen
                        val currentBatteryLevel = getBatteryLevel()

                        // Falls AVERAGE-Sub aktiv war, dessen PerSecondClips mit aufnehmen
                        val perSecondFromAvg = subResults
                            .firstOrNull { it.subMode == LongSubMode.AVERAGE }
                            ?.perSecondClips

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
                            isDevMode = _isDevMode,  // Whether Dev Mode is active
                            perSecondClips = perSecondFromAvg,
                            longSubResults = subResults.takeIf { it.isNotEmpty() },
                            allInOneResults = allInOneForRecord.takeIf { it.isNotEmpty() }
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

                            // Zeige Pause-Status in der UI (Sekundengenau)
                            var remainingSeconds = (pauseMs / 1000L).toInt()
                            while (remainingSeconds > 0 && isActive && isRecording) {
                                if (isPaused) {
                                    // Auto-resume when the user-set pause timer elapses.
                                    val deadline = userPauseDeadlineElapsedMs
                                    if (deadline != null && SystemClock.elapsedRealtime() >= deadline) {
                                        isPaused = false
                                        userPauseDeadlineElapsedMs = null
                                        _uiState.update {
                                            it.copy(isPaused = false, userPauseDeadlineElapsedMs = null)
                                        }
                                        Log.d(TAG, "Classification auto-resumed (user-pause timer elapsed)")
                                        continue
                                    }
                                    _uiState.update {
                                        it.copy(appState = AppState.UserPaused(remainingSeconds))
                                    }
                                    delay(500)
                                    continue
                                }
                                _uiState.update {
                                    it.copy(appState = AppState.Paused(remainingSeconds))
                                }
                                delay(1000L)
                                if (!isPaused) remainingSeconds--
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
        isPaused = false
        userPauseDeadlineElapsedMs = null
        recordingJob?.cancel()
        audioRecorder.stopRecording()
        resetAnalysisViewState()
        ActiveSessionRegistry.unregister(_isDevMode)
        _uiState.update { it.copy(
            appState = AppState.Ready,
            errorMessage = null,
            currentResult = null,
            history = emptyList(),
            totalClassifications = 0,
            averageInferenceTime = 0L,
            recordingProgress = 0f,
            currentVolume = 0f,
            perSecondResults = List(10) { null },
            runningAverageResult = null,
            isPaused = false,
            userPauseDeadlineElapsedMs = null,
            longSubResults = emptyMap(),
            allInOneResults = emptyMap(),
            pendingEvaluation = null
        ) }
        Log.d(TAG, "Classification stopped")
    }

    /**
     * User presses Pause in LONG mode: halts both the active recording loop
     * (at the next iteration boundary) and the 30-min countdown. The session
     * stays alive; Resume continues from where Pause was hit.
     *
     * @param durationMs Optional auto-resume timer in milliseconds. When the
     *   deadline elapses, the pause loop automatically resumes. null means the
     *   pause stays active until the user presses Resume.
     */
    fun pauseClassification(durationMs: Long? = null) {
        if (!isRecording || isPaused) return
        isPaused = true
        val deadline = durationMs?.let { SystemClock.elapsedRealtime() + it }
        userPauseDeadlineElapsedMs = deadline
        _uiState.update { it.copy(isPaused = true, userPauseDeadlineElapsedMs = deadline) }
        Log.d(TAG, "Classification paused by user (durationMs=${durationMs ?: -1})")
    }

    fun resumeClassification() {
        if (!isRecording || !isPaused) return
        isPaused = false
        userPauseDeadlineElapsedMs = null
        _uiState.update { it.copy(isPaused = false, userPauseDeadlineElapsedMs = null) }
        Log.d(TAG, "Classification resumed by user")
    }

    fun isUserPaused(): Boolean = isPaused

    /**
     * LONG sub-mode selection. STANDARD is always included (default, cannot be removed).
     */
    fun setLongSubs(subs: Set<LongSubMode>) {
        val normalized = (subs + LongSubMode.STANDARD).toSet()
        if (_uiState.value.selectedLongSubs != normalized) {
            _uiState.update { it.copy(selectedLongSubs = normalized) }
        }
    }

    fun toggleLongSub(sub: LongSubMode) {
        if (sub == LongSubMode.STANDARD) return  // locked on
        val current = _uiState.value.selectedLongSubs
        val next = if (sub in current) current - sub else current + sub
        setLongSubs(next)
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
     * AVERAGE mode: Records 1 second at a time, infers immediately, updates UI live,
     * repeats 10 times, then computes final averaged result.
     * Each second is recorded and processed individually so the user sees results in real-time.
     */
    private suspend fun startAverageClassification() {
        val totalClips = 10

        // Loop continuously until user presses Stop
        while (isRecording) {
            // Clear per-second state for this round. `allInOneResults` intentionally
            // keeps last round's final averages so the All-Models card doesn't blink
            // to "..." during the 10 s; it's overwritten in one go at round end.
            _uiState.update { it.copy(
                perSecondResults = List(totalClips) { null },
                runningAverageResult = null
            )}

            val results = mutableListOf<ClassificationResult>()
            // ALL-IN-ONE + AVERAGE: per-model accumulator of the 10 × 1s clip results.
            // Keyed by model filename; drives both live per-model running averages
            // and the final AllInOneResult list written to the PredictionRecord.
            val allInOnePerModelClips: MutableMap<String, MutableList<ClassificationResult>> = mutableMapOf()
            val startTime = System.currentTimeMillis()

            for (clipIndex in 0 until totalClips) {
                if (!isRecording) break

                // Update UI: Recording second (clipIndex+1) of 10
                val secondsRemaining = totalClips - clipIndex
                _uiState.update { it.copy(
                    appState = AppState.Recording(secondsRemaining),
                    recordingProgress = clipIndex.toFloat() / totalClips
                )}

                // Record 1 second using a fresh AudioRecorder
                val clipRecorder = AudioRecorder(durationSeconds = 1)
                var clipSamples: FloatArray? = null

                try {
                    clipRecorder.startRecording()
                        .catch { e: Throwable ->
                            if (e is CancellationException) throw e
                            Log.e(TAG, "AVERAGE clip $clipIndex recording error", e)
                        }
                        .collect { state ->
                            when (state) {
                                is RecordingState.Completed -> {
                                    clipSamples = state.samples
                                }
                                is RecordingState.Progress -> {
                                    // Update progress within this second
                                    val overallProgress = (clipIndex + state.progress) / totalClips
                                    _uiState.update { it.copy(
                                        recordingProgress = overallProgress
                                    )}
                                }
                                else -> { /* Started, Error handled above */ }
                            }
                        }
                } catch (e: CancellationException) {
                    Log.d(TAG, "AVERAGE mode: Recording cancelled at clip $clipIndex")
                    return
                }

                if (!isRecording || clipSamples == null) break

                // Infer this 1-second clip immediately (stay in Recording state to avoid UI jumping)
                val clipResult = withContext(mlDispatcher) {
                    modelInference.infer(clipSamples!!, RecordingMode.FAST)
                }

                // ALL-IN-ONE + AVERAGE: silently accumulate each model's per-clip result.
                // Unlike the primary AVERAGE path (which ticks per-second circles live),
                // the All-Models card stays empty during the 10 × 1 s round — user only
                // sees the final 10-clip average at the end of each round to avoid the
                // numbers jumping every second.
                if (isAllInOne) {
                    for ((idx, inf) in allInOneInferences.withIndex()) {
                        if (!isRecording) break
                        if (inf === modelInference) continue  // already computed as `clipResult`
                        val name = _allInOneModelNames.getOrNull(idx) ?: continue
                        val r = withContext(mlDispatcher) { inf.infer(clipSamples!!, RecordingMode.FAST) } ?: continue
                        allInOnePerModelClips.getOrPut(name) { mutableListOf() } += r
                    }
                    // Also accumulate primary model's clip (shared instance, already inferred above)
                    if (clipResult != null) {
                        val primaryName = _allInOneModelNames.firstOrNull()
                        if (primaryName != null) {
                            allInOnePerModelClips.getOrPut(primaryName) { mutableListOf() } += clipResult
                        }
                    }
                }

                if (clipResult != null) {
                    results.add(clipResult)

                    // Update per-second circle and running average
                    val runningAvg = computeRunningAverage(results, startTime)
                    _uiState.update { current ->
                        val updatedPerSecond = current.perSecondResults.toMutableList()
                        updatedPerSecond[clipIndex] = clipResult
                        current.copy(
                            perSecondResults = updatedPerSecond,
                            runningAverageResult = runningAvg
                        )
                    }
                }

                Log.d(TAG, "AVERAGE mode: Clip ${clipIndex + 1}/$totalClips done" +
                        (clipResult?.let { " -> ${it.sceneClass.labelShort} (${(it.confidence * 100).toInt()}%)" } ?: " -> failed"))
            }

            if (!isRecording) return

            // Compute final averaged result for this round
            if (results.isNotEmpty()) {
                val finalResult = computeRunningAverage(results, startTime)

                // Build per-second clip data
                val clips = results.mapIndexed { index, clipResult ->
                    PerSecondClip(
                        clipIndex = index,
                        sceneClass = clipResult.sceneClass,
                        confidence = clipResult.confidence,
                        allProbabilities = clipResult.allProbabilities
                    )
                }

                // Save to repository
                val top3 = finalResult.getTopPredictions(3)
                val currentBatteryLevel = getBatteryLevel()
                // ALL-IN-ONE + AVERAGE: per-model averaged result from this round's clips.
                // Built from the silently-accumulated per-clip results, then pushed to the
                // UiState in one go so the All-Models card updates once per round.
                val allInOneForRound: List<AllInOneResult> = if (isAllInOne) {
                    _allInOneModelNames.mapNotNull { name ->
                        val clipsForModel = allInOnePerModelClips[name]
                        if (clipsForModel.isNullOrEmpty()) null else {
                            val avg = computeRunningAverage(clipsForModel, startTime)
                            AllInOneResult(
                                modelName = name,
                                sceneClass = avg.sceneClass,
                                confidence = avg.confidence,
                                allProbabilities = avg.allProbabilities,
                                inferenceTimeMs = avg.inferenceTimeMs
                            )
                        }
                    }
                } else emptyList()

                if (allInOneForRound.isNotEmpty()) {
                    val finalMap = allInOneForRound.associate { entry ->
                        entry.modelName to ClassificationResult(
                            sceneClass = entry.sceneClass,
                            confidence = entry.confidence,
                            allProbabilities = entry.allProbabilities,
                            inferenceTimeMs = entry.inferenceTimeMs
                        )
                    }
                    _uiState.update { it.copy(allInOneResults = finalMap) }
                }

                val record = PredictionRecord(
                    sceneClass = finalResult.sceneClass,
                    confidence = finalResult.confidence,
                    allProbabilities = finalResult.allProbabilities,
                    topPredictions = top3,
                    inferenceTimeMs = finalResult.inferenceTimeMs,
                    recordingMode = currentMode,
                    sessionStartTime = sessionStartTime,
                    batteryLevel = currentBatteryLevel,
                    modelName = _modelName,
                    isDevMode = _isDevMode,
                    perSecondClips = clips,
                    allInOneResults = allInOneForRound.takeIf { it.isNotEmpty() }
                )
                predictionRepository.addPrediction(record)
                updateStatistics()
                updateStateWithResult(finalResult)

                Log.d(TAG, "AVERAGE mode round complete: ${results.size}/$totalClips clips, best=${finalResult.sceneClass.labelShort} (${(finalResult.confidence * 100).toInt()}%)")
            }

            // Continue to next round (circles will be cleared at the top of the loop)
        }
    }

    /**
     * LONG-mode fan-out: apply each selected sub-mode to the same 10 s buffer.
     * Returns (primaryResult, subResults). primaryResult mirrors the Standard sub-mode
     * (fallback: first ran sub-mode) so the existing record / UI pipeline stays untouched
     * at the top level. UI's longSubResults map is updated live per sub-mode.
     */
    private suspend fun runLongSubModes(
        audioSamples: FloatArray,
        subs: Set<LongSubMode>
    ): Pair<ClassificationResult?, List<LongSubResult>> {
        val sampleRate = 32000
        val out = mutableListOf<LongSubResult>()
        var standardResult: ClassificationResult? = null
        var anyResult: ClassificationResult? = null

        // Reset live state for this iteration
        _uiState.update { it.copy(
            longSubResults = emptyMap(),
            perSecondResults = List(10) { null },
            runningAverageResult = null
        )}

        // Standard — full 10s
        if (LongSubMode.STANDARD in subs) {
            val r = withContext(mlDispatcher) {
                modelInference.infer(audioSamples, RecordingMode.STANDARD)
            }
            if (r != null) {
                standardResult = r
                anyResult = anyResult ?: r
                pushLongSubUi(LongSubMode.STANDARD, r)
                out += LongSubResult(
                    subMode = LongSubMode.STANDARD,
                    sceneClass = r.sceneClass,
                    confidence = r.confidence,
                    allProbabilities = r.allProbabilities,
                    inferenceTimeMs = r.inferenceTimeMs
                )
            }
        }

        // Fast — middle 1s
        if (LongSubMode.FAST in subs && audioSamples.size >= sampleRate * 10) {
            val from = (sampleRate * 4.5f).toInt()
            val to = (sampleRate * 5.5f).toInt()
            val slice = audioSamples.copyOfRange(from, to)
            val r = withContext(mlDispatcher) {
                modelInference.infer(slice, RecordingMode.FAST)
            }
            if (r != null) {
                anyResult = anyResult ?: r
                pushLongSubUi(LongSubMode.FAST, r)
                out += LongSubResult(
                    subMode = LongSubMode.FAST,
                    sceneClass = r.sceneClass,
                    confidence = r.confidence,
                    allProbabilities = r.allProbabilities,
                    inferenceTimeMs = r.inferenceTimeMs
                )
            }
        }

        // Average — 10 × 1s slices, averaged, streamed live
        if (LongSubMode.AVERAGE in subs && audioSamples.size >= sampleRate * 10) {
            val startTime = System.currentTimeMillis()
            val clipResults = mutableListOf<ClassificationResult>()
            for (i in 0 until 10) {
                if (!isRecording) break
                val slice = audioSamples.copyOfRange(i * sampleRate, (i + 1) * sampleRate)
                val clipResult = withContext(mlDispatcher) {
                    modelInference.infer(slice, RecordingMode.FAST)
                } ?: continue
                clipResults += clipResult
                val runningAvg = computeRunningAverage(clipResults, startTime)
                _uiState.update { current ->
                    val per = current.perSecondResults.toMutableList()
                    if (i < per.size) per[i] = clipResult
                    current.copy(
                        perSecondResults = per,
                        runningAverageResult = runningAvg
                    )
                }
                // Brief pacing so the live per-second circles animate visibly
                delay(350L)
            }
            if (clipResults.isNotEmpty()) {
                val avg = computeRunningAverage(clipResults, startTime)
                anyResult = anyResult ?: avg
                pushLongSubUi(LongSubMode.AVERAGE, avg)
                val clips = clipResults.mapIndexed { idx, cr ->
                    PerSecondClip(
                        clipIndex = idx,
                        sceneClass = cr.sceneClass,
                        confidence = cr.confidence,
                        allProbabilities = cr.allProbabilities
                    )
                }
                out += LongSubResult(
                    subMode = LongSubMode.AVERAGE,
                    sceneClass = avg.sceneClass,
                    confidence = avg.confidence,
                    allProbabilities = avg.allProbabilities,
                    inferenceTimeMs = avg.inferenceTimeMs,
                    perSecondClips = clips
                )
            }
        }

        val primary = standardResult ?: anyResult
        return primary to out
    }

    private fun pushLongSubUi(sub: LongSubMode, result: ClassificationResult) {
        _uiState.update { state ->
            state.copy(longSubResults = state.longSubResults + (sub to result))
        }
    }

    /**
     * ALL-IN-ONE fan-out: runs every selected dev model on the same audio buffer,
     * streaming each result into [UiState.allInOneResults] as soon as it finishes
     * so the UI can tick the rows live. Returns the full list of [AllInOneResult]
     * entries (preserving selection order).
     *
     * Runs sequentially on [mlDispatcher] — parallel inference would contend for
     * the same 2-thread ML pool and actually slow things down at this scale.
     */
    private suspend fun runAllInOne(
        audioSamples: FloatArray,
        mode: RecordingMode
    ): List<AllInOneResult> {
        val out = mutableListOf<AllInOneResult>()
        // Clear previous round
        _uiState.update { it.copy(allInOneResults = emptyMap()) }

        for ((index, inference) in allInOneInferences.withIndex()) {
            if (!isRecording) break
            val name = _allInOneModelNames.getOrNull(index) ?: continue
            val r = withContext(mlDispatcher) { inference.infer(audioSamples, mode) } ?: continue
            out += AllInOneResult(
                modelName = name,
                sceneClass = r.sceneClass,
                confidence = r.confidence,
                allProbabilities = r.allProbabilities,
                inferenceTimeMs = r.inferenceTimeMs
            )
            // Stream live
            _uiState.update { state ->
                state.copy(allInOneResults = state.allInOneResults + (name to r))
            }
        }
        return out
    }

    /**
     * Computes an averaged ClassificationResult from a list of per-clip results.
     */
    private fun computeRunningAverage(
        results: List<ClassificationResult>,
        startTime: Long
    ): ClassificationResult {
        val numClasses = results.first().allProbabilities.size
        val avgProbabilities = FloatArray(numClasses)
        for (result in results) {
            for (j in result.allProbabilities.indices) {
                avgProbabilities[j] += result.allProbabilities[j]
            }
        }
        for (j in avgProbabilities.indices) {
            avgProbabilities[j] /= results.size
        }

        val bestIndex = avgProbabilities.indices.maxByOrNull { avgProbabilities[it] } ?: 0
        val bestClass = SceneClass.fromIndex(bestIndex) ?: SceneClass.TRANSIT_VEHICLES
        val totalInferenceTime = System.currentTimeMillis() - startTime

        Log.d(TAG, "AVERAGE mode: ${results.size} clips averaged, best=${bestClass.labelShort} (${(avgProbabilities[bestIndex] * 100).toInt()}%)")

        return ClassificationResult(
            sceneClass = bestClass,
            confidence = avgProbabilities[bestIndex],
            allProbabilities = avgProbabilities,
            inferenceTimeMs = totalInferenceTime
        )
    }

    /**
     * Sends an evaluation notification for LONG mode recordings.
     * User has 5 minutes to respond with their own scene classification.
     */
    private fun sendEvaluationNotification(record: PredictionRecord) {
        val context = getApplication<Application>()

        // If the app is in the foreground, surface a persistent in-app prompt
        // (card in RecordingFragment with a 5-minute countdown) instead of a system notification.
        val isForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (isForeground) {
            val deadline = android.os.SystemClock.elapsedRealtime() + EvaluationActivity.EVALUATION_TIMEOUT_MS
            _uiState.update {
                it.copy(
                    pendingEvaluation = PendingEvaluation(
                        predictionId = record.id,
                        modelClass = record.sceneClass,
                        deadlineElapsedMs = deadline
                    )
                )
            }
            // Auto-clear when deadline passes (only if still the same pending entry)
            viewModelScope.launch {
                delay(EvaluationActivity.EVALUATION_TIMEOUT_MS)
                _uiState.update { state ->
                    if (state.pendingEvaluation?.predictionId == record.id) {
                        state.copy(pendingEvaluation = null)
                    } else state
                }
            }
            Log.d(TAG, "In-app evaluation prompt set for prediction ${record.id}")
            return
        }

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