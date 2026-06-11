package com.fzi.speakerid.library

import com.fzi.speakerid.library.pipeline.OnnxSessionProvider
import com.fzi.speakerid.library.pipeline.steps.AudioPreprocessor
import com.fzi.speakerid.library.pipeline.steps.Chunker
import com.fzi.speakerid.library.pipeline.steps.SileroVad
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
 * VOLLER PARITAETS-NACHWEIS der produktiven [SileroVad]-Klasse gegen die
 * Golden-Referenzen — ALLE Chunks BEIDER Session-WAVs:
 *
 *  1) VAD-Entscheidung (`is_silence`) 100 % identisch,
 *  2) Window-Probs mittlere Abweichung < 5e-3 (pro Chunk und global),
 *  3) speech_duration-Abweichung <= 20 ms pro Chunk
 *     (Golden loggt 0.0 bei Stille-Chunks — gleiche Konvention hier).
 *
 * Parameter kommen aus meta.json (vad_threshold=0.1, min_speech_seconds=0.15,
 * window=256, use_silero_vad=true) statt hart codiert.
 */
class SileroVadGoldenParityTest {

    companion object {
        private const val MEAN_PROB_TOLERANCE = 5e-3
        private const val DURATION_TOLERANCE_S = 0.020

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
    fun conversation2AllChunksMatchGolden() {
        assertSessionParity(GoldenData.loadConversation2())
    }

    @Test
    fun overlapSyntheticAllChunksMatchGolden() {
        assertSessionParity(GoldenData.loadOverlapSynthetic())
    }

    private fun assertSessionParity(golden: GoldenSession) {
        val p = provider!!
        val meta = GoldenData.loadMeta()
        val params = meta.parameters
        val vad = SileroVad(p)

        val wav = WavReader.read(TestPaths.audio(golden.wav))
        val preprocessed = AudioPreprocessor.preprocess(wav, params.sampleRate)
        val chunks = Chunker.chunk(
            preprocessed,
            sampleRate = params.sampleRate,
            chunkDurationS = params.chunkDurationS,
            overlapS = params.chunkOverlapS,
            padLast = params.lastChunkZeroPadded,
        )
        assertEquals("Chunk-Anzahl ${golden.wav}", golden.chunks.size, chunks.size)

        var sumAbsDiff = 0.0
        var maxAbsDiff = 0.0
        var nProbs = 0
        var worstChunkMean = 0.0
        var maxDurationDiff = 0.0

        for (i in golden.chunks.indices) {
            val g = golden.chunks[i]
            val samples = chunks[i].samples

            // 1) Rohe Fenster-Wahrscheinlichkeiten (63 pro 1-s-Chunk)
            val probs = vad.windowProbs(
                samples,
                samplingRate = params.sampleRate,
                windowSizeSamples = params.vadWindowSizeSamples,
            )
            assertEquals("Fensteranzahl Chunk $i", g.vad.windowProbs.size, probs.size)
            var chunkSum = 0.0
            for (w in probs.indices) {
                val d = abs(probs[w] - g.vad.windowProbs[w])
                chunkSum += d
                sumAbsDiff += d
                nProbs++
                if (d > maxAbsDiff) maxAbsDiff = d
            }
            val chunkMean = chunkSum / probs.size
            if (chunkMean > worstChunkMean) worstChunkMean = chunkMean
            assertTrue(
                "Window-Prob mean|dP| Chunk $i = $chunkMean >= $MEAN_PROB_TOLERANCE",
                chunkMean < MEAN_PROB_TOLERANCE,
            )

            // 2) Entscheidung wie process_chunk(use_vad=True): detect_silence(0.15 / 0.1)
            val res = vad.detectSilence(
                samples,
                useVad = params.useSileroVad,
                minSpeechSeconds = params.minSpeechSeconds,
                chunkDuration = samples.size.toDouble() / params.sampleRate,
                sampleRate = params.sampleRate,
                threshold = params.vadThreshold,
            )
            assertEquals("is_silence Chunk $i (${golden.wav})", g.vad.isSilence, res.isSilence)

            // 3) speech_duration in der Golden-Logging-Konvention (0.0 bei Stille)
            val ourLogged = if (res.isSilence) 0.0 else res.speechDurationS
            val dDur = abs(ourLogged - g.vad.speechDurationS)
            if (dDur > maxDurationDiff) maxDurationDiff = dDur
            assertTrue(
                "speech_duration Chunk $i Abweichung ${dDur}s > ${DURATION_TOLERANCE_S}s",
                dDur <= DURATION_TOLERANCE_S,
            )
        }

        val meanAbsDiff = sumAbsDiff / nProbs
        println(
            "[VAD-Parity] ${golden.wav}: ${golden.chunks.size} Chunks / $nProbs Fenster — " +
                "mean|dP|=$meanAbsDiff, max|dP|=$maxAbsDiff, " +
                "worstChunkMean=$worstChunkMean, max|dDuration|=${maxDurationDiff}s"
        )
        assertTrue(
            "Globale mittlere VAD-Prob-Abweichung $meanAbsDiff >= $MEAN_PROB_TOLERANCE",
            meanAbsDiff < MEAN_PROB_TOLERANCE,
        )
    }
}
