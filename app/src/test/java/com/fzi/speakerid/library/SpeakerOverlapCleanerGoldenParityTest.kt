package com.fzi.speakerid.library

import com.fzi.speakerid.library.pipeline.OnnxSessionProvider
import com.fzi.speakerid.library.pipeline.steps.AudioPreprocessor
import com.fzi.speakerid.library.pipeline.steps.Chunker
import com.fzi.speakerid.library.pipeline.steps.SpeakerOverlapCleaner
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
 * PARITAETS-NACHWEIS des [SpeakerOverlapCleaner]-Streaming-Pfads gegen die
 * Overlap-Shadow-Aufzeichnung der Golden-Referenzen (ALLE Chunks BEIDER WAVs).
 *
 * Replay exakt wie `generate_golden.py::process_session`: frischer Cleaner pro
 * Session (Defaults wie `LiveProcessor.__init__`), `reset_stream()` wie
 * `LiveProcessor.start()`, dann pro 1-s-Chunk `process_streaming(min_duration,
 * min_island)` mit den meta.json-Parametern. Der [RecordingOverlapCleaner]
 * protokolliert wie im Generator die Frame-Statistik des letzten Chunks.
 *
 * Soll-Toleranzen (Task): Gesamt-Overlap-Zeit +-0.05 s; conversation2 = 0.00 s.
 * Zusaetzlich pro Chunk: n_frames/max_active exakt, Frame-Zaehler +-3 Frames
 * (= 0.05 s bei 59 fps), overlap_seconds +-0.05 s, Solo-Segmente gleiche
 * Anzahl/Sprecher-Indizes und Dauer +-0.05 s.
 */
class SpeakerOverlapCleanerGoldenParityTest {

    companion object {
        private const val TOTAL_OVERLAP_TOLERANCE_S = 0.05
        private const val CHUNK_OVERLAP_TOLERANCE_S = 0.05
        private const val SEGMENT_DURATION_TOLERANCE_S = 0.05
        private const val FRAME_COUNT_TOLERANCE = 3

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
    fun conversation2ShadowMatchesGoldenAndIsOverlapFree() {
        val total = assertSessionParity(GoldenData.loadConversation2())
        assertEquals("conversation2 muss 0.00 s Overlap ergeben", 0.0, total, 1e-9)
    }

    @Test
    fun overlapSyntheticShadowMatchesGolden() {
        assertSessionParity(GoldenData.loadOverlapSynthetic())
    }

    /** Shadow-Cleaner wie `generate_golden.py::RecordingOverlapCleaner`. */
    private class RecordingOverlapCleaner(
        provider: OnnxSessionProvider,
        windowDurationS: Double,
        audioMergeStrategy: String,
    ) : SpeakerOverlapCleaner(provider, windowDurationS, audioMergeStrategy) {

        class Stats(
            val nFrames: Int,
            val framesSpeech: Int,
            val framesOverlap: Int,
            val overlapSeconds: Double,
        )

        var lastStats: Stats? = null

        override fun extractPureSegments(
            audio: FloatArray,
            probs: Array<FloatArray>,
            minDuration: Double,
            minIslandDurationS: Double,
        ): CleanResult {
            val nSamples = audio.size
            val nFrames = probs.size
            lastStats = if (nSamples > 0 && nFrames > 0) {
                var framesSpeech = 0
                var framesOverlap = 0
                for (f in 0 until nFrames) {
                    val nActive = SPEAKER_ACTIVITY[argmax(probs[f])].sum()
                    if (nActive >= 1) framesSpeech++
                    if (nActive >= 2) framesOverlap++
                }
                // fps wie im Generator: _frames_per_second (im Streaming None/0.0)
                // oder Fallback nFrames / (nSamples / SR).
                val fps = framesPerSecond?.takeIf { it != 0.0 }
                    ?: (nFrames.toDouble() / (nSamples.toDouble() / SAMPLE_RATE))
                Stats(
                    nFrames = nFrames,
                    framesSpeech = framesSpeech,
                    framesOverlap = framesOverlap,
                    overlapSeconds = if (fps > 0) framesOverlap / fps else 0.0,
                )
            } else {
                Stats(0, 0, 0, 0.0)
            }
            return super.extractPureSegments(audio, probs, minDuration, minIslandDurationS)
        }
    }

    /** Spielt die Session ab, prueft jeden Chunk, liefert die Gesamt-Overlap-Zeit. */
    private fun assertSessionParity(golden: GoldenSession): Double {
        val p = provider!!
        val params = GoldenData.loadMeta().parameters
        val cleanerParams = params.overlapCleaner

        val cleaner = RecordingOverlapCleaner(
            p,
            windowDurationS = cleanerParams.windowDurationS,
            audioMergeStrategy = cleanerParams.audioMergeStrategy,
        )
        cleaner.resetStream() // wie LiveProcessor.start()

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

        var totalOverlapSeconds = 0.0
        var maxChunkOverlapDiff = 0.0
        var maxFrameCountDiff = 0
        var maxSegmentDurationDiff = 0.0

        for (i in golden.chunks.indices) {
            val g = golden.chunks[i].overlapShadow
            val result = cleaner.processStreaming(
                chunks[i].samples,
                minDuration = cleanerParams.minDurationS,
                minIslandDurationS = cleanerParams.minIslandDurationS,
            )
            val stats = cleaner.lastStats!!
            totalOverlapSeconds += stats.overlapSeconds

            assertEquals("n_frames Chunk $i (${golden.wav})", g.nFrames, stats.nFrames)
            assertEquals(
                "max_active_speakers Chunk $i (${golden.wav})",
                g.maxActiveSpeakers, result.maxActiveSpeakers,
            )

            val dSpeech = abs(stats.framesSpeech - g.framesSpeech)
            val dOverlapFrames = abs(stats.framesOverlap - g.framesOverlap)
            maxFrameCountDiff = maxOf(maxFrameCountDiff, dSpeech, dOverlapFrames)
            assertTrue(
                "frames_speech Chunk $i: ${stats.framesSpeech} vs ${g.framesSpeech}",
                dSpeech <= FRAME_COUNT_TOLERANCE,
            )
            assertTrue(
                "frames_overlap Chunk $i: ${stats.framesOverlap} vs ${g.framesOverlap}",
                dOverlapFrames <= FRAME_COUNT_TOLERANCE,
            )

            val dOverlapS = abs(stats.overlapSeconds - g.overlapSeconds)
            if (dOverlapS > maxChunkOverlapDiff) maxChunkOverlapDiff = dOverlapS
            assertTrue(
                "overlap_seconds Chunk $i Abweichung ${dOverlapS}s > ${CHUNK_OVERLAP_TOLERANCE_S}s",
                dOverlapS <= CHUNK_OVERLAP_TOLERANCE_S,
            )

            assertEquals(
                "Anzahl solo_segments Chunk $i (${golden.wav})",
                g.soloSegments.size, result.segments.size,
            )
            for (s in g.soloSegments.indices) {
                assertEquals(
                    "local_speaker_idx Chunk $i Segment $s",
                    g.soloSegments[s].localSpeakerIdx, result.segments[s].localSpeakerIdx,
                )
                val dDur = abs(result.segments[s].duration - g.soloSegments[s].durationS)
                if (dDur > maxSegmentDurationDiff) maxSegmentDurationDiff = dDur
                assertTrue(
                    "Segment-Dauer Chunk $i Segment $s Abweichung ${dDur}s",
                    dDur <= SEGMENT_DURATION_TOLERANCE_S,
                )
            }
        }

        val dTotal = abs(totalOverlapSeconds - golden.summary.overlapZeitS)
        println(
            "[Overlap-Parity] ${golden.wav}: ${golden.chunks.size} Chunks — " +
                "Gesamt-Overlap=${totalOverlapSeconds}s (Golden ${golden.summary.overlapZeitS}s, " +
                "dTotal=${dTotal}s), max|dOverlap/Chunk|=${maxChunkOverlapDiff}s, " +
                "max|dFrames|=$maxFrameCountDiff, max|dSegmentDauer|=${maxSegmentDurationDiff}s"
        )
        assertTrue(
            "Gesamt-Overlap-Zeit ${totalOverlapSeconds}s weicht ${dTotal}s von " +
                "${golden.summary.overlapZeitS}s ab (> ${TOTAL_OVERLAP_TOLERANCE_S}s)",
            dTotal <= TOTAL_OVERLAP_TOLERANCE_S,
        )
        return totalOverlapSeconds
    }
}
