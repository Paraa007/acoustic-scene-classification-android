package com.fzi.speakerid.library.calculations

import kotlin.math.sqrt

/**
 * Port von siqas `src/library/calculations/similarity.py::normalized_cosine_similarity`
 * (identisch verwendet von `speaker_matching.calculate_normalized_similarity`).
 *
 * Similarity = 1 - 0.5 * (1 - cos) in [0, 1]:
 * 1.0 = identisch, 0.5 = orthogonal, 0.0 = entgegengesetzt.
 * Norm-Guard wie im Original: Norm < 1e-9 => 0.0.
 * Alle Berechnungen in Double (numpy promotet auf float64).
 */
object CosineSimilarity {

    private const val NORM_EPS = 1e-9

    /** Roher Kosinus in [-1, 1]; 0.0 wenn eine Norm < 1e-9 (Guard wie siqas). */
    fun cosine(v1: DoubleArray, v2: DoubleArray): Double {
        require(v1.size == v2.size) { "Vektorlaengen ungleich: ${v1.size} vs ${v2.size}" }
        var dot = 0.0
        var sq1 = 0.0
        var sq2 = 0.0
        for (i in v1.indices) {
            dot += v1[i] * v2[i]
            sq1 += v1[i] * v1[i]
            sq2 += v2[i] * v2[i]
        }
        val norm1 = sqrt(sq1)
        val norm2 = sqrt(sq2)
        if (norm1 < NORM_EPS || norm2 < NORM_EPS) return 0.0
        return dot / (norm1 * norm2)
    }

    /**
     * Normalisierter Kosinus `1 - 0.5*(1 - cos)`.
     * Gibt 0.0 zurueck, wenn eine der Normen < 1e-9 ist (exakt wie das Original,
     * das in diesem Fall VOR der Formel abbricht).
     */
    fun normalizedCosine(v1: DoubleArray, v2: DoubleArray): Double {
        require(v1.size == v2.size) { "Vektorlaengen ungleich: ${v1.size} vs ${v2.size}" }
        var dot = 0.0
        var sq1 = 0.0
        var sq2 = 0.0
        for (i in v1.indices) {
            dot += v1[i] * v2[i]
            sq1 += v1[i] * v1[i]
            sq2 += v2[i] * v2[i]
        }
        val norm1 = sqrt(sq1)
        val norm2 = sqrt(sq2)
        if (norm1 < NORM_EPS || norm2 < NORM_EPS) return 0.0
        return 1.0 - 0.5 * (1.0 - dot / (norm1 * norm2))
    }

    // ── FloatArray-Overloads (Embeddings aus ONNX sind Float32) ────────────────

    fun cosine(v1: FloatArray, v2: FloatArray): Double =
        cosine(toDouble(v1), toDouble(v2))

    fun normalizedCosine(v1: FloatArray, v2: FloatArray): Double =
        normalizedCosine(toDouble(v1), toDouble(v2))

    fun normalizedCosine(v1: FloatArray, v2: DoubleArray): Double =
        normalizedCosine(toDouble(v1), v2)

    private fun toDouble(v: FloatArray): DoubleArray =
        DoubleArray(v.size) { v[it].toDouble() }
}
