package com.fzi.speakerid.testutil

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Test-Referenz-Implementierung des Silero-VAD-Pfads aus siqas
 * `src/library/pipeline/steps/vad.py` (get_speech_timestamps / detect_silence)
 * bzw. `generate_golden.py::vad_window_probs`.
 *
 * Bewusst NUR Test-Scope: die produktive VAD-Klasse entsteht im
 * Pipeline-Modul; dieser Runner dient dem Paritaets-Nachweis von
 * WavReader/Resampler/Chunker gegen die Golden-Daten.
 *
 * Verhalten exakt wie Python:
 *  - Audio wird auf ein Vielfaches von 256 Samples mit Nullen gepaddet
 *  - LSTM-State startet PRO AUFRUF bei Null (2,1,128)
 *  - sr als int64-Skalar
 */
class SileroVadRunner(
    private val env: OrtEnvironment,
    private val session: OrtSession,
) {
    private val outputIdx: Int
    private val stateIdx: Int

    init {
        val names = session.outputNames.toList()
        stateIdx = names.indexOfFirst { it.contains("state") }.let { if (it >= 0) it else 1 }
        outputIdx = names.indices.firstOrNull { it != stateIdx } ?: 0
    }

    /** Rohe Speech-Wahrscheinlichkeit pro 256-Sample-Fenster (63 fuer 16000 Samples). */
    fun windowProbs(
        audio: FloatArray,
        windowSizeSamples: Int = 256,
        sampleRate: Int = 16000,
    ): DoubleArray {
        val remainder = audio.size % windowSizeSamples
        val padded =
            if (remainder != 0) audio.copyOf(audio.size + windowSizeSamples - remainder) else audio
        val nWindows = padded.size / windowSizeSamples
        val state = FloatArray(2 * 1 * 128)
        val probs = DoubleArray(nWindows)

        OnnxTensor.createTensor(
            env, LongBuffer.wrap(longArrayOf(sampleRate.toLong())), longArrayOf()
        ).use { srTensor ->
            for (w in 0 until nWindows) {
                val inputBuf =
                    FloatBuffer.wrap(padded, w * windowSizeSamples, windowSizeSamples)
                OnnxTensor.createTensor(env, inputBuf, longArrayOf(1, windowSizeSamples.toLong()))
                    .use { inputTensor ->
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128))
                            .use { stateTensor ->
                                session.run(
                                    mapOf(
                                        "input" to inputTensor,
                                        "state" to stateTensor,
                                        "sr" to srTensor,
                                    )
                                ).use { result ->
                                    val out = result.get(outputIdx) as OnnxTensor
                                    probs[w] = out.floatBuffer.get(0).toDouble()
                                    val newState = result.get(stateIdx) as OnnxTensor
                                    newState.floatBuffer.get(state)
                                }
                            }
                    }
            }
        }
        return probs
    }

    class SpeechSegment(val start: Int, val end: Int)

    /** Port von `vad.get_speech_timestamps` (Defaults wie Python). */
    fun speechTimestamps(
        audio: FloatArray,
        sampleRate: Int = 16000,
        threshold: Double = 0.1,
        windowSizeSamples: Int = 256,
        minSilenceDurationMs: Int = 100,
        minSpeechDurationMs: Int = 0,
        speechPadMs: Int = 30,
    ): List<SpeechSegment> {
        val nOrig = audio.size
        val probs = windowProbs(audio, windowSizeSamples, sampleRate)

        // Untere Hysterese-Schwelle wie Python
        val negThreshold = max(threshold - 0.15, threshold * 0.5)

        val raw = ArrayList<IntArray>()
        var isSpeaking = false
        var startSpeech = 0
        for (w in probs.indices) {
            val i = w * windowSizeSamples
            val p = probs[w]
            if (p >= threshold && !isSpeaking) {
                isSpeaking = true
                startSpeech = i
            } else if (p < negThreshold && isSpeaking) {
                isSpeaking = false
                raw.add(intArrayOf(startSpeech, i))
            }
        }
        if (isSpeaking) raw.add(intArrayOf(startSpeech, nOrig))
        if (raw.isEmpty()) return emptyList()

        val minSilenceSamples = sampleRate * minSilenceDurationMs / 1000
        val merged = ArrayList<IntArray>()
        merged.add(raw[0])
        for (j in 1 until raw.size) {
            val seg = raw[j]
            if (seg[0] - merged.last()[1] < minSilenceSamples) {
                merged.last()[1] = seg[1]
            } else {
                merged.add(seg)
            }
        }

        val pad = sampleRate * speechPadMs / 1000
        val minSpeechSamples = sampleRate * minSpeechDurationMs / 1000
        return merged
            .map { SpeechSegment(max(0, it[0] - pad), min(nOrig, it[1] + pad)) }
            .filter { it.end - it.start >= minSpeechSamples }
    }

    /** Port von `vad.get_speech_duration`. */
    fun speechDuration(audio: FloatArray, sampleRate: Int = 16000, threshold: Double = 0.1): Double {
        val ts = speechTimestamps(audio, sampleRate = sampleRate, threshold = threshold)
        if (ts.isEmpty()) return 0.0
        return ts.sumOf { (it.end - it.start).toLong() }.toDouble() / sampleRate
    }

    /** Port von `vad.detect_silence`: (istStille, Sprechdauer in s). */
    fun detectSilence(
        audio: FloatArray,
        minSpeechSeconds: Double = 0.15,
        sampleRate: Int = 16000,
        threshold: Double = 0.1,
    ): Pair<Boolean, Double> {
        val duration = speechDuration(audio, sampleRate, threshold)
        return Pair(duration < minSpeechSeconds, duration)
    }
}
