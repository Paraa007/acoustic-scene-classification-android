package com.fzi.speakerid.library.pipeline.steps

/**
 * Chunking wie der siqas-Live-Flow (`VirtualFileRecorder._playback_loop` bzw.
 * `golden/generate_golden.py::iter_virtual_mic_chunks`):
 * 1.0-s-Chunks (16000 Samples), Step = chunk - overlap, der LETZTE Chunk wird
 * mit Nullen auf volle Laenge gepaddet (`padLast = true`, Golden-Verhalten).
 *
 * Mit `padLast = false` entspricht es `audio_processing.chunk_audio`, das
 * unvollstaendige Rest-Chunks verwirft.
 */
object Chunker {

    /** Ein Chunk fester Laenge. `startTimeS` = startSample / sampleRate. */
    class AudioChunk(
        val index: Int,
        val startTimeS: Double,
        val samples: FloatArray,
    )

    fun chunk(
        waveform: FloatArray,
        sampleRate: Int = 16000,
        chunkDurationS: Double = 1.0,
        overlapS: Double = 0.0,
        padLast: Boolean = true,
    ): List<AudioChunk> {
        val chunkSamples = (sampleRate * chunkDurationS).toInt()
        val stepSamples = (sampleRate * (chunkDurationS - overlapS)).toInt()
        require(chunkSamples > 0 && stepSamples > 0) { "Chunk-/Step-Groesse muss > 0 sein" }

        val chunks = ArrayList<AudioChunk>()
        var index = 0
        var start = 0
        while (start < waveform.size) {
            val end = minOf(start + chunkSamples, waveform.size)
            val available = end - start
            if (available < chunkSamples && !padLast) break
            // copyOfRange + Zero-Padding (FloatArray ist mit 0f initialisiert)
            val samples = FloatArray(chunkSamples)
            System.arraycopy(waveform, start, samples, 0, available)
            chunks.add(AudioChunk(index, start.toDouble() / sampleRate, samples))
            index++
            start += stepSamples
        }
        return chunks
    }
}
