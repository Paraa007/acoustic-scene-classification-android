package com.fzi.acousticscene.ui

import com.fzi.acousticscene.model.ClassificationResult
import com.fzi.acousticscene.model.LongSubMode
import com.fzi.acousticscene.model.RecordingMode
import com.fzi.acousticscene.model.SceneClass

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
    val pendingEvaluation: PendingEvaluation? = null,
    val isPaused: Boolean = false,
    val selectedLongSubs: Set<LongSubMode> = setOf(LongSubMode.STANDARD),
    val longSubResults: Map<LongSubMode, ClassificationResult?> = emptyMap()
)