package com.fzi.speakerid.testutil

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File

/**
 * Gson-Modelle und Loader fuer die Golden-Referenzdaten.
 * Feldnamen exakt nach `the_App_T/golden/generate_golden.py` (autoritative Doku).
 */

// ── golden_test_*.json ────────────────────────────────────────────────────────

class GoldenSession(
    val wav: String,
    val chunks: List<GoldenChunk>,
    val summary: GoldenSummary,
)

class GoldenChunk(
    @SerializedName("chunk_idx") val chunkIdx: Int,
    @SerializedName("start_time_s") val startTimeS: Double,
    val rms: Double,
    val vad: GoldenVad,
    @SerializedName("overlap_shadow") val overlapShadow: GoldenOverlapShadow,
    /** 192-dim ReDimNet-Embedding; null bei Stille-Chunks. */
    val embedding: List<Double>?,
    /** Normalized-Cosine je Kandidaten-Cluster (vor dem Update); leer bei Stille. */
    @SerializedName("similarity_scores") val similarityScores: Map<String, Double>,
    /** Live zugeordnete Cluster-ID ("0" Pool, "1" Target, "-1" Stille, "2".. neu). */
    @SerializedName("zugeordneter_sprecher") val zugeordneterSprecher: String,
    /** Neue Cluster-ID, falls dieser Chunk eine Pool-Promotion ausgeloest hat. */
    @SerializedName("pool_promotion_new_id") val poolPromotionNewId: String?,
    /** Finale ID nach retroaktiver Pool-Promotion (History-Rewrite). */
    @SerializedName("final_sprecher") val finalSprecher: String,
)

class GoldenVad(
    @SerializedName("is_silence") val isSilence: Boolean,
    @SerializedName("speech_duration_s") val speechDurationS: Double,
    @SerializedName("prob_max") val probMax: Double,
    @SerializedName("prob_mean") val probMean: Double,
    /** Rohe Silero-Wahrscheinlichkeiten, ein Wert pro 256-Sample-Fenster (63/Chunk). */
    @SerializedName("window_probs") val windowProbs: List<Double>,
)

class GoldenOverlapShadow(
    @SerializedName("enabled_in_gui_default") val enabledInGuiDefault: Boolean,
    @SerializedName("max_active_speakers") val maxActiveSpeakers: Int,
    @SerializedName("overlap_seconds") val overlapSeconds: Double,
    @SerializedName("n_frames") val nFrames: Int,
    @SerializedName("frames_speech") val framesSpeech: Int,
    @SerializedName("frames_overlap") val framesOverlap: Int,
    @SerializedName("solo_segments") val soloSegments: List<GoldenSoloSegment>,
)

class GoldenSoloSegment(
    @SerializedName("local_speaker_idx") val localSpeakerIdx: Int,
    @SerializedName("duration_s") val durationS: Double,
)

class GoldenSummary(
    @SerializedName("sprechzeit_pro_sprecher_s") val sprechzeitProSprecherS: Map<String, Double>,
    @SerializedName("unlabeled_zeit_s") val unlabeledZeitS: Double,
    @SerializedName("stille_zeit_s") val stilleZeitS: Double,
    @SerializedName("overlap_zeit_s") val overlapZeitS: Double,
    @SerializedName("anzahl_sprecher") val anzahlSprecher: Int,
    @SerializedName("chunks_gesamt") val chunksGesamt: Int,
    @SerializedName("speech_chunks") val speechChunks: Int,
    @SerializedName("cluster_total_time_s") val clusterTotalTimeS: Map<String, Double>,
    @SerializedName("cluster_occurrences") val clusterOccurrences: Map<String, Int>,
)

// ── meta.json ─────────────────────────────────────────────────────────────────

class GoldenMeta(
    @SerializedName("siqas_commit") val siqasCommit: String,
    val generator: String,
    val description: String,
    @SerializedName("target_reference_wav") val targetReferenceWav: String,
    /** 192-dim Target-Centroid (L2-normiertes Mittel der Referenz-Embeddings). */
    @SerializedName("target_centroid") val targetCentroid: List<Double>,
    @SerializedName("session_wavs") val sessionWavs: List<String>,
    val parameters: GoldenParameters,
    @SerializedName("model_sha256") val modelSha256: Map<String, String>,
    val versions: Map<String, String>,
)

class GoldenParameters(
    @SerializedName("sample_rate") val sampleRate: Int,
    @SerializedName("chunk_duration_s") val chunkDurationS: Double,
    @SerializedName("chunk_overlap_s") val chunkOverlapS: Double,
    @SerializedName("chunk_samples") val chunkSamples: Int,
    @SerializedName("last_chunk_zero_padded") val lastChunkZeroPadded: Boolean,
    val preprocessing: String,
    @SerializedName("use_noise_reduction") val useNoiseReduction: Boolean,
    @SerializedName("use_pyannote_overlap") val usePyannoteOverlap: Boolean,
    @SerializedName("overlap_shadow_recorded") val overlapShadowRecorded: Boolean,
    @SerializedName("overlap_cleaner") val overlapCleaner: GoldenOverlapCleanerParams,
    @SerializedName("use_silero_vad") val useSileroVad: Boolean,
    @SerializedName("vad_threshold") val vadThreshold: Double,
    @SerializedName("vad_window_size_samples") val vadWindowSizeSamples: Int,
    @SerializedName("min_speech_seconds") val minSpeechSeconds: Double,
    @SerializedName("embedding_model") val embeddingModel: String,
    @SerializedName("assignment_strategy") val assignmentStrategy: String,
    @SerializedName("threshold_target") val thresholdTarget: Double,
    @SerializedName("threshold_normal") val thresholdNormal: Double,
    val similarity: String,
    @SerializedName("centroid_update_strategy") val centroidUpdateStrategy: String,
    @SerializedName("ema_alpha") val emaAlpha: Double,
    @SerializedName("moving_average_window_size") val movingAverageWindowSize: Int,
    @SerializedName("min_samples_for_new_speaker") val minSamplesForNewSpeaker: Int,
    @SerializedName("pool_extraction_strategy") val poolExtractionStrategy: String,
    @SerializedName("time_per_chunk_s") val timePerChunkS: Double,
)

class GoldenOverlapCleanerParams(
    @SerializedName("window_duration_s") val windowDurationS: Double,
    @SerializedName("audio_merge_strategy") val audioMergeStrategy: String,
    @SerializedName("min_duration_s") val minDurationS: Double,
    @SerializedName("min_island_duration_s") val minIslandDurationS: Double,
    val streaming: Boolean,
)

// ── Loader ────────────────────────────────────────────────────────────────────

object GoldenData {
    private val gson = Gson()

    const val CONVERSATION2 = "golden_test_conversation2.json"
    const val OVERLAP_SYNTHETIC = "golden_test_overlap_synthetic.json"
    const val META = "meta.json"

    fun loadSession(file: File): GoldenSession =
        file.bufferedReader().use { gson.fromJson(it, GoldenSession::class.java) }

    fun loadConversation2(): GoldenSession = loadSession(TestPaths.golden(CONVERSATION2))

    fun loadOverlapSynthetic(): GoldenSession = loadSession(TestPaths.golden(OVERLAP_SYNTHETIC))

    fun loadMeta(): GoldenMeta =
        TestPaths.golden(META).bufferedReader().use { gson.fromJson(it, GoldenMeta::class.java) }
}
