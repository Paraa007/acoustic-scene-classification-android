package com.fzi.speakerid.library

import com.fzi.speakerid.library.calculations.RmsCalculator
import com.fzi.speakerid.library.pipeline.steps.AudioPreprocessor
import com.fzi.speakerid.library.pipeline.steps.Chunker
import com.fzi.speakerid.library.pipeline.steps.WavReader
import com.fzi.speakerid.testutil.GoldenData
import com.fzi.speakerid.testutil.TestPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

/**
 * Paritaet von WavReader + AudioPreprocessor + Chunker + RmsCalculator gegen
 * die Golden-Daten: Chunk-Anzahl, Startzeiten und per-Chunk-RMS muessen den
 * Python-Werten entsprechen (RMS-Toleranz 1e-9 — nur Summationsreihenfolge).
 */
class AudioFoundationGoldenTest {

    @Before
    fun requireHostData() = TestPaths.assumeHostData()

    private fun checkSession(wavName: String, goldenFile: String) {
        val golden = GoldenData.loadSession(TestPaths.golden(goldenFile))
        val wav = WavReader.read(TestPaths.audio(wavName))
        assertEquals("Session-WAVs sind 16 kHz", 16000, wav.sampleRate)
        assertEquals("Session-WAVs sind mono", 1, wav.numChannels)

        val preprocessed = AudioPreprocessor.preprocess(wav)
        val chunks = Chunker.chunk(preprocessed)

        assertEquals(golden.chunks.size, chunks.size)
        var maxRmsDiff = 0.0
        for (i in chunks.indices) {
            val g = golden.chunks[i]
            assertEquals(16000, chunks[i].samples.size)
            assertEquals(g.startTimeS, chunks[i].startTimeS, 0.0)
            val rms = RmsCalculator.rms(chunks[i].samples)
            val diff = abs(rms - g.rms)
            if (diff > maxRmsDiff) maxRmsDiff = diff
            assertEquals("RMS Chunk $i", g.rms, rms, 1e-9)
        }
        println("[$wavName] ${chunks.size} Chunks, max |dRMS| = $maxRmsDiff")
    }

    @Test
    fun conversation2ChunksAndRmsMatchGolden() =
        checkSession("test_conversation2.wav", GoldenData.CONVERSATION2)

    @Test
    fun overlapSyntheticChunksAndRmsMatchGolden() =
        checkSession("test_overlap_synthetic.wav", GoldenData.OVERLAP_SYNTHETIC)

    @Test
    fun lastChunkIsZeroPadded() {
        // test_conversation2.wav: 1.850.080 Samples -> Chunk 115 hat 10.080 echte
        // Samples + 5.920 Nullen (VirtualFileRecorder-Verhalten).
        val wav = WavReader.read(TestPaths.audio("test_conversation2.wav"))
        val preprocessed = AudioPreprocessor.preprocess(wav)
        assertEquals(1_850_080, preprocessed.size)
        val chunks = Chunker.chunk(preprocessed)
        assertEquals(116, chunks.size)
        val last = chunks.last().samples
        for (i in 10_080 until 16_000) {
            assertEquals("Padding-Sample $i muss 0 sein", 0f, last[i], 0f)
        }
        // Ohne Padding (chunk_audio-Verhalten) faellt der Rest-Chunk weg
        assertEquals(115, Chunker.chunk(preprocessed, padLast = false).size)
        assertTrue(chunks.first().samples.isNotEmpty())
    }
}
