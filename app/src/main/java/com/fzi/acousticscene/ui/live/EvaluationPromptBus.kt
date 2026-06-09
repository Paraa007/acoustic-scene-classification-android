package com.fzi.acousticscene.ui.live

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
    // Capacity 64: bisher 4, was bei mehreren schnellen Bewertungen still überlief
    // (tryEmit gibt false zurück, der Dismiss wäre verloren). 64 ist großzügig
    // genug für jeden realistischen Burst und kostet nichts.
    private val _dismissals = MutableSharedFlow<Long>(extraBufferCapacity = 64)
    val dismissals: SharedFlow<Long> = _dismissals.asSharedFlow()

    fun dismiss(predictionId: Long) {
        // tryEmit kann hier nur fehlschlagen, wenn der Buffer (64) voll ist —
        // dann hat der Collector schon ein massives Problem. Trotzdem loggen
        // wir den Drop nicht still, sondern nehmen ihn als Signal: wenn das je
        // false zurückgibt, muss der Collector beschleunigt werden.
        val accepted = _dismissals.tryEmit(predictionId)
        if (!accepted) {
            android.util.Log.w("EvaluationPromptBus", "Dropped dismissal $predictionId — buffer full")
        }
    }
}
