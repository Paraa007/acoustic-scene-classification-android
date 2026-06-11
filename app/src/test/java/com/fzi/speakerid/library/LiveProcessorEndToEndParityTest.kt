package com.fzi.speakerid.library

import com.fzi.speakerid.library.pipeline.OnnxSessionProvider
import com.fzi.speakerid.library.pipeline.steps.AudioPreprocessor
import com.fzi.speakerid.library.pipeline.steps.WavReader
import com.fzi.speakerid.testutil.GoldenData
import com.fzi.speakerid.testutil.GoldenMeta
import com.fzi.speakerid.testutil.GoldenSession
import com.fzi.speakerid.testutil.TestPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-End-Paritaet von den ROHEN Session-WAVs: WavReader ->
 * AudioPreprocessor -> [LiveProcessor.processAll] (RMS -> VAD -> Embedding ->
 * SpeakerManager; Overlap per GUI-Default AUS) gegen die Golden-Referenz.
 *
 * Gefordert:
 *  - `zugeordneter_sprecher` und `final_sprecher` pro Chunk: Ziel 100 %,
 *    harte Grenze >= 98 % (sonst FAIL mit Mismatch-Analyse im Output).
 *  - End-Sprechzeiten pro Sprecher, Pool- und Stille-Zeit: Toleranz +-0.1 s
 *    (conversation2: 22/21/23/25 s, Pool 8, Stille 17;
 *     overlap_synthetic: 17/5 s, Pool 3, Stille 10).
 */
class LiveProcessorEndToEndParityTest {

    private companion object {
        const val MIN_MATCH_RATIO = 0.98
        const val TIME_TOLERANCE_S = 0.1
        const val RMS_TOLERANCE = 1e-8
    }

    private fun configFromMeta(meta: GoldenMeta): LiveProcessorConfig {
        val p = meta.parameters
        return LiveProcessorConfig(
            sampleRate = p.sampleRate,
            chunkDurationS = p.chunkDurationS,
            chunkOverlapS = p.chunkOverlapS,
            useSileroVad = p.useSileroVad,
            vadThreshold = p.vadThreshold,
            minSpeechSeconds = p.minSpeechSeconds,
            usePyannote = p.usePyannoteOverlap,
            cleanerMinDurationS = p.overlapCleaner.minDurationS,
            cleanerMinIslandDurationS = p.overlapCleaner.minIslandDurationS,
            assignmentStrategy = p.assignmentStrategy,
            targetThreshold = p.thresholdTarget,
            normalThreshold = p.thresholdNormal,
            centroidUpdateStrategy = p.centroidUpdateStrategy,
            emaAlpha = p.emaAlpha,
            movingAverageWindowSize = p.movingAverageWindowSize,
            minSamplesForNewSpeaker = p.minSamplesForNewSpeaker,
            poolExtractionStrategy = p.poolExtractionStrategy,
        )
    }

    /** Prozentuale Uebereinstimmung mit Mismatch-Analyse als Assertion-Text. */
    private fun assertMatchRatio(
        label: String,
        expected: List<String>,
        actual: List<String>,
        mismatchDetail: (Int) -> String = { "" },
    ): Double {
        assertEquals("$label: Chunk-Anzahl", expected.size, actual.size)
        val mismatches = expected.indices.filter { expected[it] != actual[it] }
        val ratio = (expected.size - mismatches.size).toDouble() / expected.size
        val analysis = mismatches.joinToString("\n") { i ->
            "  chunk $i: golden=${expected[i]} kotlin=${actual[i]}${mismatchDetail(i)}"
        }
        println("[e2e] $label: ${"%.2f".format(ratio * 100)}% (${mismatches.size}/${expected.size} Mismatches)")
        if (mismatches.isNotEmpty()) println("[e2e] Mismatch-Analyse $label:\n$analysis")
        assertTrue(
            "$label: nur ${"%.2f".format(ratio * 100)}% Uebereinstimmung (< 98%).\n$analysis",
            ratio >= MIN_MATCH_RATIO,
        )
        return ratio
    }

    private fun runEndToEnd(golden: GoldenSession) {
        TestPaths.assumeHostData()
        val meta = GoldenData.loadMeta()
        val config = configFromMeta(meta)

        // Rohe WAV -> 16 kHz mono (Session-WAVs sind bereits 16 kHz mono).
        val wav = WavReader.read(TestPaths.audio(golden.wav))
        val processed = AudioPreprocessor.preprocess(wav, config.sampleRate)

        OnnxSessionProvider(TestPaths.modelsDir).use { provider ->
            val processor = LiveProcessor(
                provider = provider,
                targetCentroid = meta.targetCentroid.toDoubleArray(),
                config = config,
            )
            val results = processor.processAll(processed)

            assertEquals("${golden.wav}: Chunk-Anzahl", golden.chunks.size, results.size)

            // Default-Pfad (Overlap aus): genau 1 Segment pro Chunk + RMS-Paritaet.
            for ((i, result) in results.withIndex()) {
                assertEquals("${golden.wav} chunk $i: Segment-Anzahl", 1, result.segments.size)
                assertEquals("${golden.wav} chunk $i: rms", golden.chunks[i].rms, result.rms, RMS_TOLERANCE)
            }

            // zugeordneter_sprecher (Live-Zuordnung) pro Chunk.
            assertMatchRatio(
                label = "${golden.wav} zugeordneter_sprecher",
                expected = golden.chunks.map { it.zugeordneterSprecher },
                actual = results.map { it.segments.single().assignedId },
            ) { i ->
                val g = golden.chunks[i]
                val r = results[i].segments.single()
                " (golden silence=${g.vad.isSilence}, kotlin silence=${r.isSilent}," +
                    " golden sims=${g.similarityScores})"
            }

            // final_sprecher (nach retroaktiven Pool-Promotions) pro Chunk.
            assertMatchRatio(
                label = "${golden.wav} final_sprecher",
                expected = golden.chunks.map { it.finalSprecher },
                actual = processor.speakerManager.finalSpeakerIds(),
            )

            // End-Statistik: Sprechzeiten pro Sprecher, Pool, Stille (+-0.1 s).
            val stats = processor.stats()
            val summary = golden.summary
            assertEquals(
                "${golden.wav}: Sprecher-IDs",
                summary.sprechzeitProSprecherS.keys,
                stats.speechTimePerSpeakerS.keys,
            )
            for ((cid, expected) in summary.sprechzeitProSprecherS) {
                assertEquals(
                    "${golden.wav}: Sprechzeit [$cid]",
                    expected,
                    stats.speechTimePerSpeakerS.getValue(cid),
                    TIME_TOLERANCE_S,
                )
            }
            assertEquals("${golden.wav}: Pool-Zeit", summary.unlabeledZeitS, stats.unlabeledTimeS, TIME_TOLERANCE_S)
            assertEquals("${golden.wav}: Stille-Zeit", summary.stilleZeitS, stats.silenceTimeS, TIME_TOLERANCE_S)
            assertEquals("${golden.wav}: anzahl_sprecher", summary.anzahlSprecher, stats.activeSpeakersCount)
            assertEquals("${golden.wav}: speech_chunks", summary.speechChunks, stats.speechChunks)
            assertEquals("${golden.wav}: chunks_gesamt", summary.chunksGesamt, stats.chunksProcessed)

            println(
                "[e2e] ${golden.wav}: Zeiten=${stats.speechTimePerSpeakerS}, " +
                    "Pool=${stats.unlabeledTimeS}s, Stille=${stats.silenceTimeS}s, " +
                    "Sprecher=${stats.activeSpeakersCount}",
            )
        }
    }

    @Test
    fun conversation2_endToEndFromRawWav() {
        runEndToEnd(GoldenData.loadConversation2())
    }

    @Test
    fun overlapSynthetic_endToEndFromRawWav() {
        runEndToEnd(GoldenData.loadOverlapSynthetic())
    }
}
