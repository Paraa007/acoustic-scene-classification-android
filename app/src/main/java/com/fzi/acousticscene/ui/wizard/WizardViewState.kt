package com.fzi.acousticscene.ui.wizard

import com.fzi.acousticscene.model.LongInterval
import com.fzi.acousticscene.model.LongSubMode
import com.fzi.acousticscene.model.RecordingCategory
import com.fzi.acousticscene.model.SessionConfig
import com.fzi.acousticscene.model.SessionDuration
import com.fzi.acousticscene.model.SessionMode
import com.fzi.acousticscene.model.WizardIntent
import com.fzi.acousticscene.model.WizardStep
import com.fzi.acousticscene.ui.MainViewModel

/**
 * In-flight wizard answers. Persisted to no storage — kept only on the
 * [MainViewModel] until the user reaches the summary and confirms, at which
 * point the resolved [SessionConfig] takes over and the wizard state is reset.
 *
 * [intent] tells the Summary which CTA to render and what to do after — see
 * [WizardIntent] for the three branches.
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
    val intent: WizardIntent = WizardIntent.StartRecording
) {
    /**
     * True when the Summary is a read-only single page — Quick Start and the
     * test-slot launch both reuse the same review screen.
     */
    val quickStartMode: Boolean
        get() = intent is WizardIntent.QuickStart || intent is WizardIntent.QuickStartTest

    /** True when the wizard exits by saving a quickstart slot instead of launching a session. */
    val saveAsSlotMode: Boolean get() = intent is WizardIntent.SaveAsSlot

    /** Test-mode sessions (slot save + slot launch) are tagged so they land in test history. */
    val isTestSession: Boolean
        get() = intent is WizardIntent.SaveAsSlot || intent is WizardIntent.QuickStartTest

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
            sessionDuration = sessionDuration,
            mode = if (isTestSession) SessionMode.TEST else SessionMode.CONFIG
        )
    }
}
