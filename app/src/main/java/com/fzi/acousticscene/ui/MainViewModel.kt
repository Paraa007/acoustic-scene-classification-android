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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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

    fun resetWizard(
        availableModels: List<String>,
        prefill: SessionConfig? = null,
        quickStartMode: Boolean = false
    ) {
        _wizard.value = if (prefill != null) {
            WizardViewState(
                step = if (quickStartMode) WizardStep.Summary else WizardStep.Models,
                availableModels = availableModels,
                selectedModels = prefill.modelNames.filter { it in availableModels },
                category = prefill.category,
                continuousMethodsByModel = prefill.continuousMethodsByModel,
                intervalPause = prefill.intervalPause,
                intervalMethodsByModel = prefill.intervalMethodsByModel,
                sessionDuration = prefill.sessionDuration,
                quickStartMode = quickStartMode
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
        // Methods aren't user-pickable any more — derive them straight from
        // each model's training duration (1 s → FAST+AVG, 10 s → STANDARD).
        // Stored on both maps so Continuous and Interval can read uniformly.
        _wizard.update { state ->
            val methods = models.associateWith { ModelTrainingDuration.requiredMethodsForModel(it) }
            state.copy(
                selectedModels = models,
                intervalMethodsByModel = methods,
                continuousMethodsByModel = methods
            )
        }
    }

    fun wizardSetCategory(cat: RecordingCategory) {
        _wizard.update { it.copy(category = cat) }
    }

    fun wizardSetIntervalPause(pause: LongInterval) {
        _wizard.update { it.copy(intervalPause = pause) }
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
    @Volatile private var pausePending = false  // user pressed Pause, frame not closed yet
    private var pendingAutoResumeMs: Long? = null
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
        pausePending = false
        pendingAutoResumeMs = null
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
            pausePending = false,
            pauseTotalMs = null,
            userPauseDeadlineElapsedMs = null,
            sessionElapsedMs = 0L,
            sessionPausedMs = 0L,
            frameElapsedMs = 0L,
            frameSegments = frameSegmentsFor(config),
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
            // See comment in startSession() for why this matters.
            currentCoroutineContext().ensureActive()

            // Frame boundary: if the user pressed Pause during the previous
            // frame, activate the real pause now (status countdown starts
            // ticking from here, not from the button press). The volume chart
            // and inner ring stay frozen.
            activatePauseAtFrameEnd()

            // Hold here while paused (clip-accurate: we never enter mid-cycle).
            while (isPaused && isRunning) {
                delay(300L)
            }
            if (!isRunning) break

            val cycleStartedAt = System.currentTimeMillis()
            val frameSegments = frameSegmentsFor(config)
            _uiState.update { it.copy(
                frameSegments = frameSegments,
                frameElapsedMs = 0L,
                recordingProgress = 0f
            ) }

            // Every cycle records one 10 s buffer and dispatches per model:
            //  - 1 s-trained models receive the buffer in 10 × 1 s slices,
            //    each slice fed individually (drives the per-second rings and
            //    the running AVG). FAST takes the latest 1 s value live.
            //  - 10 s-trained models receive the full 10 s buffer in one shot
            //    for STANDARD.
            // Interval and Continuous share that loop; only the outer
            // bookkeeping differs (Interval pauses, Continuous loops back in).
            val cycleResult: CycleOutcome? = when (config.category) {
                RecordingCategory.CONTINUOUS -> runContinuousLongCycle(config)
                RecordingCategory.INTERVAL -> runIntervalCycle(config)
            }

            if (cycleResult != null) {
                persistCycle(config, cycleResult, cycleStartedAt)
            }
            if (cycleResult == null) continue

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

    private fun frameSegmentsFor(config: SessionConfig): Int {
        // The inner ring slices into 10 segments whenever any 1 s-trained
        // model is active — that's when per-second updates land. Otherwise
        // (only 10 s-models, or Interval pause) it stays a single 10 s tick.
        val hasOneSecModel = config.modelNames.any {
            ModelTrainingDuration.secondsForFilename(it) == 1
        }
        return if (hasOneSecModel) 10 else 1
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
        pausePending = false
        pendingAutoResumeMs = null
        _uiState.update { it.copy(
            appState = AppState.Ready,
            isPaused = false,
            pausePending = false,
            pauseTotalMs = null,
            userPauseDeadlineElapsedMs = null,
            recordingProgress = 0f,
            currentVolume = 0f,
            frameElapsedMs = 0L,
            perSecondResults = List(10) { null },
            runningAverageResult = null,
            perSecondResultsByModel = emptyMap()
        ) }
    }

    /**
     * Picks the [RecordingMode] that drives the AudioRecorder for one cycle.
     * Every cycle now records a 10 s buffer and dispatches per model on that
     * buffer — 1 s-models get the 10 × 1 s slices, 10 s-models get the full
     * window. Continuous uses STANDARD framing (no planned pause after),
     * Interval uses LONG (interval pause kicks in via the outer loop).
     */
    private fun effectiveModeFor(config: SessionConfig): RecordingMode = when (config.category) {
        RecordingCategory.CONTINUOUS -> RecordingMode.STANDARD
        RecordingCategory.INTERVAL -> RecordingMode.LONG
    }

    /**
     * Pads a per-second volume array to length 10 so the CSV always has
     * `volume_s1..volume_s10` to fill. Short cycles (FAST = 1 s) end up with
     * `s1` populated and `s2..s10` = 0.
     */
    private fun padToTen(src: FloatArray): FloatArray {
        val out = FloatArray(10)
        val n = minOf(src.size, 10)
        for (i in 0 until n) out[i] = src[i]
        return out
    }

    /**
     * One Interval cycle — record 10 s once, then for every model run every
     * checked method on the same buffer.
     */
    private suspend fun runIntervalCycle(config: SessionConfig): CycleOutcome? =
        runLongMultiMethodCycle(config, config.intervalMethodsByModel)

    /**
     * One Continuous cycle whenever at least one model has STANDARD or AVERAGE
     * checked. Shape-wise identical to [runIntervalCycle] (record 10 s, then
     * dispatch per model / per method on that buffer) — only the outer loop
     * tells them apart (Interval pauses, Continuous loops straight back in).
     */
    private suspend fun runContinuousLongCycle(config: SessionConfig): CycleOutcome? =
        runLongMultiMethodCycle(config, config.continuousMethodsByModel)

    /**
     * Records a 10 s buffer, slices it into 10 × 1 s pieces, and runs every
     * 1 s-trained model on every slice (live-streamed to the per-model
     * per-second rings + Fast/Avg bars). Every 10 s-trained model gets a
     * single inference on the full 10 s buffer for its STANDARD method.
     *
     * Used by both Interval (with planned pause after) and Continuous
     * Multi-Method (loops straight back in). The shape is the same; only
     * the outer loop differs.
     */
    private suspend fun runLongMultiMethodCycle(
        config: SessionConfig,
        methodsByModel: Map<String, Set<LongSubMode>>
    ): CycleOutcome? {
        val samples = recordCycleAudio(RecordingMode.LONG) ?: return null
        val volume = audioRecorder.snapshotVolumeStats()
        val sampleRate = 32000
        val totalClips = 10
        val perModel = mutableMapOf<String, MutableMap<LongSubMode, ClassificationResult>>()
        val perSecondByModel = mutableMapOf<String, MutableList<ClassificationResult>>()

        val active = inferencesByName.filterKeys { (methodsByModel[it].orEmpty().isNotEmpty()) }
        val oneSecModels = active.filterKeys { ModelTrainingDuration.secondsForFilename(it) == 1 }
        val tenSecModels = active.filterKeys { ModelTrainingDuration.secondsForFilename(it) == 10 }
        val primaryName = config.modelNames.first()
        val primaryIs1s = ModelTrainingDuration.secondsForFilename(primaryName) == 1

        _uiState.update { state ->
            val reset = state.perSecondResultsByModel.toMutableMap()
            for (name in oneSecModels.keys) reset[name] = List(totalClips) { null }
            state.copy(
                appState = AppState.Processing,
                liveResultsByModel = emptyMap(),
                perSecondResults = List(totalClips) { null },
                runningAverageResult = null,
                perSecondResultsByModel = reset
            )
        }

        val cycleStart = System.currentTimeMillis()
        // Per 1 s slice: stream FAST (live current value) and AVG (running
        // mean) for every 1 s-trained model. The per-model perSecondResults
        // map feeds each model's "Show Live Data" row independently.
        if (oneSecModels.isNotEmpty()) {
            for (i in 0 until totalClips) {
                if (!isRunning) break
                if (samples.size < sampleRate * (i + 1)) break
                val slice = samples.copyOfRange(i * sampleRate, (i + 1) * sampleRate)
                for ((modelName, inf) in oneSecModels) {
                    if (!isRunning) break
                    val cr = withContext(mlDispatcher) { inf.infer(slice, RecordingMode.FAST) } ?: continue
                    perSecondByModel.getOrPut(modelName) { mutableListOf() } += cr
                    val running = computeRunningAverage(perSecondByModel[modelName]!!, cycleStart)
                    val methods = methodsByModel[modelName].orEmpty()
                    _uiState.update { state ->
                        val perModelSlices = state.perSecondResultsByModel.toMutableMap()
                        val list: MutableList<ClassificationResult?> =
                            perModelSlices[modelName]?.toMutableList()
                                ?: MutableList(totalClips) { null }
                        if (i < list.size) list[i] = cr
                        perModelSlices[modelName] = list
                        val live = state.liveResultsByModel[modelName].orEmpty().toMutableMap()
                        if (LongSubMode.FAST in methods) live[LongSubMode.FAST] = cr
                        if (LongSubMode.AVERAGE in methods) live[LongSubMode.AVERAGE] = running
                        // Keep the legacy perSecondResults / runningAverageResult
                        // wired to the primary 1 s-model so any code still reading
                        // them keeps working.
                        val primarySlices = if (modelName == primaryName && primaryIs1s) {
                            val p = state.perSecondResults.toMutableList()
                            if (i < p.size) p[i] = cr
                            p
                        } else state.perSecondResults
                        val primaryRunning = if (modelName == primaryName && primaryIs1s) running
                        else state.runningAverageResult
                        state.copy(
                            perSecondResultsByModel = perModelSlices,
                            liveResultsByModel = state.liveResultsByModel + (modelName to live),
                            perSecondResults = primarySlices,
                            runningAverageResult = primaryRunning
                        )
                    }
                    delay(60L)
                }
            }
        }

        // Lock in the final values for every 1 s-model: FAST = last 1 s
        // inference (live tip), AVERAGE = mean over all 10 1 s inferences.
        for ((modelName, clips) in perSecondByModel) {
            if (clips.isEmpty()) continue
            val methods = methodsByModel[modelName].orEmpty()
            val avg = computeRunningAverage(clips, cycleStart)
            if (LongSubMode.FAST in methods) {
                perModel.getOrPut(modelName) { mutableMapOf() }[LongSubMode.FAST] = clips.last()
            }
            if (LongSubMode.AVERAGE in methods) {
                perModel.getOrPut(modelName) { mutableMapOf() }[LongSubMode.AVERAGE] = avg
            }
        }

        // 10 s-models: one inference on the full 10 s buffer, tagged STANDARD.
        for ((modelName, inf) in tenSecModels) {
            if (!isRunning) break
            val r = withContext(mlDispatcher) { inf.infer(samples, RecordingMode.STANDARD) } ?: continue
            perModel.getOrPut(modelName) { mutableMapOf() }[LongSubMode.STANDARD] = r
            _uiState.update { state ->
                val live = state.liveResultsByModel[modelName].orEmpty() + (LongSubMode.STANDARD to r)
                state.copy(liveResultsByModel = state.liveResultsByModel + (modelName to live))
            }
        }

        if (perModel.isEmpty()) return null
        accumulateAggregate(perModel)
        accumulateSessionVolume(volume.mean)

        // History/CSV per-second clips come from the primary 1 s-model only
        // (matches the previous behaviour). For a 10 s primary the field is
        // null because per-slice predictions don't exist for that model.
        val perSecondClips: List<PerSecondClip>? = if (primaryIs1s) {
            perSecondByModel[primaryName]?.mapIndexed { idx, cr ->
                PerSecondClip(
                    clipIndex = idx,
                    sceneClass = cr.sceneClass,
                    confidence = cr.confidence,
                    allProbabilities = cr.allProbabilities,
                    volumeMean = null,
                    volumePeak = null
                )
            }
        } else null
        return CycleOutcome(
            perModel = perModel,
            volume = volume,
            perSecondClips = perSecondClips,
            perSecondVolumes = padToTen(volume.perSecondMeans)
        )
    }

    /**
     * Drives the AudioRecorder for one cycle. The cycle occupies slot
     * [slotIndex] of [slotCount] inside the current 10 s frame, so this routine
     * also drives `frameElapsedMs` (and `recordingProgress` as the same value
     * expressed in 0..1). For non-FAST modes the call is just (0, 1) — one
     * slot = the whole frame.
     *
     * Creates and parks a fresh AudioRecorder (the previous one is released
     * first to avoid leaking native AudioRecord references — Android limits
     * how many can be open at once).
     */
    private suspend fun recordCycleAudio(
        mode: RecordingMode,
        slotIndex: Int = 0,
        slotCount: Int = 1
    ): FloatArray? {
        val durationSeconds = mode.durationSeconds
        audioRecorder.stopRecording()
        audioRecorder = AudioRecorder(durationSeconds = durationSeconds)
        startVolumeObservation()

        val slotStartMs = (slotIndex * 10_000L) / slotCount
        val slotSpanMs = 10_000L / slotCount

        _uiState.update { it.copy(
            appState = AppState.Recording(durationSeconds),
            recordingProgress = slotIndex.toFloat() / slotCount,
            frameElapsedMs = slotStartMs
        ) }
        var captured: FloatArray? = null
        // CancellationException is intentionally NOT caught here — it has to bubble
        // up so the launched recordingJob terminates. Earlier code swallowed it
        // and returned null, which let the outer loop spin against `continue` on
        // every cancellation and never exit (race with the next session start).
        audioRecorder.startRecording().collect { state ->
            when (state) {
                is RecordingState.Started -> Unit
                is RecordingState.Progress -> {
                    val remaining = (durationSeconds * (1f - state.progress)).toInt()
                    val frameMs = slotStartMs + (state.progress * slotSpanMs).toLong()
                    _uiState.update { it.copy(
                        appState = AppState.Recording(remaining),
                        recordingProgress = (slotIndex + state.progress) / slotCount,
                        frameElapsedMs = frameMs
                    ) }
                }
                is RecordingState.Completed -> captured = state.samples
                is RecordingState.Error -> _uiState.update { it.copy(
                    appState = AppState.Error(state.message),
                    errorMessage = state.message
                ) }
            }
        }
        return captured
    }

    /**
     * Interval pause loop — clip-accurate, watches isPaused / isRunning and the
     * session-timer's external soft-stop signal.
     *
     * At entry the frame just closed — clear `frameElapsedMs` so the volume
     * chart and inner ring read empty during the planned pause. Also let any
     * user-Pause-pending become active here, since the recording frame is done.
     */
    private suspend fun runIntervalPause(pauseMs: Long) {
        _uiState.update { it.copy(frameElapsedMs = 0L, recordingProgress = 0f) }
        activatePauseAtFrameEnd()
        var remaining = pauseMs
        while (remaining > 0 && isRunning) {
            // Pressing Pause during the planned interval-pause also takes
            // effect at the next half-second tick — no frame to wait on here.
            activatePauseAtFrameEnd()
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
        // Headline method for the record: FAST for 1 s-models, STANDARD for
        // 10 s-models. Any other methods land in [longSubs].
        val primarySub = ModelTrainingDuration.primaryMethodFor(primaryName)
        val primaryResult = primaryRow[primarySub] ?: primaryRow.values.firstOrNull() ?: return

        val mode = effectiveModeFor(config)
        val battery = getBatteryLevel()
        val top3 = primaryResult.getTopPredictions(3)

        // Every cycle ran the multi-method long path now, so persist every
        // (model × method) result. The per-second clips ride on the primary
        // model's AVG entry, matching the History detail view's contract.
        val longSubs: List<LongSubResult>? = run {
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

        // Multi-Model continuous: persist as AllInOneResults so the existing CSV
        // dynamic columns and History dialog rendering keep working.
        val allInOne: List<AllInOneResult>? = if (
            config.category == RecordingCategory.CONTINUOUS && config.modelNames.size >= 2
        ) {
            outcome.perModel.entries.mapNotNull { (name, methods) ->
                val primary = ModelTrainingDuration.primaryMethodFor(name)
                val res = methods[primary] ?: methods.values.firstOrNull() ?: return@mapNotNull null
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
            volumePeak = outcome.volume.peak,
            modelsSelected = config.modelNames,
            recordingCategory = config.category,
            // Mirror the wizard snapshot 1:1 — both maps are per-model sets; Interval
            // also picks methods per model since the May 2026 wizard rework.
            continuousMethodsByModel = if (config.category == RecordingCategory.CONTINUOUS) {
                config.continuousMethodsByModel.takeIf { it.isNotEmpty() }
            } else null,
            intervalMethodsByModel = if (config.category == RecordingCategory.INTERVAL) {
                config.intervalMethodsByModel.takeIf { it.isNotEmpty() }
            } else null,
            sessionDurationPlanned = config.sessionDuration,
            perSecondVolumes = outcome.perSecondVolumes
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
        pausePending = false
        pendingAutoResumeMs = null
        autoResumeJob?.cancel()
        autoResumeJob = null
        recordingJob?.cancel()
        sessionTimerJob?.cancel()
        audioRecorder.stopRecording()
    }

    /**
     * Releases the native PyTorch modules of the parked session and resets UI
     * state. Safe to call multiple times. Called from the Results Summary
     * (when the user navigates away) and from the Live Recording screen
     * (when the user backs out without ever starting).
     */
    fun clearSessionResults() {
        activeConfig = null
        for (inf in inferencesByName.values) {
            inf.release()
        }
        inferencesByName = LinkedHashMap()
        _uiState.update { UiState() }
    }

    /**
     * Requests a pause. Doesn't pause immediately — the recording loop has to
     * finish the current 10 s frame first, so the inner ring + volume chart
     * close cleanly at 10 s instead of mid-cycle. Once the frame closes, the
     * recording loop calls [activatePauseAtFrameEnd] and the real pause begins
     * (status label countdown starts ticking, auto-resume job starts).
     *
     * If [autoResumeAfterMs] is non-null, an auto-resume timer fires that long
     * AFTER the real pause has activated (not from the moment the button was
     * pressed). Null = indefinite pause.
     *
     * If a frame is not currently running (e.g. the user pressed Pause during
     * an interval-pause), the loop activates the pause at its next gate check.
     */
    fun pauseSession(autoResumeAfterMs: Long? = null) {
        if (!isRunning || isPaused || pausePending) return
        pausePending = true
        pendingAutoResumeMs = autoResumeAfterMs
        _uiState.update { it.copy(
            pausePending = true,
            pauseTotalMs = autoResumeAfterMs
        ) }
        // Real pause is wired up by activatePauseAtFrameEnd() after the frame
        // closes. Synthetic PAUSE record is written when the user resumes.
    }

    /**
     * Called by the recording loop at frame boundaries (and at the top of the
     * interval-pause loop) so that a pending pause-request kicks in cleanly
     * without breaking the middle of a cycle.
     */
    private fun activatePauseAtFrameEnd() {
        if (!pausePending || isPaused) return
        pausePending = false
        isPaused = true
        sessionElapsedAtPauseMs += SystemClock.elapsedRealtime() - sessionResumeWallClockMs
        pauseStartedWallClockMs = SystemClock.elapsedRealtime()
        val autoResumeMs = pendingAutoResumeMs
        pauseStartedAutoResumeMin = autoResumeMs?.let { (it / 60_000L).toInt() }
        val deadline = autoResumeMs?.let { SystemClock.elapsedRealtime() + it }
        _uiState.update { it.copy(
            isPaused = true,
            pausePending = false,
            userPauseDeadlineElapsedMs = deadline,
            pauseTotalMs = autoResumeMs
        ) }
        autoResumeJob?.cancel()
        autoResumeJob = if (autoResumeMs != null) {
            viewModelScope.launch {
                delay(autoResumeMs)
                if (isPaused && isRunning) resumeSession()
            }
        } else null
        pendingAutoResumeMs = null
    }

    private var pauseStartedWallClockMs: Long = 0L
    private var autoResumeJob: Job? = null
    // Picker value (in minutes) that the user chose for the currently active
    // pause — captured at the moment the real pause activates so the synthetic
    // PAUSE record can carry it. null = "no timer" (indefinite pause).
    private var pauseStartedAutoResumeMin: Int? = null

    fun resumeSession() {
        if (!isRunning) return
        // If the user hits Resume while the pause is still only pending (frame
        // hadn't closed yet), just cancel the pending state without writing a
        // synthetic Pause record — no actual pause time elapsed.
        if (pausePending && !isPaused) {
            pausePending = false
            pendingAutoResumeMs = null
            _uiState.update { it.copy(
                pausePending = false,
                pauseTotalMs = null,
                userPauseDeadlineElapsedMs = null
            ) }
            return
        }
        if (!isPaused) return
        autoResumeJob?.cancel()
        autoResumeJob = null
        val pauseDurationMs = SystemClock.elapsedRealtime() - pauseStartedWallClockMs
        if (pauseDurationMs >= 1000L) {
            persistPauseRecord(pauseDurationMs)
        }
        isPaused = false
        sessionResumeWallClockMs = SystemClock.elapsedRealtime()
        _uiState.update { state ->
            state.copy(
                isPaused = false,
                userPauseDeadlineElapsedMs = null,
                pauseTotalMs = null,
                sessionPausedMs = state.sessionPausedMs + pauseDurationMs.coerceAtLeast(0L)
            )
        }
    }

    private fun persistPauseRecord(durationMs: Long) {
        val config = activeConfig
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
            modelName = config?.modelNames?.firstOrNull().orEmpty(),
            isPause = true,
            pauseDurationSec = durationMs / 1000L,
            // Volume is zero for the duration of the pause — explicit zeros so
            // the CSV stays numeric instead of mixing empty cells in.
            volumeMean = 0f,
            volumePeak = 0f,
            perSecondVolumes = FloatArray(10),
            // Carry the same session config the surrounding regular records carry,
            // so a PAUSE row can be filtered/aggregated together with its session.
            modelsSelected = config?.modelNames,
            recordingCategory = config?.category,
            continuousMethodsByModel = if (config?.category == RecordingCategory.CONTINUOUS) {
                config.continuousMethodsByModel.takeIf { it.isNotEmpty() }
            } else null,
            intervalMethodsByModel = if (config?.category == RecordingCategory.INTERVAL) {
                config.intervalMethodsByModel.takeIf { it.isNotEmpty() }
            } else null,
            sessionDurationPlanned = config?.sessionDuration,
            longIntervalMinutes = config?.intervalPause?.pauseMinutes,
            pauseAutoResumeMin = pauseStartedAutoResumeMin
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
        // Don't release ModelInference modules here — viewModelScope is cancelled
        // but a native Module.forward() that's already running won't observe
        // cancellation, and destroy() during forward() can crash. Process death
        // (or GC) reclaims the native handles. Explicit user-driven exits
        // (back-from-Live, exit-from-Results) call clearSessionResults() while
        // we know no inference is in flight.
        mlDispatcher.close()
    }

    /**
     * Internal: result of one full recording cycle, ready to be persisted and
     * accumulated into the session aggregates.
     *
     * `perSecondVolumes` is always length 10, padded with 0 for short cycles
     * (FAST: only s1 carries data) and assembled from the per-slice means for
     * AVERAGE. Lives on the outcome so persistCycle doesn't have to re-derive
     * it from the volume stats.
     */
    private data class CycleOutcome(
        val perModel: Map<String, Map<LongSubMode, ClassificationResult>>,
        val volume: AudioRecorder.VolumeStats,
        val perSecondClips: List<PerSecondClip>?,
        val perSecondVolumes: FloatArray
    )
}
