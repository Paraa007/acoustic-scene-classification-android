package com.fzi.speakerid.ui.targetrecorder

import com.fzi.speakerid.library.pipeline.OnnxSessionProvider
import com.fzi.speakerid.library.pipeline.steps.AudioPreprocessor
import com.fzi.speakerid.library.pipeline.steps.Chunker
import com.fzi.speakerid.library.pipeline.steps.EmbeddingExtractor
import com.fzi.speakerid.library.pipeline.steps.SileroVad
import com.fzi.speakerid.library.pipeline.steps.WavReader
import java.io.File
import kotlin.math.sqrt

/**
 * Port von siqas `audio_processing.py::generate_target_centroid_from_files`
 * (+ `extract_pure_speech`): mittelt die Embeddings aller Sprach-Chunks der
 * angegebenen WAV-Dateien und L2-normiert das Ergebnis.
 *
 * Ablauf je Datei exakt wie Python:
 *  1. `load_audio_file`     -> [WavReader.read]
 *  2. `preprocess_audio`    -> [AudioPreprocessor.preprocess]
 *  3. `extract_pure_speech` -> VAD-Timestamps, Sprach-Samples konkateniert
 *  4. `chunk_audio`         -> [Chunker.chunk] mit `padLast = false`
 *     (unvollstaendige Rest-Chunks werden wie im Original verworfen)
 *  5. `extract_embedding`   -> [EmbeddingExtractor.extractEmbedding]
 *
 * Fallbacks wie Python: ist die Sprache kuerzer als ein Chunk, wird das ganze
 * Sprach-Stueck eingebettet; findet VAD nirgends Sprache, wird das gesamte
 * (vorverarbeitete) Audio aller Dateien kombiniert eingebettet. Sind die
 * Dateien leer, wird wie `ValueError` eine [IllegalArgumentException] geworfen.
 */
object TargetCentroid {

    /** Port von `generate_target_centroid_from_files(file_paths)`. */
    fun generateFromFiles(filePaths: List<String>, provider: OnnxSessionProvider): DoubleArray {
        val vad = SileroVad(provider)
        val extractor = EmbeddingExtractor(provider)

        val allEmbeddings = ArrayList<DoubleArray>()
        val cleanAudios = ArrayList<FloatArray>()

        for (path in filePaths) {
            val raw = WavReader.read(File(path))
            val clean = AudioPreprocessor.preprocess(raw)
            cleanAudios.add(clean)

            val pure = extractPureSpeech(vad, clean)
            if (pure.isEmpty()) continue

            // chunk_audio-Defaults: 1.0 s, kein Overlap, 16 kHz, Rest verwerfen
            val chunks = Chunker.chunk(
                waveform = pure,
                sampleRate = AudioPreprocessor.TARGET_SAMPLE_RATE,
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
            val total = cleanAudios.sumOf { it.size }
            if (total == 0) {
                // Python: raise ValueError("Keine der Dateien enthielt Audio.")
                throw IllegalArgumentException("Keine der Dateien enthielt Audio.")
            }
            val combined = FloatArray(total)
            var offset = 0
            for (a in cleanAudios) {
                System.arraycopy(a, 0, combined, offset, a.size)
                offset += a.size
            }
            allEmbeddings.add(extractor.extractEmbedding(combined))
        }

        // centroid = mean(axis=0); anschliessend L2-Normierung (falls norm > 0)
        val dim = allEmbeddings[0].size
        val centroid = DoubleArray(dim)
        for (emb in allEmbeddings) {
            for (i in 0 until dim) centroid[i] += emb[i]
        }
        for (i in 0 until dim) centroid[i] /= allEmbeddings.size

        var normSq = 0.0
        for (v in centroid) normSq += v * v
        val norm = sqrt(normSq)
        return if (norm > 0) DoubleArray(dim) { centroid[it] / norm } else centroid
    }

    /**
     * Port von `extract_pure_speech`: schneidet Stille heraus und gibt nur die
     * konkatenierten Sprach-Samples zurueck (leer, wenn VAD nichts findet).
     */
    private fun extractPureSpeech(
        vad: SileroVad,
        waveform: FloatArray,
        sampleRate: Int = AudioPreprocessor.TARGET_SAMPLE_RATE,
    ): FloatArray {
        val timestamps = vad.getSpeechTimestamps(waveform, samplingRate = sampleRate)
        if (timestamps.isEmpty()) return FloatArray(0)

        val total = timestamps.sumOf { it.lengthSamples }
        val out = FloatArray(total)
        var offset = 0
        for (ts in timestamps) {
            System.arraycopy(waveform, ts.startSample, out, offset, ts.lengthSamples)
            offset += ts.lengthSamples
        }
        return out
    }
}
