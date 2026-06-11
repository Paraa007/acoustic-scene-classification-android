package com.fzi.speakerid.library.data

import com.fzi.speakerid.library.pipeline.steps.MatchingSimilarity
import com.fzi.speakerid.library.pipeline.steps.SpeakerExtraction
import com.fzi.speakerid.library.pipeline.steps.SpeakerMatcher

/**
 * Eintrag der Chunk-History — Pendant zu den Dicts in `dm.chunk_history`
 * (`{"id", "status", "prev_id"}`). `id == null` markiert Stille; nach einer
 * Pool-Promotion wird der Eintrag mit der neuen Sprecher-ID und
 * `prevId = "0"` ueberschrieben.
 */
data class HistoryEntry(
    val id: String?,
    val status: String,
    val prevId: String? = null,
)

/**
 * Port der siqas-Sprecherverwaltung: `src/library/cluster_manager.py::
 * ClusterManager` plus der Live-Flow-Logik aus `gui/data/data_manager.py`
 * (`add_new_embedding`, `process_unlabeled_pool`, `reset`) und
 * `gui/services/live_processor.py::_apply_results` — exakt wie in
 * `golden/generate_golden.py::process_session` nachgebildet.
 *
 * Reservierte Cluster: "0" Unlabeled-Pool, "1" Target, "-1" Stille; neue
 * Sprecher erhalten "2", "3", ... Die [clusters]-Map ist insertion-ordered
 * (Python-Dict-Semantik) — Matching und Golden-Reihenfolge haengen daran.
 *
 * GUI-Default-Parameter (meta.json): best_overall 0.7/0.7, static
 * alpha=0.1 window=10, Clique-Pool thr=normalThreshold min=3.
 *
 * Nicht portiert (nicht auf dem Golden-Pfad): `merge_clusters`,
 * `reevaluate_unlabeled_pool`, `find_merge_candidates`.
 */
class SpeakerManager(
    targetCentroid: DoubleArray? = null,
    val assignmentStrategy: String = SpeakerMatcher.STRATEGY_BEST_OVERALL,
    val targetThreshold: Double = 0.7,
    val normalThreshold: Double = 0.7,
    val centroidUpdateStrategy: String = "static",
    val emaAlpha: Double = 0.1,
    val movingAverageWindowSize: Int = 10,
    val minSamplesForNewSpeaker: Int = 3,
    val poolExtractionStrategy: String = SpeakerExtraction.STRATEGY_CLIQUE,
    val chunkDurationS: Double = 1.0,
    val chunkOverlapS: Double = 0.0,
) {

    init {
        require(chunkDurationS > 0.0) { "chunkDurationS muss > 0 sein" }
    }

    private val initialTargetCentroid: DoubleArray? = targetCentroid?.copyOf()

    /** Insertion-ordered wie Python-Dict; Schluessel = Cluster-IDs. */
    val clusters: LinkedHashMap<String, Cluster> = LinkedHashMap()

    /** Ein Eintrag pro verarbeitetem Chunk; wird bei Promotion retroaktiv umgeschrieben. */
    val chunkHistory: MutableList<HistoryEntry> = mutableListOf()

    init {
        reset()
    }

    /** Convenience fuer den Target-Centroid aus meta.json (`List<Double>`). */
    constructor(targetCentroid: List<Double>) : this(targetCentroid.toDoubleArray())

    val target: Cluster get() = clusters.getValue(RESERVED_TARGET)
    val unlabeled: Cluster get() = clusters.getValue(RESERVED_UNLABELED)
    val silence: Cluster get() = clusters.getValue(RESERVED_SILENCE)

    /**
     * Port von `DataManager.reset` + Target-Registrierung
     * (`explorer._on_target_success`): frische Cluster "0"/"1"/"-1", Target-
     * Centroid gesetzt, leere History.
     */
    fun reset() {
        clusters.clear()
        clusters[RESERVED_UNLABELED] = Cluster(RESERVED_UNLABELED)
        clusters[RESERVED_TARGET] = Cluster(RESERVED_TARGET)
        clusters[RESERVED_SILENCE] = Cluster(RESERVED_SILENCE)
        initialTargetCentroid?.let { clusters.getValue(RESERVED_TARGET).centroid = it.copyOf() }
        chunkHistory.clear()
    }

    /** Ergebnis von [applyResult]: Live-Zuordnung + ggf. Pool-Promotion. */
    data class ApplyResult(val assignedId: String, val promotionId: String?)

    /**
     * Port von `LiveProcessor._apply_results`: verarbeitet das Ergebnis eines
     * Chunks (Embedding oder Stille). `durationS` ist die Segment-Dauer
     * (Live-Flow: ganzer Chunk = 1.0 s), die wie in Python mit dem
     * Overlap-Faktor skaliert als Zeit verbucht wird.
     *
     * Bei Zuordnung in den Unlabeled-Pool ("0") laeuft sofort das
     * Clique-Mining; eine Promotion schreibt die betroffenen History-
     * Eintraege retroaktiv auf die neue Sprecher-ID um (=> final_sprecher).
     */
    fun applyResult(
        embedding: DoubleArray?,
        isSilent: Boolean,
        durationS: Double = 1.0,
        startTimeS: Double = 0.0,
    ): ApplyResult {
        val overlapFactor = (chunkDurationS - chunkOverlapS) / chunkDurationS
        val realTimeAdded = durationS * overlapFactor

        if (isSilent) {
            chunkHistory.add(HistoryEntry(id = null, status = STATUS_SILENCE))
            addNewEmbedding(
                embedding = null,
                clusterId = RESERVED_SILENCE,
                time = realTimeAdded,
                historyIdx = chunkHistory.size - 1,
                startTime = startTimeS,
            )
            return ApplyResult(RESERVED_SILENCE, null)
        }

        val vector = requireNotNull(embedding) { "Speech-Ergebnis ohne Embedding" }
        val matchedId = findClusterForEmbedding(vector)

        chunkHistory.add(HistoryEntry(id = matchedId, status = STATUS_SPEECH))
        addNewEmbedding(
            embedding = vector,
            clusterId = matchedId,
            time = realTimeAdded,
            historyIdx = chunkHistory.size - 1,
            startTime = startTimeS,
        )

        val promotionId = if (matchedId == RESERVED_UNLABELED) processUnlabeledPool() else null
        return ApplyResult(matchedId, promotionId)
    }

    /** Port von `DataManager.add_new_embedding` (ohne Kivy-Dispatch). */
    fun addNewEmbedding(
        embedding: DoubleArray?,
        clusterId: String = RESERVED_UNLABELED,
        time: Double = 1.0,
        historyIdx: Int? = null,
        startTime: Double? = null,
    ) {
        val cluster = clusters.getOrPut(clusterId) { Cluster(clusterId) }
        cluster.addEmbedding(
            embedding = embedding,
            time = time,
            updateStrategy = centroidUpdateStrategy,
            historyIdx = historyIdx,
            startTime = startTime,
            alpha = emaAlpha,
            windowSize = movingAverageWindowSize,
        )
    }

    /** Matching mit den konfigurierten Schwellwerten (siehe [SpeakerMatcher]). */
    fun findClusterForEmbedding(embedding: DoubleArray): String =
        SpeakerMatcher.findClusterForEmbedding(
            newEmbedding = embedding,
            clustersDict = clusters,
            strategyName = assignmentStrategy,
            targetThreshold = targetThreshold,
            normalThreshold = normalThreshold,
        )

    /**
     * Similarity je Kandidaten-Cluster aus Sicht von best_overall (Cluster
     * ohne Centroid und der Pool werden uebersprungen) — entspricht dem
     * golden Feld `similarity_scores`.
     */
    fun similarityScores(embedding: DoubleArray): Map<String, Double> {
        val scores = LinkedHashMap<String, Double>()
        for ((cid, cluster) in clusters) {
            val centroid = cluster.centroid ?: continue
            if (cluster.isUnlabeled) continue
            scores[cid] = MatchingSimilarity.calculateNormalizedSimilarity(embedding, centroid)
        }
        return scores
    }

    /** Port von `ClusterManager.next_speaker_id`: echte Sprecher beginnen bei "2". */
    fun nextSpeakerId(): String {
        val realSpeakerIds = clusters.keys
            .filter { it.isNotEmpty() && it.all { ch -> ch.isDigit() } }
            .map { it.toInt() }
            .filter { it > 1 }
        return if (realSpeakerIds.isEmpty()) "2" else (realSpeakerIds.max() + 1).toString()
    }

    /** Port von `ClusterManager.create_speaker`. */
    fun createSpeaker(clusterId: String? = null): Cluster {
        val cid = clusterId ?: nextSpeakerId()
        val existing = clusters[cid]
        if (existing != null) {
            if (cid == RESERVED_TARGET && existing.occurrences == 0) return existing
            throw IllegalArgumentException("Cluster '$cid' existiert bereits")
        }
        val cluster = Cluster(cid)
        clusters[cid] = cluster
        return cluster
    }

    /**
     * Port von `ClusterManager.process_unlabeled_pool` (Parameter wie
     * `DataManager.process_unlabeled_pool` aus der Konfiguration):
     * Clique-Mining im Pool; bei Erfolg werden die Gruendungs-Punkte in den
     * neuen Sprecher verschoben (Pool-Zeiten OHNE Clamping reduziert, exakt
     * wie Python), die History retroaktiv umgeschrieben und der Centroid
     * unabhaengig von der Update-Strategie als Mittel der Gruendungs-
     * Embeddings gesetzt.
     */
    fun processUnlabeledPool(): String? {
        val pool = clusters[RESERVED_UNLABELED] ?: return null

        val similarPoints = SpeakerExtraction.extractNewClusterFromPool(
            poolEmbeddings = pool.embeddings.toList(),
            threshold = normalThreshold,
            minSamples = minSamplesForNewSpeaker,
            strategy = poolExtractionStrategy,
        )
        if (similarPoints.isEmpty()) return null

        val newId = nextSpeakerId()
        val newCluster = createSpeaker(newId)

        for (point in similarPoints) {
            val poolIdx = pool.embeddings.indexOfFirst { it === point }
            if (poolIdx >= 0) {
                pool.embeddings.removeAt(poolIdx)
                pool.totalTime -= point.time
                pool.occurrences -= 1
            }

            // Python forwarded hier nur history_idx; startTime bleibt als
            // reines Metadatum zusaetzlich erhalten (verhaltensneutral).
            newCluster.addEmbedding(
                embedding = point.embedding,
                time = point.time,
                updateStrategy = centroidUpdateStrategy,
                historyIdx = point.historyIdx,
                startTime = point.startTime,
                alpha = emaAlpha,
                windowSize = movingAverageWindowSize,
            )

            val historyIdx = point.historyIdx
            if (historyIdx != null && historyIdx < chunkHistory.size) {
                chunkHistory[historyIdx] = HistoryEntry(
                    id = newId,
                    status = chunkHistory[historyIdx].status,
                    prevId = RESERVED_UNLABELED,
                )
            }
        }

        // Centroid = Mittel aller Gruendungs-Embeddings, unabhaengig von der Strategie.
        newCluster.centroid = null
        newCluster.updateCentroid("mean", emaAlpha, movingAverageWindowSize)
        return newId
    }

    // ── Auswertung (Port der ClusterManager-Analytics) ────────────────────────

    /** Port von `active_speakers_count`: numerische IDs > 0 mit Centroid. */
    val activeSpeakersCount: Int
        get() = clusters.count { (cid, cluster) ->
            cid.isNotEmpty() && cid.all { it.isDigit() } && cid.toInt() > 0 && cluster.centroid != null
        }

    /** Port von `unlabeled_ratio` (Anteil Pool an Gesamtzeit, in %). */
    val unlabeledRatio: Double
        get() {
            val totalTime = clusters.values.sumOf { it.totalTime }
            if (totalTime <= 0.0) return 0.0
            return (clusters[RESERVED_UNLABELED]?.totalTime ?: 0.0) / totalTime * 100.0
        }

    /** Port von `silence_ratio` (Anteil Stille an Gesamtzeit, in %). */
    val silenceRatio: Double
        get() {
            val totalTime = clusters.values.sumOf { it.totalTime }
            if (totalTime <= 0.0) return 0.0
            return (clusters[RESERVED_SILENCE]?.totalTime ?: 0.0) / totalTime * 100.0
        }

    /** Port von `total_embeddings_count`. */
    val totalEmbeddingsCount: Int
        get() = clusters.values.sumOf { it.embeddings.size }

    /** Port von `unlabeled_samples_count`. */
    val unlabeledSamplesCount: Int
        get() = clusters[RESERVED_UNLABELED]?.embeddings?.size ?: 0

    /** Port von `get_valid_speakers`: echte Sprecher (inkl. Target) ueber `minTime`. */
    fun getValidSpeakers(minTime: Double = 0.0): List<Cluster> =
        clusters.values.filter { !it.isUnlabeled && !it.isSilence && it.totalTime > minTime }

    /** Port von `total_valid_time`. */
    val totalValidTime: Double
        get() = getValidSpeakers().sumOf { it.totalTime }

    /** Port von `identified_ratio` (in %). */
    val identifiedRatio: Double
        get() {
            val totalValid = totalValidTime
            val unlabeledTime = clusters[RESERVED_UNLABELED]?.totalTime ?: 0.0
            val totalSpeech = totalValid + unlabeledTime
            return if (totalSpeech > 0.0) totalValid / totalSpeech * 100.0 else 0.0
        }

    /**
     * End-Sprechzeiten je echtem Sprecher (ohne Pool/Stille, nur > 0) —
     * entspricht dem golden Summary-Feld `sprechzeit_pro_sprecher_s`.
     */
    fun speechTimesPerSpeaker(): Map<String, Double> =
        clusters
            .filter { (cid, cluster) ->
                cid != RESERVED_UNLABELED && cid != RESERVED_SILENCE && cluster.totalTime > 0.0
            }
            .mapValues { it.value.totalTime }

    /**
     * Finale Sprecher-ID je Chunk nach allen retroaktiven Promotions
     * (`final_sprecher`): History-ID, Stille als "-1".
     */
    fun finalSpeakerIds(): List<String> =
        chunkHistory.map { it.id ?: RESERVED_SILENCE }

    override fun toString(): String =
        "SpeakerManager(${clusters.size} clusters: ${clusters.keys.toList()})"

    companion object {
        const val RESERVED_TARGET = "1"
        const val RESERVED_UNLABELED = "0"
        const val RESERVED_SILENCE = "-1"

        const val STATUS_SPEECH = "speech"
        const val STATUS_SILENCE = "silence"
    }
}
