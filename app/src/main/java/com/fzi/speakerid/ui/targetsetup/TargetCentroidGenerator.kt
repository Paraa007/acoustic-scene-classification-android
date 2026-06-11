package com.fzi.speakerid.ui.targetsetup

import com.fzi.speakerid.library.pipeline.OnnxSessionProvider
import com.fzi.speakerid.library.pipeline.steps.AudioPreprocessor
import com.fzi.speakerid.library.pipeline.steps.Chunker
import com.fzi.speakerid.library.pipeline.steps.EmbeddingExtractor
import com.fzi.speakerid.library.pipeline.steps.SileroVad
import com.fzi.speakerid.library.pipeline.steps.WavReader
import java.io.File
import kotlin.math.sqrt

/**
 * Port von siqas `library/pipeline/steps/audio_processing.py::
 * generate_target_centroid_from_files` fuer den Dateiauswahl-Weg des
 * Target-Setups (Explorer im Modus "target").
 *
 * Mittelt die Embeddings aller Sprach-Chunks der angegebenen WAV-Dateien:
 *  1. load_audio_file        -> [WavReader.read]
 *  2. preprocess_audio       -> [AudioPreprocessor.preprocess] (Mono, Norm, 16 kHz)
 *  3. extract_pure_speech    -> [SileroVad.getSpeechTimestamps] + Konkatenation
 *  4. chunk_audio (Defaults) -> [Chunker.chunk] mit padLast = false
 *  5. extract_embedding      -> [EmbeddingExtractor.extractEmbedding]
 *
 * Findet VAD in keiner Datei Sprache, wird das gesamte (konkatentierte) Audio
 * direkt eingebettet, statt zu scheitern. Ergebnis ist das L2-normierte Mittel.
 */
object TargetCentroidGenerator {

    private const val SAMPLE_RATE = 16000

    fun generateFromFiles(files: List<File>, modelsDir: File): DoubleArray {
        OnnxSessionProvider(modelsDir).use { provider ->
            val vad = SileroVad(provider)
            val extractor = EmbeddingExtractor(provider)

            val allEmbeddings = ArrayList<DoubleArray>()
            val cleanAudios = ArrayList<FloatArray>()

            for (file in files) {
                val raw = WavReader.read(file)
                val clean = AudioPreprocessor.preprocess(raw)
                cleanAudios.add(clean)

                val pure = extractPureSpeech(vad, clean)
                if (pure.isEmpty()) continue

                // chunk_audio mit Modul-Defaults (1.0 s, kein Overlap);
                // unvollstaendige Rest-Chunks werden verworfen (padLast=false).
                val chunks = Chunker.chunk(
                    waveform = pure,
                    sampleRate = SAMPLE_RATE,
                    chunkDurationS = 1.0,
                    overlapS = 0.0,
                    padLast = false,
                )
                if (chunks.isNotEmpty()) {
                    for (chunk in chunks) {
                        allEmbeddings.add(extractor.extractEmbedding(chunk.samples))
                    }
                } else {
                    // Sprache kuerzer als ein Chunk: ganzes Stueck einbetten
                    allEmbeddings.add(extractor.extractEmbedding(pure))
                }
            }

            if (allEmbeddings.isEmpty()) {
                val combined = concat(cleanAudios)
                if (combined.isEmpty()) {
                    throw IllegalArgumentException("Keine der Dateien enthielt Audio.")
                }
                allEmbeddings.add(extractor.extractEmbedding(combined))
            }

            // centroid = arr.mean(axis=0); anschliessend L2-normieren (norm > 0)
            val dim = allEmbeddings[0].size
            val centroid = DoubleArray(dim)
            for (emb in allEmbeddings) {
                for (i in 0 until dim) centroid[i] += emb[i]
            }
            for (i in 0 until dim) centroid[i] /= allEmbeddings.size

            var sumSq = 0.0
            for (x in centroid) sumSq += x * x
            val norm = sqrt(sumSq)
            return if (norm > 0) DoubleArray(dim) { centroid[it] / norm } else centroid
        }
    }

    /** Port von `extract_pure_speech`: Stille herausschneiden, Sprache konkatenieren. */
    private fun extractPureSpeech(vad: SileroVad, audio: FloatArray): FloatArray {
        val segments = vad.getSpeechTimestamps(audio, samplingRate = SAMPLE_RATE)
        if (segments.isEmpty()) return FloatArray(0)
        val total = segments.sumOf { it.lengthSamples }
        val out = FloatArray(total)
        var pos = 0
        for (seg in segments) {
            System.arraycopy(audio, seg.startSample, out, pos, seg.lengthSamples)
            pos += seg.lengthSamples
        }
        return out
    }

    private fun concat(arrays: List<FloatArray>): FloatArray {
        val total = arrays.sumOf { it.size }
        val out = FloatArray(total)
        var pos = 0
        for (a in arrays) {
            System.arraycopy(a, 0, out, pos, a.size)
            pos += a.size
        }
        return out
    }
}
