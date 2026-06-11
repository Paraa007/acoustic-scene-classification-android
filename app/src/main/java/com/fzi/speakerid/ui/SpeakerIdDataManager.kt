package com.fzi.speakerid.ui

import android.content.Context
import android.util.Log
import com.fzi.speakerid.library.data.Cluster
import com.fzi.speakerid.library.data.ClusterEmbedding
import com.fzi.speakerid.library.data.HistoryEntry
import com.fzi.speakerid.library.data.SpeakerManager
import com.fzi.speakerid.library.pipeline.steps.SpeakerMatcher
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/**
 * Port von siqas `gui/data/data_manager.py::DataManager` — zentraler
 * Zustandsverwalter der Speaker-ID-GUI.
 *
 * Observer-Mechanik:
 *  - Alle Einstellungen/Status-Felder sind oeffentliche [MutableStateFlow]s
 *    (Kivy-Property-Pendants). Screens lesen `flow.value`, schreiben
 *    `flow.value = x` und binden sich per `collect` (wie Kivys `bind`).
 *  - Cluster-Mutationen werden wie Pythons
 *    `self.property('clusters').dispatch(self)` ueber [clustersVersion]
 *    signalisiert: bei jeder Aenderung zaehlt der Wert hoch; Screens
 *    collecten [clustersVersion] und lesen dann [clusters]/[chunkHistory] neu.
 *
 * Threading: Mutierende Operationen sind `@Synchronized` (Monitor = diese
 * Instanz). Der [SpeakerSessionController] synchronisiert seine
 * LiveProcessor-Aufrufe auf dasselbe Objekt; UI-seitige Snapshots holt man
 * sich ueber [clustersSnapshot].
 *
 * Der Cluster-Zustand selbst lebt in einem Phase-1-[SpeakerManager]
 * (Pendant zu Pythons `clusters`-Dict + `ClusterManager`). Da dessen
 * Parameter Konstruktor-final sind, wird er bei Einstellungs-Aenderungen
 * lazy neu aufgebaut (Cluster/History werden uebernommen) — nie waehrend
 * einer laufenden Aufnahme ([isRecordingRunning]).
 */
class SpeakerIdDataManager private constructor(private val appContext: Context?) {

    // ── Einstellungen & Status (Kivy-Properties -> MutableStateFlow) ────────

    /** `is_pipeline_running` */
    val isPipelineRunning = MutableStateFlow(false)

    /** `is_recording_running` */
    val isRecordingRunning = MutableStateFlow(false)

    /** `threshold_target` (gui_config.THRESHOLD_TARGET) */
    val thresholdTarget = MutableStateFlow(THRESHOLD_TARGET)

    /** `threshold_normal` (gui_config.THRESHOLD_NORMAL) */
    val thresholdNormal = MutableStateFlow(THRESHOLD_NORMAL)

    /** `centroid_update_strategy`: "static" | "mean" | "ema" | "moving_average" */
    val centroidUpdateStrategy = MutableStateFlow("static")

    /** `ema_alpha` */
    val emaAlpha = MutableStateFlow(0.1)

    /** `moving_average_window_size` */
    val movingAverageWindowSize = MutableStateFlow(10)

    /** `chunk_duration` — Laenge eines Audio-Chunks in Sekunden. */
    val chunkDuration = MutableStateFlow(CHUNK_DURATION)

    /** `cluster_assignment_strategy` (gui_config.DEFAULT_ASSIGNMENT) */
    val clusterAssignmentStrategy = MutableStateFlow(DEFAULT_ASSIGNMENT)

    /** `use_silero_vad` */
    val useSileroVad = MutableStateFlow(true)

    /** `use_pyannote` — Pyannote-Overlap-Cleaner (Solo-Only-Splitting). */
    val usePyannote = MutableStateFlow(false)

    /**
     * `use_noise_reduction` — GUI-Toggle; der Denoise-Schritt selbst wurde in
     * Phase 1 bewusst nicht portiert, der Toggle hat im Port keine Wirkung.
     */
    val useNoiseReduction = MutableStateFlow(false)

    /** `noise_strategy` */
    val noiseStrategy = MutableStateFlow("spectral_subtract")

    /** `cleaner_min_duration` */
    val cleanerMinDuration = MutableStateFlow(0.3)

    /** `cleaner_min_island_duration` */
    val cleanerMinIslandDuration = MutableStateFlow(0.08)

    /** `projection_method` (gui_config.DEFAULT_PROJECTION) */
    val projectionMethod = MutableStateFlow(DEFAULT_PROJECTION)

    /** `is_mobile` — auf Android immer true (Python: config.IS_MOBILE). */
    val isMobile = MutableStateFlow(true)

    /** `current_active_voices` — "-", "0".."2", "3+" (nur Pyannote-Pfad). */
    val currentActiveVoices = MutableStateFlow("-")

    /** `use_virtual_mic` — true: Datei-Simulation, false: echtes Mikrofon. */
    val useVirtualMic = MutableStateFlow(true)

    /** `current_audio_path` — WAV fuer die Datei-Simulation. */
    val currentAudioPath = MutableStateFlow("")

    /** `target_audio_path` — Referenzaufnahme des Zielsprechers. */
    val targetAudioPath = MutableStateFlow("")

    /** `selected_points` — [neueste, vorherige] Embedding-ID. */
    val selectedPoints = MutableStateFlow<List<String?>>(listOf(null, null))

    /** `expert_mode_active` */
    val expertModeActive = MutableStateFlow(false)

    /** `speaker_positions_2d` — cluster_id -> [x, y] (Centroid-Projektion). */
    val speakerPositions2d = MutableStateFlow<Map<String, FloatArray>>(emptyMap())

    /** `embedding_coords_2d` — [x, y] pro Embedding (Reihenfolge wie Projektion). */
    val embeddingCoords2d = MutableStateFlow<List<FloatArray>>(emptyList())

    // ── Cluster-Zustand & Dispatch ───────────────────────────────────────────

    private val _clustersVersion = MutableStateFlow(0)

    /**
     * Pendant zu Kivys `clusters`-Dispatch: zaehlt bei jeder Cluster-/History-
     * Mutation hoch. Screens collecten diesen Flow und lesen dann neu.
     */
    val clustersVersion: StateFlow<Int> = _clustersVersion.asStateFlow()

    private var manager: SpeakerManager = buildManager()

    init {
        // Python `__init__` -> `reset()` -> u. a. `load_target_cache()`.
        loadTargetCache()
    }

    /** `clusters` — Live-Sicht (Insertion-Order wie Python-Dict). */
    val clusters: Map<String, Cluster>
        get() = manager.clusters

    /** `chunk_history` — ein Eintrag pro verarbeitetem Chunk. */
    val chunkHistory: List<HistoryEntry>
        get() = manager.chunkHistory

    /** Thread-sicherer (flacher) Schnappschuss von [clusters] fuer die UI. */
    @Synchronized
    fun clustersSnapshot(): Map<String, Cluster> = LinkedHashMap(manager.clusters)

    /** Thread-sicherer Schnappschuss von [chunkHistory]. */
    @Synchronized
    fun chunkHistorySnapshot(): List<HistoryEntry> = manager.chunkHistory.toList()

    /**
     * Der unterliegende Phase-1-[SpeakerManager] — wird vom
     * [SpeakerSessionController] an den LiveProcessor gereicht. Synchronisiert
     * bei Bedarf die Einstellungen (nie waehrend laufender Aufnahme).
     */
    @Synchronized
    fun speakerManager(): SpeakerManager {
        if (!isRecordingRunning.value && !managerMatchesSettings()) {
            val old = manager
            manager = buildManager().also { fresh ->
                fresh.clusters.clear()
                fresh.clusters.putAll(old.clusters)
                fresh.chunkHistory.clear()
                fresh.chunkHistory.addAll(old.chunkHistory)
            }
        }
        return manager
    }

    /** Pendant zu `self.property('clusters').dispatch(self)`. */
    fun dispatchClusters() {
        _clustersVersion.update { it + 1 }
    }

    private fun buildManager(): SpeakerManager = SpeakerManager(
        targetCentroid = null,
        assignmentStrategy = clusterAssignmentStrategy.value,
        targetThreshold = thresholdTarget.value,
        normalThreshold = thresholdNormal.value,
        centroidUpdateStrategy = centroidUpdateStrategy.value,
        emaAlpha = emaAlpha.value,
        movingAverageWindowSize = movingAverageWindowSize.value,
        minSamplesForNewSpeaker = MIN_SAMPLES_FOR_NEW_SPEAKER,
        chunkDurationS = chunkDuration.value,
        chunkOverlapS = CHUNK_OVERLAP,
    )

    private fun managerMatchesSettings(): Boolean =
        manager.assignmentStrategy == clusterAssignmentStrategy.value &&
            manager.targetThreshold == thresholdTarget.value &&
            manager.normalThreshold == thresholdNormal.value &&
            manager.centroidUpdateStrategy == centroidUpdateStrategy.value &&
            manager.emaAlpha == emaAlpha.value &&
            manager.movingAverageWindowSize == movingAverageWindowSize.value &&
            manager.chunkDurationS == chunkDuration.value

    // ── Abgeleitete Werte (Python AliasProperties) ───────────────────────────

    /** `num_speakers` — aktive Sprecher (numerische ID > 0 mit Centroid). */
    val numSpeakers: Int get() = manager.activeSpeakersCount

    /** `num_embeddings` — Embeddings ueber alle Cluster. */
    val numEmbeddings: Int get() = manager.totalEmbeddingsCount

    /** `unlabeled_count` — Embeddings im Pool "0". */
    val unlabeledCount: Int get() = manager.unlabeledSamplesCount

    /** `unlabeled_percentage` — Pool-Anteil an der Gesamtzeit in % (0..100). */
    val unlabeledPercentage: Double get() = manager.unlabeledRatio

    /** `silence_percentage` — Stille-Anteil an der Gesamtzeit in % (0..100). */
    val silencePercentage: Double get() = manager.silenceRatio

    // ── Operationen (Port der Python-Methoden) ──────────────────────────────

    /** Port von `DataManager.reset`. */
    @Synchronized
    fun reset() {
        speakerManager().reset()
        loadTargetCache()
        isPipelineRunning.value = false
        isRecordingRunning.value = false
        speakerPositions2d.value = emptyMap()
        embeddingCoords2d.value = emptyList()
        dispatchClusters()
    }

    /** Port von `new_point_selected`: [neu, bisher-neuester]. */
    fun newPointSelected(embeddingId: String) {
        selectedPoints.value = listOf(embeddingId, selectedPoints.value.getOrNull(0))
    }

    /** Port von `add_new_cluster`. */
    @Synchronized
    fun addNewCluster(clusterId: String) {
        speakerManager().clusters[clusterId] = Cluster(clusterId)
        dispatchClusters()
    }

    /**
     * Port von `add_new_embedding`: legt das Cluster bei Bedarf an, fuegt das
     * Embedding mit der aktuellen Centroid-Update-Strategie (+ alpha/window)
     * hinzu und informiert Beobachter.
     */
    @Synchronized
    fun addNewEmbedding(
        embedding: DoubleArray?,
        clusterId: String = SpeakerManager.RESERVED_UNLABELED,
        time: Double = 1.0,
        historyIdx: Int? = null,
        startTime: Double? = null,
    ) {
        speakerManager().addNewEmbedding(embedding, clusterId, time, historyIdx, startTime)
        dispatchClusters()
    }

    /** Port von `delete_cluster`. */
    @Synchronized
    fun deleteCluster(clusterId: String) {
        if (speakerManager().clusters.remove(clusterId) != null) {
            dispatchClusters()
        }
    }

    /**
     * Port von `merge_clusters` (= `ClusterManager.merge_clusters`):
     * migriert alle Embeddings von `sourceId` nach `targetId`, schreibt die
     * History um (`prev_id` = Quelle) und loescht das Quell-Cluster.
     */
    @Synchronized
    fun mergeClusters(sourceId: String, targetId: String): Boolean {
        val mgr = speakerManager()
        val src = mgr.clusters[sourceId] ?: return false
        val tgt = mgr.clusters[targetId] ?: return false

        for (emb in src.embeddings.toList()) {
            tgt.addEmbedding(
                embedding = emb.embedding,
                time = emb.time,
                updateStrategy = centroidUpdateStrategy.value,
                historyIdx = emb.historyIdx,
                alpha = emaAlpha.value,
                windowSize = movingAverageWindowSize.value,
            )
        }

        val history = mgr.chunkHistory
        for (i in history.indices) {
            val entry = history[i]
            if (entry.id == sourceId) {
                history[i] = HistoryEntry(id = targetId, status = entry.status, prevId = sourceId)
            }
        }

        mgr.clusters.remove(sourceId)
        dispatchClusters()
        return true
    }

    /** Port von `get_all_cluster_ids`. */
    fun getAllClusterIds(): List<String> = manager.clusters.keys.toList()

    /** Port von `get_cluster_embeddings`. */
    fun getClusterEmbeddings(clusterId: String): List<ClusterEmbedding> =
        manager.clusters[clusterId]?.embeddings ?: emptyList()

    /**
     * Port von `process_new_audio_point`: Matching mit den aktuellen
     * Schwellwerten/Strategien, Embedding verbuchen, beste Cluster-ID zurueck.
     */
    @Synchronized
    fun processNewAudioPoint(newEmbedding: DoubleArray, time: Double = 1.0): String {
        val mgr = speakerManager()
        val bestClusterId = mgr.findClusterForEmbedding(newEmbedding)
        mgr.addNewEmbedding(newEmbedding, bestClusterId, time)
        dispatchClusters()
        return bestClusterId
    }

    /**
     * Port von `process_unlabeled_pool`: Clique-Mining im Sammelbecken "0";
     * bei Erfolg neue Sprecher-ID (History wird retroaktiv umgeschrieben).
     */
    @Synchronized
    fun processUnlabeledPool(): String? {
        val newId = speakerManager().processUnlabeledPool()
        if (newId != null) dispatchClusters()
        return newId
    }

    /**
     * Port von `reevaluate_unlabeled_pool` (= `ClusterManager.
     * reevaluate_unlabeled_pool`): prueft alle Pool-Embeddings erneut gegen
     * die echten Sprecher-Cluster und weist Treffer nachtraeglich zu.
     */
    @Synchronized
    fun reevaluateUnlabeledPool() {
        val mgr = speakerManager()
        val pool = mgr.clusters[SpeakerManager.RESERVED_UNLABELED] ?: return
        if (pool.embeddings.isEmpty()) return

        val candidateClusters = mgr.clusters.filter { (_, cluster) ->
            !cluster.isUnlabeled && !cluster.isSilence && cluster.centroid != null
        }
        if (candidateClusters.isEmpty()) return

        // `speaker_extraction.reevaluate_unlabeled_pool`
        val toAssign = ArrayList<Pair<ClusterEmbedding, String>>()
        for (p in pool.embeddings.toList()) {
            val vector = p.embedding ?: continue
            val matchedId = SpeakerMatcher.findClusterForEmbedding(
                newEmbedding = vector,
                clustersDict = candidateClusters,
                strategyName = clusterAssignmentStrategy.value,
                targetThreshold = thresholdTarget.value,
                normalThreshold = thresholdNormal.value,
            )
            if (matchedId != SpeakerManager.RESERVED_UNLABELED) {
                toAssign.add(p to matchedId)
            }
        }
        if (toAssign.isEmpty()) return

        val history = mgr.chunkHistory
        for ((p, matchedId) in toAssign) {
            pool.removeEmbedding(p)
            mgr.clusters.getValue(matchedId).addEmbedding(
                embedding = p.embedding,
                time = p.time,
                updateStrategy = centroidUpdateStrategy.value,
                historyIdx = p.historyIdx,
                alpha = emaAlpha.value,
                windowSize = movingAverageWindowSize.value,
            )
            val hIdx = p.historyIdx
            if (hIdx != null && hIdx < history.size) {
                history[hIdx] = HistoryEntry(
                    id = matchedId,
                    status = history[hIdx].status,
                    prevId = SpeakerManager.RESERVED_UNLABELED,
                )
            }
        }
        Log.i(TAG, "Re-Evaluation: ${toAssign.size} Pool-Punkte nachtraeglich zugewiesen.")
        dispatchClusters()
    }

    // ── Target-Profil-Persistenz (Port von `target_profile.py`) ─────────────

    private val targetCacheFile: File?
        get() = appContext?.let { File(File(it.filesDir, "speakerid"), "target_cache.json") }

    /** Port von `save_target_cache`: Target-Centroid + Pfad als JSON. */
    @Synchronized
    fun saveTargetCache(): Boolean {
        val centroid = manager.clusters[SpeakerManager.RESERVED_TARGET]?.centroid ?: return false
        val file = targetCacheFile ?: return false
        return try {
            file.parentFile?.mkdirs()
            val json = JSONObject()
                .put("centroid", JSONArray().also { arr -> centroid.forEach { arr.put(it) } })
                .put("path", targetAudioPath.value)
            file.writeText(json.toString())
            Log.i(TAG, "Profil gespeichert: ${targetAudioPath.value}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Speichern: $e")
            false
        }
    }

    /** Port von `load_target_cache`: setzt Target-Centroid + Pfad aus dem Cache. */
    @Synchronized
    fun loadTargetCache(): Boolean {
        val file = targetCacheFile ?: return false
        if (!file.exists()) return false
        return try {
            val json = JSONObject(file.readText())
            val arr = json.getJSONArray("centroid")
            val centroid = DoubleArray(arr.length()) { arr.getDouble(it) }
            val target = manager.clusters[SpeakerManager.RESERVED_TARGET] ?: return false
            target.centroid = centroid
            targetAudioPath.value = json.optString("path", targetAudioPath.value)
            Log.i(TAG, "Profil erfolgreich geladen.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Laden: $e")
            false
        }
    }

    // ── RTTM-Export (Port von `library/rttm.py::export_history_to_rttm`) ────

    /**
     * Exportiert die Chunk-History als RTTM-Zeilen; fasst aufeinanderfolgende
     * Chunks desselben Sprechers zusammen, ignoriert Stille und Pool ("0").
     */
    @Synchronized
    fun exportHistoryToRttm(audioId: String = "live_session"): List<String> {
        val history = manager.chunkHistory
        if (history.isEmpty()) return emptyList()

        val speakerIds = history.map { entry ->
            if (entry.status == SpeakerManager.STATUS_SILENCE) "-1" else entry.id
        }

        // `merge_chunks_into_segments`
        var step = chunkDuration.value - CHUNK_OVERLAP
        if (step <= 0) step = 1.0
        val duration = chunkDuration.value
        val segments = ArrayList<Triple<String, Double, Double>>()
        var current: Triple<String, Double, Double>? = null
        for ((idx, speakerId) in speakerIds.withIndex()) {
            val start = idx * step
            val end = start + duration
            if (speakerId == null || speakerId == "0" || speakerId == "-1") {
                current?.let { segments.add(it) }
                current = null
                continue
            }
            val cur = current
            current = when {
                cur == null -> Triple(speakerId, start, end)
                cur.first == speakerId && kotlin.math.abs(cur.third - start) < 1e-6 ->
                    Triple(cur.first, cur.second, end)
                else -> {
                    segments.add(cur)
                    Triple(speakerId, start, end)
                }
            }
        }
        current?.let { segments.add(it) }

        // `format_segments_to_rttm_lines`
        return segments.map { (speakerId, start, end) ->
            String.format(
                java.util.Locale.US,
                "SPEAKER %s 0 %.6f %.6f <NA> <NA> %s <NA> <NA>",
                audioId, start, end - start, speakerId,
            )
        }
    }

    companion object {
        private const val TAG = "SpeakerIdDataManager"

        // gui_config.py-Defaults
        const val THRESHOLD_TARGET = 0.7
        const val THRESHOLD_NORMAL = 0.7
        const val MIN_SAMPLES_FOR_NEW_SPEAKER = 3
        const val CHUNK_DURATION = 1.0
        const val CHUNK_OVERLAP = 0.0
        const val DEFAULT_PROJECTION = "PCA"
        const val DEFAULT_ASSIGNMENT = SpeakerMatcher.STRATEGY_BEST_OVERALL

        @Volatile
        private var INSTANCE: SpeakerIdDataManager? = null

        /** Singleton — Pendant zu `app.data_manager`. */
        @JvmStatic
        fun getInstance(context: Context): SpeakerIdDataManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SpeakerIdDataManager(context.applicationContext)
                    .also { INSTANCE = it }
            }
    }
}
