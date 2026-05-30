package com.fzi.acousticscene.ui

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fzi.acousticscene.data.LastConfigStore
import com.fzi.acousticscene.data.PredictionRepository
import com.fzi.acousticscene.data.PredictionStatistics
import com.fzi.acousticscene.data.RecordingEngineHolder
import com.fzi.acousticscene.model.LongInterval
import com.fzi.acousticscene.model.ModelTrainingDuration
import com.fzi.acousticscene.model.PredictionRecord
import com.fzi.acousticscene.model.RecordingCategory
import com.fzi.acousticscene.model.SessionConfig
import com.fzi.acousticscene.model.SessionDuration
import com.fzi.acousticscene.model.WizardIntent
import com.fzi.acousticscene.model.WizardStep
import com.fzi.acousticscene.service.ClassificationService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * View-model thin wrapper around the wizard UI state and the recording engine
 * hosted by [ClassificationService]. The recording loop (audio capture +
 * inference) used to live in `viewModelScope`, which meant Android killing the
 * activity under memory pressure (screen off, Doze, OEM aggression) would also
 * kill the loop. Now the loop lives inside the foreground service so the
 * session survives activity destruction; this view-model just relays commands
 * and re-exposes the live [UiState] for fragments that bind via
 * `activityViewModels()`.
 *
 * Wizard state stays on the view-model — it's pure UI scratchpad, no
 * persistence beyond the in-memory `_wizard` flow.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "MainViewModel"
    }

    private val predictionRepository = PredictionRepository.getInstance(application)

    // ========================================================================
    // Wizard state — collected on the wizard pages, resolved into a SessionConfig
    // when the user confirms on the summary page.
    // ========================================================================

    private val _wizard = MutableStateFlow(WizardViewState())
    val wizard: StateFlow<WizardViewState> = _wizard.asStateFlow()

    fun resetWizard(
        availableModels: List<String>,
        prefill: SessionConfig? = null,
        intent: WizardIntent = WizardIntent.StartRecording
    ) {
        val startOnSummary =
            intent is WizardIntent.QuickStart || intent is WizardIntent.QuickStartTest
        _wizard.value = if (prefill != null) {
            WizardViewState(
                step = if (startOnSummary) WizardStep.Summary else WizardStep.Models,
                availableModels = availableModels,
                selectedModels = prefill.modelNames.filter { it in availableModels },
                category = prefill.category,
                continuousMethodsByModel = prefill.continuousMethodsByModel,
                intervalPause = prefill.intervalPause,
                intervalMethodsByModel = prefill.intervalMethodsByModel,
                sessionDuration = prefill.sessionDuration,
                intent = intent
            )
        } else {
            WizardViewState(availableModels = availableModels, intent = intent)
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
    // Session lifecycle — delegated to the foreground service. The view-model
    // just sends the right intent; the actual work happens inside
    // [com.fzi.acousticscene.service.RecordingEngine].
    // ========================================================================

    /** Mirrors the engine's live state so fragments keep collecting from the VM. */
    val uiState: StateFlow<UiState> = RecordingEngineHolder.uiState

    private val _statistics = MutableStateFlow(PredictionStatistics())
    val statistics: StateFlow<PredictionStatistics> = _statistics.asStateFlow()

    private val _totalPredictionsCount = MutableStateFlow(0)
    val totalPredictionsCount: StateFlow<Int> = _totalPredictionsCount.asStateFlow()

    init {
        refreshStatistics()
    }

    private fun refreshStatistics() {
        _statistics.value = predictionRepository.getStatistics()
        _totalPredictionsCount.value = predictionRepository.getCount()
    }

    /**
     * Loads the picked models into memory (inside the service) and parks the
     * resolved config until [startSession] is called. Always promotes the
     * service to foreground so model loading happens with WakeLock + Doze
     * protection in place.
     */
    fun applySessionConfig(config: SessionConfig) {
        Log.d(TAG, "applySessionConfig → service")
        RecordingEngineHolder.pendingConfig = config
        sendServiceCommand(ClassificationService.ACTION_APPLY_CONFIG, foreground = true)
        LastConfigStore.save(getApplication(), config)
    }

    fun currentSessionConfig(): SessionConfig? = uiState.value.sessionConfig

    fun startSession() {
        Log.d(TAG, "startSession → service")
        sendServiceCommand(ClassificationService.ACTION_START, foreground = true)
    }

    fun stopSession() {
        Log.d(TAG, "stopSession → service")
        sendServiceCommand(ClassificationService.ACTION_STOP, foreground = false)
        // Once a session ends we recompute stats so any UI bound to them refreshes.
        viewModelScope.launch { refreshStatistics() }
    }

    fun pauseSession(autoResumeAfterMs: Long? = null) {
        Log.d(TAG, "pauseSession → service")
        RecordingEngineHolder.pendingPauseAutoResumeMs = autoResumeAfterMs
        sendServiceCommand(ClassificationService.ACTION_PAUSE, foreground = false)
    }

    fun resumeSession() {
        Log.d(TAG, "resumeSession → service")
        sendServiceCommand(ClassificationService.ACTION_RESUME, foreground = false)
    }

    fun clearSessionResults() {
        Log.d(TAG, "clearSessionResults → service")
        sendServiceCommand(ClassificationService.ACTION_CLEAR_RESULTS, foreground = false)
        refreshStatistics()
    }

    fun isClassifying(): Boolean {
        val app = uiState.value.appState
        return app is AppState.Recording || app is AppState.Processing ||
                app is AppState.Paused || app is AppState.UserPaused
    }

    fun dismissPendingEvaluation() {
        RecordingEngineHolder.mutableUiState.update { it.copy(pendingEvaluation = null) }
    }

    fun clearError() {
        RecordingEngineHolder.mutableUiState.update {
            it.copy(errorMessage = null, appState = AppState.Ready)
        }
    }

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
        refreshStatistics()
    }

    fun clearOldPredictions(days: Int) {
        predictionRepository.clearOlderThan(days)
        refreshStatistics()
    }

    private fun sendServiceCommand(action: String, foreground: Boolean) {
        val app = getApplication<Application>()
        val intent = Intent(app, ClassificationService::class.java).apply {
            this.action = action
        }
        try {
            if (foreground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
        } catch (e: Exception) {
            // If the foreground-service launch is rejected (background-start
            // restrictions, missing notification permission, …) fall back to a
            // plain startService so an already-foreground service still gets the
            // command. This is best-effort; without foreground promotion the OS
            // may throttle, but the action still fires.
            Log.w(TAG, "Service start rejected ($action), retrying as plain start", e)
            try {
                app.startService(intent)
            } catch (e2: Exception) {
                Log.e(TAG, "Service start failed for $action", e2)
            }
        }
    }
}
