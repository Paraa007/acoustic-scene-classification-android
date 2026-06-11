package com.fzi.speakerid.library.pipeline.steps

import com.fzi.speakerid.library.data.ClusterEmbedding
import kotlin.math.sqrt

/**
 * Port von siqas `src/library/pipeline/steps/speaker_extraction.py::
 * extract_new_cluster_from_pool` — Suche nach neuen Sprechern im
 * Unlabeled-Pool ("Clique-Mining").
 *
 * Strategien wie Python: `clique` (Default), `temporal`, sonst `star`.
 * Wichtige Clique-Semantik (Code ist autoritativ, nicht der Docstring):
 * Kandidaten = alle Pool-Punkte mit Similarity >= threshold zum NEUESTEN
 * Punkt; der Clique-Kern wird vom ERSTEN Kandidaten (Pool-Reihenfolge)
 * aus greedy aufgebaut und bricht beim Erreichen von `minSamples` sofort
 * ab — es werden also genau `minSamples` Punkte promoted.
 *
 * Similarity = 0.5 * (1 + cos) auf zeilennormierten Vektoren; Normierungs-
 * Guards exakt wie `_pool_to_matrix`/`_sims_to_query` (Norm < 1e-9 ->
 * Division durch 1.0 bzw. Query unnormiert). Python rechnet hier in
 * numpy-float32, dieser Port in Double; bei den L2-normierten
 * ReDimNet-Embeddings ist die Differenz O(1e-7) und golden-verifiziert
 * entscheidungsneutral.
 */
object SpeakerExtraction {

    const val STRATEGY_CLIQUE = "clique"
    const val STRATEGY_TEMPORAL = "temporal"
    const val STRATEGY_STAR = "star"

    /** Port von `extract_new_cluster_from_pool`. */
    fun extractNewClusterFromPool(
        poolEmbeddings: List<ClusterEmbedding>,
        threshold: Double,
        minSamples: Int = 3,
        strategy: String = STRATEGY_CLIQUE,
    ): List<ClusterEmbedding> {
        if (poolEmbeddings.isEmpty() || poolEmbeddings.size < minSamples) return emptyList()
        return when (strategy) {
            STRATEGY_CLIQUE -> extractClique(poolEmbeddings, threshold, minSamples)
            STRATEGY_TEMPORAL -> extractTemporal(poolEmbeddings, threshold, minSamples)
            else -> extractStar(poolEmbeddings, threshold, minSamples)
        }
    }

    /** Port von `_extract_clique`. */
    private fun extractClique(
        pool: List<ClusterEmbedding>,
        threshold: Double,
        minSamples: Int,
    ): List<ClusterEmbedding> {
        val latest = pool.last().embedding ?: return emptyList()
        val matrix = poolToMatrix(pool) ?: return emptyList()
        val sims = simsToQuery(matrix.rows, latest)
        val candPositions = sims.indices.filter { sims[it] >= threshold }
        if (candPositions.size < minSamples) return emptyList()

        val candRows = candPositions.map { matrix.rows[it] }
        val core = mutableListOf(0)
        fun corePoints() = core.map { pool[matrix.poolIndices[candPositions[it]]] }
        if (core.size >= minSamples) return corePoints()
        for (ci in 1 until candRows.size) {
            if (core.all { simOfNormalized(candRows[ci], candRows[it]) >= threshold }) {
                core.add(ci)
            }
            if (core.size >= minSamples) return corePoints()
        }
        return emptyList()
    }

    /** Port von `_extract_star`: alle Punkte vs. neuestem Punkt. */
    private fun extractStar(
        pool: List<ClusterEmbedding>,
        threshold: Double,
        minSamples: Int,
    ): List<ClusterEmbedding> {
        val latest = pool.last().embedding ?: return emptyList()
        val matrix = poolToMatrix(pool) ?: return emptyList()
        val sims = simsToQuery(matrix.rows, latest)
        val similar = sims.indices.filter { sims[it] >= threshold }
            .map { pool[matrix.poolIndices[it]] }
        return if (similar.size >= minSamples) similar else emptyList()
    }

    /** Port von `_extract_temporal`: letztes Zeitfenster, alle Paare ueber Threshold. */
    private fun extractTemporal(
        pool: List<ClusterEmbedding>,
        threshold: Double,
        minSamples: Int,
    ): List<ClusterEmbedding> {
        val window = pool.takeLast(minSamples)
        if (window.size < minSamples) return emptyList()
        val matrix = poolToMatrix(window) ?: return emptyList()
        if (matrix.rows.size < minSamples) return emptyList()
        for (i in matrix.rows.indices) {
            for (j in i + 1 until matrix.rows.size) {
                if (simOfNormalized(matrix.rows[i], matrix.rows[j]) < threshold) return emptyList()
            }
        }
        return window
    }

    // ── Helfer (Port von `_pool_to_matrix` / `_sims_to_query`) ────────────────

    private class PoolMatrix(val rows: List<DoubleArray>, val poolIndices: List<Int>)

    private fun poolToMatrix(pool: List<ClusterEmbedding>): PoolMatrix? {
        val rows = ArrayList<DoubleArray>(pool.size)
        val indices = ArrayList<Int>(pool.size)
        for ((i, entry) in pool.withIndex()) {
            val v = entry.embedding ?: continue
            rows.add(normalizeRow(v))
            indices.add(i)
        }
        if (rows.isEmpty()) return null
        return PoolMatrix(rows, indices)
    }

    /** Zeilennorm < 1e-9 -> Division durch 1.0 (wie `np.where(norms < 1e-9, 1.0, norms)`). */
    private fun normalizeRow(v: DoubleArray): DoubleArray {
        var sq = 0.0
        for (x in v) sq += x * x
        val norm = sqrt(sq)
        val divisor = if (norm < 1e-9) 1.0 else norm
        return DoubleArray(v.size) { v[it] / divisor }
    }

    private fun simsToQuery(rows: List<DoubleArray>, query: DoubleArray): DoubleArray {
        var sq = 0.0
        for (x in query) sq += x * x
        val norm = sqrt(sq)
        val q = if (norm > 1e-9) DoubleArray(query.size) { query[it] / norm } else query
        return DoubleArray(rows.size) { r -> simOfNormalized(rows[r], q) }
    }

    /** 0.5 * (1 + dot) fuer bereits normierte Vektoren. */
    private fun simOfNormalized(a: DoubleArray, b: DoubleArray): Double {
        var dot = 0.0
        for (i in a.indices) dot += a[i] * b[i]
        return 0.5 * (1.0 + dot)
    }
}
