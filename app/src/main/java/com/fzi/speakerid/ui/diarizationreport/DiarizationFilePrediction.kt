package com.fzi.speakerid.ui.diarizationreport

import com.fzi.speakerid.library.LiveProcessor
import com.fzi.speakerid.library.LiveProcessorConfig
import com.fzi.speakerid.library.data.SpeakerManager
import com.fzi.speakerid.library.pipeline.OnnxSessionProvider
import com.fzi.speakerid.library.pipeline.steps.AudioPreprocessor
import com.fzi.speakerid.library.pipeline.steps.Chunker
import com.fzi.speakerid.library.pipeline.steps.EmbeddingExtractor
import com.fzi.speakerid.library.pipeline.steps.SileroVad
import com.fzi.speakerid.library.pipeline.steps.WavReader
import java.io.File
import java.util.Locale
import kotlin.math.sqrt

/**
 * Offline-Datei-Vorhersage fuer den Diarisations-Report — Pendant zu
 * `DiarizationReportScreen._run_file_prediction_background` mit
 * `library.pipeline.pipeline.run_on_wav` + frischem `ClusterManager`:
 * die komplette WAV-Datei wird chunk-weise durch einen Phase-1-
 * [LiveProcessor] mit EIGENEM (frischem) [SpeakerManager] geschickt —
 * der App-Zustand im SpeakerIdDataManager bleibt unberuehrt.
 *
 * Enthaelt ausserdem den Port von `generate_target_centroid_from_file`
 * (Target-Seeding) und von `merge_chunks_into_segments` /
 * `format_segments_to_rttm_lines` aus `library/rttm.py`.
 */
object DiarizationFilePrediction {

    /** Ergebnis analog `PipelineRunResult` + Embedding-Extraktion des Screens. */
    class Result(
        /** RTTM-Zeilen der Vorhersage (`result.to_rttm_lines(stem)`). */
        val hypLines: List<String>,
        /** [(cluster_id, embedding)] aller Cluster ausser "-1". */
        val flatEmbeddings: List<Pair<String, DoubleArray>>,
        /** [(cluster_id, centroid)] aller Cluster ausser "-1". */
        val centroidEmbeddings: List<Pair<String, DoubleArray>>,
    )

    /**
     * Port von `run_on_wav(wav_path, cfg, world)`: WAV laden, auf 16 kHz mono
     * vorverarbeiten, in Chunks schneiden (`chunk_audio` verwirft den
     * unvollstaendigen Rest-Chunk -> `padLast = false`) und sequenziell
     * verarbeiten. Pool-Promotions schreiben die Chunk-History retroaktiv um
     * (Pendant zu `propagate_retroactive_promotions`).
     */
    fun runOnWav(
        provider: OnnxSessionProvider,
        wavFile: File,
        config: LiveProcessorConfig,
        targetCentroid: DoubleArray?,
    ): Result {
        val processor = LiveProcessor(provider, targetCentroid, config)
        processor.resetStream()

        val waveform = AudioPreprocessor.preprocess(WavReader.read(wavFile), config.sampleRate)
        val chunks = Chunker.chunk(
            waveform = waveform,
            sampleRate = config.sampleRate,
            chunkDurationS = config.chunkDurationS,
            overlapS = config.chunkOverlapS,
            padLast = false,
        )
        for (chunk in chunks) {
            processor.processChunk(chunk.index, chunk.samples)
        }

        val manager = processor.speakerManager
        val stem = wavFile.nameWithoutExtension
        val hypLines = historyToRttmLines(manager, config, stem)

        // Cluster/Embeddings fuer die statische PCA-Projektion extrahieren
        val flatEmbeddings = ArrayList<Pair<String, DoubleArray>>()
        val centroidEmbeddings = ArrayList<Pair<String, DoubleArray>>()
        for ((cid, cluster) in manager.clusters) {
            if (cid == "-1") continue
            cluster.centroid?.let { centroidEmbeddings.add(cid to it.copyOf()) }
            for (emb in cluster.embeddings) {
                emb.embedding?.let { flatEmbeddings.add(cid to it.copyOf()) }
            }
        }

        return Result(hypLines, flatEmbeddings, centroidEmbeddings)
    }

    /**
     * Port von `library/rttm.py::merge_chunks_into_segments` +
     * `format_segments_to_rttm_lines` auf der Chunk-History des frischen
     * Managers (identisch zu `SpeakerIdDataManager.exportHistoryToRttm`,
     * nur mit den eingefrorenen Config-Werten statt der App-Settings).
     */
    private fun historyToRttmLines(
        manager: SpeakerManager,
        config: LiveProcessorConfig,
        audioId: String,
    ): List<String> {
        val history = manager.chunkHistory
        if (history.isEmpty()) return emptyList()

        val speakerIds = history.map { entry ->
            if (entry.status == SpeakerManager.STATUS_SILENCE) "-1" else entry.id
        }

        var step = config.chunkDurationS - config.chunkOverlapS
        if (step <= 0) step = 1.0
        val duration = config.chunkDurationS

        val segments = ArrayList<Triple<String, Double, Double>>()
        var current: Triple<String, Double, Double>? = null
        for ((idx, speakerId) in speakerIds.withIndex()) {
            val start = idx * step
            val end = start + duration
            if (speakerId == null || speakerId == "0" || speakerId == "-1") {
                current?.let { segments.add(it) }
                current = null
                continue
            }
            val cur = current
            current = when {
                cur == null -> Triple(speakerId, start, end)
                cur.first == speakerId && kotlin.math.abs(cur.third - start) < 1e-6 ->
                    Triple(cur.first, cur.second, end)
                else -> {
                    segments.add(cur)
                    Triple(speakerId, start, end)
                }
            }
        }
        current?.let { segments.add(it) }

        return segments.map { (speakerId, start, end) ->
            String.format(
                Locale.US,
                "SPEAKER %s 0 %.6f %.6f <NA> <NA> %s <NA> <NA>",
                audioId, start, end - start, speakerId,
            )
        }
    }

    /**
     * Port von `audio_processing.generate_target_centroid_from_file`:
     * mittelt die Embeddings aller Sprach-Chunks der Datei. Ist die Sprache
     * kuerzer als ein Chunk, wird das ganze Stueck eingebettet; findet VAD
     * keine Sprache, wird das gesamte Audio direkt eingebettet.
     */
    fun generateTargetCentroidFromFile(provider: OnnxSessionProvider, file: File): DoubleArray {
        val vad = SileroVad(provider)
        val extractor = EmbeddingExtractor(provider)

        val clean = AudioPreprocessor.preprocess(WavReader.read(file), SAMPLE_RATE)

        // extract_pure_speech: Stille herausschneiden, Sprach-Samples konkatenieren
        val timestamps = vad.getSpeechTimestamps(clean, samplingRate = SAMPLE_RATE)
        var pureLength = 0
        for (ts in timestamps) pureLength += ts.endSample - ts.startSample
        val pure = FloatArray(pureLength)
        var offset = 0
        for (ts in timestamps) {
            val len = ts.endSample - ts.startSample
            System.arraycopy(clean, ts.startSample, pure, offset, len)
            offset += len
        }

        val allEmbeddings = ArrayList<DoubleArray>()
        if (pure.isNotEmpty()) {
            // chunk_audio (verwirft unvollstaendige Rest-Chunks)
            val chunks = Chunker.chunk(
                waveform = pure,
                sampleRate = SAMPLE_RATE,
                chunkDurationS = 1.0,
                overlapS = 0.0,
                padLast = false,
            )
            if (chunks.isNotEmpty()) {
                for (ch in chunks) allEmbeddings.add(extractor.extractEmbedding(ch.samples))
            } else {
                allEmbeddings.add(extractor.extractEmbedding(pure))
            }
        }

        if (allEmbeddings.isEmpty()) {
            // Fallback: gesamtes Audio direkt einbetten
            require(clean.isNotEmpty()) { "Keine der Dateien enthielt Audio." }
            allEmbeddings.add(extractor.extractEmbedding(clean))
        }

        // Mittelwert + L2-Normierung
        val dim = allEmbeddings[0].size
        val centroid = DoubleArray(dim)
        for (emb in allEmbeddings) for (j in 0 until dim) centroid[j] += emb[j]
        for (j in 0 until dim) centroid[j] /= allEmbeddings.size

        var sq = 0.0
        for (x in centroid) sq += x * x
        val norm = sqrt(sq)
        return if (norm > 0) DoubleArray(dim) { centroid[it] / norm } else centroid
    }

    private const val SAMPLE_RATE = 16000
}
