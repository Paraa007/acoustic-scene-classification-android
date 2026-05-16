package com.fzi.acousticscene.ui

import com.fzi.acousticscene.model.LongInterval
import com.fzi.acousticscene.model.LongSubMode
import com.fzi.acousticscene.model.RecordingCategory
import com.fzi.acousticscene.model.SessionConfig
import com.fzi.acousticscene.model.SessionDuration
import com.fzi.acousticscene.model.WizardStep

/**
 * In-flight wizard answers. Persisted to no storage — kept only on the
 * [MainViewModel] until the user reaches the summary and confirms, at which
 * point the resolved [SessionConfig] takes over and the wizard state is reset.
 */
data class WizardViewState(
    val step: WizardStep = WizardStep.Models,
    val availableModels: List<String> = emptyList(),
    val selectedModels: List<String> = emptyList(),
    val category: RecordingCategory? = null,
    val continuousMethodsByModel: Map<String, Set<LongSubMode>> = emptyMap(),
    val intervalPause: LongInterval? = null,
    val intervalMethodsByModel: Map<String, Set<LongSubMode>> = emptyMap(),
    val sessionDuration: SessionDuration = SessionDuration.DEFAULT,
    /**
     * True when the wizard was entered via the Quick Start shortcut on the welcome
     * page. Renders the Summary read-only: step dots hidden, summary rows not
     * tappable, back arrow leaves the wizard instead of walking through steps.
     */
    val quickStartMode: Boolean = false
) {
    /** Branch-aware ordered list of steps for the current category choice. */
    fun stepOrder(): List<WizardStep> = when (category) {
        RecordingCategory.INTERVAL -> WizardStep.intervalSteps()
        else -> WizardStep.continuousSteps()
    }

    /**
     * Whether the user has filled in everything the current step needs in order
     * to advance to the next page.
     */
    fun canAdvance(): Boolean = when (step) {
        WizardStep.Models -> selectedModels.isNotEmpty()
        WizardStep.Category -> category != null
        WizardStep.IntervalPause -> intervalPause != null
        WizardStep.SessionDuration -> true
        WizardStep.Summary -> true
    }

    /** Resolves the final config from the collected answers. */
    fun toSessionConfig(): SessionConfig {
        val cat = requireNotNull(category) { "category not picked" }
        return SessionConfig(
            modelNames = selectedModels,
            category = cat,
            continuousMethodsByModel = continuousMethodsByModel,
            intervalPause = intervalPause,
            intervalMethodsByModel = intervalMethodsByModel,
            sessionDuration = sessionDuration
        )
    }
}
