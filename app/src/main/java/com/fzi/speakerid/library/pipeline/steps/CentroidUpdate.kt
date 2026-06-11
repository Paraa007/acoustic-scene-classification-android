package com.fzi.speakerid.library.pipeline.steps

import com.fzi.speakerid.library.calculations.CosineSimilarity
import com.fzi.speakerid.library.data.ClusterEmbedding
import kotlin.math.sqrt

/**
 * Port von siqas `src/library/pipeline/steps/centroid_update.py`:
 * pure Centroid-Update-Strategien ohne Cluster-Mutation.
 *
 * Strategien wie Python: `static`, `mean`, `moving_average`, `ema`, `median`,
 * `medoid`; unbekannte Namen fallen wie die Python-Registry auf `ema` zurueck.
 * Alle Berechnungen in Double (numpy rechnet hier float64).
 */
object CentroidUpdate {

    /** Port von `calculate_new_centroid`. */
    fun calculateNewCentroid(
        currentCentroid: DoubleArray?,
        embeddings: List<ClusterEmbedding>,
        strategyName: String,
        alpha: Double = 0.1,
        windowSize: Int = 10,
    ): DoubleArray? {
        if (embeddings.isEmpty()) return currentCentroid
        val valid = embeddings.filter { it.embedding != null }
        if (valid.isEmpty()) return currentCentroid
        return when (strategyName) {
            "static" -> currentCentroid ?: initMean(valid)
            "mean" -> initMean(valid)
            "moving_average" -> initMean(valid.takeLast(windowSize))
            "median" -> updateMedian(valid)
            "medoid" -> updateMedoid(currentCentroid, valid)
            else -> updateEma(currentCentroid, valid, alpha) // "ema" + Fallback
        }
    }

    /** Port von `_init_mean`: normiertes arithmetisches Mittel. */
    private fun initMean(embeddings: List<ClusterEmbedding>): DoubleArray? {
        if (embeddings.isEmpty()) return null
        val dim = embeddings[0].embedding!!.size
        val mean = DoubleArray(dim)
        for (entry in embeddings) {
            val v = entry.embedding!!
            for (i in 0 until dim) mean[i] += v[i]
        }
        val n = embeddings.size.toDouble()
        for (i in 0 until dim) mean[i] /= n
        return normalize(mean)
    }

    /** Port von `update_ema`. */
    private fun updateEma(current: DoubleArray?, valid: List<ClusterEmbedding>, alpha: Double): DoubleArray? {
        if (current == null) return initMean(valid)
        val last = valid.last().embedding!!
        val raw = DoubleArray(current.size) { i -> alpha * last[i] + (1.0 - alpha) * current[i] }
        return normalize(raw)
    }

    /** Port von `update_median`: Median je Dimension (numpy-Semantik), normiert. */
    private fun updateMedian(valid: List<ClusterEmbedding>): DoubleArray {
        val dim = valid[0].embedding!!.size
        val n = valid.size
        val median = DoubleArray(dim)
        val column = DoubleArray(n)
        for (i in 0 until dim) {
            for (j in 0 until n) column[j] = valid[j].embedding!![i]
            column.sort()
            median[i] = if (n % 2 == 1) column[n / 2] else 0.5 * (column[n / 2 - 1] + column[n / 2])
        }
        return normalize(median)
    }

    /** Port von `update_medoid`: echter Datenpunkt mit minimaler Gesamtdistanz (unnormiert). */
    private fun updateMedoid(current: DoubleArray?, valid: List<ClusterEmbedding>): DoubleArray? {
        if (current == null || valid.size < 3) return initMean(valid)
        var bestMedoid: DoubleArray? = null
        var minDist = Double.POSITIVE_INFINITY
        for (i in valid.indices) {
            val candidate = valid[i].embedding!!
            var total = 0.0
            for (j in valid.indices) {
                if (i == j) continue
                total += 1.0 - CosineSimilarity.normalizedCosine(candidate, valid[j].embedding!!)
            }
            if (total < minDist) {
                minDist = total
                bestMedoid = candidate
            }
        }
        return bestMedoid?.copyOf()
    }

    /** Port von `_normalize`: Division nur bei Norm > 0, sonst unveraendert. */
    private fun normalize(vector: DoubleArray): DoubleArray {
        var sq = 0.0
        for (x in vector) sq += x * x
        val norm = sqrt(sq)
        if (norm <= 0.0) return vector
        return DoubleArray(vector.size) { i -> vector[i] / norm }
    }
}
