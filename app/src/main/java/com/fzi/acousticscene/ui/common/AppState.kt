package com.fzi.acousticscene.ui.common

import com.fzi.acousticscene.model.ClassificationResult
import com.fzi.acousticscene.model.LongInterval
import com.fzi.acousticscene.model.LongSubMode
import com.fzi.acousticscene.model.RecordingMode
import com.fzi.acousticscene.model.SceneClass
import com.fzi.acousticscene.model.SessionConfig

/**
 * Pending evaluation that sits in the UI as a persistent "Rate" card/button
 * while the user is inside the app. Expires after 5 min (see deadlineElapsed).
 */
data class PendingEvaluation(
    val predictionId: Long,
    val modelClass: SceneClass,
    val deadlineElapsedMs: Long // SystemClock.elapsedRealtime()-based
)

/**
 * Sealed Class für App-Zustände
 */
sealed class AppState {
    /**
     * Idle: App ist bereit, aber noch nicht gestartet
     */
    object Idle : AppState()
    
    /**
     * Loading: Model wird geladen
     */
    object Loading : AppState()
    
    /**
     * Ready: Model geladen, bereit zum Starten
     */
    object Ready : AppState()
    
    /**
     * Recording: Audio wird aufgezeichnet
     * @param secondsRemaining Verbleibende Sekunden bis Ende der Aufnahme
     */
    data class Recording(val secondsRemaining: Int) : AppState()

    /**
     * Paused: Pause zwischen Aufnahmen (nur bei LONG-Modus)
     * @param minutesRemaining Verbleibende Minuten bis zur nächsten Aufnahme
     */
    data class Paused(val secondsRemaining: Int) : AppState()

    /**
     * UserPaused: User explicitly paused the LONG-mode loop. Mirrors `Paused`
     * (kept separate so UI can show a different label).
     */
    data class UserPaused(val secondsRemaining: Int) : AppState()

    /**
     * Processing: Inferenz läuft
     */
    object Processing : AppState()
    
    /**
     * Error: Fehler aufgetreten
     * @param message Fehlermeldung
     */
    data class Error(val message: String) : AppState()
}

/**
 * UI State für MainActivity
 * @param appState Der aktuelle App-Zustand
 * @param currentResult Das aktuelle Klassifikations-Ergebnis
 * @param history Liste der letzten Klassifikationen (max. 5)
 * @param totalClassifications Anzahl der durchgeführten Klassifikationen
 * @param averageInferenceTime Durchschnittliche Inferenz-Zeit in ms
 * @param isModelLoaded Ob das Model geladen ist
 * @param recordingProgress Fortschritt der Aufnahme (0.0 - 1.0)
 * @param currentVolume Aktuelle Lautstärke (0.0 - 1.0) für Visualisierung
 */
data class UiState(
    val appState: AppState = AppState.Idle,
    val currentResult: ClassificationResult? = null,
    val history: List<ClassificationResult> = emptyList(),
    val totalClassifications: Int = 0,
    val averageInferenceTime: Long = 0L,
    val isModelLoaded: Boolean = false,
    val errorMessage: String? = null,
    val recordingMode: RecordingMode = RecordingMode.STANDARD,
    val recordingProgress: Float = 0f,
    val currentVolume: Float = 0f,
    val perSecondResults: List<ClassificationResult?> = List(10) { null },
    val runningAverageResult: ClassificationResult? = null,
    // Per-model 1 s slice results streamed during a Continuous/Interval cycle.
    // Drives the "Show Live Data" circle row for every 1 s-trained model with
    // AVG active. Each list is fixed size 10; entries null until that slot's
    // inference lands.
    val perSecondResultsByModel: Map<String, List<ClassificationResult?>> = emptyMap(),
    // Total wall-clock paused time across the active session, in ms. Used by
    // the Session-Ended screen to show "Paused for X" alongside the recording
    // duration.
    val sessionPausedMs: Long = 0L,
    val pendingEvaluation: PendingEvaluation? = null,
    // Anti-bias blinding (interval mode only). True from the start of a cycle
    // that the rating quota selected for a "Rate now" prompt until that rating
    // resolves: submitted/skipped (EvaluationPromptBus), 5-min window expiry,
    // or session end. While true the live screen must not render any
    // class-bearing UI for the current cycle (model-card bars, per-second
    // slice colors, all models) — otherwise the subject can peek at the
    // prediction before rating. Volume, timers and device metrics stay live;
    // they carry no class information. The persisted-but-blind record is
    // identified by [pendingEvaluation]'s predictionId — History excludes it
    // until the rating resolves.
    val blindCycleActive: Boolean = false,
    // Repository id of the record the active blind belongs to. null while the
    // blinded cycle is still recording (nothing persisted yet). Resolution
    // paths (dismissal, expiry) must only lift the blind when their prediction
    // id matches this — an expiring OLD rating must not unblind a NEWER cycle
    // that is already in flight.
    val blindPredictionId: Long? = null,
    val isPaused: Boolean = false,
    // When the user pauses with a timer, this holds the SystemClock.elapsedRealtime()
    // deadline at which the session should auto-resume. null = indefinite pause.
    val userPauseDeadlineElapsedMs: Long? = null,
    // User pressed Pause but the active 10 s frame is still finishing. Recording
    // keeps running, the status label shows the chosen pause time *frozen*. Real
    // pause kicks in only when the frame closes.
    val pausePending: Boolean = false,
    // The pause duration the user picked, so the UI can show it static while
    // pausePending = true (deadline is only set once the real pause starts).
    val pauseTotalMs: Long? = null,
    // 0–10 000 wall-clock position inside the current 10 s frame. Drives the
    // inner stopwatch ring AND the volume chart from one source so they can't
    // drift apart.
    val frameElapsedMs: Long = 0L,
    // 1 (single arc) for STANDARD / INTERVAL, 10 (per-second segments) for FAST
    // / AVERAGE. Read by the stopwatch view.
    val frameSegments: Int = 1,
    // Multi-Model Evaluation (LONG mode): per-model sub-mode selection.
    // Key = model filename, value = checked sub-modes for that model. The locked
    // default (Fast for _1s_ models, Standard for the rest) is always part of the set.
    val selectedLongSubsByModel: Map<String, Set<LongSubMode>> = emptyMap(),
    // LONG mode (Dev Mode): user-chosen pause interval between 10 s recordings.
    // null = the user has not picked an interval yet — must be set explicitly via the picker
    // before the LONG mode can start. Not persisted across app launches.
    val selectedLongInterval: LongInterval? = null,
    // Multi-Model Evaluation: per-(model, sub-mode) live result, keyed first by model.
    val longSubResultsByModel: Map<String, Map<LongSubMode, ClassificationResult>> = emptyMap(),
    // ALL IN ONE: model-name-keyed live result, filled as each model finishes inferring.
    // Empty map = single-model session (regular dev / user mode).
    val allInOneResults: Map<String, ClassificationResult> = emptyMap(),
    // Fixed list of model filenames currently selected in ALL IN ONE mode (drives UI order).
    val allInOneModelNames: List<String> = emptyList(),
    // The active session's config (set when applySessionConfig is called, cleared on stop).
    // Drives the live UI: model order, methods per model, expected cycle/session length.
    val sessionConfig: SessionConfig? = null,
    // Live result of every (model, method) combination for the current cycle. Keyed by model
    // name, value is a per-method snapshot. Replaces longSubResultsByModel for the new flow.
    val liveResultsByModel: Map<String, Map<LongSubMode, ClassificationResult>> = emptyMap(),
    // Per-(model, method) running aggregate over the entire session. Drives the Results
    // Summary screen and the Live UI's bar charts (mean over all cycles, not last cycle).
    val aggregateResultsByModel: Map<String, Map<LongSubMode, ClassificationResult>> = emptyMap(),
    // Per-(model, method) cycle counter — how many cycles each combination has produced
    // a result for. Shown on the Results Summary card.
    val cycleCountByModelMethod: Map<Pair<String, LongSubMode>, Int> = emptyMap(),
    // Per-(model, method) top-class histogram: how many cycles each scene class
    // was the top-1 prediction. Drives the "Die meist erwähnte Klasse"-line on
    // the Results Summary — that's the count-based mode, separate from the
    // probability-mean in [aggregateResultsByModel].
    val topClassCountByModelMethod: Map<Pair<String, LongSubMode>, Map<SceneClass, Int>> = emptyMap(),
    // Wall-clock elapsed time of the active session in ms (excluding pauses).
    val sessionElapsedMs: Long = 0L,
    // Average volume (mean) accumulated across every cycle's mean — shown on Results Summary.
    val sessionVolumeMean: Float = 0f,
    val sessionVolumeMeanSampleCount: Int = 0,
    // Compute (inference) time, in ms. [lastCycleComputeMs] is the wall-clock the
    // last cycle spent on mel + inference (i.e. the gap on top of the 10 s
    // recording); [sessionComputeMs] is the running sum across the session. Both
    // are surfaced on the live recording screen.
    val lastCycleComputeMs: Long = 0L,
    val sessionComputeMs: Long = 0L
)

/**
 * Lifts the anti-bias blind if — and only if — it belongs to [predictionId].
 * A resolving OLD rating (dismissal or 5-min expiry arriving late) must never
 * unblind a newer cycle that is already in flight (blindPredictionId == null)
 * or pending under a different id.
 */
fun UiState.withBlindResolved(predictionId: Long): UiState =
    if (blindCycleActive && blindPredictionId == predictionId) {
        copy(blindCycleActive = false, blindPredictionId = null)
    } else this