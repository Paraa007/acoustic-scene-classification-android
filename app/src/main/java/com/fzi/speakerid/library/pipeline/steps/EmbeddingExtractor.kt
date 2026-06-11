package com.fzi.speakerid.library.pipeline.steps

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.fzi.speakerid.library.pipeline.OnnxSessionProvider
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * Port von siqas `src/library/pipeline/steps/embedding.py`
 * (`extract_embedding` / `extract_single_embedding`): ReDimNet-B2-ONNX-
 * Inferenz mit anschliessender L2-Normierung.
 *
 * Verhalten exakt wie Python:
 *  - 1-D-Audio wird auf [1, 1, N] expandiert und als float32 unter dem
 *    Input-Namen "input" gefuettert. N ist dabei die tatsaechliche
 *    Sample-Anzahl — der Extractor paddet/trimmt NICHT selbst: kuerzere
 *    letzte Chunks werden bereits upstream (VirtualFileRecorder bzw.
 *    [Chunker] mit `padLast=true`) auf volle 16000 Samples zero-gepaddet.
 *  - Der Output (Shape [1, 192]) wird entlang der letzten Achse L2-normiert
 *    und gesqueezt; Python konvertiert via `.tolist()` nach float64 — hier
 *    entsprechend [DoubleArray].
 *
 * Das Session-Singleton (`_get_session`) uebernimmt der [OnnxSessionProvider].
 */
class EmbeddingExtractor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
) {

    constructor(provider: OnnxSessionProvider) : this(provider.env, provider.embedding())

    /** Port von `extract_embedding`: L2-normiertes Speaker-Embedding via ReDimNet. */
    fun extractEmbedding(audio: FloatArray): DoubleArray {
        OnnxTensor.createTensor(
            env, FloatBuffer.wrap(audio), longArrayOf(1, 1, audio.size.toLong())
        ).use { inputTensor ->
            session.run(mapOf(INPUT_NAME to inputTensor)).use { result ->
                val out = result.get(0) as OnnxTensor
                val shape = out.info.shape
                val dim = shape.last().toInt()
                require(shape.fold(1L) { a, b -> a * b } == dim.toLong()) {
                    "Erwarte Batch 1, ReDimNet-Output-Shape war ${shape.contentToString()}"
                }
                val raw = FloatArray(dim)
                out.floatBuffer.get(raw)
                return l2Normalize(raw)
            }
        }
    }

    /**
     * Port von `extract_single_embedding` — Standard-Pfad ohne Overlap:
     * der gesamte Chunk wird als EIN Speech-Embedding extrahiert.
     */
    fun extractSingleEmbedding(
        processedAudio: FloatArray,
        speechDuration: Double,
        startTime: Double,
    ): List<Embedding> = listOf(
        Embedding(
            embedding = extractEmbedding(processedAudio),
            duration = speechDuration,
            timestamp = startTime,
            status = Embedding.STATUS_SPEECH,
        )
    )

    /** L2-Normierung wie `out / np.linalg.norm(out, ord=2, axis=-1, keepdims=True)`. */
    private fun l2Normalize(vector: FloatArray): DoubleArray {
        var sumSq = 0.0
        for (x in vector) sumSq += x.toDouble() * x.toDouble()
        val norm = sqrt(sumSq)
        return DoubleArray(vector.size) { vector[it] / norm }
    }

    companion object {
        const val INPUT_NAME = "input"
        const val EMBEDDING_DIM = 192
    }
}
