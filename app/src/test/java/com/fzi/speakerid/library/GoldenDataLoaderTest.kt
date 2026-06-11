package com.fzi.speakerid.library

import com.fzi.speakerid.testutil.GoldenData
import com.fzi.speakerid.testutil.TestPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Sanity-Checks fuer den Golden-Loader gegen die bekannten Eckdaten. */
class GoldenDataLoaderTest {

    @Before
    fun requireHostData() = TestPaths.assumeHostData()

    @Test
    fun conversation2HasExpectedShape() {
        val g = GoldenData.loadConversation2()
        assertEquals("test_conversation2.wav", g.wav)
        assertEquals(116, g.chunks.size)
        assertEquals(116, g.summary.chunksGesamt)
        assertEquals(99, g.summary.speechChunks)
        assertEquals(4, g.summary.anzahlSprecher)

        val c0 = g.chunks[0]
        assertEquals(0, c0.chunkIdx)
        assertEquals(0.0, c0.startTimeS, 0.0)
        assertFalse(c0.vad.isSilence)
        assertEquals(63, c0.vad.windowProbs.size)
        assertNotNull(c0.embedding)
        assertEquals(192, c0.embedding!!.size)
        assertTrue(c0.similarityScores.containsKey("1"))
        assertEquals("1", c0.zugeordneterSprecher)
        assertEquals("1", c0.finalSprecher)

        val silent = g.chunks.first { it.vad.isSilence }
        assertNull(silent.embedding)
        assertEquals("-1", silent.zugeordneterSprecher)
        assertEquals(0.0, silent.vad.speechDurationS, 0.0)
    }

    @Test
    fun overlapSyntheticHasExpectedShape() {
        val g = GoldenData.loadOverlapSynthetic()
        assertEquals("test_overlap_synthetic.wav", g.wav)
        assertEquals(35, g.chunks.size)
        assertEquals(25, g.summary.speechChunks)
        assertEquals(2, g.summary.anzahlSprecher)
        assertEquals(2.305084745762712, g.summary.overlapZeitS, 1e-9)
        // Shadow-Overlap-Felder vorhanden
        val shadow = g.chunks[0].overlapShadow
        assertFalse(shadow.enabledInGuiDefault)
        assertTrue(shadow.nFrames >= 0)
    }

    @Test
    fun metaMatchesPipelineParameters() {
        val m = GoldenData.loadMeta()
        assertEquals(192, m.targetCentroid.size)
        assertEquals(16000, m.parameters.sampleRate)
        assertEquals(16000, m.parameters.chunkSamples)
        assertEquals(1.0, m.parameters.chunkDurationS, 0.0)
        assertEquals(0.1, m.parameters.vadThreshold, 0.0)
        assertEquals(256, m.parameters.vadWindowSizeSamples)
        assertEquals(0.15, m.parameters.minSpeechSeconds, 0.0)
        assertEquals(0.7, m.parameters.thresholdTarget, 0.0)
        assertEquals(0.7, m.parameters.thresholdNormal, 0.0)
        assertEquals("static", m.parameters.centroidUpdateStrategy)
        assertEquals(0.1, m.parameters.emaAlpha, 0.0)
        assertEquals(10, m.parameters.movingAverageWindowSize)
        assertEquals(3, m.parameters.minSamplesForNewSpeaker)
        assertEquals(true, m.parameters.lastChunkZeroPadded)
        assertEquals(0.3, m.parameters.overlapCleaner.minDurationS, 0.0)
        assertEquals(0.08, m.parameters.overlapCleaner.minIslandDurationS, 0.0)
        assertTrue(m.modelSha256.containsKey("silero_vad.onnx"))
        assertTrue(m.modelSha256.containsKey("redimnet_b2.onnx"))
    }
}
