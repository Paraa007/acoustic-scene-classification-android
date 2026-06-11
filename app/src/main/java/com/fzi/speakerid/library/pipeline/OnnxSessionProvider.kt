package com.fzi.speakerid.library.pipeline

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.FileNotFoundException

/**
 * Port von siqas `src/library/pipeline/onnx_session.py` (+ den `_get_session`-
 * Singletons in `vad.py` / `embedding.py`): laedt die drei ONNX-Modelle lazy
 * aus einem konfigurierbaren Verzeichnis und cached die Sessions.
 *
 * Pfade:
 *  - Android: App-internes Verzeichnis, in das die Assets aus
 *    `assets/speakerid/models/` kopiert wurden (Session braucht echte Dateien,
 *    weil `redimnet_b2.onnx` externe Gewichte in `redimnet_b2.onnx.data`
 *    nebenan erwartet).
 *  - Host-Unit-Tests: Repo-Pfad `app/src/main/assets/speakerid/models`.
 *
 * Sessions laufen wie in Python auf dem CPU-Execution-Provider (Default).
 */
class OnnxSessionProvider(private val modelsDir: File) : AutoCloseable {

    val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    private var vadSession: OrtSession? = null
    private var embeddingSession: OrtSession? = null
    private var segmentationSession: OrtSession? = null

    /** Silero-VAD (Input `input` [1,256] float32, `state` [2,1,128], `sr` int64-Skalar). */
    @Synchronized
    fun vad(): OrtSession =
        vadSession ?: createSession(VAD_MODEL).also { vadSession = it }

    /** ReDimNet-B2 Embedding (Input [1,1,16000] float32, Output 192-dim L2-normiert). */
    @Synchronized
    fun embedding(): OrtSession =
        embeddingSession ?: createSession(EMBEDDING_MODEL).also { embeddingSession = it }

    /** Pyannote-Segmentation (Overlap-Cleaner, 10-s-Fenster). */
    @Synchronized
    fun segmentation(): OrtSession =
        segmentationSession ?: createSession(SEGMENTATION_MODEL).also { segmentationSession = it }

    /** Pfad einer Modell-Datei im konfigurierten Verzeichnis (mit Existenz-Check). */
    fun modelFile(name: String): File {
        val f = File(modelsDir, name)
        if (!f.isFile) {
            throw FileNotFoundException(
                "ONNX-Modell nicht gefunden: ${f.absolutePath} (modelsDir=$modelsDir)"
            )
        }
        return f
    }

    private fun createSession(name: String): OrtSession =
        env.createSession(modelFile(name).absolutePath, OrtSession.SessionOptions())

    /** Schliesst alle erzeugten Sessions (das globale [OrtEnvironment] bleibt offen). */
    @Synchronized
    override fun close() {
        vadSession?.close(); vadSession = null
        embeddingSession?.close(); embeddingSession = null
        segmentationSession?.close(); segmentationSession = null
    }

    companion object {
        const val VAD_MODEL = "silero_vad.onnx"
        const val EMBEDDING_MODEL = "redimnet_b2.onnx"
        const val EMBEDDING_MODEL_DATA = "redimnet_b2.onnx.data"
        const val SEGMENTATION_MODEL = "pyannote_segmentation.onnx"

        /** Asset-Unterordner in der App (`app/src/main/assets/`). */
        const val ASSET_SUBDIR = "speakerid/models"
    }
}
