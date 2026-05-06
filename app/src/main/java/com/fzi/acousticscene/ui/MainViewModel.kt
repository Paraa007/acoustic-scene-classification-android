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
import androidx.lifecycle.viewModelScope
import com.fzi.acousticscene.R
import com.fzi.acousticscene.audio.AudioRecorder
import com.fzi.acousticscene.audio.RecordingState
import com.fzi.acousticscene.data.ActiveSessionRegistry
import com.fzi.acousticscene.data.LastConfigStore
import com.fzi.acousticscene.data.PredictionRepository
import com.fzi.acousticscene.data.PredictionStatistics
import com.fzi.acousticscene.ml.ModelInference
import com.fzi.acousticscene.model.AllInOneResult
import com.fzi.acousticscene.model.ClassificationResult
import com.fzi.acousticscene.model.LongInterval
import com.fzi.acousticscene.model.LongSubMode
import com.fzi.acousticscene.model.LongSubResult
import com.fzi.acousticscene.model.ModelConfig
import com.fzi.acousticscene.model.ModelTrainingDuration
import com.fzi.acousticscene.model.PerSecondClip
import com.fzi.acousticscene.model.PredictionRecord
import com.fzi.acousticscene.model.RecordingCategory
import com.fzi.acousticscene.model.RecordingMode
import com.fzi.acousticscene.model.SceneClass
import com.fzi.acousticscene.model.SessionConfig
import com.fzi.acousticscene.model.SessionDuration
import com.fzi.acousticscene.model.WizardStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * Main view model behind the wizard, the live recording flow, and the
 * results-summary screen. The session is configured up-front via
 * [applySessionConfig] (built by the wizard) and started by [startSession].
 *
 * Only one session can run at a time. While one is active the only legal
 * controls are pause/resume and stop — reconfiguration is intentionally
 * disallowed (see UI_REDESIGN_WIZARD.md).
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "MainViewModel"
        private const val EVALUATION_CHANNEL_ID = "evaluation_channel"
        private const val EVALUATION_NOTIFICATION_ID = 2
    }

    private val predictionRepository = PredictionRepository.getInstance(application)
    private val mlDispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()

    // ========================================================================
    // Wizard state — collected on the wizard pages, resolved into a SessionConfig
    // when the user confirms on the summary page.
    // ========================================================================

    private val _wizard = MutableStateFlow(WizardViewState())
    val wizard: StateFlow<WizardViewState> = _wizard.asStateFlow()

    fun resetWizard(availableModels: List<String>, prefill: SessionConfig? = null) {
        _wizard.value = if (prefill != null) {
            WizardViewState(
                step = WizardStep.Models,
                availableModels = availableModels,
                selectedModels = prefill.modelNames.filter { it in availableModels },
                category = prefill.category,
                continuousSubMode = prefill.continuousSubMode,
                intervalPause = prefill.intervalPause,
                intervalMethodsByModel = prefill.intervalMethodsByModel,
                sessionDuration = prefill.sessionDuration
            )
        } else {
            WizardViewState(availableModels = availableModels)
        }
    }

    fun wizardGoToStep(step: WizardStep) {
        _wizard.update { it.copy(step = step) }
    }

    fun wizardAdvance() {
        val current = _wizard.value
        if (!current.canAdvance()) return
        val order = current.stepOrder()
        val idx = order.indexOf(current.step)
        if (idx in 0 until order.lastIndex) {
            _wizard.update { it.copy(step = order[idx + 1]) }
        }
    }

    /** @return true if a previous step exists — false means we're already on the first step. */
    fun wizardBack(): Boolean {
        val current = _wizard.value
        val order = current.stepOrder()
        val idx = order.indexOf(current.step)
        return if (idx > 0) {
            _wizard.update { it.copy(step = order[idx - 1]) }
            true
        } else false
    }

    fun wizardSetModels(models: List<String>) {
        // Drop method selections for models that are no longer in the set, seed
        // the locked default for any newly added model, and drop methods that
        // don't match the model's training duration (e.g. STANDARD on a 1s
        // model). Without the seed, IntervalMethods couldn't advance —
        // canAdvance() needs every model to have at least one method ticked.
        _wizard.update { state ->
            val kept = state.intervalMethodsByModel.filterKeys { it in models }
            val seeded = models.associateWith { name ->
                val duration = ModelTrainingDuration.secondsForFilename(name)
                val locked = ModelTrainingDuration.defaultSubMode(name)
                val previous = kept[name].orEmpty()
                val compatible = previous.filter { it.isCompatibleWith(duration) }.toSet()
                compatible + locked
            }
            state.copy(selectedModels = models, intervalMethodsByModel = seeded)
        }
    }

    fun wizardSetCategory(cat: RecordingCategory) {
        _wizard.update { it.copy(category = cat) }
    }

    fun wizardSetContinuousSubMode(sub: LongSubMode) {
        _wizard.update { it.copy(continuousSubMode = sub) }
    }

    fun wizardSetIntervalPause(pause: LongInterval) {
        _wizard.update { it.copy(intervalPause = pause) }
    }

    /**
     * Toggle a method on/off for a specific model. The locked default cannot be
     * toggled off, and methods that don't match the model's training duration
     * (e.g. Standard on a 1s-model) are silently rejected — the wizard already
     * disables their checkboxes.
     */
    fun wizardToggleIntervalMethod(modelName: String, sub: LongSubMode) {
        val locked = ModelTrainingDuration.defaultSubMode(modelName)
        if (sub == locked) return
        val duration = ModelTrainingDuration.secondsForFilename(modelName)
        if (!sub.isCompatibleWith(duration)) return
        _wizard.update { state ->
            val current = state.intervalMethodsByModel[modelName]
                ?: setOf(locked)
            val next = if (sub in current) current - sub else current + sub
            state.copy(
                intervalMethodsByModel = state.intervalMethodsByModel + (modelName to (next + locked))
            )
        }
    }

    /** Ensures every selected model has at least its locked default in the methods map. */
    fun wizardEnsureMethodDefaults() {
        _wizard.update { state ->
            val merged = state.selectedModels.associateWith { name ->
                val current = state.intervalMethodsByModel[name].orEmpty()
                current + ModelTrainingDuration.defaultSubMode(name)
            }
            state.copy(intervalMethodsByModel = merged)
        }
    }

    fun wizardSetSessionDuration(duration: SessionDuration) {
        _wizard.update { it.copy(sessionDuration = duration) }
    }

    // ========================================================================
    // Session lifecycle. `applySessionConfig` is the bridge from wizard → live.
    // ========================================================================

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _statistics = MutableStateFlow(PredictionStatistics())
    val statistics: StateFlow<PredictionStatistics> = _statistics.asStateFlow()

    private val _totalPredictionsCount = MutableStateFlow(0)
    val totalPredictionsCount: StateFlow<Int> = _totalPredictionsCount.asStateFlow()

    // Inference pool — one entry per model in the session. For single-model
    // sessions this is a list of size 1.
    private var inferencesByName: LinkedHashMap<String, ModelInference> = LinkedHashMap()
    private var modelClassCount: Int = 9

    // Currently active session config. null while idle. Cleared on stop only
    // after the Results Summary has been acknowledged so that screen can read it.
    @Volatile private var activeConfig: SessionConfig? = null
    private var sessionStartTime: Long = 0L
    private var sessionElapsedAtPauseMs: Long = 0L
    private var sessionResumeWallClockMs: Long = 0L

    private var recordingJob: Job? = null
    private var sessionTimerJob: Job? = null
    private var audioRecorder: AudioRecorder = AudioRecorder(durationSeconds = 10)

    @Volatile private var isRunning = false
    @Volatile private var isPaused = false
    @Volatile private var stopReasonAuto = false  // true if soft-stop triggered

    init {
        updateStatistics()
        // Surface dismissals of the in-app evaluation prompt so the card disappears
        // once the user has rated (or skipped) on EvaluationActivity.
        viewModelScope.launch {
            EvaluationPromptBus.dismissals.collect { dismissedId ->
                _uiState.update { state ->
                    if (state.pendingEvaluation?.predictionId == dismissedId) {
                        state.copy(pendingEvaluation = null)
                    } else state
                }
            }
        }
        startVolumeObservation()
    }

    private var volumeJob: Job? = null
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
     * Loads the picked models into memory and parks the resolved config until
     * [startSession] is called. Called from the wizard summary page on Start.
     */
    fun applySessionConfig(config: SessionConfig) {
        if (isRunning) {
            Log.w(TAG, "applySessionConfig called while running — ignored")
            return
        }
        activeConfig = config
        inferencesByName = LinkedHashMap()
        for (name in config.modelNames) {
            val path = "${ModelConfig.DEV_MODELS_DIR}/$name"
            inferencesByName[name] = ModelInference(getApplication<Application>().applicationContext, path)
        }
        modelClassCount = ModelConfig.getClassCountForModel(config.modelNames.first())
        _uiState.update {
            it.copy(
                sessionConfig = config,
                appState = AppState.Loading,
                isModelLoaded = false,
                liveResultsByModel = emptyMap(),
                aggregateResultsByModel = emptyMap(),
                cycleCountByModelMethod = emptyMap(),
                sessionElapsedMs = 0L,
                sessionVolumeMean = 0f,
                sessionVolumeMeanSampleCount = 0,
                allInOneModelNames = config.modelNames,
                allInOneResults = emptyMap()
            )
        }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                inferencesByName.values.all { it.loadModel() }
            }
            _uiState.update {
                if (ok) it.copy(appState = AppState.Ready, isModelLoaded = true, errorMessage = null)
                else it.copy(appState = AppState.Error("Failed to load model"), isModelLoaded = false)
            }
        }
        LastConfigStore.save(getApplication(), config)
    }

    /**
     * Returns the currently parked or active session config. The new flow uses
     * this from RecordingFragment to know how to render the live UI.
     */
    fun currentSessionConfig(): SessionConfig? = activeConfig

    fun startSession() {
        val config = activeConfig ?: run {
            Log.e(TAG, "startSession with no config — ignored")
            return
        }
        if (isRunning) return
        if (inferencesByName.values.any { !it.isModelLoaded() }) {
            Log.e(TAG, "Models not loaded yet — ignored")
            return
        }

        isRunning = true
        isPaused = false
        stopReasonAuto = false
        sessionStartTime = System.currentTimeMillis()
        sessionResumeWallClockMs = SystemClock.elapsedRealtime()
        sessionElapsedAtPauseMs = 0L

        ActiveSessionRegistry.register(
            ActiveSessionRegistry.Entry(
                modelPath = "${ModelConfig.DEV_MODELS_DIR}/${config.modelNames.first()}",
                modelName = config.modelNames.first(),
                numClasses = modelClassCount,
                sessionStartTime = sessionStartTime,
                allInOneModels = config.modelNames.takeIf { it.size >= 2 }
            )
        )

        _uiState.update { it.copy(
            isPaused = false,
            sessionElapsedMs = 0L,
            liveResultsByModel = emptyMap(),
            aggregateResultsByModel = emptyMap(),
            cycleCountByModelMethod = emptyMap(),
            sessionVolumeMean = 0f,
            sessionVolumeMeanSampleCount = 0,
            allInOneResults = emptyMap()
        ) }

        // Session timer — ticks every 500 ms so the stopwatch stays smooth, fires
        // soft-stop when the chosen window elapses.
        sessionTimerJob?.cancel()
        sessionTimerJob = viewModelScope.launch {
            val total = config.sessionDuration.totalMs
            while (isActive && isRunning) {
                if (!isPaused) {
                    val elapsed = sessionElapsedAtPauseMs +
                            (SystemClock.elapsedRealtime() - sessionResumeWallClockMs)
                    _uiState.update { it.copy(sessionElapsedMs = elapsed) }
                    if (total != null && elapsed >= total) {
                        stopReasonAuto = true
                        // Soft stop — flag the recording loop to exit at the next
                        // safe boundary (recording finishes, no new cycle starts).
                        isRunning = false
                        Log.d(TAG, "Session window elapsed — soft stop scheduled")
                        break
                    }
                }
                delay(500L)
            }
        }

        // Audio + inference loop
        recordingJob = viewModelScope.launch {
            try {
                runSessionLoop(config)
            } catch (_: CancellationException) {
                Log.d(TAG, "Recording loop cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Session loop error", e)
                _uiState.update { it.copy(
                    appState = AppState.Error(e.message ?: "Unknown error"),
                    errorMessage = e.message ?: "Unknown error"
                ) }
            } finally {
                onSessionLoopExit()
            }
        }
    }

    private suspend fun runSessionLoop(config: SessionConfig) {
        while (isRunning) {
            // Hold here while paused (clip-accurate: we never enter mid-cycle).
            while (isPaused && isRunning) {
                delay(300L)
            }
            if (!isRunning) break

            val cycleStartedAt = System.currentTimeMillis()
            val mode = effectiveModeFor(config)
            audioRecorder = AudioRecorder(durationSeconds = mode.durationSeconds)
            startVolumeObservation()

            // For AVERAGE in continuous: the AVG flow does its own per-second loop.
            // For STANDARD/FAST or any Interval method: record once, then infer.
            val cycleResult: CycleOutcome = (when {
                config.category == RecordingCategory.CONTINUOUS &&
                        config.continuousSubMode == LongSubMode.AVERAGE -> runAverageCycle(config)
                config.category == RecordingCategory.CONTINUOUS -> runSingleClipCycle(config, mode)
                else /* INTERVAL */ -> runIntervalCycle(config)
            }) ?: continue

            // Persist this cycle as a single PredictionRecord (or one per model row, as designed).
            persistCycle(config, cycleResult, cycleStartedAt)

            // Interval: pause between recordings, then evaluation prompt.
            if (config.category == RecordingCategory.INTERVAL && isRunning) {
                val pauseMs = config.intervalPause?.pauseMs ?: 0L
                if (pauseMs > 0L) {
                    sendIntervalEvaluationNotification(cycleResult)
                    runIntervalPause(pauseMs)
                }
            }
        }
    }

    /**
     * Handles the auto/manual stop teardown. Always runs from the recording job's
     * finally block.
     */
    private fun onSessionLoopExit() {
        val auto = stopReasonAuto
        Log.d(TAG, "Session loop exited (auto=$auto)")
        if (isRunning) isRunning = false
        sessionTimerJob?.cancel()
        audioRecorder.stopRecording()
        ActiveSessionRegistry.unregister()
        _uiState.update { it.copy(
            appState = AppState.Ready,
            isPaused = false,
            recordingProgress = 0f,
            currentVolume = 0f,
            perSecondResults = List(10) { null },
            runningAverageResult = null
        ) }
    }

    /**
     * Picks the [RecordingMode] that drives the AudioRecorder / inference call
     * for the active config. Continuous: derived from continuousSubMode; Interval
     * always records 10 s (LONG) and then dispatches per-method on that buffer.
     */
    private fun effectiveModeFor(config: SessionConfig): RecordingMode = when (config.category) {
        RecordingCategory.CONTINUOUS -> when (config.continuousSubMode) {
            LongSubMode.FAST -> RecordingMode.FAST
            LongSubMode.AVERAGE -> RecordingMode.AVERAGE
            LongSubMode.STANDARD -> RecordingMode.STANDARD
        }
        RecordingCategory.INTERVAL -> RecordingMode.LONG
    }

    /**
     * One Continuous cycle for the FAST/STANDARD path. Records, then runs every
     * model on the buffer.
     */
    private suspend fun runSingleClipCycle(config: SessionConfig, mode: RecordingMode): CycleOutcome? {
        val samples = recordCycleAudio(mode) ?: return null
        val sub = config.continuousSubMode
        val perModel = mutableMapOf<String, MutableMap<LongSubMode, ClassificationResult>>()
        _uiState.update { it.copy(appState = AppState.Processing, liveResultsByModel = emptyMap()) }
        for ((name, inf) in inferencesByName) {
            if (!isRunning) break
            val r = withContext(mlDispatcher) { inf.infer(samples, mode) } ?: continue
            perModel.getOrPut(name) { mutableMapOf() }[sub] = r
            // Stream into UI as each model finishes
            _uiState.update { state ->
                state.copy(liveResultsByModel = state.liveResultsByModel + (name to mapOf(sub to r)))
            }
        }
        val volume = audioRecorder.snapshotVolumeStats()
        accumulateAggregate(perModel)
        accumulateSessionVolume(volume.mean)
        return CycleOutcome(perModel = perModel, volume = volume, perSecondClips = null)
    }

    /**
     * One Continuous AVERAGE cycle: 10 × 1 s recordings, each fed through every
     * model. Per-second circles update for the primary model only (UX choice).
     */
    private suspend fun runAverageCycle(config: SessionConfig): CycleOutcome? {
        val totalClips = 10
        _uiState.update { it.copy(
            perSecondResults = List(totalClips) { null },
            runningAverageResult = null,
            liveResultsByModel = emptyMap()
        ) }
        val perModelClips: MutableMap<String, MutableList<ClassificationResult>> = mutableMapOf()
        val volMeans = mutableListOf<Float>()
        var volPeak = 0f
        val cycleStart = System.currentTimeMillis()
        for (i in 0 until totalClips) {
            if (!isRunning || isPaused) break
            val recorder = AudioRecorder(durationSeconds = 1)
            audioRecorder = recorder
            startVolumeObservation()
            var clipSamples: FloatArray? = null
            try {
                recorder.startRecording().collect { state ->
                    when (state) {
                        is RecordingState.Completed -> clipSamples = state.samples
                        is RecordingState.Progress -> {
                            val overall = (i + state.progress) / totalClips
                            _uiState.update {
                                it.copy(
                                    appState = AppState.Recording(totalClips - i),
                                    recordingProgress = overall
                                )
                            }
                        }
                        else -> Unit
                    }
                }
            } catch (_: CancellationException) {
                return null
            }
            val samples = clipSamples ?: break
            val volStats = recorder.snapshotVolumeStats()
            volMeans += volStats.mean
            if (volStats.peak > volPeak) volPeak = volStats.peak

            for ((name, inf) in inferencesByName) {
                if (!isRunning) break
                val r = withContext(mlDispatcher) { inf.infer(samples, RecordingMode.FAST) } ?: continue
                perModelClips.getOrPut(name) { mutableListOf() } += r
                if (name == config.modelNames.first()) {
                    val running = computeRunningAverage(perModelClips[name]!!, cycleStart)
                    _uiState.update { state ->
                        val per = state.perSecondResults.toMutableList()
                        if (i < per.size) per[i] = r
                        state.copy(
                            perSecondResults = per,
                            runningAverageResult = running,
                            liveResultsByModel = state.liveResultsByModel + (name to mapOf(LongSubMode.AVERAGE to running))
                        )
                    }
                } else {
                    val running = computeRunningAverage(perModelClips[name]!!, cycleStart)
                    _uiState.update { state ->
                        state.copy(liveResultsByModel = state.liveResultsByModel + (name to mapOf(LongSubMode.AVERAGE to running)))
                    }
                }
            }
        }
        if (perModelClips.isEmpty()) return null

        val perModel = mutableMapOf<String, MutableMap<LongSubMode, ClassificationResult>>()
        for ((name, clips) in perModelClips) {
            val avg = computeRunningAverage(clips, cycleStart)
            perModel.getOrPut(name) { mutableMapOf() }[LongSubMode.AVERAGE] = avg
        }
        accumulateAggregate(perModel)
        val cycleVolMean = if (volMeans.isNotEmpty()) volMeans.average().toFloat() else 0f
        accumulateSessionVolume(cycleVolMean)
        // Per-second clips (primary model only, for History compatibility).
        val primaryName = config.modelNames.first()
        val primaryClips = perModelClips[primaryName].orEmpty()
        val perSecondClips = primaryClips.mapIndexed { idx, cr ->
            PerSecondClip(
                clipIndex = idx,
                sceneClass = cr.sceneClass,
                confidence = cr.confidence,
                allProbabilities = cr.allProbabilities,
                volumeMean = volMeans.getOrNull(idx),
                volumePeak = null
            )
        }
        return CycleOutcome(
            perModel = perModel,
            volume = AudioRecorder.VolumeStats(mean = cycleVolMean, peak = volPeak),
            perSecondClips = perSecondClips
        )
    }

    /**
     * One Interval cycle — record 10 s once, then for every model run every
     * checked method on the same buffer.
     */
    private suspend fun runIntervalCycle(config: SessionConfig): CycleOutcome? {
        val samples = recordCycleAudio(RecordingMode.LONG) ?: return null
        val volume = audioRecorder.snapshotVolumeStats()
        val sampleRate = 32000
        val perModel = mutableMapOf<String, MutableMap<LongSubMode, ClassificationResult>>()
        val perSecondClipsByPrimary = mutableListOf<PerSecondClip>()
        _uiState.update { it.copy(appState = AppState.Processing, liveResultsByModel = emptyMap()) }

        for ((modelName, inf) in inferencesByName) {
            if (!isRunning) break
            val methods = config.intervalMethodsByModel[modelName].orEmpty()
            for (sub in methods) {
                if (!isRunning) break
                val r: ClassificationResult? = when (sub) {
                    LongSubMode.STANDARD -> withContext(mlDispatcher) { inf.infer(samples, RecordingMode.STANDARD) }
                    LongSubMode.FAST -> {
                        if (samples.size < sampleRate * 10) null
                        else {
                            val from = (sampleRate * 4.5f).toInt()
                            val to = (sampleRate * 5.5f).toInt()
                            withContext(mlDispatcher) { inf.infer(samples.copyOfRange(from, to), RecordingMode.FAST) }
                        }
                    }
                    LongSubMode.AVERAGE -> {
                        if (samples.size < sampleRate * 10) null
                        else {
                            val clipResults = mutableListOf<ClassificationResult>()
                            val startTime = System.currentTimeMillis()
                            for (i in 0 until 10) {
                                if (!isRunning) break
                                val slice = samples.copyOfRange(i * sampleRate, (i + 1) * sampleRate)
                                val cr = withContext(mlDispatcher) { inf.infer(slice, RecordingMode.FAST) } ?: continue
                                clipResults += cr
                                if (modelName == config.modelNames.first()) {
                                    perSecondClipsByPrimary += PerSecondClip(
                                        clipIndex = i,
                                        sceneClass = cr.sceneClass,
                                        confidence = cr.confidence,
                                        allProbabilities = cr.allProbabilities,
                                        volumeMean = null,
                                        volumePeak = null
                                    )
                                    val running = computeRunningAverage(clipResults, startTime)
                                    _uiState.update { state ->
                                        val per = state.perSecondResults.toMutableList()
                                        if (i < per.size) per[i] = cr
                                        state.copy(
                                            perSecondResults = per,
                                            runningAverageResult = running
                                        )
                                    }
                                    delay(150L)
                                }
                            }
                            if (clipResults.isEmpty()) null else computeRunningAverage(clipResults, startTime)
                        }
                    }
                }
                if (r != null) {
                    perModel.getOrPut(modelName) { mutableMapOf() }[sub] = r
                    _uiState.update { state ->
                        val prev = state.liveResultsByModel[modelName].orEmpty()
                        state.copy(
                            liveResultsByModel = state.liveResultsByModel + (modelName to (prev + (sub to r)))
                        )
                    }
                }
            }
        }
        if (perModel.isEmpty()) return null
        accumulateAggregate(perModel)
        accumulateSessionVolume(volume.mean)
        return CycleOutcome(
            perModel = perModel,
            volume = volume,
            perSecondClips = perSecondClipsByPrimary.takeIf { it.isNotEmpty() }
        )
    }

    /**
     * Drives the AudioRecorder for one full cycle (Continuous single-clip / Interval).
     * Returns the captured samples, or null if recording was cancelled or errored.
     */
    private suspend fun recordCycleAudio(mode: RecordingMode): FloatArray? {
        val durationSeconds = mode.durationSeconds
        _uiState.update { it.copy(
            appState = AppState.Recording(durationSeconds),
            recordingProgress = 0f
        ) }
        var captured: FloatArray? = null
        try {
            audioRecorder.startRecording().collect { state ->
                when (state) {
                    is RecordingState.Started -> Unit
                    is RecordingState.Progress -> {
                        val remaining = (durationSeconds * (1f - state.progress)).toInt()
                        _uiState.update { it.copy(
                            appState = AppState.Recording(remaining),
                            recordingProgress = state.progress
                        ) }
                    }
                    is RecordingState.Completed -> captured = state.samples
                    is RecordingState.Error -> _uiState.update { it.copy(
                        appState = AppState.Error(state.message),
                        errorMessage = state.message
                    ) }
                }
            }
        } catch (_: CancellationException) {
            return null
        }
        return captured
    }

    /**
     * Interval pause loop — clip-accurate, watches isPaused / isRunning and the
     * session-timer's external soft-stop signal.
     */
    private suspend fun runIntervalPause(pauseMs: Long) {
        var remaining = pauseMs
        while (remaining > 0 && isRunning) {
            if (isPaused) {
                _uiState.update { it.copy(appState = AppState.UserPaused((remaining / 1000L).toInt())) }
                delay(500L)
                continue
            }
            _uiState.update { it.copy(appState = AppState.Paused((remaining / 1000L).toInt())) }
            delay(1000L)
            if (!isPaused) remaining -= 1000L
        }
    }

    /**
     * Writes one PredictionRecord per cycle into the repository. Multi-Model
     * sessions still write a single row — per-model results live inside
     * `allInOneResults` (Continuous) or `longSubResults` (Interval).
     */
    private fun persistCycle(
        config: SessionConfig,
        outcome: CycleOutcome,
        cycleStartedAt: Long
    ) {
        val primaryName = config.modelNames.first()
        val primaryRow = outcome.perModel[primaryName] ?: return
        val primarySub = if (config.category == RecordingCategory.CONTINUOUS) {
            config.continuousSubMode
        } else {
            ModelTrainingDuration.defaultSubMode(primaryName)
        }
        val primaryResult = primaryRow[primarySub] ?: primaryRow.values.firstOrNull() ?: return

        val mode = effectiveModeFor(config)
        val battery = getBatteryLevel()
        val top3 = primaryResult.getTopPredictions(3)

        // Build LongSubResults (Interval-only or AVERAGE-with-clips).
        val longSubs: List<LongSubResult>? = when {
            config.category == RecordingCategory.INTERVAL -> {
                val list = mutableListOf<LongSubResult>()
                for ((modelName, methods) in outcome.perModel) {
                    for ((sub, res) in methods) {
                        list += LongSubResult(
                            subMode = sub,
                            sceneClass = res.sceneClass,
                            confidence = res.confidence,
                            allProbabilities = res.allProbabilities,
                            inferenceTimeMs = res.inferenceTimeMs,
                            perSecondClips = if (sub == LongSubMode.AVERAGE && modelName == primaryName)
                                outcome.perSecondClips else null,
                            modelName = modelName
                        )
                    }
                }
                list.takeIf { it.isNotEmpty() }
            }
            else -> null
        }

        // Multi-Model continuous: persist as AllInOneResults so the existing CSV
        // dynamic columns and History dialog rendering keep working.
        val allInOne: List<AllInOneResult>? = if (
            config.category == RecordingCategory.CONTINUOUS && config.modelNames.size >= 2
        ) {
            outcome.perModel.entries.mapNotNull { (name, methods) ->
                val res = methods[config.continuousSubMode] ?: methods.values.firstOrNull() ?: return@mapNotNull null
                AllInOneResult(
                    modelName = name,
                    sceneClass = res.sceneClass,
                    confidence = res.confidence,
                    allProbabilities = res.allProbabilities,
                    inferenceTimeMs = res.inferenceTimeMs
                )
            }.takeIf { it.isNotEmpty() }
        } else null

        val record = PredictionRecord(
            timestamp = cycleStartedAt,
            sessionStartTime = sessionStartTime,
            sceneClass = primaryResult.sceneClass,
            confidence = primaryResult.confidence,
            allProbabilities = primaryResult.allProbabilities,
            topPredictions = top3,
            inferenceTimeMs = primaryResult.inferenceTimeMs,
            recordingMode = mode,
            batteryLevel = battery,
            modelName = primaryName,
            perSecondClips = outcome.perSecondClips,
            longSubResults = longSubs,
            longIntervalMinutes = config.intervalPause?.pauseMinutes,
            allInOneResults = allInOne,
            volumeMean = outcome.volume.mean,
            volumePeak = outcome.volume.peak
        )
        predictionRepository.addPrediction(record)
        updateStatistics()

        // Mirror primary result onto currentResult so legacy widgets that read
        // it (volume graph, etc.) keep working.
        _uiState.update { it.copy(currentResult = primaryResult, errorMessage = null) }

        // Send Interval evaluation prompt — only for Interval, only Foreground.
        if (config.category == RecordingCategory.INTERVAL) {
            // Interval evaluation prompt is sent right after the cycle completes
            // (during the pause) — so this is a no-op here. See sendIntervalEvaluationNotification.
        }
    }

    private fun accumulateAggregate(
        perModel: Map<String, Map<LongSubMode, ClassificationResult>>
    ) {
        _uiState.update { state ->
            val nextAgg = state.aggregateResultsByModel.toMutableMap()
            val nextCounts = state.cycleCountByModelMethod.toMutableMap()
            for ((model, methods) in perModel) {
                val perMethodAgg = nextAgg[model]?.toMutableMap() ?: mutableMapOf()
                for ((sub, result) in methods) {
                    val key = model to sub
                    val priorCount = nextCounts[key] ?: 0
                    val merged = mergeClassificationResults(perMethodAgg[sub], result, priorCount)
                    perMethodAgg[sub] = merged
                    nextCounts[key] = priorCount + 1
                }
                nextAgg[model] = perMethodAgg
            }
            state.copy(
                aggregateResultsByModel = nextAgg,
                cycleCountByModelMethod = nextCounts
            )
        }
    }

    private fun mergeClassificationResults(
        prior: ClassificationResult?,
        next: ClassificationResult,
        priorCount: Int
    ): ClassificationResult {
        if (prior == null || priorCount == 0) return next
        val n = next.allProbabilities.size
        val mixed = FloatArray(n)
        for (i in 0 until n) {
            mixed[i] = (prior.allProbabilities[i] * priorCount + next.allProbabilities[i]) / (priorCount + 1)
        }
        val bestIdx = mixed.indices.maxByOrNull { mixed[it] } ?: 0
        return ClassificationResult(
            sceneClass = SceneClass.fromIndex(bestIdx) ?: prior.sceneClass,
            confidence = mixed[bestIdx],
            allProbabilities = mixed,
            inferenceTimeMs = next.inferenceTimeMs
        )
    }

    private fun accumulateSessionVolume(cycleMean: Float) {
        _uiState.update {
            val n = it.sessionVolumeMeanSampleCount
            val newMean = if (n == 0) cycleMean else (it.sessionVolumeMean * n + cycleMean) / (n + 1)
            it.copy(sessionVolumeMean = newMean, sessionVolumeMeanSampleCount = n + 1)
        }
    }

    private fun computeRunningAverage(
        results: List<ClassificationResult>,
        startTime: Long
    ): ClassificationResult {
        val n = results.first().allProbabilities.size
        val avg = FloatArray(n)
        for (r in results) for (j in r.allProbabilities.indices) avg[j] += r.allProbabilities[j]
        for (j in avg.indices) avg[j] /= results.size
        val bestIdx = avg.indices.maxByOrNull { avg[it] } ?: 0
        val bestClass = SceneClass.fromIndex(bestIdx) ?: SceneClass.TRANSIT_VEHICLES
        return ClassificationResult(
            sceneClass = bestClass,
            confidence = avg[bestIdx],
            allProbabilities = avg,
            inferenceTimeMs = System.currentTimeMillis() - startTime
        )
    }

    /**
     * User stops the session manually (or auto-stop bubbled up). Cleans up the
     * audio + ML pipeline. The session config + aggregate results stay on the
     * UiState so the results-summary screen can render them; clear them with
     * [clearSessionResults] once the user navigates away.
     */
    fun stopSession() {
        if (!isRunning && recordingJob == null) return
        Log.d(TAG, "stopSession()")
        isRunning = false
        isPaused = false
        recordingJob?.cancel()
        sessionTimerJob?.cancel()
        audioRecorder.stopRecording()
    }

    /** Called from the Results Summary screen when the user navigates away. */
    fun clearSessionResults() {
        activeConfig = null
        inferencesByName = LinkedHashMap()
        _uiState.update { UiState() }
    }

    /**
     * Pauses the session. If [autoResumeAfterMs] is non-null, schedules an
     * automatic resume after that many milliseconds (the user can still resume
     * earlier manually). Null = indefinite pause.
     */
    fun pauseSession(autoResumeAfterMs: Long? = null) {
        if (!isRunning || isPaused) return
        isPaused = true
        sessionElapsedAtPauseMs += SystemClock.elapsedRealtime() - sessionResumeWallClockMs
        pauseStartedWallClockMs = SystemClock.elapsedRealtime()
        val deadline = autoResumeAfterMs?.let { SystemClock.elapsedRealtime() + it }
        _uiState.update { it.copy(isPaused = true, userPauseDeadlineElapsedMs = deadline) }
        autoResumeJob?.cancel()
        autoResumeJob = if (autoResumeAfterMs != null) {
            viewModelScope.launch {
                delay(autoResumeAfterMs)
                if (isPaused && isRunning) resumeSession()
            }
        } else null
        // Synthetic PAUSE record is written when the user resumes (so we know the
        // duration). Deferred persistence avoids zero-length pauses if the user
        // pauses then immediately stops.
    }

    private var pauseStartedWallClockMs: Long = 0L
    private var autoResumeJob: Job? = null

    fun resumeSession() {
        if (!isRunning || !isPaused) return
        autoResumeJob?.cancel()
        autoResumeJob = null
        val pauseDurationMs = SystemClock.elapsedRealtime() - pauseStartedWallClockMs
        if (pauseDurationMs >= 1000L) {
            persistPauseRecord(pauseDurationMs)
        }
        isPaused = false
        sessionResumeWallClockMs = SystemClock.elapsedRealtime()
        _uiState.update { it.copy(isPaused = false, userPauseDeadlineElapsedMs = null) }
    }

    private fun persistPauseRecord(durationMs: Long) {
        val record = PredictionRecord(
            timestamp = System.currentTimeMillis(),
            sessionStartTime = sessionStartTime,
            sceneClass = SceneClass.TRANSIT_VEHICLES,  // placeholder, ignored when isPause
            confidence = 0f,
            allProbabilities = FloatArray(modelClassCount),
            topPredictions = emptyList(),
            inferenceTimeMs = 0L,
            recordingMode = RecordingMode.STANDARD,
            batteryLevel = -1,
            modelName = activeConfig?.modelNames?.firstOrNull().orEmpty(),
            isPause = true,
            pauseDurationSec = durationMs / 1000L
        )
        predictionRepository.addPrediction(record)
    }

    /**
     * Ships an Interval-mode evaluation prompt — system notification when the app
     * is backgrounded, in-app card when foregrounded. Skipped for Continuous
     * (would fire every 10 s, unusable).
     */
    private fun sendIntervalEvaluationNotification(outcome: CycleOutcome) {
        val record = predictionRepository.getAllPredictions().lastOrNull() ?: return
        val context = getApplication<Application>()
        val isForeground = ProcessLifecycleOwner.get().lifecycle.currentState
            .isAtLeast(Lifecycle.State.STARTED)
        if (isForeground) {
            val deadline = SystemClock.elapsedRealtime() + EvaluationActivity.EVALUATION_TIMEOUT_MS
            _uiState.update { it.copy(
                pendingEvaluation = PendingEvaluation(
                    predictionId = record.id,
                    modelClass = record.sceneClass,
                    deadlineElapsedMs = deadline
                )
            ) }
            viewModelScope.launch {
                delay(EvaluationActivity.EVALUATION_TIMEOUT_MS)
                _uiState.update { state ->
                    if (state.pendingEvaluation?.predictionId == record.id) {
                        state.copy(pendingEvaluation = null)
                    } else state
                }
            }
            return
        }
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            EVALUATION_CHANNEL_ID,
            context.getString(R.string.evaluation_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.evaluation_channel_description)
        }
        notificationManager.createNotificationChannel(channel)
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
            .setTimeoutAfter(EvaluationActivity.EVALUATION_TIMEOUT_MS)
            .build()
        notificationManager.notify(EVALUATION_NOTIFICATION_ID, notification)
    }

    private fun getBatteryLevel(): Int = try {
        val bm = getApplication<Application>().getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (_: Exception) { -1 }

    fun isClassifying(): Boolean = isRunning

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

    fun getAllPredictions(): List<PredictionRecord> = predictionRepository.getAllPredictions()
    fun clearAllPredictions() {
        predictionRepository.clearAll()
        updateStatistics()
    }
    fun clearOldPredictions(days: Int) {
        predictionRepository.clearOlderThan(days)
        updateStatistics()
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null, appState = AppState.Ready) }
    }

    /**
     * Skip-button on the in-app evaluation card. Same effect as letting the
     * 5-minute timer expire — the prompt is cleared without writing a user rating.
     */
    fun dismissPendingEvaluation() {
        _uiState.update { it.copy(pendingEvaluation = null) }
    }

    override fun onCleared() {
        super.onCleared()
        stopSession()
        mlDispatcher.close()
    }

    /**
     * Internal: result of one full recording cycle, ready to be persisted and
     * accumulated into the session aggregates.
     */
    private data class CycleOutcome(
        val perModel: Map<String, Map<LongSubMode, ClassificationResult>>,
        val volume: AudioRecorder.VolumeStats,
        val perSecondClips: List<PerSecondClip>?
    )
}
