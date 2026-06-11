package com.fzi.speakerid.library

import com.fzi.speakerid.library.calculations.RmsCalculator
import com.fzi.speakerid.library.pipeline.OnnxSessionProvider
import com.fzi.speakerid.library.pipeline.steps.AudioPreprocessor
import com.fzi.speakerid.library.pipeline.steps.Chunker
import com.fzi.speakerid.library.pipeline.steps.Resampler
import com.fzi.speakerid.library.pipeline.steps.WavReader
import com.fzi.speakerid.testutil.SileroVadRunner
import com.fzi.speakerid.testutil.TestPaths
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import kotlin.math.abs

/**
 * Soxr-Paritaet des Kotlin-Resamplers (44,1 kHz stereo -> 16 kHz mono).
 *
 * Referenz: app/src/test/resources/speakerid/soxr_reference_*.json —
 * erzeugt von app/src/test/tools/generate_soxr_reference.py mit dem ECHTEN
 * python-soxr (1.1.0, HQ-Default) ueber den kompletten siqas-Preprocess-Pfad.
 * Verglichen werden Sample-Slices, Chunk-RMS und vor allem die
 * Silero-VAD-Wahrscheinlichkeiten auf dem resampelten Signal
 * (Ziel: Entscheidungen identisch, Probs im Mittel < 5e-3).
 */
class ResamplerSoxrParityTest {

    class Fixture(
        val wav: String,
        @SerializedName("input_rate") val inputRate: Int,
        @SerializedName("output_rate") val outputRate: Int,
        @SerializedName("input_len") val inputLen: Int,
        @SerializedName("peak_max_abs_raw_mono") val peakMaxAbsRawMono: Double,
        @SerializedName("preproc44_head") val preproc44Head: List<Double>,
        @SerializedName("output_len") val outputLen: Int,
        @SerializedName("sample_slices") val sampleSlices: Map<String, List<Double>>,
        @SerializedName("chunk_rms") val chunkRms: List<Double>,
        val vad: FixtureVad,
    )

    class FixtureVad(
        @SerializedName("window_probs") val windowProbs: List<List<Double>>,
        @SerializedName("is_silence") val isSilence: List<Boolean>,
        @SerializedName("speech_duration_s") val speechDurationS: List<Double>,
    )

    companion object {
        private const val RESOURCE = "speakerid/soxr_reference_test_stereo_44100hz_10s.json"
        private const val MEAN_PROB_TOLERANCE = 5e-3
        private const val SAMPLE_TOLERANCE = 5e-3
        private const val RMS_TOLERANCE = 1e-4

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

    private lateinit var fixture: Fixture
    private lateinit var resampled: FloatArray

    @Before
    fun setUp() {
        TestPaths.assumeHostData()
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream(RESOURCE)) {
            "Fixture $RESOURCE fehlt — generate_soxr_reference.py ausfuehren"
        }
        fixture = stream.bufferedReader().use { Gson().fromJson(it, Fixture::class.java) }

        val wav = WavReader.read(TestPaths.audio(fixture.wav))
        assertEquals(fixture.inputRate, wav.sampleRate)
        assertEquals(2, wav.numChannels)
        assertEquals(fixture.inputLen, wav.numFrames)

        // Zwischenstand vor dem Resampling exakt pruefen (isoliert den Resampler)
        val monoNorm = AudioPreprocessor.peakNormalize(wav.monoMix())
        for (i in fixture.preproc44Head.indices) {
            assertEquals("preproc44[$i]", fixture.preproc44Head[i], monoNorm[i].toDouble(), 1e-7)
        }

        resampled = AudioPreprocessor.preprocess(wav)
    }

    @Test
    fun outputLengthMatchesSoxr() {
        assertEquals(fixture.outputLen, resampled.size)
        assertEquals(
            fixture.outputLen,
            Resampler.outputLength(fixture.inputLen, fixture.inputRate, fixture.outputRate),
        )
    }

    @Test
    fun samplesCloseToSoxr() {
        var maxDiff = 0.0
        var sumDiff = 0.0
        var n = 0
        for ((startStr, ref) in fixture.sampleSlices) {
            val start = startStr.toInt()
            for (j in ref.indices) {
                val d = abs(resampled[start + j] - ref[j])
                if (d > maxDiff) maxDiff = d
                sumDiff += d
                n++
            }
        }
        println("[Resampler] Sample-Diff vs soxr: mean = ${sumDiff / n}, max = $maxDiff ($n Samples)")
        assertTrue("max Sample-Abweichung $maxDiff >= $SAMPLE_TOLERANCE", maxDiff < SAMPLE_TOLERANCE)
    }

    @Test
    fun chunkRmsCloseToSoxr() {
        val chunks = Chunker.chunk(resampled)
        assertEquals(fixture.chunkRms.size, chunks.size)
        var maxDiff = 0.0
        for (i in chunks.indices) {
            val d = abs(RmsCalculator.rms(chunks[i].samples) - fixture.chunkRms[i])
            if (d > maxDiff) maxDiff = d
            assertEquals("RMS Chunk $i", fixture.chunkRms[i], RmsCalculator.rms(chunks[i].samples), RMS_TOLERANCE)
        }
        println("[Resampler] max |dRMS| vs soxr = $maxDiff")
    }

    @Test
    fun vadProbsAndDecisionsMatchSoxrReference() {
        val p = provider!!
        val vad = SileroVadRunner(p.env, p.vad())
        val chunks = Chunker.chunk(resampled)
        assertEquals(fixture.vad.windowProbs.size, chunks.size)

        var sumAbsDiff = 0.0
        var maxAbsDiff = 0.0
        var n = 0
        for (i in chunks.indices) {
            val probs = vad.windowProbs(chunks[i].samples)
            val ref = fixture.vad.windowProbs[i]
            assertEquals(ref.size, probs.size)
            for (w in probs.indices) {
                val d = abs(probs[w] - ref[w])
                sumAbsDiff += d
                if (d > maxAbsDiff) maxAbsDiff = d
                n++
            }
            val (isSilence, _) = vad.detectSilence(chunks[i].samples)
            assertEquals("is_silence Chunk $i", fixture.vad.isSilence[i], isSilence)
        }
        val mean = sumAbsDiff / n
        println("[Resampler] VAD vs soxr-Referenz: mean|dP| = $mean, max|dP| = $maxAbsDiff")
        assertTrue("Mittlere VAD-Prob-Abweichung $mean >= $MEAN_PROB_TOLERANCE", mean < MEAN_PROB_TOLERANCE)
    }
}
