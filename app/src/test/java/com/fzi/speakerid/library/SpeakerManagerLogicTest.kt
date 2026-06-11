package com.fzi.speakerid.library

import com.fzi.speakerid.library.data.Cluster
import com.fzi.speakerid.library.data.SpeakerManager
import com.fzi.speakerid.library.pipeline.steps.SpeakerMatcher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Maschinen-unabhaengige Logik-Tests des SpeakerManagers mit synthetischen
 * Vektoren (laufen ohne Golden-Daten): Stille-Pfad, statischer Target-
 * Centroid, Clique-Promotion mit History-Rewrite, ID-Vergabe, best_overall.
 */
class SpeakerManagerLogicTest {

    /** Target zeigt auf Achse 0; "fremde" Sprecher auf Achse 1. */
    private val targetAxis = doubleArrayOf(1.0, 0.0, 0.0, 0.0)

    private fun newManager() = SpeakerManager(targetCentroid = targetAxis)

    private fun unit(vararg v: Double): DoubleArray {
        val norm = sqrt(v.sumOf { it * it })
        return DoubleArray(v.size) { v[it] / norm }
    }

    @Test
    fun silenceChunk_goesToSilenceCluster() {
        val manager = newManager()
        val result = manager.applyResult(null, isSilent = true, durationS = 1.0)
        assertEquals("-1", result.assignedId)
        assertNull(result.promotionId)
        assertEquals(1.0, manager.silence.totalTime, 0.0)
        assertEquals(1, manager.silence.occurrences)
        assertNull(manager.chunkHistory[0].id)
        assertEquals(listOf("-1"), manager.finalSpeakerIds())
    }

    @Test
    fun targetMatch_keepsStaticCentroid() {
        val manager = newManager()
        val nearTarget = unit(1.0, 0.1, 0.0, 0.0)
        val result = manager.applyResult(nearTarget, isSilent = false)
        assertEquals("1", result.assignedId)
        // static: Centroid bleibt exakt auf dem registrierten Target stehen.
        assertArrayEquals(targetAxis, manager.target.centroid, 0.0)
        assertEquals(1.0, manager.target.totalTime, 0.0)
    }

    @Test
    fun threeSimilarPoolPoints_promoteNewSpeakerAndRewriteHistory() {
        val manager = newManager()
        val stranger = unit(0.0, 1.0, 0.05, 0.0)
        val results = (0 until 3).map {
            manager.applyResult(stranger, isSilent = false, startTimeS = it.toDouble())
        }
        assertEquals(listOf("0", "0", "0"), results.map { it.assignedId })
        assertNull(results[0].promotionId)
        assertNull(results[1].promotionId)
        assertEquals("2", results[2].promotionId)

        // Pool geleert, Zeiten/Occurrences in den neuen Sprecher verschoben.
        assertEquals(0, manager.unlabeled.occurrences)
        assertEquals(0.0, manager.unlabeled.totalTime, 0.0)
        val speaker = manager.clusters.getValue("2")
        assertEquals(3, speaker.occurrences)
        assertEquals(3.0, speaker.totalTime, 0.0)

        // History retroaktiv umgeschrieben -> final_sprecher "2".
        assertEquals(listOf("2", "2", "2"), manager.finalSpeakerIds())
        assertTrue(manager.chunkHistory.all { it.prevId == "0" })

        // Folge-Chunk matcht direkt den neuen Sprecher.
        assertEquals("2", manager.applyResult(stranger, isSilent = false).assignedId)
        assertEquals(mapOf("2" to 4.0), manager.speechTimesPerSpeaker())
        // Target zaehlt ohne Sprechzeit mit, weil sein Centroid registriert ist.
        assertEquals(2, manager.activeSpeakersCount)
    }

    @Test
    fun twoPoolPoints_doNotPromote() {
        val manager = newManager()
        val stranger = unit(0.0, 1.0, 0.0, 0.0)
        manager.applyResult(stranger, isSilent = false)
        val second = manager.applyResult(stranger, isSilent = false)
        assertNull(second.promotionId)
        assertEquals(2, manager.unlabeled.occurrences)
        assertEquals(listOf("0", "0"), manager.finalSpeakerIds())
    }

    @Test
    fun nextSpeakerId_skipsReservedIds() {
        val manager = newManager()
        assertEquals("2", manager.nextSpeakerId())
        manager.createSpeaker("2")
        assertEquals("3", manager.nextSpeakerId())
        manager.createSpeaker("7")
        assertEquals("8", manager.nextSpeakerId())
    }

    @Test
    fun bestOverall_picksHighestSimilarityAboveThreshold() {
        val clusters = LinkedHashMap<String, Cluster>()
        clusters["0"] = Cluster("0")
        clusters["1"] = Cluster("1").apply { centroid = unit(1.0, 0.0, 0.0, 0.0) }
        clusters["-1"] = Cluster("-1")
        clusters["2"] = Cluster("2").apply { centroid = unit(0.6, 0.8, 0.0, 0.0) }

        val query = unit(0.7, 0.714, 0.0, 0.0) // beiden aehnlich, "2" am naechsten
        val matched = SpeakerMatcher.findClusterForEmbedding(
            newEmbedding = query,
            clustersDict = clusters,
            strategyName = SpeakerMatcher.STRATEGY_BEST_OVERALL,
            targetThreshold = 0.7,
            normalThreshold = 0.7,
        )
        assertEquals("2", matched)

        val orthogonal = unit(0.0, 0.0, 1.0, 0.0)
        val unmatched = SpeakerMatcher.findClusterForEmbedding(
            newEmbedding = orthogonal,
            clustersDict = clusters,
            strategyName = SpeakerMatcher.STRATEGY_BEST_OVERALL,
            targetThreshold = 0.7,
            normalThreshold = 0.7,
        )
        assertEquals("0", unmatched)
    }

    /**
     * Arena-"Laufzeit" = Summe aller Cluster-Zeiten (physics_arena.py
     * `_sync_ui`): pro Chunk darf exakt die (overlap-skalierte) Segment-
     * Dauer dazukommen — 60 Chunks a 1,0 s ohne Overlap ergeben 60 s,
     * nicht mehr (Regression gegen zu schnell laufende Laufzeit-Anzeige).
     */
    @Test
    fun clusterTimeSum_growsExactlyByChunkDuration() {
        val manager = newManager()
        val nearTarget = unit(1.0, 0.1, 0.0, 0.0)
        repeat(30) { manager.applyResult(null, isSilent = true, durationS = 1.0) }
        repeat(30) { manager.applyResult(nearTarget, isSilent = false, durationS = 1.0) }

        val totalSec = manager.clusters.values.sumOf { it.totalTime }
        assertEquals(60.0, totalSec, 1e-9)

        // Mit Overlap: real_time_added = duration * (chunk - overlap) / chunk
        // (live_processor.py `_apply_results`).
        val overlapManager = SpeakerManager(
            targetCentroid = targetAxis,
            chunkDurationS = 1.0,
            chunkOverlapS = 0.5,
        )
        repeat(10) { overlapManager.applyResult(null, isSilent = true, durationS = 1.0) }
        val overlapTotal = overlapManager.clusters.values.sumOf { it.totalTime }
        assertEquals(5.0, overlapTotal, 1e-9)
    }
}
