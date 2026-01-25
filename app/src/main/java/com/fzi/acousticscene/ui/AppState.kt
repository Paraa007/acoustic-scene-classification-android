package com.fzi.acousticscene.ui

import com.fzi.acousticscene.model.ClassificationResult
import com.fzi.acousticscene.model.RecordingMode

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
    val recordingProgress: Float = 0f
)