package com.fzi.speakerid.library

import com.fzi.speakerid.library.pipeline.OnnxSessionProvider
import com.fzi.speakerid.library.pipeline.steps.AudioPreprocessor
import com.fzi.speakerid.library.pipeline.steps.Chunker
import com.fzi.speakerid.library.pipeline.steps.WavReader
import com.fzi.speakerid.testutil.GoldenData
import com.fzi.speakerid.testutil.SileroVadRunner
import com.fzi.speakerid.testutil.TestPaths
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import kotlin.math.abs

/**
 * KRITISCHER PARITAETS-NACHWEIS (Smoke-Test, bleibt dauerhaft bestehen):
 * test_conversation2.wav -> WavReader -> Preprocessing -> Chunker -> Silero-VAD
 * auf den ersten 10 Chunks, verglichen mit den Golden-Wahrscheinlichkeiten.
 *
 * Ziel laut Auftrag: VAD-Entscheidungen identisch, Probs im Mittel < 5e-3
 * Abweichung. (Die Session-WAVs sind bereits 16 kHz mono — der Pfad testet
 * damit WavReader/Peak-Norm/Chunker bit-genau; der Resampler wird separat in
 * ResamplerSoxrParityTest gegen echte soxr-Referenzen nachgewiesen.)
 */
class SileroVadGoldenSmokeTest {

    companion object {
        private const val NUM_CHUNKS = 10
        private const val MEAN_PROB_TOLERANCE = 5e-3

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
    fun first10ChunksMatchGoldenVadProbsAndDecisions() {
        val p = provider!!
        val golden = GoldenData.loadConversation2()
        val wav = WavReader.read(TestPaths.audio("test_conversation2.wav"))
        val chunks = Chunker.chunk(AudioPreprocessor.preprocess(wav))
        val vad = SileroVadRunner(p.env, p.vad())

        var sumAbsDiff = 0.0
        var maxAbsDiff = 0.0
        var nProbs = 0

        for (i in 0 until NUM_CHUNKS) {
            val g = golden.chunks[i]
            val samples = chunks[i].samples

            // 1) Rohe Fenster-Wahrscheinlichkeiten (63 pro Chunk)
            val probs = vad.windowProbs(samples)
            assertEquals("Fensteranzahl Chunk $i", g.vad.windowProbs.size, probs.size)
            for (w in probs.indices) {
                val d = abs(probs[w] - g.vad.windowProbs[w])
                sumAbsDiff += d
                if (d > maxAbsDiff) maxAbsDiff = d
                nProbs++
            }

            // 2) Entscheidung identisch (detect_silence: min_speech=0.15, thr=0.1)
            val (isSilence, speechDuration) = vad.detectSilence(samples)
            assertEquals("is_silence Chunk $i", g.vad.isSilence, isSilence)
            val goldenDuration = if (g.vad.isSilence) 0.0 else g.vad.speechDurationS
            val ourDuration = if (isSilence) 0.0 else speechDuration
            assertEquals("speech_duration_s Chunk $i", goldenDuration, ourDuration, 1e-6)
        }

        val meanAbsDiff = sumAbsDiff / nProbs
        println(
            "[VAD-Smoke] $NUM_CHUNKS Chunks / $nProbs Fenster: " +
                "mean|dP| = $meanAbsDiff, max|dP| = $maxAbsDiff"
        )
        assertTrue(
            "Mittlere VAD-Prob-Abweichung $meanAbsDiff >= $MEAN_PROB_TOLERANCE",
            meanAbsDiff < MEAN_PROB_TOLERANCE,
        )
    }
}
