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
     * Continuous: per-model checked methods. The locked default for each model
     * is always part of the set (Standard for 10 s-trained, Fast for 1 s-trained
     * models, see [ModelTrainingDuration]). Average can be added on top.
     * Interval: ignored — Interval has its own field below.
     */
    val continuousMethodsByModel: Map<String, Set<LongSubMode>> = emptyMap(),
    /**
     * Interval only — pause between recordings.
     */
    val intervalPause: LongInterval? = null,
    /**
     * Interval only — per-model checked methods. Same shape as
     * [continuousMethodsByModel].
     */
    val intervalMethodsByModel: Map<String, Set<LongSubMode>> = emptyMap(),
    val sessionDuration: SessionDuration = SessionDuration.DEFAULT
) {
    val isMultiModel: Boolean get() = modelNames.size >= 2

    /**
     * Whether this saved config can still be started 1:1 with the models the app
     * currently has access to. Used by Quick Start on the welcome page to decide
     * whether to even offer the shortcut — if anything has drifted (model deleted
     * from assets, methods no longer compatible after a model swap, missing
     * pause), the button hides and the user falls back to "Start new session".
     */
    fun isExecutable(availableModels: List<String>): Boolean {
        if (modelNames.isEmpty()) return false
        if (!modelNames.all { it in availableModels }) return false
        return when (category) {
            RecordingCategory.CONTINUOUS -> modelNames.all { model ->
                val methods = continuousMethodsByModel[model].orEmpty()
                val seconds = ModelTrainingDuration.secondsForFilename(model)
                methods.isNotEmpty() && methods.all { it.isCompatibleWith(seconds) }
            }
            RecordingCategory.INTERVAL -> {
                if (intervalPause == null) return false
                modelNames.all { model ->
                    val methods = intervalMethodsByModel[model].orEmpty()
                    val seconds = ModelTrainingDuration.secondsForFilename(model)
                    methods.isNotEmpty() && methods.all { it.isCompatibleWith(seconds) }
                }
            }
        }
    }

    /**
     * Compact one-line label used on history tiles.
     */
    fun shortLabel(): String {
        val modelLabel = if (modelNames.size == 1) "🧠 ${modelNames.first()}"
        else "🧠 ${modelNames.size} models"
        val pathLabel = when (category) {
            RecordingCategory.CONTINUOUS -> {
                val methodCount = continuousMethodsByModel.values.flatten().toSet().size
                "Continuous · $methodCount methods"
            }
            RecordingCategory.INTERVAL -> {
                val pause = intervalPause?.label ?: "?"
                val methodCount = intervalMethodsByModel.values.flatten().toSet().size
                "Interval $pause · $methodCount methods"
            }
        }
        return "$modelLabel · $pathLabel · ${sessionDuration.label}"
    }
}
