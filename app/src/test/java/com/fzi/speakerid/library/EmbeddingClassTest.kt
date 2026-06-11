package com.fzi.speakerid.library

import com.fzi.speakerid.library.pipeline.steps.Embedding
import com.fzi.speakerid.library.pipeline.steps.MatchingSimilarity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure Unit-Tests (ohne Host-Daten/ONNX) fuer die Python-Guard-Semantik von
 * `Embedding.similarity` / `Embedding.is_speech` und
 * `speaker_matching.calculate_normalized_similarity`.
 */
class EmbeddingClassTest {

    private val v = doubleArrayOf(1.0, 0.0, 0.0)

    @Test
    fun isSpeechRequiresSpeechStatusAndVector() {
        assertTrue(Embedding(v, 1.0, 0.0, Embedding.STATUS_SPEECH).isSpeech)
        assertFalse(Embedding(null, 1.0, 0.0, Embedding.STATUS_SPEECH).isSpeech)
        assertFalse(Embedding(v, 1.0, 0.0, Embedding.STATUS_SILENCE).isSpeech)
        assertFalse(Embedding(v, 1.0, 0.0, Embedding.STATUS_OVERLAP_DROP).isSpeech)
    }

    @Test
    fun similarityGuardsNullVectorsLikePython() {
        val speech = Embedding(v, 1.0, 0.0, Embedding.STATUS_SPEECH)
        val silence = Embedding(null, 1.0, 0.0, Embedding.STATUS_SILENCE)
        assertEquals(0.0, silence.similarity(speech), 0.0)
        assertEquals(0.0, speech.similarity(silence), 0.0)
        assertEquals(0.0, speech.similarity(null as DoubleArray?), 0.0)
        // identisch -> 1.0, orthogonal -> 0.5, entgegengesetzt -> 0.0
        assertEquals(1.0, speech.similarity(doubleArrayOf(2.0, 0.0, 0.0)), 1e-12)
        assertEquals(0.5, speech.similarity(doubleArrayOf(0.0, 3.0, 0.0)), 1e-12)
        assertEquals(0.0, speech.similarity(doubleArrayOf(-1.0, 0.0, 0.0)), 1e-12)
    }

    @Test
    fun calculateNormalizedSimilarityGuardsLikePython() {
        assertEquals(0.0, MatchingSimilarity.calculateNormalizedSimilarity(null, v), 0.0)
        assertEquals(0.0, MatchingSimilarity.calculateNormalizedSimilarity(v, null as DoubleArray?), 0.0)
        assertEquals(0.0, MatchingSimilarity.calculateNormalizedSimilarity(v, DoubleArray(0)), 0.0)
        assertEquals(0.0, MatchingSimilarity.calculateNormalizedSimilarity(v, doubleArrayOf(0.0, 0.0, 0.0)), 0.0)
        assertEquals(1.0, MatchingSimilarity.calculateNormalizedSimilarity(v, listOf(5.0, 0.0, 0.0)), 1e-12)
    }
}
