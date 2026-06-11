package com.fzi.speakerid.library

import com.fzi.speakerid.library.data.SpeakerManager
import com.fzi.speakerid.testutil.GoldenData
import com.fzi.speakerid.testutil.GoldenSession
import com.fzi.speakerid.testutil.TestPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Paritaets-Test des SpeakerManagers gegen die Golden-Referenz: die
 * Golden-Embeddings beider Session-WAVs werden Chunk fuer Chunk durch den
 * Manager gefuettert (reine Logik, kein ONNX -> frei von Numerik-Drift).
 *
 * Gefordert: 100% Uebereinstimmung von `zugeordneter_sprecher` und
 * `final_sprecher` fuer ALLE Chunks sowie der End-Sprechzeiten pro Sprecher.
 * Zusaetzlich verifiziert: `similarity_scores` (Centroid-Buchhaltung),
 * `pool_promotion_new_id`, Cluster-Zeiten/-Occurrences und Sprecherzahl.
 */
class SpeakerManagerGoldenParityTest {

    /** Doubles aus dem JSON round-trippen exakt; Toleranz nur fuer Summier-Reihenfolge. */
    private val simTolerance = 1e-9

    private fun managerFromMeta(): SpeakerManager {
        val meta = GoldenData.loadMeta()
        val p = meta.parameters
        return SpeakerManager(
            targetCentroid = meta.targetCentroid.toDoubleArray(),
            assignmentStrategy = p.assignmentStrategy,
            targetThreshold = p.thresholdTarget,
            normalThreshold = p.thresholdNormal,
            centroidUpdateStrategy = p.centroidUpdateStrategy,
            emaAlpha = p.emaAlpha,
            movingAverageWindowSize = p.movingAverageWindowSize,
            minSamplesForNewSpeaker = p.minSamplesForNewSpeaker,
            poolExtractionStrategy = p.poolExtractionStrategy,
            chunkDurationS = p.chunkDurationS,
            chunkOverlapS = p.chunkOverlapS,
        )
    }

    private fun runSessionParity(session: GoldenSession) {
        val manager = managerFromMeta()
        var maxSimDiff = 0.0
        var simCount = 0

        for (chunk in session.chunks) {
            val label = "${session.wav} chunk ${chunk.chunkIdx}"

            if (chunk.vad.isSilence) {
                val result = manager.applyResult(
                    embedding = null,
                    isSilent = true,
                    durationS = 1.0,
                    startTimeS = chunk.chunkIdx.toDouble(),
                )
                assertEquals(label, chunk.zugeordneterSprecher, result.assignedId)
                assertNull("$label promotion", result.promotionId)
                continue
            }

            val vector = requireNotNull(chunk.embedding) { "$label: Speech ohne Embedding" }
                .toDoubleArray()

            // Similarity-Scores VOR dem Update — verifiziert die Centroid-Buchhaltung.
            val sims = manager.similarityScores(vector)
            assertEquals("$label sim keys", chunk.similarityScores.keys, sims.keys)
            for ((cid, expected) in chunk.similarityScores) {
                assertEquals("$label sim[$cid]", expected, sims.getValue(cid), simTolerance)
                maxSimDiff = maxOf(maxSimDiff, kotlin.math.abs(expected - sims.getValue(cid)))
                simCount += 1
            }

            val result = manager.applyResult(
                embedding = vector,
                isSilent = false,
                durationS = 1.0,
                startTimeS = chunk.chunkIdx.toDouble(),
            )
            assertEquals(label, chunk.zugeordneterSprecher, result.assignedId)
            assertEquals("$label promotion", chunk.poolPromotionNewId, result.promotionId)
        }

        // final_sprecher: 100% aller Chunks nach retroaktiven Promotions.
        val finalIds = manager.finalSpeakerIds()
        assertEquals("${session.wav} history size", session.chunks.size, finalIds.size)
        for ((i, chunk) in session.chunks.withIndex()) {
            assertEquals("${session.wav} final chunk ${chunk.chunkIdx}", chunk.finalSprecher, finalIds[i])
        }

        // End-Sprechzeiten pro Sprecher + restliche Summary-Felder.
        val summary = session.summary
        val speechTimes = manager.speechTimesPerSpeaker()
        assertEquals("${session.wav} speaker ids", summary.sprechzeitProSprecherS.keys, speechTimes.keys)
        for ((cid, expected) in summary.sprechzeitProSprecherS) {
            assertEquals("${session.wav} speech time [$cid]", expected, speechTimes.getValue(cid), simTolerance)
        }
        assertEquals("${session.wav} pool time", summary.unlabeledZeitS, manager.unlabeled.totalTime, simTolerance)
        assertEquals("${session.wav} silence time", summary.stilleZeitS, manager.silence.totalTime, simTolerance)
        assertEquals("${session.wav} speaker count", summary.anzahlSprecher, manager.activeSpeakersCount)

        // Cluster-Bestand inkl. Erzeugungs-Reihenfolge (Python-Dict-Ordnung).
        assertEquals(
            "${session.wav} cluster order",
            summary.clusterTotalTimeS.keys.toList(),
            manager.clusters.keys.toList(),
        )
        for ((cid, expected) in summary.clusterTotalTimeS) {
            assertEquals(
                "${session.wav} total_time [$cid]",
                expected,
                manager.clusters.getValue(cid).totalTime,
                simTolerance,
            )
        }
        for ((cid, expected) in summary.clusterOccurrences) {
            assertEquals(
                "${session.wav} occurrences [$cid]",
                expected,
                manager.clusters.getValue(cid).occurrences,
            )
        }

        println(
            "[parity] ${session.wav}: ${session.chunks.size} Chunks, " +
                "$simCount Similarity-Werte, max |dSim| = $maxSimDiff",
        )
    }

    @Test
    fun conversation2_fullParity() {
        TestPaths.assumeHostData()
        runSessionParity(GoldenData.loadConversation2())
    }

    @Test
    fun overlapSynthetic_fullParity() {
        TestPaths.assumeHostData()
        runSessionParity(GoldenData.loadOverlapSynthetic())
    }
}
