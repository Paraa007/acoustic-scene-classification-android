package com.fzi.speakerid.library.data

import com.fzi.speakerid.library.pipeline.steps.CentroidUpdate
import kotlin.math.sqrt

/**
 * Ein im Cluster gespeichertes Embedding — Pendant zum Python-`emb_dict`
 * (`{"embedding", "time", "history_idx", "start_time"}`) aus
 * `Cluster.add_embedding`. Entfernen erfolgt identitaetsbasiert (Python nutzt
 * `list.remove` auf demselben Objekt).
 */
class ClusterEmbedding(
    val embedding: DoubleArray?,
    val time: Double,
    /** Index in der Chunk-History — Schluessel fuer die retroaktive Pool-Promotion. */
    val historyIdx: Int? = null,
    /** Startzeit (Chunk-Index) im Original-Audio; reines Metadatum. */
    val startTime: Double? = null,
)

/**
 * Port von siqas `src/library/cluster.py::Cluster`:
 * repraesentiert einen Sprecher oder eine Audiogruppe (Stille, Unlabeled-Pool).
 *
 * Verhalten exakt wie Python:
 * - `addEmbedding` zaehlt IMMER `occurrences`/`totalTime` hoch; Centroid-Update
 *   nur bei echtem Vektor und weder Silence- noch Unlabeled-Cluster.
 * - Erstes Embedding initialisiert den Centroid immer "static" (= normiertes
 *   Mittel), danach greift die uebergebene Update-Strategie.
 * - [std] wird wie `_recompute_std` als mittlere Kosinus-Distanz aller Paare
 *   berechnet (hier in Double statt numpy-float32; rein informativ).
 */
class Cluster(clusterId: String) {

    val id: String = clusterId

    /** Centroid als 192-dim Vektor (Python: `list[float]`); null solange leer. */
    var centroid: DoubleArray? = null

    val embeddings: MutableList<ClusterEmbedding> = mutableListOf()

    /**
     * Direkt mutierbar wie die Python-Attribute: die Pool-Promotion in
     * [SpeakerManager.processUnlabeledPool] schreibt `totalTime`/`occurrences`
     * ohne Clamping (exakt wie `cluster_manager.process_unlabeled_pool`).
     */
    var totalTime: Double = 0.0
    var occurrences: Int = 0
    var std: Double = 0.0
        private set

    val isTarget: Boolean = id == "1"
    val isUnlabeled: Boolean = id == "0"
    val isSilence: Boolean = id in SILENCE_IDS

    /** Port von `Cluster.add_embedding`. */
    fun addEmbedding(
        embedding: DoubleArray?,
        time: Double = 1.0,
        updateStrategy: String = "ema",
        historyIdx: Int? = null,
        startTime: Double? = null,
        alpha: Double = 0.1,
        windowSize: Int = 10,
    ) {
        embeddings.add(ClusterEmbedding(embedding, time, historyIdx, startTime))
        occurrences += 1
        totalTime += time

        if (embedding == null || isSilence || isUnlabeled) return

        recomputeStd()
        val strategy = if (centroid == null) "static" else updateStrategy
        updateCentroid(strategy, alpha, windowSize)
    }

    /** Port von `Cluster.update_centroid`. */
    fun updateCentroid(strategyName: String = "ema", alpha: Double = 0.1, windowSize: Int = 10) {
        centroid = CentroidUpdate.calculateNewCentroid(
            currentCentroid = centroid,
            embeddings = embeddings,
            strategyName = strategyName,
            alpha = alpha,
            windowSize = windowSize,
        )
    }

    /** Port von `Cluster.remove_embedding` (z. B. bei Pool-Promotion); mit Clamping. */
    fun removeEmbedding(entry: ClusterEmbedding): Boolean {
        val idx = embeddings.indexOfFirst { it === entry }
        if (idx < 0) return false
        embeddings.removeAt(idx)
        totalTime = maxOf(0.0, totalTime - entry.time)
        occurrences = maxOf(0, occurrences - 1)
        return true
    }

    /** Port von `Cluster._recompute_std`: mittlere paarweise Kosinus-Distanz. */
    private fun recomputeStd() {
        val vecs = embeddings.mapNotNull { it.embedding }
        val n = vecs.size
        if (n < 2) {
            std = 0.0
            return
        }
        val normed = vecs.map { v ->
            var sq = 0.0
            for (x in v) sq += x * x
            val norm = sqrt(sq)
            val divisor = if (norm < 1e-9) 1.0 else norm
            DoubleArray(v.size) { i -> v[i] / divisor }
        }
        var sum = 0.0
        var count = 0
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                var dot = 0.0
                val a = normed[i]
                val b = normed[j]
                for (k in a.indices) dot += a[k] * b[k]
                sum += 1.0 - dot
                count += 1
            }
        }
        std = sum / count
    }

    override fun toString(): String {
        val flag = if (isTarget) "T" else if (isUnlabeled) "U" else if (isSilence) "S" else "."
        return "Cluster(id=$id [$flag] n=$occurrences t=${"%.1f".format(totalTime)}s)"
    }

    companion object {
        private val SILENCE_IDS = setOf("-1", "-2", "-3", "-4", "-5", "silence", "s")
    }
}
