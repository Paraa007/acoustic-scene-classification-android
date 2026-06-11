package com.fzi.speakerid.library.pipeline.steps

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.fzi.speakerid.library.calculations.SlidingAudioBuffer
import com.fzi.speakerid.library.pipeline.OnnxSessionProvider
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Port von siqas `src/library/pipeline/steps/overlap.py`
 * (`SpeakerOverlapCleaner`, pyannote-segmentation-3.0 ONNX).
 *
 * Im Live-Default ist das Feature AUS (`use_pyannote=false`, meta.json) und
 * wurde in den Golden-Daten nur als Shadow geloggt — der Port ist trotzdem
 * vollstaendig und Feature-Flag-faehig.
 *
 * Verhalten exakt wie der Python-Code:
 *  - Powerset-Modell: 7 Klassen → Aktivitaet von max. [N_SPEAKERS] Sprechern
 *    ([SPEAKER_ACTIVITY], argmax pro Frame; 10-s-Fenster → 589 Frames).
 *  - [processStreaming]: rollender [SlidingAudioBuffer] (Fenstergroesse, mit
 *    Nullen initialisiert), Inferenz ueber den GANZEN Puffer, ausgewertet
 *    werden nur die letzten `round(frames * nNew / windowSamples)` Frames.
 *  - Solo-Segmente pro Sprecher: Insel-Filter ([minIslandDurationS], Frames
 *    via Python-`int()`-Truncation), Sample-Maske `i * nFrames // nSamples`,
 *    Mindestdauer [DEFAULT_MIN_DURATION_S]; "muting" liefert ein Chunk-langes
 *    Array mit Nullen ausserhalb der Solo-Samples, "concat" nur die Samples.
 *  - [precomputeFullAudio]/[processAt]: Offline-Pfad mit ueberlappenden
 *    Fenstern (Hop = Fenster/2) und gemittelten Wahrscheinlichkeiten.
 *  - Python-`round()` = banker's rounding → [pyRound] (Math.rint).
 *
 * Die Session kommt von aussen ([OnnxSessionProvider.segmentation]) — das
 * ersetzt das Lazy-Loading ueber den Modellpfad im Python-Original.
 * `open` + `protected` [extractPureSegments], damit Tests wie der
 * `RecordingOverlapCleaner` aus `generate_golden.py` Frame-Statistiken
 * mitprotokollieren koennen.
 */
open class SpeakerOverlapCleaner(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    val windowDurationS: Double = DEFAULT_WINDOW_S,
    val audioMergeStrategy: String = MERGE_MUTING,
) {

    constructor(
        provider: OnnxSessionProvider,
        windowDurationS: Double = DEFAULT_WINDOW_S,
        audioMergeStrategy: String = MERGE_MUTING,
    ) : this(provider.env, provider.segmentation(), windowDurationS, audioMergeStrategy)

    val windowSamples: Int = (SAMPLE_RATE * windowDurationS).toInt()
    val windowHopS: Double = windowDurationS / 2.0
    val windowHopSamples: Int = (SAMPLE_RATE * windowHopS).toInt()

    /** Nur im Offline-Pfad gesetzt ([precomputeFullAudio]); im Streaming null. */
    protected var framesPerSecond: Double? = null
        private set

    private var fullProbs: Array<FloatArray>? = null
    private var fullAudioRef: FloatArray? = null
    private val streamBuffer = SlidingAudioBuffer(windowSamples)

    private val inputName: String = session.inputNames.first()
    private val inputRank: Int =
        (session.inputInfo.getValue(inputName).info as TensorInfo).shape.size

    init {
        require(audioMergeStrategy == MERGE_MUTING || audioMergeStrategy == MERGE_CONCAT) {
            "audioMergeStrategy muss 'muting' oder 'concat' sein, nicht '$audioMergeStrategy'"
        }
    }

    /** Solo-Segment eines Sprechers — wie das Python-Dict {audio, duration, local_speaker_idx}. */
    class SoloSegment(
        val audio: FloatArray,
        val duration: Double,
        val localSpeakerIdx: Int,
    )

    /** Ergebnis-Tupel `(results, max_active)` aus dem Python-Code. */
    class CleanResult(
        val segments: List<SoloSegment>,
        val maxActiveSpeakers: Int,
    )

    /**
     * `_infer_window`: ein Fenster durch das Modell, exp(log_probs) → [nFrames][7].
     * Input [1, N] bei Rang 2, sonst [1, 1, N] (pyannote-ONNX: `input_values`, Rang 3).
     */
    protected fun inferWindow(audioWindow: FloatArray): Array<FloatArray> {
        val shape =
            if (inputRank == 2) longArrayOf(1, audioWindow.size.toLong())
            else longArrayOf(1, 1, audioWindow.size.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(audioWindow), shape).use { input ->
            session.run(mapOf(inputName to input)).use { result ->
                val out = result.get(0) as OnnxTensor
                val outShape = out.info.shape // [1, nFrames, 7]
                val nFrames = outShape[1].toInt()
                val nClasses = outShape[2].toInt()
                val buf = out.floatBuffer
                return Array(nFrames) { f ->
                    FloatArray(nClasses) { c -> exp(buf.get(f * nClasses + c)) }
                }
            }
        }
    }

    /** `precompute_full_audio`: gemittelte Frame-Probs ueber das ganze Audio (Hop = Fenster/2). */
    fun precomputeFullAudio(audio: FloatArray) {
        val nTotal = audio.size
        if (nTotal == 0) {
            fullProbs = emptyArray()
            framesPerSecond = 0.0
            fullAudioRef = audio
            return
        }

        val probeLen = min(windowSamples, nTotal)
        val probe =
            if (probeLen < windowSamples) audio.copyOf(windowSamples)
            else audio.copyOfRange(0, probeLen)
        val probeProbs = inferWindow(probe)
        val framesPerWindow = probeProbs.size
        val fps = framesPerWindow / windowDurationS
        val nFramesTotal = ceil(nTotal.toDouble() / SAMPLE_RATE * fps).toInt()
        val probSum = Array(nFramesTotal) { FloatArray(N_CLASSES) }
        val probCnt = FloatArray(nFramesTotal)

        val endFrame = min(framesPerWindow, nFramesTotal)
        for (f in 0 until endFrame) {
            for (c in 0 until N_CLASSES) probSum[f][c] += probeProbs[f][c]
            probCnt[f] += 1f
        }

        var start = windowHopSamples
        while (start < nTotal) {
            var window = audio.copyOfRange(start, min(start + windowSamples, nTotal))
            if (window.size < windowSamples) window = window.copyOf(windowSamples)
            val probs = inferWindow(window)
            val f0 = pyRound(start.toDouble() / SAMPLE_RATE * fps)
            val f1 = min(f0 + framesPerWindow, nFramesTotal)
            val valid = f1 - f0
            if (valid > 0) {
                for (f in 0 until valid) {
                    for (c in 0 until N_CLASSES) probSum[f0 + f][c] += probs[f][c]
                    probCnt[f0 + f] += 1f
                }
            }
            start += windowHopSamples
        }

        fullProbs = Array(nFramesTotal) { f ->
            FloatArray(N_CLASSES) { c -> probSum[f][c] / max(probCnt[f], 1e-9f) }
        }
        framesPerSecond = fps
        fullAudioRef = audio
    }

    /** `process_at`: Auswertung eines Zeitfensters auf den vorberechneten Probs. */
    fun processAt(
        tStart: Double,
        tEnd: Double,
        minDuration: Double = DEFAULT_MIN_DURATION_S,
        minIslandDurationS: Double = 0.25,
    ): CleanResult {
        val probs = fullProbs
        val audio = fullAudioRef
        check(probs != null && audio != null) {
            "processAt() benoetigt zuvor precomputeFullAudio(audio)."
        }
        val fps = framesPerSecond ?: 0.0
        val f0 = max(0, pyRound(tStart * fps))
        val f1 = min(probs.size, pyRound(tEnd * fps))
        val s0 = max(0, pyRound(tStart * SAMPLE_RATE))
        val s1 = min(audio.size, pyRound(tEnd * SAMPLE_RATE.toDouble()))
        if (f1 <= f0 || s1 <= s0) return CleanResult(emptyList(), 0)
        return extractPureSegments(
            audio.copyOfRange(s0, s1), probs.copyOfRange(f0, f1),
            minDuration, minIslandDurationS,
        )
    }

    /** `process`: Einzel-Auswertung (nur das erste Fenster, kurzes Audio wird gepaddet). */
    fun process(
        audio: FloatArray,
        minDuration: Double = DEFAULT_MIN_DURATION_S,
        minIslandDurationS: Double = DEFAULT_MIN_ISLAND_S,
    ): CleanResult {
        if (audio.isEmpty()) return CleanResult(emptyList(), 0)
        val nSamples = audio.size
        val probs: Array<FloatArray> =
            if (nSamples < windowSamples) {
                val full = inferWindow(audio.copyOf(windowSamples))
                val nUsed = max(1, pyRound(full.size.toDouble() * nSamples / windowSamples))
                full.copyOfRange(0, min(nUsed, full.size))
            } else {
                inferWindow(audio.copyOfRange(0, windowSamples))
            }
        return extractPureSegments(audio, probs, minDuration, minIslandDurationS)
    }

    /** `reset_stream`: Streaming-Puffer auf Nullen (wie `LiveProcessor.start()`). */
    fun resetStream() {
        streamBuffer.reset()
    }

    /**
     * `process_streaming`: neuen Chunk in den rollenden 10-s-Puffer schieben,
     * Inferenz ueber den ganzen Puffer, ausgewertet werden nur die Frames des
     * neuen Chunks (Tail der Frame-Achse).
     */
    fun processStreaming(
        newChunk: FloatArray,
        minDuration: Double = DEFAULT_MIN_DURATION_S,
        minIslandDurationS: Double = DEFAULT_MIN_ISLAND_S,
    ): CleanResult {
        val nNew = newChunk.size
        if (nNew == 0) return CleanResult(emptyList(), 0)

        streamBuffer.extend(newChunk)
        val probsFull = inferWindow(streamBuffer.getView())
        val nFramesNew = max(1, pyRound(probsFull.size.toDouble() * nNew / windowSamples))
        val from = max(0, probsFull.size - nFramesNew) // Python-Slice [-n:] toleriert n > len
        return extractPureSegments(
            newChunk, probsFull.copyOfRange(from, probsFull.size),
            minDuration, minIslandDurationS,
        )
    }

    /**
     * `_extract_pure_segments`: argmax → Powerset-Aktivitaet → Solo-Masken pro
     * Sprecher → Insel-Filter → Sample-Maske → muting/concat-Audio.
     */
    protected open fun extractPureSegments(
        audio: FloatArray,
        probs: Array<FloatArray>,
        minDuration: Double,
        minIslandDurationS: Double = DEFAULT_MIN_ISLAND_S,
    ): CleanResult {
        val nSamples = audio.size
        val nFrames = probs.size
        if (nSamples == 0 || nFrames == 0) return CleanResult(emptyList(), 0)

        val spkActive = Array(nFrames) { f -> SPEAKER_ACTIVITY[argmax(probs[f])] }
        val nActive = IntArray(nFrames) { f -> spkActive[f].sum() }
        var maxActive = 0
        for (a in nActive) if (a > maxActive) maxActive = a
        val pureMask = BooleanArray(nFrames) { nActive[it] == 1 }

        val results = ArrayList<SoloSegment>()
        for (speakerIdx in 0 until N_SPEAKERS) {
            var soloMask = BooleanArray(nFrames) { pureMask[it] && spkActive[it][speakerIdx] == 1 }

            if (minIslandDurationS > 0) {
                // Python: `self._frames_per_second or (n_frames / (n_samples / SR))`
                // — None UND 0.0 fallen auf den Fallback durch.
                val fps = framesPerSecond?.takeIf { it != 0.0 }
                    ?: (nFrames.toDouble() / (nSamples.toDouble() / SAMPLE_RATE))
                if (fps > 0) {
                    soloMask = filterShortSpeechIslands(
                        soloMask, (fps * minIslandDurationS).toInt(), // int() = Truncation
                    )
                }
            }

            if (soloMask.none { it }) continue

            // sample_to_frame = arange(nSamples) * nFrames // nSamples (Floor-Division)
            val sampleMask = BooleanArray(nSamples) { i ->
                soloMask[(i.toLong() * nFrames / nSamples).toInt()]
            }
            val soloSampleCount = sampleMask.count { it }
            val durationSec = soloSampleCount.toDouble() / SAMPLE_RATE

            if (durationSec >= minDuration) {
                val pureAudio: FloatArray =
                    if (audioMergeStrategy == MERGE_MUTING) {
                        FloatArray(nSamples).also { out ->
                            for (i in 0 until nSamples) if (sampleMask[i]) out[i] = audio[i]
                        }
                    } else { // concat
                        val out = FloatArray(soloSampleCount)
                        var k = 0
                        for (i in 0 until nSamples) if (sampleMask[i]) out[k++] = audio[i]
                        out
                    }
                results.add(SoloSegment(pureAudio, durationSec, speakerIdx))
            }
        }

        return CleanResult(results, maxActive)
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val N_SPEAKERS = 3
        const val N_CLASSES = 7
        const val DEFAULT_WINDOW_S = 10.0

        const val MERGE_MUTING = "muting"
        const val MERGE_CONCAT = "concat"

        /** `cleaner_min_duration` (meta.json: 0.3 s). */
        const val DEFAULT_MIN_DURATION_S = 0.3

        /** `cleaner_min_island_duration` (meta.json: 0.08 s). */
        const val DEFAULT_MIN_ISLAND_S = 0.08

        /** `_PYANNOTE_SPK_ACTIVITY`: Powerset-Klasse → Aktivitaet der 3 Sprecher. */
        val SPEAKER_ACTIVITY: Array<IntArray> = arrayOf(
            intArrayOf(0, 0, 0),
            intArrayOf(1, 0, 0),
            intArrayOf(0, 1, 0),
            intArrayOf(0, 0, 1),
            intArrayOf(1, 1, 0),
            intArrayOf(1, 0, 1),
            intArrayOf(0, 1, 1),
        )

        /** Python-`round()`: banker's rounding (half-even). */
        internal fun pyRound(x: Double): Int = Math.rint(x).toInt()

        /** np.argmax: erster Index des Maximums. */
        internal fun argmax(row: FloatArray): Int {
            var best = 0
            var bestV = row[0]
            for (c in 1 until row.size) {
                if (row[c] > bestV) {
                    bestV = row[c]
                    best = c
                }
            }
            return best
        }

        /**
         * `_filter_short_speech_islands`: entfernt zusammenhaengende True-Inseln,
         * die kuerzer als [minLenFrames] sind (<=1 → Maske unveraendert).
         */
        internal fun filterShortSpeechIslands(mask: BooleanArray, minLenFrames: Int): BooleanArray {
            if (minLenFrames <= 1 || mask.isEmpty()) return mask
            val cleaned = mask.copyOf()
            val starts = ArrayList<Int>()
            val ends = ArrayList<Int>()
            if (mask[0]) starts.add(0)
            for (i in 1 until mask.size) {
                if (mask[i] && !mask[i - 1]) starts.add(i)
                if (!mask[i] && mask[i - 1]) ends.add(i)
            }
            if (mask[mask.size - 1]) ends.add(mask.size)
            val n = min(starts.size, ends.size) // zip-Semantik
            for (k in 0 until n) {
                if (ends[k] - starts[k] < minLenFrames) {
                    for (i in starts[k] until ends[k]) cleaned[i] = false
                }
            }
            return cleaned
        }
    }
}
