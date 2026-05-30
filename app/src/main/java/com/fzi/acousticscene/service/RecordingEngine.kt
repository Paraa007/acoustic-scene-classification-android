package com.fzi.acousticscene.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.fzi.acousticscene.R
import com.fzi.acousticscene.audio.AudioRecorder
import com.fzi.acousticscene.audio.RecordingState
import com.fzi.acousticscene.data.ActiveSessionRegistry
import com.fzi.acousticscene.data.PredictionRepository
import com.fzi.acousticscene.data.PredictionStatistics
import com.fzi.acousticscene.data.RecordingEngineHolder
import com.fzi.acousticscene.ml.ModelInference
import com.fzi.acousticscene.model.AllInOneResult
import com.fzi.acousticscene.model.ClassificationResult
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
import com.fzi.acousticscene.ui.AppState
import com.fzi.acousticscene.ui.EvaluationActivity
import com.fzi.acousticscene.ui.EvaluationPromptBus
import com.fzi.acousticscene.ui.PendingEvaluation
import com.fzi.acousticscene.ui.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
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
import java.util.concurrent.Executors

/**
 * Owns the entire recording-loop machinery — audio capture, inference, live
 * state emission, persistence. Lives inside the foreground
 * [ClassificationService] so the loop survives even when Android kills the
 * hosting activity (the original failure mode: screen off → activity destroyed
 * → recording dies).
 *
 * The engine is the only writer into [RecordingEngineHolder.uiState]. ViewModel
 * and fragments read it through that singleton.
 *
 * Lifecycle: created once by the service in onCreate, released in onDestroy.
 * One session at a time, controlled via [applyConfig] → [start] → [stop],
 * with [pause] / [resume] in between.
 */
class RecordingEngine(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "RecordingEngine"
        private const val EVALUATION_CHANNEL_ID = "evaluation_channel"
        private const val EVALUATION_NOTIFICATION_ID = 2
    }

    private val predictionRepository = PredictionRepository.getInstance(context)
    private val mlDispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()

    private val _statistics = MutableStateFlow(PredictionStatistics())
    val statistics: StateFlow<PredictionStatistics> = _statistics.asStateFlow()

    private val _totalPredictionsCount = MutableStateFlow(0)
    val totalPredictionsCount: StateFlow<Int> = _totalPredictionsCount.asStateFlow()

    // Inference pool — one entry per model in the session.
    private var inferencesByName: LinkedHashMap<String, ModelInference> = LinkedHashMap()
    private var modelClassCount: Int = 9

    @Volatile private var activeConfig: SessionConfig? = null
    private var sessionStartTime: Long = 0L
    private var sessionElapsedAtPauseMs: Long = 0L
    private var sessionResumeWallClockMs: Long = 0L

    private var recordingJob: Job? = null
    private var sessionTimerJob: Job? = null
    private var volumeJob: Job? = null
    private var autoResumeJob: Job? = null
    private var evalDismissJob: Job? = null
    private var audioRecorder: AudioRecorder = AudioRecorder(durationSeconds = 10)

    @Volatile private var isRunning = false
    @Volatile private var isPaused = false
    @Volatile private var pausePending = false
    private var pendingAutoResumeMs: Long? = null
    @Volatile private var stopReasonAuto = false

    private var pauseStartedWallClockMs: Long = 0L
    private var pauseStartedAutoResumeMin: Int? = null

    init {
        updateStatistics()
        scope.launch {
            EvaluationPromptBus.dismissals.collect { dismissedId ->
                state.update { s ->
                    if (s.pendingEvaluation?.predictionId == dismissedId) {
                        s.copy(pendingEvaluation = null)
                    } else s
                }
            }
        }
        startVolumeObservation()
    }

    private val state get() = RecordingEngineHolder.mutableUiState

    private fun startVolumeObservation() {
        volumeJob?.cancel()
        volumeJob = scope.launch {
            audioRecorder.volumeFlow.collect { volume ->
                state.update { it.copy(currentVolume = volume) }
            }
        }
    }

    private fun updateStatistics() {
        _statistics.value = predictionRepository.getStatistics()
        _totalPredictionsCount.value = predictionRepository.getCount()
    }

    fun currentSessionConfig(): SessionConfig? = activeConfig
    fun isClassifying(): Boolean = isRunning

    /**
     * Loads the picked models into memory and parks the resolved config until
     * [start] is called.
     */
    fun applyConfig(config: SessionConfig) {
        if (isRunning) {
            Log.w(TAG, "applyConfig called while running — ignored")
            return
        }
        activeConfig = config
        for (inf in inferencesByName.values) {
            inf.release()
        }
        inferencesByName = LinkedHashMap()
        for (name in config.modelNames) {
            val path = "${ModelConfig.DEV_MODELS_DIR}/$name"
            inferencesByName[name] = ModelInference(context, path)
        }
        modelClassCount = ModelConfig.getClassCountForModel(config.modelNames.first())
        state.update {
            it.copy(
                sessionConfig = config,
                appState = AppState.Loading,
                isModelLoaded = false,
                liveResultsByModel = emptyMap(),
                aggregateResultsByModel = emptyMap(),
                cycleCountByModelMethod = emptyMap(),
                topClassCountByModelMethod = emptyMap(),
                sessionElapsedMs = 0L,
                sessionVolumeMean = 0f,
                sessionVolumeMeanSampleCount = 0,
                allInOneModelNames = config.modelNames,
                allInOneResults = emptyMap()
            )
        }
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                inferencesByName.values.all { it.loadModel() }
            }
            state.update {
                if (ok) it.copy(appState = AppState.Ready, isModelLoaded = true, errorMessage = null)
                else it.copy(appState = AppState.Error("Failed to load model"), isModelLoaded = false)
            }
        }
    }

    fun start() {
        val config = activeConfig ?: run {
            Log.e(TAG, "start with no config — ignored")
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

        state.update {
            it.copy(
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
                topClassCountByModelMethod = emptyMap(),
                sessionVolumeMean = 0f,
                sessionVolumeMeanSampleCount = 0,
                allInOneResults = emptyMap(),
                lastCycleComputeMs = 0L,
                sessionComputeMs = 0L
            )
        }

        sessionTimerJob?.cancel()
        sessionTimerJob = scope.launch {
            val total = config.sessionDuration.totalMs
            while (isActive && isRunning) {
                if (!isPaused) {
                    val elapsed = sessionElapsedAtPauseMs +
                            (SystemClock.elapsedRealtime() - sessionResumeWallClockMs)
                    state.update { it.copy(sessionElapsedMs = elapsed) }
                    if (total != null && elapsed >= total) {
                        stopReasonAuto = true
                        isRunning = false
                        Log.d(TAG, "Session window elapsed — soft stop scheduled")
                        break
                    }
                }
                delay(500L)
            }
        }

        recordingJob = scope.launch {
            try {
                runSessionLoop(config)
            } catch (_: CancellationException) {
                Log.d(TAG, "Recording loop cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Session loop error", e)
                state.update {
                    it.copy(
                        appState = AppState.Error(e.message ?: "Unknown error"),
                        errorMessage = e.message ?: "Unknown error"
                    )
                }
            } finally {
                onSessionLoopExit()
            }
        }
    }

    private suspend fun runSessionLoop(config: SessionConfig) {
        while (isRunning) {
            currentCoroutineContext().ensureActive()
            activatePauseAtFrameEnd()
            while (isPaused && isRunning) {
                delay(300L)
            }
            if (!isRunning) break

            val cycleStartedAt = System.currentTimeMillis()
            val frameSegments = frameSegmentsFor(config)
            state.update {
                it.copy(
                    frameSegments = frameSegments,
                    frameElapsedMs = 0L,
                    recordingProgress = 0f
                )
            }

            val cycleResult: CycleOutcome? = when (config.category) {
                RecordingCategory.CONTINUOUS -> runContinuousLongCycle(config)
                RecordingCategory.INTERVAL -> runIntervalCycle(config)
            }

            if (cycleResult != null) {
                persistCycle(config, cycleResult, cycleStartedAt)
            }
            if (cycleResult == null) continue

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
        val hasOneSecModel = config.modelNames.any {
            ModelTrainingDuration.secondsForFilename(it) == 1
        }
        return if (hasOneSecModel) 10 else 1
    }

    private fun onSessionLoopExit() {
        val auto = stopReasonAuto
        Log.d(TAG, "Session loop exited (auto=$auto)")
        if (isRunning) isRunning = false
        sessionTimerJob?.cancel()
        audioRecorder.stopRecording()
        ActiveSessionRegistry.unregister()
        pausePending = false
        pendingAutoResumeMs = null
        state.update {
            it.copy(
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
            )
        }
    }

    private fun effectiveModeFor(config: SessionConfig): RecordingMode = when (config.category) {
        RecordingCategory.CONTINUOUS -> RecordingMode.STANDARD
        RecordingCategory.INTERVAL -> RecordingMode.LONG
    }

    private fun padToTen(src: FloatArray): FloatArray {
        val out = FloatArray(10)
        val n = minOf(src.size, 10)
        for (i in 0 until n) out[i] = src[i]
        return out
    }

    private suspend fun runIntervalCycle(config: SessionConfig): CycleOutcome? =
        runLongMultiMethodCycle(config, config.intervalMethodsByModel)

    private suspend fun runContinuousLongCycle(config: SessionConfig): CycleOutcome? =
        runLongMultiMethodCycle(config, config.continuousMethodsByModel)

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

        state.update { s ->
            val reset = s.perSecondResultsByModel.toMutableMap()
            for (name in oneSecModels.keys) reset[name] = List(totalClips) { null }
            s.copy(
                appState = AppState.Processing,
                liveResultsByModel = emptyMap(),
                perSecondResults = List(totalClips) { null },
                runningAverageResult = null,
                perSecondResultsByModel = reset
            )
        }

        val cycleStart = System.currentTimeMillis()
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
                    state.update { s ->
                        val perModelSlices = s.perSecondResultsByModel.toMutableMap()
                        val list: MutableList<ClassificationResult?> =
                            perModelSlices[modelName]?.toMutableList()
                                ?: MutableList(totalClips) { null }
                        if (i < list.size) list[i] = cr
                        perModelSlices[modelName] = list
                        val live = s.liveResultsByModel[modelName].orEmpty().toMutableMap()
                        if (LongSubMode.FAST in methods) live[LongSubMode.FAST] = cr
                        if (LongSubMode.AVERAGE in methods) live[LongSubMode.AVERAGE] = running
                        val primarySlices = if (modelName == primaryName && primaryIs1s) {
                            val p = s.perSecondResults.toMutableList()
                            if (i < p.size) p[i] = cr
                            p
                        } else s.perSecondResults
                        val primaryRunning = if (modelName == primaryName && primaryIs1s) running
                        else s.runningAverageResult
                        s.copy(
                            perSecondResultsByModel = perModelSlices,
                            liveResultsByModel = s.liveResultsByModel + (modelName to live),
                            perSecondResults = primarySlices,
                            runningAverageResult = primaryRunning
                        )
                    }
                }
            }
        }

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

        if (tenSecModels.isNotEmpty()) {
            // Every 10 s model runs on the same 10 s audio, so the log-mel
            // spectrogram (the expensive part) is identical — compute it once
            // and feed each model, instead of recomputing it per model.
            val sharedMel = withContext(mlDispatcher) {
                tenSecModels.values.first().computeLogMel(samples, RecordingMode.STANDARD)
            }
            for ((modelName, inf) in tenSecModels) {
                if (!isRunning) break
                val r = withContext(mlDispatcher) {
                    if (sharedMel != null) inf.inferFromLogMel(sharedMel)
                    else inf.infer(samples, RecordingMode.STANDARD)
                } ?: continue
                perModel.getOrPut(modelName) { mutableMapOf() }[LongSubMode.STANDARD] = r
                state.update { s ->
                    val live = s.liveResultsByModel[modelName].orEmpty() + (LongSubMode.STANDARD to r)
                    s.copy(liveResultsByModel = s.liveResultsByModel + (modelName to live))
                }
            }
        }

        if (perModel.isEmpty()) return null

        // Wall-clock spent on mel + inference this cycle (the gap on top of the
        // 10 s recording). Surfaced live and summed across the session.
        val computeMs = System.currentTimeMillis() - cycleStart
        state.update {
            it.copy(
                lastCycleComputeMs = computeMs,
                sessionComputeMs = it.sessionComputeMs + computeMs
            )
        }

        accumulateAggregate(perModel)
        accumulateSessionVolume(volume.mean)

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

        state.update {
            it.copy(
                appState = AppState.Recording(durationSeconds),
                recordingProgress = slotIndex.toFloat() / slotCount,
                frameElapsedMs = slotStartMs
            )
        }
        var captured: FloatArray? = null
        audioRecorder.startRecording().collect { rs ->
            when (rs) {
                is RecordingState.Started -> Unit
                is RecordingState.Progress -> {
                    val remaining = (durationSeconds * (1f - rs.progress)).toInt()
                    val frameMs = slotStartMs + (rs.progress * slotSpanMs).toLong()
                    state.update {
                        it.copy(
                            appState = AppState.Recording(remaining),
                            recordingProgress = (slotIndex + rs.progress) / slotCount,
                            frameElapsedMs = frameMs
                        )
                    }
                }
                is RecordingState.Completed -> captured = rs.samples
                is RecordingState.Error -> state.update {
                    it.copy(
                        appState = AppState.Error(rs.message),
                        errorMessage = rs.message
                    )
                }
            }
        }
        return captured
    }

    private suspend fun runIntervalPause(pauseMs: Long) {
        state.update { it.copy(frameElapsedMs = 0L, recordingProgress = 0f) }
        activatePauseAtFrameEnd()
        var remaining = pauseMs
        while (remaining > 0 && isRunning) {
            activatePauseAtFrameEnd()
            if (isPaused) {
                state.update { it.copy(appState = AppState.UserPaused((remaining / 1000L).toInt())) }
                delay(500L)
                continue
            }
            state.update { it.copy(appState = AppState.Paused((remaining / 1000L).toInt())) }
            delay(1000L)
            if (!isPaused) remaining -= 1000L
        }
    }

    private fun persistCycle(
        config: SessionConfig,
        outcome: CycleOutcome,
        cycleStartedAt: Long
    ) {
        val primaryName = config.modelNames.first()
        val primaryRow = outcome.perModel[primaryName] ?: return
        val primarySub = ModelTrainingDuration.primaryMethodFor(primaryName)
        val primaryResult = primaryRow[primarySub] ?: primaryRow.values.firstOrNull() ?: return

        val mode = effectiveModeFor(config)
        val battery = getBatteryLevel()
        val top3 = primaryResult.getTopPredictions(3)

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
            continuousMethodsByModel = if (config.category == RecordingCategory.CONTINUOUS) {
                config.continuousMethodsByModel.takeIf { it.isNotEmpty() }
            } else null,
            intervalMethodsByModel = if (config.category == RecordingCategory.INTERVAL) {
                config.intervalMethodsByModel.takeIf { it.isNotEmpty() }
            } else null,
            sessionDurationPlanned = config.sessionDuration,
            perSecondVolumes = outcome.perSecondVolumes,
            sessionMode = config.mode
        )
        predictionRepository.addPrediction(record)
        updateStatistics()

        state.update { it.copy(currentResult = primaryResult, errorMessage = null) }
    }

    private fun accumulateAggregate(
        perModel: Map<String, Map<LongSubMode, ClassificationResult>>
    ) {
        state.update { s ->
            val nextAgg = s.aggregateResultsByModel.toMutableMap()
            val nextCounts = s.cycleCountByModelMethod.toMutableMap()
            val nextTopHist = s.topClassCountByModelMethod.toMutableMap()
            for ((model, methods) in perModel) {
                val perMethodAgg = nextAgg[model]?.toMutableMap() ?: mutableMapOf()
                for ((sub, result) in methods) {
                    val key = model to sub
                    val priorCount = nextCounts[key] ?: 0
                    val merged = mergeClassificationResults(perMethodAgg[sub], result, priorCount)
                    perMethodAgg[sub] = merged
                    nextCounts[key] = priorCount + 1
                    val hist = nextTopHist[key]?.toMutableMap() ?: mutableMapOf()
                    hist[result.sceneClass] = (hist[result.sceneClass] ?: 0) + 1
                    nextTopHist[key] = hist
                }
                nextAgg[model] = perMethodAgg
            }
            s.copy(
                aggregateResultsByModel = nextAgg,
                cycleCountByModelMethod = nextCounts,
                topClassCountByModelMethod = nextTopHist
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
        state.update {
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

    fun stop() {
        if (!isRunning && recordingJob == null) return
        Log.d(TAG, "stop()")
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
     * state. Safe to call multiple times.
     */
    fun clearResults() {
        activeConfig = null
        for (inf in inferencesByName.values) {
            inf.release()
        }
        inferencesByName = LinkedHashMap()
        state.update { UiState() }
    }

    fun pause(autoResumeAfterMs: Long? = null) {
        if (!isRunning || isPaused || pausePending) return
        pausePending = true
        pendingAutoResumeMs = autoResumeAfterMs
        state.update {
            it.copy(
                pausePending = true,
                pauseTotalMs = autoResumeAfterMs
            )
        }
    }

    private fun activatePauseAtFrameEnd() {
        if (!pausePending || isPaused) return
        pausePending = false
        isPaused = true
        sessionElapsedAtPauseMs += SystemClock.elapsedRealtime() - sessionResumeWallClockMs
        pauseStartedWallClockMs = SystemClock.elapsedRealtime()
        val autoResumeMs = pendingAutoResumeMs
        pauseStartedAutoResumeMin = autoResumeMs?.let { (it / 60_000L).toInt() }
        val deadline = autoResumeMs?.let { SystemClock.elapsedRealtime() + it }
        state.update {
            it.copy(
                isPaused = true,
                pausePending = false,
                userPauseDeadlineElapsedMs = deadline,
                pauseTotalMs = autoResumeMs
            )
        }
        autoResumeJob?.cancel()
        autoResumeJob = if (autoResumeMs != null) {
            scope.launch {
                delay(autoResumeMs)
                if (isPaused && isRunning) resume()
            }
        } else null
        pendingAutoResumeMs = null
    }

    fun resume() {
        if (!isRunning) return
        if (pausePending && !isPaused) {
            pausePending = false
            pendingAutoResumeMs = null
            state.update {
                it.copy(
                    pausePending = false,
                    pauseTotalMs = null,
                    userPauseDeadlineElapsedMs = null
                )
            }
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
        state.update { s ->
            s.copy(
                isPaused = false,
                userPauseDeadlineElapsedMs = null,
                pauseTotalMs = null,
                sessionPausedMs = s.sessionPausedMs + pauseDurationMs.coerceAtLeast(0L)
            )
        }
    }

    private fun persistPauseRecord(durationMs: Long) {
        val config = activeConfig
        val record = PredictionRecord(
            timestamp = System.currentTimeMillis(),
            sessionStartTime = sessionStartTime,
            sceneClass = SceneClass.TRANSIT_VEHICLES,
            confidence = 0f,
            allProbabilities = FloatArray(modelClassCount),
            topPredictions = emptyList(),
            inferenceTimeMs = 0L,
            recordingMode = RecordingMode.STANDARD,
            batteryLevel = -1,
            modelName = config?.modelNames?.firstOrNull().orEmpty(),
            isPause = true,
            pauseDurationSec = durationMs / 1000L,
            volumeMean = 0f,
            volumePeak = 0f,
            perSecondVolumes = FloatArray(10),
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
            pauseAutoResumeMin = pauseStartedAutoResumeMin,
            sessionMode = config?.mode
        )
        predictionRepository.addPrediction(record)
    }

    private fun sendIntervalEvaluationNotification(outcome: CycleOutcome) {
        val record = predictionRepository.getAllPredictions().lastOrNull() ?: return
        val isForeground = ProcessLifecycleOwner.get().lifecycle.currentState
            .isAtLeast(Lifecycle.State.STARTED)
        if (isForeground) {
            val deadline = SystemClock.elapsedRealtime() + EvaluationActivity.EVALUATION_TIMEOUT_MS
            state.update {
                it.copy(
                    pendingEvaluation = PendingEvaluation(
                        predictionId = record.id,
                        modelClass = record.sceneClass,
                        deadlineElapsedMs = deadline
                    )
                )
            }
            evalDismissJob?.cancel()
            evalDismissJob = scope.launch {
                delay(EvaluationActivity.EVALUATION_TIMEOUT_MS)
                state.update { s ->
                    if (s.pendingEvaluation?.predictionId == record.id) {
                        s.copy(pendingEvaluation = null)
                    } else s
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
            .setContentText(context.getString(R.string.eval_blind_prompt))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setTimeoutAfter(EvaluationActivity.EVALUATION_TIMEOUT_MS)
            .build()
        notificationManager.notify(EVALUATION_NOTIFICATION_ID, notification)
    }

    fun dismissPendingEvaluation() {
        state.update { it.copy(pendingEvaluation = null) }
    }

    fun clearError() {
        state.update { it.copy(errorMessage = null, appState = AppState.Ready) }
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

    private fun getBatteryLevel(): Int = try {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (_: Exception) {
        -1
    }

    /**
     * Final teardown. Cancels the local scope-owned jobs and releases native
     * resources. The service calls this from onDestroy.
     */
    fun release() {
        stop()
        mlDispatcher.close()
    }

    private data class CycleOutcome(
        val perModel: Map<String, Map<LongSubMode, ClassificationResult>>,
        val volume: AudioRecorder.VolumeStats,
        val perSecondClips: List<PerSecondClip>?,
        val perSecondVolumes: FloatArray
    )
}
