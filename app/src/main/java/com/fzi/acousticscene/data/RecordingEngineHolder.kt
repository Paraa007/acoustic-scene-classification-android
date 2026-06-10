package com.fzi.acousticscene.data

import android.content.Context
import com.fzi.acousticscene.model.SessionConfig
import com.fzi.acousticscene.service.RecordingEngine
import com.fzi.acousticscene.ui.common.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide channel between the [com.fzi.acousticscene.service.ClassificationService]
 * (which owns the audio + inference loop) and any UI layer that wants to observe
 * the live state (ViewModel, fragments).
 *
 * Why this exists: the recording loop used to live in the ViewModel's
 * `viewModelScope`. When Android killed the hosting activity under memory
 * pressure (very common while the screen is off and the user is moving around),
 * the loop died with it — even with a foreground service running. Moving the
 * loop into the service fixes that, but the UI still needs to subscribe to the
 * live state. This singleton is the bridge.
 *
 * The service is the only writer into [uiState]. Reads happen from anywhere.
 * The "pending" fields are how the UI hands the service a SessionConfig (and
 * pause-timer parameters) without having to serialize them through an Intent —
 * everything is in-process, so a static handoff is fine.
 */
object RecordingEngineHolder {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /**
     * Backing mutable handle for the engine. Only the engine writes here; callers
     * read via [uiState].
     */
    internal val mutableUiState: MutableStateFlow<UiState> get() = _uiState

    /**
     * SessionConfig that the next ACTION_APPLY_CONFIG should consume. Set by the
     * ViewModel just before kicking the service. Cleared by the engine once it
     * has picked the config up.
     */
    @Volatile var pendingConfig: SessionConfig? = null

    /**
     * Auto-resume delay (ms) for the next ACTION_PAUSE. null = indefinite pause.
     */
    @Volatile var pendingPauseAutoResumeMs: Long? = null

    /**
     * Set by MainActivity when it restarts an interrupted interval session from
     * the recovery notification. The engine consumes it in start(): it keeps
     * the original sessionStartTime (so all records stay in one session block)
     * and writes a synthetic pause record covering the gap since the last
     * persisted cycle.
     */
    data class ResumeInfo(val originalSessionStartTime: Long, val gapSec: Long)

    @Volatile var pendingResume: ResumeInfo? = null

    /**
     * The recording engine lives at process scope, not service scope. The
     * service is the only thing that *drives* it via intents, but the engine
     * itself outlives stop/start cycles so that:
     *
     *  1. The ResultsSummary screen can still issue `clearResults` after the
     *     service has called stopSelf.
     *  2. A future "resume from notification" flow can spin up the engine again
     *     without losing in-memory model handles between recordings.
     *
     * Use [ensureEngine] to lazily create it. Anything that survives the
     * process eventually goes through [shutdown].
     */
    private var engine: RecordingEngine? = null
    private var engineScope: CoroutineScope = newEngineScope()

    private fun newEngineScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Synchronized
    fun ensureEngine(context: Context): RecordingEngine {
        val existing = engine
        if (existing != null) return existing
        if (!engineScope.coroutineContext[kotlinx.coroutines.Job]!!.isActive) {
            engineScope = newEngineScope()
        }
        val created = RecordingEngine(context.applicationContext, engineScope)
        engine = created
        return created
    }

    fun engineOrNull(): RecordingEngine? = engine

    /**
     * Tears the engine and its scope down. Called when the application process
     * itself is going away (Application.onTerminate is unreliable, so in
     * practice this is invoked only on explicit cleanup paths and from tests).
     */
    @Synchronized
    fun shutdown() {
        engine?.release()
        engine = null
        engineScope.cancel()
    }
}
