package com.fzi.speakerid.library.pipeline.steps

import kotlin.math.abs

/**
 * Port von siqas `src/library/pipeline/steps/audio_processing.py::preprocess_audio`.
 *
 * Reihenfolge exakt wie im Original:
 *   1. Mono-Mix (Mittelwert ueber Kanaele, Float32) — [WavReader.WavAudio.monoMix]
 *   2. Peak-Normalisierung (Division durch max(|x|), nur falls > 0, Float32)
 *   3. Resampling auf die Zielrate NUR falls sample_rate != target (soxr-Aequivalent)
 */
object AudioPreprocessor {

    const val TARGET_SAMPLE_RATE = 16000

    /** Komplettes Preprocessing einer geladenen WAV: Mono-Mix -> Peak-Norm -> Resample. */
    fun preprocess(audio: WavReader.WavAudio, targetSampleRate: Int = TARGET_SAMPLE_RATE): FloatArray {
        var mono = audio.monoMix()
        mono = peakNormalize(mono)
        if (audio.sampleRate != targetSampleRate) {
            mono = Resampler.resample(mono, audio.sampleRate, targetSampleRate)
        }
        return mono
    }

    /**
     * Peak-Normalisierung wie siqas: `waveform / np.max(np.abs(waveform))`,
     * nur wenn das Maximum > 0 ist. Float32-Arithmetik, gibt ein neues Array zurueck.
     */
    fun peakNormalize(samples: FloatArray): FloatArray {
        var maxVal = 0f
        for (s in samples) {
            val a = abs(s)
            if (a > maxVal) maxVal = a
        }
        if (maxVal <= 0f) return samples.copyOf()
        val out = FloatArray(samples.size)
        for (i in samples.indices) out[i] = samples[i] / maxVal
        return out
    }
}
