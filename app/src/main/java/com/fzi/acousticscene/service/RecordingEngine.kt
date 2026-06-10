package com.fzi.acousticscene.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.fzi.acousticscene.R
import com.fzi.acousticscene.audio.AudioRecorder
import com.fzi.acousticscene.audio.RecordingState
import com.fzi.acousticscene.data.ActiveSessionRegistry
import com.fzi.acousticscene.data.ActiveSessionStore
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
import com.fzi.acousticscene.ui.common.AppState
import com.fzi.acousticscene.ui.live.EvaluationActivity
import com.fzi.acousticscene.ui.live.EvaluationPromptBus
import com.fzi.acousticscene.ui.common.PendingEvaluation
import com.fzi.acousticscene.ui.common.UiState
import com.fzi.acousticscene.ui.common.withBlindResolved
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

    // "Rate now" quota for the running interval session. Rebuilt on every
    // start(); 100 % behaves exactly like the pre-quota app. Drawn exactly
    // once per interval cycle, at CYCLE START — the decision must exist before
    // recording begins so a will-prompt cycle is blinded from its first
    // streamed per-second slice (see UiState.blindCycleActive).
    private var ratingQuota = RatingQuotaSchedule(100)

    // True while the running session is snapshotted in [ActiveSessionStore]
    // (interval + calendar end date). Cleared together with the store.
    @Volatile private var trackInterruption = false

    init {
        updateStatistics()
        scope.launch {
            EvaluationPromptBus.dismissals.collect { dismissedId ->
                state.update { s ->
                    if (s.pendingEvaluation?.predictionId == dismissedId) {
                        // Rating resolved (submit/skip/timeout in the activity)
                        // → lift the anti-bias blind, the live screen may show
                        // the cycle's prediction again.
                        s.withBlindResolved(dismissedId).copy(pendingEvaluation = null)
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

        // Recovery restart? Keep the original session identity so the resumed
        // cycles land in the same session block as the pre-crash ones.
        val resume = RecordingEngineHolder.pendingResume
        RecordingEngineHolder.pendingResume = null

        isRunning = true
        isPaused = false
        pausePending = false
        pendingAutoResumeMs = null
        stopReasonAuto = false
        pauseStartedAutoResumeMin = null
        sessionStartTime = resume?.originalSessionStartTime ?: System.currentTimeMillis()
        sessionResumeWallClockMs = SystemClock.elapsedRealtime()
        sessionElapsedAtPauseMs = 0L
        ratingQuota = RatingQuotaSchedule(config.ratingPercent)

        // A deliberate new start supersedes any stale interruption snapshot.
        ActiveSessionStore.clear(context)
        trackInterruption = config.category == RecordingCategory.INTERVAL &&
                config.sessionDuration.endDateMillis != null
        if (trackInterruption) {
            ActiveSessionStore.save(
                context = context,
                config = config,
                sessionStartTime = sessionStartTime,
                plannedEndMillis = config.sessionDuration.endDateMillis!!,
                lastCycleTimestamp = System.currentTimeMillis()
            )
        }

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
                sessionComputeMs = 0L,
                pendingEvaluation = null,
                blindCycleActive = false,
                blindPredictionId = null
            )
        }

        // The downtime between the interruption and this restart goes into the
        // CSV as a regular synthetic pause row, so the session's timeline stays
        // gap-free for analysis.
        if (resume != null && resume.gapSec >= 1) {
            persistPauseRecord(resume.gapSec * 1000L)
        }

        sessionTimerJob?.cancel()
        sessionTimerJob = scope.launch {
            val total = config.sessionDuration.totalMs
            val endWallMs = config.sessionDuration.endDateMillis
            while (isActive && isRunning) {
                // Calendar deadline is wall clock — checked even while paused,
                // unlike the elapsed-based window below.
                if (endWallMs != null && System.currentTimeMillis() >= endWallMs) {
                    stopReasonAuto = true
                    isRunning = false
                    Log.d(TAG, "Session end date reached — soft stop scheduled")
                    break
                }
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
            // Device-metric snapshots for this cycle: app CPU time + wall time
            // at cycle start; the deltas after the cycle yield cpuUsagePercent.
            val cpuTimeAtCycleStartMs = Process.getElapsedCpuTime()
            val wallAtCycleStartMs = SystemClock.elapsedRealtime()
            // Quota gate, drawn BEFORE recording begins (one draw per interval
            // cycle): a cycle that will raise the rating prompt is blinded for
            // its entire lifetime, including the in-flight per-second slices —
            // deciding only after the cycle would leak the streamed results.
            // Quota-skipped cycles prompt nothing and render normally.
            val willPrompt = config.category == RecordingCategory.INTERVAL &&
                    ratingQuota.shouldPrompt()
            val frameSegments = frameSegmentsFor(config)
            state.update {
                // This update retargets the blind to the new cycle. If the
                // previous prompt cycle's rating is still open (interval pause
                // shorter than the 5-min window, or a late expiry job), its
                // results are still sitting in the live maps and would paint
                // the moment the old blind is dropped — wipe them so the
                // unrated prediction never surfaces. The pendingEvaluation
                // (rating card + History exclusion) stays untouched.
                val carryingBlind = it.blindCycleActive && it.blindPredictionId != null
                it.copy(
                    frameSegments = frameSegments,
                    frameElapsedMs = 0L,
                    recordingProgress = 0f,
                    blindCycleActive = willPrompt,
                    blindPredictionId = null,
                    liveResultsByModel = if (carryingBlind) emptyMap() else it.liveResultsByModel,
                    perSecondResultsByModel = if (carryingBlind) emptyMap() else it.perSecondResultsByModel,
                    perSecondResults = if (carryingBlind) List(10) { null } else it.perSecondResults,
                    runningAverageResult = if (carryingBlind) null else it.runningAverageResult
                )
            }

            val cycleResult: CycleOutcome? = when (config.category) {
                RecordingCategory.CONTINUOUS -> runContinuousLongCycle(config)
                RecordingCategory.INTERVAL -> runIntervalCycle(config)
            }

            val record: PredictionRecord? = if (cycleResult != null) {
                persistCycle(
                    config = config,
                    outcome = cycleResult,
                    cycleStartedAt = cycleStartedAt,
                    batteryTempC = getBatteryTemperatureC(),
                    cpuUsagePercent = computeCpuUsagePercent(cpuTimeAtCycleStartMs, wallAtCycleStartMs)
                )
            } else null
            if (cycleResult == null) {
                // Failed cycle: nothing was persisted, nothing to hide. The
                // quota draw is consumed regardless — exactly one per cycle.
                if (willPrompt) {
                    state.update { it.copy(blindCycleActive = false, blindPredictionId = null) }
                }
                continue
            }

            if (config.category == RecordingCategory.INTERVAL) {
                val pauseMs = config.intervalPause?.pauseMs ?: 0L
                val promptNow = willPrompt && record != null && isRunning && pauseMs > 0L
                if (promptNow) {
                    sendIntervalEvaluationNotification(record!!)
                } else if (willPrompt) {
                    // Drawn to prompt but the prompt can't fire (session just
                    // ended, persist failed, or zero-pause interval) — lift the
                    // blind, there is no rating to wait for.
                    state.update { it.copy(blindCycleActive = false, blindPredictionId = null) }
                }
                if (isRunning && pauseMs > 0L) {
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
        // Normal exit (user stop, soft stop, error) — drop the interruption
        // snapshot so no recovery prompt fires for a session that ended cleanly.
        if (trackInterruption) {
            trackInterruption = false
            ActiveSessionStore.clear(context)
        }
        pausePending = false
        pendingAutoResumeMs = null
        // Session end closes the rating window (the Results Summary reveals
        // everything anyway), so an unresolved blind/pending rating is lifted
        // here — otherwise the record would stay hidden in History forever.
        evalDismissJob?.cancel()
        evalDismissJob = null
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
                perSecondResultsByModel = emptyMap(),
                pendingEvaluation = null,
                blindCycleActive = false,
                blindPredictionId = null
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

    /** @return the persisted record, or null when no primary result was available. */
    private fun persistCycle(
        config: SessionConfig,
        outcome: CycleOutcome,
        cycleStartedAt: Long,
        batteryTempC: Float?,
        cpuUsagePercent: Float?
    ): PredictionRecord? {
        val primaryName = config.modelNames.first()
        val primaryRow = outcome.perModel[primaryName] ?: return null
        val primarySub = ModelTrainingDuration.primaryMethodFor(primaryName)
        val primaryResult = primaryRow[primarySub] ?: primaryRow.values.firstOrNull() ?: return null

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
            batteryTempC = batteryTempC,
            cpuUsagePercent = cpuUsagePercent,
            modelsSelected = config.modelNames,
            recordingCategory = config.category,
            continuousMethodsByModel = if (config.category == RecordingCategory.CONTINUOUS) {
                config.continuousMethodsByModel.takeIf { it.isNotEmpty() }
            } else null,
            intervalMethodsByModel = if (config.category == RecordingCategory.INTERVAL) {
                config.intervalMethodsByModel.takeIf { it.isNotEmpty() }
            } else null,
            sessionDurationPlanned = config.sessionDuration,
            ratingPercent = if (config.category == RecordingCategory.INTERVAL) {
                config.ratingPercent
            } else null,
            perSecondVolumes = outcome.perSecondVolumes,
            sessionMode = config.mode
        )
        predictionRepository.addPrediction(record)
        if (trackInterruption) {
            ActiveSessionStore.updateLastCycle(context, System.currentTimeMillis())
        }
        updateStatistics()

        // currentResult is updated even for blinded cycles: the live screen
        // uses its identity purely as a "new cycle landed" signal for the
        // battery/CPU metric charts, which carry no class information.
        state.update { it.copy(currentResult = primaryResult, errorMessage = null) }
        return record
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
            // batteryTempC / cpuUsagePercent stay null on pause records — a 0
            // would read as a real measurement (see PredictionRecord).
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
            ratingPercent = if (config?.category == RecordingCategory.INTERVAL) {
                config.ratingPercent
            } else null,
            sessionMode = config?.mode
        )
        predictionRepository.addPrediction(record)
    }

    private fun sendIntervalEvaluationNotification(record: PredictionRecord) {
        // pendingEvaluation is set on BOTH the foreground and the background
        // path: it drives the in-app rating card, marks the record as blinded
        // for History, and the dismiss job below guarantees the 5-min expiry
        // lifts the blind even if the user never opens the evaluation.
        val deadline = SystemClock.elapsedRealtime() + EvaluationActivity.EVALUATION_TIMEOUT_MS
        state.update {
            it.copy(
                pendingEvaluation = PendingEvaluation(
                    predictionId = record.id,
                    modelClass = record.sceneClass,
                    deadlineElapsedMs = deadline
                ),
                // The blind set at cycle start now belongs to this record.
                blindPredictionId = record.id
            )
        }
        evalDismissJob?.cancel()
        evalDismissJob = scope.launch {
            delay(EvaluationActivity.EVALUATION_TIMEOUT_MS)
            state.update { s ->
                if (s.pendingEvaluation?.predictionId == record.id) {
                    // Deadline reached without a rating — reveal everywhere.
                    s.withBlindResolved(record.id).copy(pendingEvaluation = null)
                } else s
            }
        }
        val isForeground = ProcessLifecycleOwner.get().lifecycle.currentState
            .isAtLeast(Lifecycle.State.STARTED)
        if (isForeground) return
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

    /** Skip path (live-screen card). Skipping resolves the rating → reveal. */
    fun dismissPendingEvaluation() {
        state.update { s ->
            val resolvedId = s.pendingEvaluation?.predictionId
            val base = if (resolvedId != null) s.withBlindResolved(resolvedId) else s
            base.copy(pendingEvaluation = null)
        }
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
     * Battery temperature in °C, read from the sticky ACTION_BATTERY_CHANGED
     * intent (EXTRA_TEMPERATURE carries tenths of a degree). Returns null when
     * the intent or the extra is unavailable (-1 sentinel or missing).
     */
    private fun getBatteryTemperatureC(): Float? = try {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        if (tenths == -1) null else tenths / 10f
    } catch (_: Exception) {
        null
    }

    /**
     * This app's own CPU usage across the cycle, in percent of wall time on a
     * single-core scale: delta of [Process.getElapsedCpuTime] (CPU time used by
     * this process across all its threads) divided by the elapsed wall time,
     * times 100. Because multiple threads can burn CPU in parallel, the value
     * can exceed 100 on multi-core devices. Returns null when the wall-time
     * delta is non-positive.
     */
    private fun computeCpuUsagePercent(cpuTimeAtStartMs: Long, wallAtStartMs: Long): Float? {
        val wallDeltaMs = SystemClock.elapsedRealtime() - wallAtStartMs
        if (wallDeltaMs <= 0L) return null
        val cpuDeltaMs = Process.getElapsedCpuTime() - cpuTimeAtStartMs
        return cpuDeltaMs * 100f / wallDeltaMs
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
