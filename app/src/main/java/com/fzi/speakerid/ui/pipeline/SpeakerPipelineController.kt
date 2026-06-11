package com.fzi.speakerid.ui.pipeline

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.fzi.acousticscene.R
import com.fzi.speakerid.library.data.HistoryEntry
import com.fzi.speakerid.library.data.SpeakerManager
import com.fzi.speakerid.library.pipeline.OnnxSessionProvider
import com.fzi.speakerid.library.pipeline.steps.AudioPreprocessor
import com.fzi.speakerid.library.pipeline.steps.Chunker
import com.fzi.speakerid.library.pipeline.steps.EmbeddingExtractor
import com.fzi.speakerid.library.pipeline.steps.SileroVad
import com.fzi.speakerid.library.pipeline.steps.SpeakerMatcher
import com.fzi.speakerid.library.pipeline.steps.WavReader
import com.fzi.speakerid.ui.AssetModelInstaller
import com.fzi.speakerid.ui.SpeakerIdDataManager
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Port von siqas `gui/logic/pipeline_logic.py::PipelineController`:
 * steuert die Offline-Datei-Analyse in atomaren Schritten (Laden -> Chunking
 * -> KI-Loop mit den Mikro-Schritten VAD/Embedding/Matching/Speichern) und
 * erlaubt Pausieren nach jedem Schritt.
 *
 * Observer-Mechanik: Kivy-Properties -> [MutableStateFlow]s
 * (Screens collecten wie `controller.bind(...)`).
 *
 * Lebensdauer: In Kivy haengt der Controller am persistenten Screen-Objekt
 * und laeuft beim Verlassen des Screens weiter (`Clock.schedule_interval`
 * wird in `on_leave` nicht gestoppt) — hier deshalb Prozess-Singleton wie
 * [com.fzi.speakerid.ui.SpeakerLiveSession].
 *
 * Threading-Abweichung (notwendig): Kivy fuehrt die Schritte auf dem
 * Main-Thread aus; auf Android laufen sie auf einem 1-Worker-Dispatcher
 * (ONNX-Inferenz gehoert nicht auf den UI-Thread). Die Schritt-Frequenz
 * entspricht `gui_config.PIPELINE_STEP_INTERVAL` = 1/30 s (IS_MOBILE).
 */
class SpeakerPipelineController private constructor(
    private val appContext: Context,
    private val dm: SpeakerIdDataManager,
) {

    // ── Kivy-Properties -> StateFlows ────────────────────────────────────────

    /** Hauptphasen: 0=Laden, 1=Chunking, 2=KI-Loop. */
    val currentState = MutableStateFlow(0)

    /** Mikro-Schritte in Phase 2: 0=VAD, 1=Embedding, 2=Matching, 3=Speichern. */
    val currentSubState = MutableStateFlow(0)

    val currentChunkIdx = MutableStateFlow(0)
    val totalChunks = MutableStateFlow(0)

    // Beobachtbare Werte fuer die UI
    val lastVadResult = MutableStateFlow(0.0)
    val lastVectorPreview = MutableStateFlow("")
    val lastMatchedId = MutableStateFlow("")

    val statusText = MutableStateFlow(
        appContext.getString(R.string.speakerid_pipeline_status_ready)
    )
    val isAutoRunning = MutableStateFlow(false)
    val isFinished = MutableStateFlow(false)
    val stepLogs = MutableStateFlow<List<String>>(emptyList())

    // ── interner Zustand (wie die Python-Member) ─────────────────────────────

    private var rawWaveform: FloatArray? = null
    private var chunksData: List<Chunker.AudioChunk> = emptyList()
    private var currentVec: DoubleArray? = null
    private var autoJob: Job? = null

    /** 1-Worker = sequenzielle Schritte wie Kivys Main-Loop. */
    private val scope = CoroutineScope(
        SupervisorJob() + Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "SpeakerPipelineStep").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    )

    private var provider: OnnxSessionProvider? = null
    private var vad: SileroVad? = null
    private var embedder: EmbeddingExtractor? = null

    // ── Port der Python-Methoden ─────────────────────────────────────────────

    /**
     * Port von `reset` (laeuft sequenziell nach einem evtl. aktiven Schritt).
     * Liefert den Job, damit Aufrufer wie Python "nach dem Reset" fortsetzen
     * koennen (z. B. `_refresh_file_info` in `reset_pipeline`).
     */
    fun reset(): Job {
        stopAuto()
        return scope.launch {
            currentState.value = 0
            currentSubState.value = 0
            currentChunkIdx.value = 0
            totalChunks.value = 0
            isFinished.value = false
            stepLogs.value = emptyList()
            chunksData = emptyList()
            currentVec = null
            statusText.value = getString(R.string.speakerid_pipeline_status_reset)
        }
    }

    /** Port von `execute_next_step` (Button "1 Schritt"). */
    fun requestNextStep() {
        scope.launch {
            try {
                executeNextStep()
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    /** Port von `start_auto` (`Clock.schedule_interval`, 1/30 s). */
    fun startAuto() {
        if (isFinished.value || isAutoRunning.value) return
        isAutoRunning.value = true
        autoJob = scope.launch {
            while (isActive && isAutoRunning.value) {
                val t0 = SystemClock.elapsedRealtime()
                try {
                    executeNextStep()
                } catch (e: Exception) {
                    handleError(e)
                    break
                }
                val wait = STEP_INTERVAL_MS - (SystemClock.elapsedRealtime() - t0)
                if (wait > 0) delay(wait)
            }
        }
    }

    /** Port von `stop_auto`. */
    fun stopAuto() {
        isAutoRunning.value = false
        autoJob?.cancel()
        autoJob = null
    }

    /** Port von `execute_next_step` — exakt EIN kleiner Logik-Baustein. */
    private fun executeNextStep() {
        if (isFinished.value) return

        // --- PHASE 0: Laden ---
        if (currentState.value == 0) {
            val filePath = dm.currentAudioPath.value
            log(getString(R.string.speakerid_pipeline_log_loading))
            // load_audio_file + preprocess_audio (Mono, Peak-Norm, 16 kHz)
            val wav = WavReader.read(File(filePath))
            rawWaveform = AudioPreprocessor.preprocess(wav)
            currentState.value = 1
            statusText.value = getString(R.string.speakerid_pipeline_status_audio_ready)
            return
        }

        // --- PHASE 1: Chunking ---
        if (currentState.value == 1) {
            log(getString(R.string.speakerid_pipeline_log_chunking))
            // chunk_audio: 1.0 s / 0.0 s Overlap (gui_config), Rest verwerfen
            chunksData = Chunker.chunk(
                waveform = requireNotNull(rawWaveform),
                sampleRate = AudioPreprocessor.TARGET_SAMPLE_RATE,
                chunkDurationS = CHUNK_DURATION,
                overlapS = CHUNK_OVERLAP,
                padLast = false,
            )
            totalChunks.value = chunksData.size
            currentState.value = 2
            statusText.value =
                getString(R.string.speakerid_pipeline_status_segments, totalChunks.value)
            return
        }

        // --- PHASE 2: KI-Loop (Mikro-Schritte) ---
        if (currentChunkIdx.value >= totalChunks.value) {
            isFinished.value = true
            statusText.value = getString(R.string.speakerid_pipeline_status_done)
            log(getString(R.string.speakerid_pipeline_log_done))
            stopAuto()
            return
        }

        val chunk = chunksData[currentChunkIdx.value]
        val idx = currentChunkIdx.value

        when (currentSubState.value) {
            // SUB-STEP 0: VAD (Stille-Check)
            0 -> {
                ensureModels()
                lastVadResult.value = requireNotNull(vad).getSpeechDuration(chunk.samples)
                log(getString(R.string.speakerid_pipeline_log_vad, idx, fmt2(lastVadResult.value)))

                // Schwellenwert-Check
                if (lastVadResult.value < MIN_SPEECH_RATIO) {
                    lastMatchedId.value = SpeakerManager.RESERVED_SILENCE
                    log(getString(R.string.speakerid_pipeline_log_silence, idx))
                    currentSubState.value = 3 // Ueberspringe Embedding und Matching
                } else {
                    currentSubState.value = 1
                }
            }

            // SUB-STEP 1: Embedding (KI-Berechnung)
            1 -> {
                ensureModels()
                val vec = requireNotNull(embedder).extractEmbedding(chunk.samples)
                currentVec = vec
                lastVectorPreview.value = previewOf(vec)
                log(getString(R.string.speakerid_pipeline_log_embedding, idx))
                currentSubState.value = 2
            }

            // SUB-STEP 2: Matching (Identifikation)
            2 -> {
                lastMatchedId.value = synchronized(dm) {
                    SpeakerMatcher.findClusterForEmbedding(
                        newEmbedding = requireNotNull(currentVec),
                        clustersDict = dm.clusters,
                        strategyName = dm.clusterAssignmentStrategy.value,
                        targetThreshold = dm.thresholdTarget.value,
                        normalThreshold = dm.thresholdNormal.value,
                    )
                }
                log(getString(R.string.speakerid_pipeline_log_match, idx, lastMatchedId.value))
                currentSubState.value = 3
            }

            // SUB-STEP 3: Update (DataManager-Speicherung)
            3 -> {
                // Speichere Daten ab (auch fuer Stille, dann ist currentVec null)
                val vec = currentVec

                // History-Zuweisung VOR dem Embedding-Save
                val entry = if (vec != null) {
                    HistoryEntry(id = lastMatchedId.value, status = SpeakerManager.STATUS_SPEECH)
                } else {
                    HistoryEntry(id = null, status = SpeakerManager.STATUS_SILENCE)
                }
                val historyIdx = synchronized(dm) {
                    val history = dm.speakerManager().chunkHistory
                    history.add(entry)
                    history.size - 1
                }

                // Zeitskalierung: bei Overlap nur die Schrittweite gutschreiben
                val overlapFactor = (CHUNK_DURATION - CHUNK_OVERLAP) / CHUNK_DURATION
                val realTimeAdded = lastVadResult.value * overlapFactor

                // Jetzt erst Embedding hinzufuegen (history_idx ist bekannt);
                // dispatcht auch `clusters` -> UI-Update der Timeline
                dm.addNewEmbedding(
                    embedding = vec,
                    clusterId = lastMatchedId.value,
                    time = realTimeAdded,
                    historyIdx = historyIdx,
                    startTime = chunk.startTimeS,
                )

                // Neue Cluster aus dem Unlabeled-Pool extrahieren (Live-Pfad)
                if (lastMatchedId.value == SpeakerManager.RESERVED_UNLABELED && vec != null) {
                    dm.processUnlabeledPool()?.let { newId ->
                        lastMatchedId.value = newId
                    }
                }

                // Vorbereitung fuer den naechsten Chunk
                currentChunkIdx.value += 1
                currentSubState.value = 0
                currentVec = null
                statusText.value = getString(
                    R.string.speakerid_pipeline_status_progress,
                    currentChunkIdx.value,
                    totalChunks.value,
                )
            }
        }
    }

    // ── Helfer ───────────────────────────────────────────────────────────────

    /** Port von `_log`: nur die letzten 500 Eintraege behalten. */
    private fun log(msg: String) {
        stepLogs.value = (stepLogs.value + msg).takeLast(500)
    }

    /**
     * ONNX-Modelle lazy bereitstellen (Pendant zum Python-Import der
     * Library-Steps, die ihre Sessions selbst cachen). Idempotent.
     */
    private fun ensureModels() {
        if (vad != null && embedder != null) return
        AssetModelInstaller.install(appContext)
        val p = provider
            ?: OnnxSessionProvider(AssetModelInstaller.modelsDir(appContext)).also { provider = it }
        if (vad == null) vad = SileroVad(p)
        if (embedder == null) embedder = EmbeddingExtractor(p)
    }

    /** `str(vec[:7]) + "..."` — die ersten 7 Komponenten des 192D-Vektors. */
    private fun previewOf(vec: DoubleArray): String =
        vec.take(7).joinToString(
            separator = " ",
            prefix = "[",
            postfix = "]...",
        ) { String.format(Locale.US, "%.4f", it) }

    /** Python haette die Exception durchschlagen lassen; hier Status + Stopp. */
    private fun handleError(e: Exception) {
        Log.e(TAG, "Pipeline-Schritt fehlgeschlagen", e)
        stopAuto()
        statusText.value =
            getString(R.string.speakerid_pipeline_status_error, e.message ?: e.javaClass.simpleName)
    }

    private fun fmt2(v: Double): String = String.format(Locale.US, "%.2f", v)

    private fun getString(resId: Int, vararg args: Any): String =
        appContext.getString(resId, *args)

    companion object {
        private const val TAG = "SpeakerPipeline"

        // gui_config.py
        const val MIN_SPEECH_RATIO = 0.15
        const val CHUNK_DURATION = 1.0
        const val CHUNK_OVERLAP = 0.0

        /** PIPELINE_STEP_INTERVAL = 1/30 (IS_MOBILE). */
        private const val STEP_INTERVAL_MS = 1000L / 30L

        @Volatile
        private var INSTANCE: SpeakerPipelineController? = null

        /**
         * Prozess-Singleton — Pendant zum Controller am persistenten
         * Kivy-Screen (`if not self.controller: ...` in `on_enter_screen`).
         */
        @JvmStatic
        fun getInstance(context: Context): SpeakerPipelineController =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SpeakerPipelineController(
                    context.applicationContext,
                    SpeakerIdDataManager.getInstance(context.applicationContext),
                ).also { INSTANCE = it }
            }
    }
}
