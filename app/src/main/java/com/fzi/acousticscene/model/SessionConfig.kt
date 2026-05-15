package com.fzi.acousticscene.model

/**
 * Snapshot of every wizard answer needed to start a recording session. Built up
 * step by step in the wizard and handed to the live recording flow once the user
 * confirms on the summary page. Persisted as the "last config" for one-tap reuse
 * from the welcome page.
 */
data class SessionConfig(
    val modelNames: List<String>,
    val category: RecordingCategory,
    /**
     * Continuous: which clip method to run on every cycle. Exactly one entry.
     * Interval: ignored — Interval picks per-model methods instead.
     */
    val continuousSubMode: LongSubMode = LongSubMode.STANDARD,
    /**
     * Interval only — pause between recordings.
     */
    val intervalPause: LongInterval? = null,
    /**
     * Interval only — per-model checked methods. The locked default for each
     * model is always part of the set (Standard for 10 s-trained, Fast for
     * 1 s-trained models, see [ModelTrainingDuration]).
     */
    val intervalMethodsByModel: Map<String, Set<LongSubMode>> = emptyMap(),
    val sessionDuration: SessionDuration = SessionDuration.DEFAULT
) {
    val isMultiModel: Boolean get() = modelNames.size >= 2

    /**
     * Compact one-line label used on history tiles and the welcome page's
     * "Letzte Config nutzen" button.
     */
    fun shortLabel(): String {
        val modelLabel = if (modelNames.size == 1) "🧠 ${modelNames.first()}"
        else "🧠 ${modelNames.size} models"
        val pathLabel = when (category) {
            RecordingCategory.CONTINUOUS -> "Continuous · ${continuousSubMode.label}"
            RecordingCategory.INTERVAL -> {
                val pause = intervalPause?.label ?: "?"
                val methodCount = intervalMethodsByModel.values.flatten().toSet().size
                "Interval $pause · $methodCount methods"
            }
        }
        return "$modelLabel · $pathLabel · ${sessionDuration.label}"
    }
}
