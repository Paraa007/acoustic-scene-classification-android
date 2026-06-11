package com.fzi.speakerid.library

import com.fzi.speakerid.library.calculations.CosineSimilarity
import com.fzi.speakerid.testutil.GoldenData
import com.fzi.speakerid.testutil.TestPaths
import org.junit.Assert.assertEquals
import org.junit.Test

/** Formel-Paritaet des NormalizedCosine gegen siqas + Golden-Daten. */
class CosineSimilarityTest {

    @Test
    fun basicProperties() {
        val a = doubleArrayOf(1.0, 0.0, 2.0)
        assertEquals(1.0, CosineSimilarity.normalizedCosine(a, a), 1e-12)
        assertEquals(0.0, CosineSimilarity.normalizedCosine(a, doubleArrayOf(-1.0, 0.0, -2.0)), 1e-12)
        // orthogonal -> 0.5
        assertEquals(
            0.5,
            CosineSimilarity.normalizedCosine(doubleArrayOf(1.0, 0.0), doubleArrayOf(0.0, 1.0)),
            1e-12,
        )
        // Norm-Guard wie siqas: Nullvektor -> 0.0
        assertEquals(0.0, CosineSimilarity.normalizedCosine(a, doubleArrayOf(0.0, 0.0, 0.0)), 0.0)
        assertEquals(1.0, CosineSimilarity.cosine(a, a), 1e-12)
    }

    @Test
    fun chunk0SimilarityAgainstTargetCentroidMatchesGolden() {
        TestPaths.assumeHostData()
        val meta = GoldenData.loadMeta()
        val golden = GoldenData.loadConversation2()

        // Chunk 0 ist der erste Speech-Chunk: similarity_scores["1"] wurde in
        // Python VOR dem ersten Centroid-Update gegen das Target-Centroid aus
        // meta.json gerechnet -> exakt reproduzierbar.
        val c0 = golden.chunks[0]
        val embedding = c0.embedding!!.toDoubleArray()
        val centroid = meta.targetCentroid.toDoubleArray()
        val expected = c0.similarityScores.getValue("1")
        val actual = CosineSimilarity.normalizedCosine(embedding, centroid)
        assertEquals(expected, actual, 1e-9)
    }
}
