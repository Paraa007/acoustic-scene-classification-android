package com.fzi.speakerid.library.pipeline.steps

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Port von siqas `src/library/pipeline/steps/audio_processing.py::load_audio_file`.
 *
 * Liest PCM-16 RIFF/WAVE-Dateien und skaliert die Samples wie das Python-Original:
 * `int16 / 32767` (numpy `np.iinfo(np.int16).max`, NICHT 32768) als Float32.
 * Kanaele werden getrennt zurueckgegeben (Shape-Aequivalent zu `(n_channels, n)`);
 * der Mono-Mix passiert wie in siqas erst in [WavAudio.monoMix] / [AudioPreprocessor]
 * via Mittelwert ueber die Kanaele (`np.mean(axis=0)` in Float32).
 */
object WavReader {

    /** Aequivalent zum Rueckgabewert `(data, sr)` von `load_audio_file`. */
    class WavAudio(
        /** Ein FloatArray pro Kanal, Samples in [-1, 1] (int16/32767). */
        val channels: List<FloatArray>,
        val sampleRate: Int,
    ) {
        val numChannels: Int get() = channels.size
        val numFrames: Int get() = if (channels.isEmpty()) 0 else channels[0].size

        /**
         * Stereo->Mono exakt wie siqas `preprocess_audio`:
         * `np.mean(waveform, axis=0)` in Float32 — Summe der Kanaele pro Frame
         * geteilt durch die Kanalanzahl, alles in Float-Arithmetik.
         * Bei Mono wird das Original-Array kopiert zurueckgegeben.
         */
        fun monoMix(): FloatArray {
            if (numChannels == 1) return channels[0].copyOf()
            val n = numFrames
            val out = FloatArray(n)
            val ch = numChannels.toFloat()
            for (i in 0 until n) {
                var acc = 0f
                for (c in channels) acc += c[i]
                out[i] = acc / ch
            }
            return out
        }
    }

    private const val INT16_MAX = 32767f

    /** Liest eine PCM-16 WAV-Datei. Wirft [IOException] bei anderen Formaten. */
    fun read(file: File): WavAudio {
        val bytes = file.readBytes()
        if (bytes.size < 12) throw IOException("WAV zu kurz: ${file.path}")
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        if (readFourCc(buf) != "RIFF") throw IOException("Kein RIFF-Header: ${file.path}")
        buf.int // RIFF-Groesse (ignoriert)
        if (readFourCc(buf) != "WAVE") throw IOException("Kein WAVE-Format: ${file.path}")

        var audioFormat = -1
        var numChannels = -1
        var sampleRate = -1
        var bitsPerSample = -1
        var dataOffset = -1
        var dataSize = -1

        // Sub-Chunks durchgehen (fmt , data, sonstige ueberspringen; auf gerade Laenge gepolstert)
        while (buf.remaining() >= 8) {
            val id = readFourCc(buf)
            val size = buf.int
            if (size < 0 || size > buf.remaining()) {
                if (id == "data" && dataOffset == -1) {
                    // Toleranz fuer kaputte Groessenangabe: Rest der Datei als Daten nehmen
                    dataOffset = buf.position()
                    dataSize = buf.remaining()
                }
                break
            }
            when (id) {
                "fmt " -> {
                    val start = buf.position()
                    audioFormat = buf.short.toInt() and 0xFFFF
                    numChannels = buf.short.toInt() and 0xFFFF
                    sampleRate = buf.int
                    buf.int   // byteRate
                    buf.short // blockAlign
                    bitsPerSample = buf.short.toInt() and 0xFFFF
                    if (audioFormat == 0xFFFE && size >= 40) {
                        // WAVE_FORMAT_EXTENSIBLE: tatsaechliches Format aus dem SubFormat-GUID
                        buf.short // cbSize
                        buf.short // validBitsPerSample
                        buf.int   // channelMask
                        audioFormat = buf.short.toInt() and 0xFFFF
                    }
                    buf.position(start + size + (size and 1))
                }
                "data" -> {
                    dataOffset = buf.position()
                    dataSize = size
                    buf.position(buf.position() + size + (size and 1))
                }
                else -> buf.position(buf.position() + size + (size and 1))
            }
        }

        if (audioFormat != 1) throw IOException("Nur PCM (Format 1) unterstuetzt, war $audioFormat: ${file.path}")
        if (bitsPerSample != 16) throw IOException("Nur 16-bit PCM unterstuetzt, war $bitsPerSample bit: ${file.path}")
        if (numChannels < 1) throw IOException("Ungueltige Kanalanzahl $numChannels: ${file.path}")
        if (dataOffset < 0) throw IOException("Kein data-Chunk gefunden: ${file.path}")

        val bytesPerFrame = 2 * numChannels
        val numFrames = dataSize / bytesPerFrame
        val data = ByteBuffer.wrap(bytes, dataOffset, numFrames * bytesPerFrame)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()

        // Interleaved -> getrennte Kanaele, Skalierung /32767 wie siqas
        val channels = List(numChannels) { FloatArray(numFrames) }
        for (frame in 0 until numFrames) {
            for (c in 0 until numChannels) {
                channels[c][frame] = data.get(frame * numChannels + c) / INT16_MAX
            }
        }
        return WavAudio(channels, sampleRate)
    }

    private fun readFourCc(buf: ByteBuffer): String {
        val b = ByteArray(4)
        buf.get(b)
        return String(b, Charsets.US_ASCII)
    }
}
