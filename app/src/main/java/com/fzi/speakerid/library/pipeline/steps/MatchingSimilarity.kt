package com.fzi.speakerid.library.pipeline.steps

import com.fzi.speakerid.library.calculations.CosineSimilarity

/**
 * Port von siqas `src/library/pipeline/steps/speaker_matching.py::
 * calculate_normalized_similarity` — die Similarity, mit der das Matching
 * Embeddings gegen Cluster-Centroids vergleicht.
 *
 * Normalized Cosine `1 - 0.5*(1 - cos)` in [0, 1]; 1.0 = perfekter Match.
 * Guards exakt wie Python: 0.0 bei null/leeren Vektoren oder Norm < 1e-9.
 *
 * Die Matching-Strategien selbst (`find_cluster_for_embedding` etc.) entstehen
 * im Matching-Modul; diese Datei stellt nur deren Similarity-Kern bereit.
 */
object MatchingSimilarity {

    fun calculateNormalizedSimilarity(vec1: DoubleArray?, vec2: DoubleArray?): Double {
        if (vec1 == null || vec2 == null || vec1.isEmpty() || vec2.isEmpty()) return 0.0
        return CosineSimilarity.normalizedCosine(vec1, vec2)
    }

    /** Overload fuer Centroids, die als `List<Double>` vorliegen (wie Pythons `list`). */
    fun calculateNormalizedSimilarity(vec1: DoubleArray?, vec2: List<Double>?): Double =
        calculateNormalizedSimilarity(vec1, vec2?.toDoubleArray())
}
