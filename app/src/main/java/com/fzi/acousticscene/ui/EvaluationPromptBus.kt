package com.fzi.acousticscene.ui

import com.fzi.acousticscene.model.SceneClass
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class EvaluationPromptData(
    val predictionId: Long,
    val modelPredictedClass: SceneClass
)

/**
 * Process-wide bus that signals when an evaluation prompt is resolved
 * (user submitted, skipped, or timed out inside EvaluationActivity).
 * MainViewModel observes this and clears its `pendingEvaluation` UI state.
 */
object EvaluationPromptBus {
    private val _dismissals = MutableSharedFlow<Long>(extraBufferCapacity = 4)
    val dismissals: SharedFlow<Long> = _dismissals.asSharedFlow()

    fun dismiss(predictionId: Long) {
        _dismissals.tryEmit(predictionId)
    }
}
