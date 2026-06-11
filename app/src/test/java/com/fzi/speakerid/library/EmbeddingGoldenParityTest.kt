package com.fzi.speakerid.library

import com.fzi.speakerid.library.calculations.CosineSimilarity
import com.fzi.speakerid.library.pipeline.OnnxSessionProvider
import com.fzi.speakerid.library.pipeline.steps.AudioPreprocessor
import com.fzi.speakerid.library.pipeline.steps.Chunker
import com.fzi.speakerid.library.pipeline.steps.EmbeddingExtractor
import com.fzi.speakerid.library.pipeline.steps.MatchingSimilarity
import com.fzi.speakerid.library.pipeline.steps.WavReader
import com.fzi.speakerid.testutil.GoldenData
import com.fzi.speakerid.testutil.GoldenSession
import com.fzi.speakerid.testutil.TestPaths
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import kotlin.math.abs

/**
 * PARITAETS-NACHWEIS Embedding-Step ueber ALLE Speech-Chunks BEIDER Session-WAVs:
 * WavReader -> Preprocessing -> Chunker -> ReDimNet-ONNX -> L2-Normierung,
 * verglichen mit den Golden-Embeddings und den similarity_scores gegen den
 * Target-Centroid aus meta.json.
 *
 * Toleranzen laut Auftrag:
 *  - cosine(KotlinEmbedding, GoldenEmbedding) > 0.9995 pro Chunk
 *  - |sim(KotlinEmbedding, TargetCentroid) - golden similarity_scores["1"]| < 5e-3
 *
 * Der Vergleich gegen `similarity_scores["1"]` ist zulaessig, weil die
 * Centroid-Update-Strategie der Golden-Daten "static" ist: der Centroid von
 * Cluster "1" bleibt die gesamte Session ueber exakt der Target-Centroid aus
 * meta.json (per Python verifiziert: max. Abweichung ~1e-16).
 */
class EmbeddingGoldenParityTest {

    companion object {
        private const val MIN_COSINE = 0.9995
        private const val MAX_SIM_DEVIATION = 5e-3

        private var provider: OnnxSessionProvider? = null

        @BeforeClass
        @JvmStatic
        fun setUpProvider() {
            provider = OnnxSessionProvider(TestPaths.modelsDir)
        }

        @AfterClass
        @JvmStatic
        fun tearDownProvider() {
            provider?.close()
            provider = null
        }
    }

    @Before
    fun requireHostData() = TestPaths.assumeHostData()

    @Test
    fun conversation2AllSpeechChunksMatchGoldenEmbeddings() {
        runParity(GoldenData.loadConversation2(), "test_conversation2.wav")
    }

    @Test
    fun overlapSyntheticAllSpeechChunksMatchGoldenEmbeddings() {
        runParity(GoldenData.loadOverlapSynthetic(), "test_overlap_synthetic.wav")
    }

    private fun runParity(golden: GoldenSession, wavName: String) {
        val extractor = EmbeddingExtractor(provider!!)
        val targetCentroid = GoldenData.loadMeta().targetCentroid.toDoubleArray()
        assertEquals("Target-Centroid-Dim", EmbeddingExtractor.EMBEDDING_DIM, targetCentroid.size)

        val wav = WavReader.read(TestPaths.audio(wavName))
        val chunks = Chunker.chunk(AudioPreprocessor.preprocess(wav))
        assertEquals("Chunk-Anzahl $wavName", golden.chunks.size, chunks.size)

        var speechChunks = 0
        var minCos = 1.0
        var maxSimDev = 0.0

        for (g in golden.chunks) {
            // Stille-Chunks haben kein Golden-Embedding -> nicht Teil dieses Steps
            val goldenEmbedding = g.embedding ?: continue
            speechChunks++

            val ours = extractor.extractEmbedding(chunks[g.chunkIdx].samples)
            assertEquals(
                "Embedding-Dim Chunk ${g.chunkIdx}",
                EmbeddingExtractor.EMBEDDING_DIM, ours.size,
            )

            // 1) Embedding-Paritaet: roher Kosinus Kotlin vs. Golden
            val cos = CosineSimilarity.cosine(ours, goldenEmbedding.toDoubleArray())
            if (cos < minCos) minCos = cos
            assertTrue(
                "cosine(kotlin, golden) Chunk ${g.chunkIdx} ($wavName) = $cos <= $MIN_COSINE",
                cos > MIN_COSINE,
            )

            // 2) similarity_scores gegen Target-Centroid (Matching-Sicht)
            val ourSim = MatchingSimilarity.calculateNormalizedSimilarity(ours, targetCentroid)
            val goldenSim = g.similarityScores.getValue("1")
            val dev = abs(ourSim - goldenSim)
            if (dev > maxSimDev) maxSimDev = dev
            assertTrue(
                "similarity vs Target Chunk ${g.chunkIdx} ($wavName): " +
                    "|$ourSim - $goldenSim| = $dev >= $MAX_SIM_DEVIATION",
                dev < MAX_SIM_DEVIATION,
            )
        }

        assertEquals("Speech-Chunk-Anzahl $wavName", golden.summary.speechChunks, speechChunks)
        println(
            "[Embedding-Paritaet] $wavName: $speechChunks Speech-Chunks, " +
                "min cos = $minCos, max |dSim vs Target| = $maxSimDev"
        )
    }
}
