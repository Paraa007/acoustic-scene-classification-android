package com.fzi.speakerid.library.pipeline.steps

import com.fzi.speakerid.library.data.Cluster

/**
 * Port von siqas `src/library/pipeline/steps/speaker_matching.py::
 * find_cluster_for_embedding` samt Zuweisungs-Strategien.
 *
 * Jede Strategie nimmt (embedding, clustersDict, targetThreshold,
 * normalThreshold) und liefert die Ziel-Cluster-ID; "0" = Unlabeled-Pool,
 * wenn kein Cluster ueber seinem Schwellwert liegt. Die Map MUSS
 * Einfuege-Reihenfolge garantieren (LinkedHashMap), da Python-Dicts
 * insertion-ordered iterieren.
 *
 * Nicht portiert: `knn_temporal` (per GUI nicht erreichbar, braucht
 * kwargs-Zustand). Unbekannte Strategie-Namen fallen wie die
 * Python-Registry (`.get(..., _match_target_priority)`) auf
 * `target_priority` zurueck.
 */
object SpeakerMatcher {

    const val UNLABELED_ID = "0"

    const val STRATEGY_FIRST_MATCH = "first_match"
    const val STRATEGY_LAST_MATCH = "last_match"
    const val STRATEGY_BEST_OVERALL = "best_overall"
    const val STRATEGY_TARGET_PRIORITY = "target_priority"

    fun findClusterForEmbedding(
        newEmbedding: DoubleArray,
        clustersDict: Map<String, Cluster>,
        strategyName: String,
        targetThreshold: Double,
        normalThreshold: Double,
    ): String = when (strategyName) {
        STRATEGY_FIRST_MATCH -> matchFirst(newEmbedding, clustersDict, targetThreshold, normalThreshold)
        STRATEGY_LAST_MATCH -> matchLast(newEmbedding, clustersDict, targetThreshold, normalThreshold)
        STRATEGY_BEST_OVERALL -> matchBestOverall(newEmbedding, clustersDict, targetThreshold, normalThreshold)
        else -> matchTargetPriority(newEmbedding, clustersDict, targetThreshold, normalThreshold)
    }

    /** Port von `_match_first`. */
    private fun matchFirst(
        newEmbedding: DoubleArray,
        clusters: Map<String, Cluster>,
        targetThreshold: Double,
        normalThreshold: Double,
    ): String {
        for ((cid, cluster) in clusters) {
            val centroid = cluster.centroid ?: continue
            if (cluster.isUnlabeled) continue
            val threshold = if (cluster.isTarget) targetThreshold else normalThreshold
            if (MatchingSimilarity.calculateNormalizedSimilarity(newEmbedding, centroid) >= threshold) {
                return cid
            }
        }
        return UNLABELED_ID
    }

    /** Port von `_match_last`. */
    private fun matchLast(
        newEmbedding: DoubleArray,
        clusters: Map<String, Cluster>,
        targetThreshold: Double,
        normalThreshold: Double,
    ): String {
        for ((cid, cluster) in clusters.entries.toList().asReversed()) {
            val centroid = cluster.centroid ?: continue
            if (cluster.isUnlabeled) continue
            val threshold = if (cluster.isTarget) targetThreshold else normalThreshold
            if (MatchingSimilarity.calculateNormalizedSimilarity(newEmbedding, centroid) >= threshold) {
                return cid
            }
        }
        return UNLABELED_ID
    }

    /** Port von `_match_best_overall` (GUI-Default). */
    private fun matchBestOverall(
        newEmbedding: DoubleArray,
        clusters: Map<String, Cluster>,
        targetThreshold: Double,
        normalThreshold: Double,
    ): String {
        var bestId = UNLABELED_ID
        var bestSim = -1.0
        for ((cid, cluster) in clusters) {
            val centroid = cluster.centroid ?: continue
            if (cluster.isUnlabeled) continue
            val threshold = if (cluster.isTarget) targetThreshold else normalThreshold
            val sim = MatchingSimilarity.calculateNormalizedSimilarity(newEmbedding, centroid)
            if (sim >= threshold && sim > bestSim) {
                bestSim = sim
                bestId = cid
            }
        }
        return bestId
    }

    /** Port von `_match_target_priority` (zugleich Fallback wie in Python). */
    private fun matchTargetPriority(
        newEmbedding: DoubleArray,
        clusters: Map<String, Cluster>,
        targetThreshold: Double,
        normalThreshold: Double,
    ): String {
        for ((cid, cluster) in clusters) {
            val centroid = cluster.centroid
            if (cluster.isTarget && centroid != null &&
                MatchingSimilarity.calculateNormalizedSimilarity(newEmbedding, centroid) >= targetThreshold
            ) {
                return cid
            }
        }
        var bestId = UNLABELED_ID
        var bestSim = -1.0
        for ((cid, cluster) in clusters) {
            val centroid = cluster.centroid ?: continue
            if (cluster.isTarget || cluster.isUnlabeled) continue
            val sim = MatchingSimilarity.calculateNormalizedSimilarity(newEmbedding, centroid)
            if (sim >= normalThreshold && sim > bestSim) {
                bestSim = sim
                bestId = cid
            }
        }
        return bestId
    }
}
