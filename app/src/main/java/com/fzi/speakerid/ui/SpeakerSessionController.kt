package com.fzi.speakerid.ui

import android.util.Log
import com.fzi.speakerid.audio.SpeakerAudioRecorder
import com.fzi.speakerid.library.LiveProcessor
import com.fzi.speakerid.library.LiveProcessorConfig
import com.fzi.speakerid.library.pipeline.OnnxSessionProvider
import com.fzi.speakerid.library.data.SpeakerManager
import com.fzi.speakerid.library.pipeline.steps.AudioPreprocessor
import com.fzi.speakerid.library.pipeline.steps.Chunker
import com.fzi.speakerid.library.pipeline.steps.EmbeddingExtractor
import com.fzi.speakerid.library.pipeline.steps.SileroVad
import com.fzi.speakerid.library.pipeline.steps.SpeakerOverlapCleaner
import com.fzi.speakerid.library.pipeline.steps.WavReader
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Port von siqas `gui/services/live_processor.py::LiveProcessor` (der
 * GUI-Service, in der App als `app.live_controller` gebunden): verbindet
 * [SpeakerAudioRecorder] -> Phase-1-[LiveProcessor] -> [SpeakerIdDataManager].
 *
 * Observable Status (Kivy-Properties -> StateFlows):
 *  - [isLiveProcessing] (`is_live_processing`)
 *  - [currentSpeakerId] (`current_speaker_id`, Default "-1")
 *
 * Verarbeitung laeuft wie Pythons 1-Worker-Executor sequenziell auf einem
 * IO-Dispatcher mit Parallelitaet 1; jeder `processChunk`-Aufruf ist auf den
 * [SpeakerIdDataManager] synchronisiert, danach wird `dispatchClusters()`
 * gefeuert (Pendant zum Kivy-`clusters`-Dispatch pro Chunk).
 *
 * Konfiguration/Defaults: [LiveProcessorConfig] aus Phase 1, befuellt mit den
 * aktuellen DataManager-Werten beim [start] (Aenderungen waehrend einer
 * laufenden Session greifen wie dokumentiert erst beim naechsten Start).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpeakerSessionController(
    private val dataManager: SpeakerIdDataManager,
    /** ONNX-Modellverzeichnis, z. B. von [AssetModelInstaller.install]. */
    private val modelsDir: File,
) {

    private val _isLiveProcessing = MutableStateFlow(false)

    /** `is_live_processing` */
    val isLiveProcessing: StateFlow<Boolean> = _isLiveProcessing.asStateFlow()

    private val _currentSpeakerId = MutableStateFlow(SpeakerManager.RESERVED_SILENCE)

    /** `current_speaker_id` — wer spricht gerade? ("-1" Stille) */
    val currentSpeakerId: StateFlow<String> = _currentSpeakerId.asStateFlow()

    /** 1-Worker-Pendant zum `ThreadPoolExecutor(max_workers=1)`. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private var provider: OnnxSessionProvider? = null
    private var liveProcessor: LiveProcessor? = null
    private var recorder: SpeakerAudioRecorder? = null
    private var virtualJob: Job? = null
    private var lastToggleNanos = 0L

    /** Der zuletzt gebaute Phase-1-LiveProcessor (Statistiken, Tests). */
    fun liveProcessorOrNull(): LiveProcessor? = liveProcessor

    /**
     * Port von `toggle_live`: Start/Stopp mit 0,3-s-Debounce gegen
     * Touch-Doppelevents.
     */
    fun toggleLive() {
        val now = System.nanoTime()
        if (now - lastToggleNanos < 300_000_000L) return
        lastToggleNanos = now

        if (_isLiveProcessing.value) stop() else start()
    }

    /**
     * Port von `LiveProcessor.start` — exakt dieselbe Reihenfolge:
     *  1. Recorder waehlen: echtes Mikrofon ([SpeakerAudioRecorder]) bei
     *     `use_virtual_mic == false`, sonst Datei-Simulation
     *     (`VirtualFileRecorder`-Pendant) mit `current_audio_path`.
     *  2. Recorder starten.
     *  3. Streaming-Zustand zuruecksetzen (`overlap_cleaner.reset_stream()`).
     *  4. Flags setzen: [isLiveProcessing] und `dm.is_recording_running`.
     *
     * @return true bei Erfolg (wie Python; false z. B. wenn die
     *   Simulations-Datei fehlt oder ein Fehler auftritt).
     */
    fun start(): Boolean {
        if (_isLiveProcessing.value) return true
        try {
            val processor = buildLiveProcessor()
            liveProcessor = processor

            // Streaming-Zustand zuruecksetzen, bevor der erste Chunk kommt
            // (`overlap_cleaner.reset_stream()`).
            processor.resetStream()

            if (!dataManager.useVirtualMic.value) {
                // --- ECHTES MIKROFON (LiveRecorder) ---
                val mic = SpeakerAudioRecorder(
                    sampleRate = processor.config.sampleRate,
                    chunkDurationS = dataManager.chunkDuration.value,
                    chunkOverlapS = SpeakerIdDataManager.CHUNK_OVERLAP,
                ) { chunkIdx, samples ->
                    // Worker-Thread -> sequenzielle Verarbeitung im Scope
                    scope.launch { handleChunk(chunkIdx, samples) }
                }
                recorder = mic
                Log.i(TAG, "ECHTES MIKROFON wird gestartet.")
                _isLiveProcessing.value = true
                mic.start()
            } else {
                // --- VIRTUELLES MIKROFON (Simulation aus Datei) ---
                val simFile = File(dataManager.currentAudioPath.value)
                if (!simFile.isFile) {
                    Log.e(TAG, "Fehler: Simulations-Datei nicht gefunden unter $simFile")
                    return false
                }
                Log.i(TAG, "SIMULATION wird gestartet: ${simFile.name}")
                _isLiveProcessing.value = true
                virtualJob = scope.launch { virtualPlaybackLoop(simFile) }
            }

            dataManager.isRecordingRunning.value = true
            Log.i(TAG, "Mikrofon-Stream gestartet.")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Start: $e")
            _isLiveProcessing.value = false
            return false
        }
    }

    /** Port von `LiveProcessor.stop`. */
    fun stop() {
        recorder?.stop()
        recorder = null
        virtualJob?.cancel()
        virtualJob = null

        _isLiveProcessing.value = false
        dataManager.isRecordingRunning.value = false
        Log.i(TAG, "Mikrofon-Stream beendet.")
    }

    /** Gibt ONNX-Sessions und den Verarbeitungs-Scope endgueltig frei. */
    fun release() {
        stop()
        scope.cancel()
        provider?.close()
        provider = null
        liveProcessor = null
    }

    // ── intern ───────────────────────────────────────────────────────────────

    private fun obtainProvider(): OnnxSessionProvider =
        provider ?: OnnxSessionProvider(modelsDir).also { provider = it }

    /**
     * Baut den Phase-1-[LiveProcessor] mit den AKTUELLEN DataManager-Werten;
     * der [SpeakerManager] kommt aus dem DataManager, damit Cluster wie in
     * Python Start/Stopp ueberleben.
     */
    private fun buildLiveProcessor(): LiveProcessor {
        val p = obtainProvider()
        val config = LiveProcessorConfig(
            chunkDurationS = dataManager.chunkDuration.value,
            chunkOverlapS = SpeakerIdDataManager.CHUNK_OVERLAP,
            useSileroVad = dataManager.useSileroVad.value,
            usePyannote = dataManager.usePyannote.value,
            cleanerMinDurationS = dataManager.cleanerMinDuration.value,
            cleanerMinIslandDurationS = dataManager.cleanerMinIslandDuration.value,
            assignmentStrategy = dataManager.clusterAssignmentStrategy.value,
            targetThreshold = dataManager.thresholdTarget.value,
            normalThreshold = dataManager.thresholdNormal.value,
            centroidUpdateStrategy = dataManager.centroidUpdateStrategy.value,
            emaAlpha = dataManager.emaAlpha.value,
            movingAverageWindowSize = dataManager.movingAverageWindowSize.value,
            minSamplesForNewSpeaker = SpeakerIdDataManager.MIN_SAMPLES_FOR_NEW_SPEAKER,
        )
        return LiveProcessor(
            vad = SileroVad(p),
            embeddingExtractor = EmbeddingExtractor(p),
            speakerManager = dataManager.speakerManager(),
            overlapCleaner = if (config.usePyannote) SpeakerOverlapCleaner(p) else null,
            config = config,
        )
    }

    /**
     * Port von `_on_live_chunk` + `_process_in_thread`/`_apply_results`:
     * ein Chunk durch den Phase-1-LiveProcessor, danach UI-Status
     * (`current_active_voices`, `current_speaker_id`) und Cluster-Dispatch.
     */
    private fun handleChunk(chunkIdx: Int, samples: FloatArray) {
        if (!_isLiveProcessing.value) return
        val processor = liveProcessor ?: return
        try {
            val tStart = System.nanoTime()
            val result = synchronized(dataManager) {
                processor.processChunk(chunkIdx, samples)
            }

            val maxActive = result.maxActiveSpeakers
            dataManager.currentActiveVoices.value = when {
                maxActive == null -> "-"
                maxActive >= 3 -> "3+"
                else -> maxActive.toString()
            }
            _currentSpeakerId.value = processor.currentSpeakerId
            dataManager.dispatchClusters()

            val ms = (System.nanoTime() - tStart) / 1e6
            Log.d(TAG, "[KI-LIVE] Real-World Dauer (KI + UI): ${"%.1f".format(ms)} ms")
        } catch (e: Exception) {
            Log.e(TAG, "KI-Fehler: $e")
        }
    }

    /**
     * Port von `VirtualFileRecorder._playback_loop`: WAV laden, auf 16 kHz
     * mono vorverarbeiten, in Chunks (letzter zero-gepaddet) schneiden und in
     * Echtzeit (delay = step) einspeisen. Nach Dateiende bleibt die Session
     * wie in Python aktiv, bis der Nutzer stoppt.
     */
    private suspend fun virtualPlaybackLoop(file: File) {
        try {
            val processor = liveProcessor ?: return
            val sampleRate = processor.config.sampleRate
            val waveform = AudioPreprocessor.preprocess(WavReader.read(file), sampleRate)
            val chunks = Chunker.chunk(
                waveform = waveform,
                sampleRate = sampleRate,
                chunkDurationS = processor.config.chunkDurationS,
                overlapS = processor.config.chunkOverlapS,
                padLast = true,
            )
            val stepMs =
                ((processor.config.chunkDurationS - processor.config.chunkOverlapS) * 1000).toLong()

            for (chunk in chunks) {
                if (!_isLiveProcessing.value) break
                handleChunk(chunk.index, chunk.samples)
                // Echtzeit-Simulation (`time.sleep(self.step_duration)`);
                // delay() bricht bei Cancel des Jobs sauber ab.
                delay(stepMs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fehler bei der Dateiwiedergabe: $e")
        } finally {
            Log.i(TAG, "Datei komplett abgespielt.")
        }
    }

    companion object {
        private const val TAG = "SpeakerSession"
    }
}
