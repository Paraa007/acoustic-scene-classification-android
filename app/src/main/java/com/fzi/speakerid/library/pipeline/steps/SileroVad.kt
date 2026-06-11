package com.fzi.speakerid.library.pipeline.steps

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.fzi.speakerid.library.pipeline.OnnxSessionProvider
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Port von siqas `src/library/pipeline/steps/vad.py` (Silero-VAD ONNX).
 *
 * Verhalten exakt wie der Python-Code:
 *  - Audio wird auf ein Vielfaches von [WINDOW_SIZE_SAMPLES] (256) Samples mit
 *    Nullen gepaddet (`np.pad`-"constant"); ein 1-s-Chunk @16 kHz ergibt 63 Fenster.
 *  - Der LSTM-State (2,1,128) startet PRO AUFRUF bei Null und wird nur innerhalb
 *    des Aufrufs durch die Fenster gefaedelt (kein Chunk-uebergreifender State —
 *    so loggt auch `generate_golden.py::vad_window_probs`).
 *  - `sr` geht als int64-Skalar in die Session.
 *  - Segment-Statemachine mit Hysterese (`neg_threshold`), Silence-Merge,
 *    Speech-Padding und Mindest-Sprachdauer wie `get_speech_timestamps`.
 *
 * Die Session kommt von aussen ([OnnxSessionProvider.vad]) — das ersetzt das
 * `_get_session()`-Singleton aus dem Python-Modul.
 */
class SileroVad(
    private val env: OrtEnvironment,
    private val session: OrtSession,
) {

    constructor(provider: OnnxSessionProvider) : this(provider.env, provider.vad())

    private val stateOutputIdx: Int
    private val probOutputIdx: Int

    init {
        // Output-Reihenfolge wie in Python `out, state = session.run(None, ...)`:
        // Wahrscheinlichkeit zuerst, State danach — hier defensiv ueber die Namen
        // aufgeloest (Silero benennt den State-Output "stateN").
        val names = session.outputNames.toList()
        stateOutputIdx = names.indexOfFirst { it.contains("state") }.let { if (it >= 0) it else 1 }
        probOutputIdx = names.indices.firstOrNull { it != stateOutputIdx } ?: 0
    }

    /** Sprachsegment in Samples [startSample, endSample) — wie die Python-Dicts {"start","end"}. */
    data class SpeechSegment(val startSample: Int, val endSample: Int) {
        val lengthSamples: Int get() = endSample - startSample
    }

    /** Ergebnis von [detectSilence]: (ist_stille, sprechdauer_in_sekunden). */
    data class SilenceResult(val isSilence: Boolean, val speechDurationS: Double)

    /**
     * Rohe Speech-Wahrscheinlichkeit pro 256-Sample-Fenster (Fenster-Schleife aus
     * `get_speech_timestamps` bzw. `generate_golden.py::vad_window_probs`).
     */
    fun windowProbs(
        audio: FloatArray,
        samplingRate: Int = DEFAULT_SAMPLE_RATE,
        windowSizeSamples: Int = WINDOW_SIZE_SAMPLES,
    ): DoubleArray {
        val remainder = audio.size % windowSizeSamples
        val padded =
            if (remainder != 0) audio.copyOf(audio.size + windowSizeSamples - remainder) else audio
        val nWindows = padded.size / windowSizeSamples
        val state = FloatArray(2 * 1 * STATE_DIM)
        val probs = DoubleArray(nWindows)

        OnnxTensor.createTensor(
            env, LongBuffer.wrap(longArrayOf(samplingRate.toLong())), longArrayOf()
        ).use { srTensor ->
            for (w in 0 until nWindows) {
                val inputBuf = FloatBuffer.wrap(padded, w * windowSizeSamples, windowSizeSamples)
                OnnxTensor.createTensor(env, inputBuf, longArrayOf(1, windowSizeSamples.toLong()))
                    .use { inputTensor ->
                        OnnxTensor.createTensor(
                            env, FloatBuffer.wrap(state), longArrayOf(2, 1, STATE_DIM.toLong())
                        ).use { stateTensor ->
                            session.run(
                                mapOf(
                                    "input" to inputTensor,
                                    "state" to stateTensor,
                                    "sr" to srTensor,
                                )
                            ).use { result ->
                                probs[w] = (result.get(probOutputIdx) as OnnxTensor)
                                    .floatBuffer.get(0).toDouble()
                                (result.get(stateOutputIdx) as OnnxTensor)
                                    .floatBuffer.get(state)
                            }
                        }
                    }
            }
        }
        return probs
    }

    /** Port von `vad.get_speech_timestamps` (Defaults exakt wie Python). */
    fun getSpeechTimestamps(
        audio: FloatArray,
        samplingRate: Int = DEFAULT_SAMPLE_RATE,
        threshold: Double = DEFAULT_THRESHOLD,
        windowSizeSamples: Int = WINDOW_SIZE_SAMPLES,
        minSilenceDurationMs: Int = 100,
        minSpeechDurationMs: Int = 0,
        speechPadMs: Int = 30,
    ): List<SpeechSegment> {
        val nOrig = audio.size
        val probs = windowProbs(audio, samplingRate, windowSizeSamples)

        // Untere Hysterese-Schwelle: Sprache endet erst, wenn die Wahrscheinlichkeit
        // spuerbar unter die Start-Schwelle faellt (verhindert Flackern). Muss > 0
        // bleiben, sonst schliesst sich ein Segment nie.
        val negThreshold = max(threshold - 0.15, threshold * 0.5)

        val rawSegments = ArrayList<IntArray>()
        var isSpeaking = false
        var startSpeech = 0
        for (w in probs.indices) {
            val i = w * windowSizeSamples
            val speechProb = probs[w]
            if (speechProb >= threshold && !isSpeaking) {
                isSpeaking = true
                startSpeech = i
            } else if (speechProb < negThreshold && isSpeaking) {
                isSpeaking = false
                rawSegments.add(intArrayOf(startSpeech, i))
            }
        }
        if (isSpeaking) rawSegments.add(intArrayOf(startSpeech, nOrig))
        if (rawSegments.isEmpty()) return emptyList()

        // Python: int(sampling_rate * ms / 1000) — true division, dann Truncation.
        val minSilenceSamples = (samplingRate * minSilenceDurationMs / 1000.0).toInt()
        val merged = ArrayList<IntArray>()
        merged.add(rawSegments[0])
        for (j in 1 until rawSegments.size) {
            val seg = rawSegments[j]
            if (seg[0] - merged.last()[1] < minSilenceSamples) {
                merged.last()[1] = seg[1]
            } else {
                merged.add(seg)
            }
        }

        val pad = (samplingRate * speechPadMs / 1000.0).toInt()
        val minSpeechSamples = (samplingRate * minSpeechDurationMs / 1000.0).toInt()
        return merged
            .map { SpeechSegment(max(0, it[0] - pad), min(nOrig, it[1] + pad)) }
            .filter { it.lengthSamples >= minSpeechSamples }
    }

    /** Port von `vad.get_speech_duration`: Summe der Segmentlaengen in Sekunden. */
    fun getSpeechDuration(
        audio: FloatArray,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        threshold: Double = DEFAULT_THRESHOLD,
    ): Double {
        val timestamps = getSpeechTimestamps(audio, samplingRate = sampleRate, threshold = threshold)
        if (timestamps.isEmpty()) return 0.0
        return timestamps.sumOf { it.lengthSamples.toLong() }.toDouble() / sampleRate
    }

    /**
     * Port von `vad.detect_silence`: Chunk → (ist_stille, sprechdauer_in_sekunden).
     *
     * Ein Chunk gilt als Stille, wenn weniger als [minSpeechSeconds] Sekunden
     * Sprache erkannt wurden (absolute Dauer, unabhaengig von der Chunk-Laenge).
     * Bei `useVad = false` wird wie in Python (false, [chunkDuration]) geliefert.
     */
    fun detectSilence(
        audio: FloatArray,
        useVad: Boolean = true,
        minSpeechSeconds: Double = DEFAULT_MIN_SPEECH_SECONDS,
        chunkDuration: Double = audio.size.toDouble() / DEFAULT_SAMPLE_RATE,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        threshold: Double = DEFAULT_THRESHOLD,
    ): SilenceResult {
        if (!useVad) return SilenceResult(isSilence = false, speechDurationS = chunkDuration)
        val speechDuration = getSpeechDuration(audio, sampleRate = sampleRate, threshold = threshold)
        return SilenceResult(isSilence = speechDuration < minSpeechSeconds, speechDurationS = speechDuration)
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE = 16000
        const val WINDOW_SIZE_SAMPLES = 256
        const val STATE_DIM = 128

        /** `process_chunk`-Default `vad_threshold` (meta.json: vad_threshold=0.1). */
        const val DEFAULT_THRESHOLD = 0.1

        /** `process_chunk`-Default `min_speech_seconds` (meta.json: 0.15). */
        const val DEFAULT_MIN_SPEECH_SECONDS = 0.15
    }
}
