package com.fzi.speakerid.library

import com.fzi.speakerid.library.calculations.RmsCalculator
import com.fzi.speakerid.library.data.SpeakerManager
import com.fzi.speakerid.library.pipeline.OnnxSessionProvider
import com.fzi.speakerid.library.pipeline.steps.Chunker
import com.fzi.speakerid.library.pipeline.steps.EmbeddingExtractor
import com.fzi.speakerid.library.pipeline.steps.SileroVad
import com.fzi.speakerid.library.pipeline.steps.SpeakerExtraction
import com.fzi.speakerid.library.pipeline.steps.SpeakerMatcher
import com.fzi.speakerid.library.pipeline.steps.SpeakerOverlapCleaner

/**
 * Konfiguration des Live-Flows — Defaults exakt wie die GUI-Defaults aus
 * `gui/data/gui_config.py` / `gui/data/data_manager.py` (= Parameter-Block in
 * `golden/meta.json`). `use_noise_reduction` (GUI-Default AUS) ist im Port
 * bewusst NICHT enthalten — der Denoise-Schritt wurde nicht portiert.
 */
data class LiveProcessorConfig(
    val sampleRate: Int = 16000,
    /** `dm.chunk_duration` (gui_config.CHUNK_DURATION). */
    val chunkDurationS: Double = 1.0,
    /** gui_config.CHUNK_OVERLAP. */
    val chunkOverlapS: Double = 0.0,
    /** `dm.use_silero_vad`. */
    val useSileroVad: Boolean = true,
    /** `process_chunk`-Default `vad_threshold`. */
    val vadThreshold: Double = SileroVad.DEFAULT_THRESHOLD,
    /** `process_chunk`-Default `min_speech_seconds`. */
    val minSpeechSeconds: Double = SileroVad.DEFAULT_MIN_SPEECH_SECONDS,
    /** `dm.use_pyannote` — GUI-Default AUS (Overlap-Cleaner laeuft dann gar nicht). */
    val usePyannote: Boolean = false,
    /** `dm.cleaner_min_duration`. */
    val cleanerMinDurationS: Double = SpeakerOverlapCleaner.DEFAULT_MIN_DURATION_S,
    /** `dm.cleaner_min_island_duration`. */
    val cleanerMinIslandDurationS: Double = SpeakerOverlapCleaner.DEFAULT_MIN_ISLAND_S,
    /** `dm.cluster_assignment_strategy` (gui_config.DEFAULT_ASSIGNMENT). */
    val assignmentStrategy: String = SpeakerMatcher.STRATEGY_BEST_OVERALL,
    /** gui_config.THRESHOLD_TARGET. */
    val targetThreshold: Double = 0.7,
    /** gui_config.THRESHOLD_NORMAL. */
    val normalThreshold: Double = 0.7,
    /** `dm.centroid_update_strategy`. */
    val centroidUpdateStrategy: String = "static",
    /** `dm.ema_alpha`. */
    val emaAlpha: Double = 0.1,
    /** `dm.moving_average_window_size`. */
    val movingAverageWindowSize: Int = 10,
    /** gui_config.MIN_SAMPLES_FOR_NEW_SPEAKER. */
    val minSamplesForNewSpeaker: Int = 3,
    /** Pool-Mining-Strategie (`process_unlabeled_pool`). */
    val poolExtractionStrategy: String = SpeakerExtraction.STRATEGY_CLIQUE,
)

/**
 * UI-freier Port der Live-Orchestrierung aus
 * `src/gui/services/live_processor.py` (`_process_in_thread`/`_process_in_main`
 * + `_apply_results`) inklusive des Echtzeit-Helpers
 * `library/pipeline/pipeline.py::process_chunk`.
 *
 * Ablauf pro 1.0-s-Chunk (letzter Chunk upstream zero-gepaddet), exakt wie
 * Python:
 *  1. RMS (Statistik, wie `generate_golden.py`)
 *  2. Overlap-Cleaner NUR wenn [LiveProcessorConfig.usePyannote] an ist
 *     (GUI-Default AUS) — sonst der ganze Chunk als EIN Solo-Segment
 *  3. pro Segment `process_chunk`: Silero-VAD (`detect_silence`) und, falls
 *     Sprache, ReDimNet-Embedding; die Segment-Dauer ueberschreibt wie in
 *     Python die VAD-Dauer (`res["duration"] = seg["duration"]`)
 *  4. `_apply_results` via [SpeakerManager.applyResult]: Matching
 *     (best_overall 0.7/0.7), Cluster-Update (static) und bei Pool-Treffer
 *     ("0") sofortiges Clique-Mining mit retroaktiver Promotion
 *
 * Kivy-/Thread-Spezifika (Clock, Executor, Debounce, Recorder) sind bewusst
 * weggelassen; der Aufrufer fuettert Chunks synchron ueber [processChunk]
 * oder eine ganze vorverarbeitete Waveform ueber [processAll].
 */
class LiveProcessor(
    private val vad: SileroVad,
    private val embeddingExtractor: EmbeddingExtractor,
    val speakerManager: SpeakerManager,
    private val overlapCleaner: SpeakerOverlapCleaner? = null,
    val config: LiveProcessorConfig = LiveProcessorConfig(),
) {

    /**
     * Convenience wie `LiveProcessor.__init__`: baut VAD, Embedding-Extractor
     * und [SpeakerManager] (Target-Centroid wie `explorer._on_target_success`)
     * aus dem [OnnxSessionProvider]. Der Pyannote-Overlap-Cleaner (und damit
     * das Segmentation-Modell) wird NUR bei `usePyannote = true` erzeugt.
     */
    constructor(
        provider: OnnxSessionProvider,
        targetCentroid: DoubleArray? = null,
        config: LiveProcessorConfig = LiveProcessorConfig(),
    ) : this(
        vad = SileroVad(provider),
        embeddingExtractor = EmbeddingExtractor(provider),
        speakerManager = SpeakerManager(
            targetCentroid = targetCentroid,
            assignmentStrategy = config.assignmentStrategy,
            targetThreshold = config.targetThreshold,
            normalThreshold = config.normalThreshold,
            centroidUpdateStrategy = config.centroidUpdateStrategy,
            emaAlpha = config.emaAlpha,
            movingAverageWindowSize = config.movingAverageWindowSize,
            minSamplesForNewSpeaker = config.minSamplesForNewSpeaker,
            poolExtractionStrategy = config.poolExtractionStrategy,
            chunkDurationS = config.chunkDurationS,
            chunkOverlapS = config.chunkOverlapS,
        ),
        overlapCleaner = if (config.usePyannote) SpeakerOverlapCleaner(provider) else null,
        config = config,
    )

    init {
        require(!config.usePyannote || overlapCleaner != null) {
            "usePyannote = true erfordert einen SpeakerOverlapCleaner"
        }
        require(
            speakerManager.chunkDurationS == config.chunkDurationS &&
                speakerManager.chunkOverlapS == config.chunkOverlapS
        ) {
            "SpeakerManager-Chunk-Parameter muessen mit der LiveProcessor-Config uebereinstimmen"
        }
    }

    /** Pendant zu `current_speaker_id` (StringProperty): wer spricht gerade? */
    var currentSpeakerId: String = SpeakerManager.RESERVED_SILENCE
        private set

    /** Anzahl verarbeiteter Chunks (Lebenszeit-Zaehler, wie die dm-History). */
    var chunksProcessed: Int = 0
        private set

    /** Chunks mit mindestens einem Speech-Segment (golden `speech_chunks`). */
    var speechChunks: Int = 0
        private set

    /** Chunks ohne Speech-Segment (komplett Stille). */
    var silenceChunks: Int = 0
        private set

    /**
     * Ergebnis EINES `_apply_results`-Aufrufs (ein Segment). Auf dem
     * Default-Pfad (Overlap aus) gibt es genau ein Segment pro Chunk.
     */
    class SegmentResult(
        /** VAD-Entscheidung (`res["is_silent"]`). */
        val isSilent: Boolean,
        /** 192-dim L2-normiertes Embedding; null bei Stille. */
        val embedding: DoubleArray?,
        /** Verbuchte Segment-Dauer (`res["duration"]` NACH dem Override). */
        val durationS: Double,
        /** Rohe VAD-Sprechdauer aus `detect_silence` (vor dem Override). */
        val vadSpeechDurationS: Double,
        /** Lokaler Pyannote-Sprecher-Index (nur Overlap-Pfad, sonst null). */
        val localSpeakerIdx: Int?,
        /** Live zugeordnete Cluster-ID ("-1" Stille, "0" Pool, "1" Target, "2"...). */
        val assignedId: String,
        /** Neue Sprecher-ID, falls dieses Segment eine Pool-Promotion ausgeloest hat. */
        val promotionId: String?,
    )

    /** Ergebnis eines kompletten Chunks (0..n Segmente, Default-Pfad: genau 1). */
    class ChunkResult(
        val chunkIdx: Int,
        /** RMS des rohen Chunks (Double-Akkumulation, wie golden `rms`). */
        val rms: Double,
        /** `max_active` des Overlap-Cleaners; null wenn Pyannote aus (UI: "-"). */
        val maxActiveSpeakers: Int?,
        val segments: List<SegmentResult>,
    ) {
        /** True, wenn mindestens ein Segment Sprache enthielt. */
        val isSpeech: Boolean get() = segments.any { !it.isSilent }
    }

    /** Aggregierte Statistik — Pendant zum golden Summary-Block. */
    class Stats(
        /** Sprechzeit je echtem Sprecher inkl. Target (`sprechzeit_pro_sprecher_s`). */
        val speechTimePerSpeakerS: Map<String, Double>,
        /** Zeit im Unlabeled-Pool "0" (`unlabeled_zeit_s`). */
        val unlabeledTimeS: Double,
        /** Stille-Zeit "-1" (`stille_zeit_s`). */
        val silenceTimeS: Double,
        /** Aktive Sprecher (numerische ID > 0 mit Centroid, `anzahl_sprecher`). */
        val activeSpeakersCount: Int,
        val chunksProcessed: Int,
        val speechChunks: Int,
        val silenceChunks: Int,
        val currentSpeakerId: String,
    )

    /** Interne Segment-Sicht (Python-Dict {audio, duration[, local_speaker_idx]}). */
    private class Segment(
        val audio: FloatArray,
        val durationS: Double,
        val localSpeakerIdx: Int?,
    )

    /**
     * Wie `LiveProcessor.start()`: setzt den Streaming-Zustand (Overlap-
     * Puffer) zurueck. Der [SpeakerManager] bleibt wie der Python-DataManager
     * absichtlich unangetastet — Cluster ueberleben Start/Stopp.
     */
    fun resetStream() {
        overlapCleaner?.resetStream()
    }

    /**
     * Verarbeitet einen Chunk — Port von `_process_in_thread` (ohne Denoise,
     * GUI-Default aus). `chunkIdx` geht wie in Python als `start_time`/
     * `timestamp` in die Ergebnisse ein (bei 1.0-s-Chunks = Startzeit in s).
     */
    fun processChunk(chunkIdx: Int, samples: FloatArray): ChunkResult {
        val rms = RmsCalculator.rms(samples)

        // Schritt 1: Overlap-Cleaner nur wenn Toggle an; sonst ganzer Chunk
        // als einzelnes "Solo"-Segment (duration = len / 16000.0).
        val segments: List<Segment>
        val maxActive: Int?
        if (config.usePyannote) {
            val clean = requireNotNull(overlapCleaner).processStreaming(
                newChunk = samples,
                minDuration = config.cleanerMinDurationS,
                minIslandDurationS = config.cleanerMinIslandDurationS,
            )
            segments = clean.segments.map { Segment(it.audio, it.duration, it.localSpeakerIdx) }
            maxActive = clean.maxActiveSpeakers
        } else {
            segments = listOf(
                Segment(samples, samples.size.toDouble() / config.sampleRate, null)
            )
            maxActive = null
        }

        // Schritt 2: saubere Segmente verarbeiten.
        val segmentResults = ArrayList<SegmentResult>(segments.size.coerceAtLeast(1))
        if (segments.isEmpty()) {
            // Alle Sprecher rausgefiltert -> Stille mit literaler Dauer 1.0
            // (`{"is_silent": True, "duration": 1.0, "timestamp": chunk_idx}`).
            segmentResults += applySegment(
                embedding = null,
                isSilent = true,
                durationS = 1.0,
                vadSpeechDurationS = 0.0,
                localSpeakerIdx = null,
                chunkIdx = chunkIdx,
            )
        } else {
            for (segment in segments) {
                // `process_chunk`: VAD -> ggf. Embedding; danach ueberschreibt
                // der LiveProcessor `res["duration"]` mit der Segment-Dauer.
                val silence = vad.detectSilence(
                    audio = segment.audio,
                    useVad = config.useSileroVad,
                    minSpeechSeconds = config.minSpeechSeconds,
                    chunkDuration = segment.audio.size.toDouble() / config.sampleRate,
                    sampleRate = config.sampleRate,
                    threshold = config.vadThreshold,
                )
                val embedding =
                    if (silence.isSilence) null
                    else embeddingExtractor.extractEmbedding(segment.audio)
                segmentResults += applySegment(
                    embedding = embedding,
                    isSilent = silence.isSilence,
                    durationS = segment.durationS,
                    vadSpeechDurationS = silence.speechDurationS,
                    localSpeakerIdx = segment.localSpeakerIdx,
                    chunkIdx = chunkIdx,
                )
            }
        }

        chunksProcessed += 1
        if (segmentResults.any { !it.isSilent }) speechChunks += 1 else silenceChunks += 1
        return ChunkResult(chunkIdx, rms, maxActive, segmentResults)
    }

    /**
     * Verarbeitet eine komplette vorverarbeitete Waveform (16 kHz mono, siehe
     * [com.fzi.speakerid.library.pipeline.steps.AudioPreprocessor]) wie
     * `VirtualFileRecorder._playback_loop`: 1.0-s-Chunks, Step =
     * chunk - overlap, letzter Chunk zero-gepaddet. Setzt vorher wie
     * `LiveProcessor.start()` den Streaming-Zustand zurueck.
     */
    fun processAll(waveform: FloatArray): List<ChunkResult> {
        resetStream()
        return Chunker.chunk(
            waveform = waveform,
            sampleRate = config.sampleRate,
            chunkDurationS = config.chunkDurationS,
            overlapS = config.chunkOverlapS,
            padLast = true,
        ).map { chunk -> processChunk(chunk.index, chunk.samples) }
    }

    /** Snapshot der aggregierten Statistik (Sprechzeiten, Pool, Stille). */
    fun stats(): Stats = Stats(
        speechTimePerSpeakerS = speakerManager.speechTimesPerSpeaker(),
        unlabeledTimeS = speakerManager.unlabeled.totalTime,
        silenceTimeS = speakerManager.silence.totalTime,
        activeSpeakersCount = speakerManager.activeSpeakersCount,
        chunksProcessed = chunksProcessed,
        speechChunks = speechChunks,
        silenceChunks = silenceChunks,
        currentSpeakerId = currentSpeakerId,
    )

    /**
     * `_apply_results`: verbucht ein Segment-Ergebnis im [SpeakerManager]
     * (inkl. Overlap-Faktor-Skalierung, Pool-Promotion) und aktualisiert
     * [currentSpeakerId] (Promotion ueberschreibt die Pool-ID "0").
     */
    private fun applySegment(
        embedding: DoubleArray?,
        isSilent: Boolean,
        durationS: Double,
        vadSpeechDurationS: Double,
        localSpeakerIdx: Int?,
        chunkIdx: Int,
    ): SegmentResult {
        val result = speakerManager.applyResult(
            embedding = embedding,
            isSilent = isSilent,
            durationS = durationS,
            startTimeS = chunkIdx.toDouble(),
        )
        currentSpeakerId = result.promotionId ?: result.assignedId
        return SegmentResult(
            isSilent = isSilent,
            embedding = embedding,
            durationS = durationS,
            vadSpeechDurationS = vadSpeechDurationS,
            localSpeakerIdx = localSpeakerIdx,
            assignedId = result.assignedId,
            promotionId = result.promotionId,
        )
    }
}
