package com.fzi.acousticscene.model

import com.fzi.acousticscene.util.stripModelSuffix

/**
 * Snapshot of every wizard answer needed to start a recording session. Built up
 * step by step in the wizard and handed to the live recording flow once the user
 * confirms on the summary page. Persisted as the "last config" for one-tap reuse
 * from the welcome page, and as the body of any [QuickstartSlot].
 *
 * `continuousMethodsByModel` / `intervalMethodsByModel` are derived from the
 * picked models — the user never picks methods directly. They live on the
 * config only so the live recording flow has a single map to look up.
 */
data class SessionConfig(
    val modelNames: List<String>,
    val category: RecordingCategory,
    val continuousMethodsByModel: Map<String, Set<LongSubMode>> = emptyMap(),
    val intervalPause: LongInterval? = null,
    val intervalMethodsByModel: Map<String, Set<LongSubMode>> = emptyMap(),
    val sessionDuration: SessionDuration = SessionDuration.DEFAULT,
    /**
     * Interval mode only: how many recordings (in percent, 10–100 in steps of
     * 10) should ask for a "Rate now" evaluation. 100 keeps the historic
     * behavior of prompting after every cycle. Ignored for Continuous.
     */
    val ratingPercent: Int = 100,
    /**
     * Which app entry point this config was created from. TEST configs come
     * from a tester tapping a quickstart slot; CONFIG configs come from a
     * developer running the wizard manually.
     */
    val mode: SessionMode = SessionMode.CONFIG
) {
    val isMultiModel: Boolean get() = modelNames.size >= 2

    /**
     * Whether this saved config can still be started 1:1 with the models the app
     * currently has access to. Used by Quick Start (and quickstart slot launch)
     * to decide whether to even offer the shortcut — if anything has drifted,
     * the entry hides and the user falls back to "Start new session".
     */
    fun isExecutable(availableModels: List<String>): Boolean {
        if (modelNames.isEmpty()) return false
        return modelNames.all { it in availableModels } &&
            (category != RecordingCategory.INTERVAL || intervalPause != null)
    }

    /**
     * Compact one-line label used on history tiles.
     */
    fun shortLabel(): String {
        val modelLabel = if (modelNames.size == 1) "🧠 ${modelNames.first().stripModelSuffix()}"
        else "🧠 ${modelNames.size} models"
        val pathLabel = when (category) {
            RecordingCategory.CONTINUOUS -> "Continuous"
            RecordingCategory.INTERVAL -> "Interval ${intervalPause?.label ?: "?"}"
        }
        return "$modelLabel · $pathLabel · ${sessionDuration.label}"
    }

    /**
     * Human-readable slot description for the Test Welcome screen. Spec rules:
     *   - 10 s-only models   → "30 min recording"
     *   - any 1 s-only model → "<duration>, fast mode"
     *   - mix (1 s included with average bias) → "<duration>, averaging"
     *   - interval           → "<duration>, every <interval>"
     */
    fun slotDescription(): String {
        val durationLabel = sessionDuration.label
        val hasOneSec = modelNames.any { ModelTrainingDuration.secondsForFilename(it) == 1 }
        val hasTenSec = modelNames.any { ModelTrainingDuration.secondsForFilename(it) == 10 }
        return when {
            sessionDuration.isManual -> "Stops on tap"
            category == RecordingCategory.INTERVAL -> {
                val pause = intervalPause?.label ?: "?"
                "$durationLabel, every $pause"
            }
            hasOneSec && hasTenSec -> "$durationLabel, averaging"
            hasOneSec -> "$durationLabel, fast mode"
            else -> "$durationLabel recording"
        }
    }
}
