package com.fzi.speakerid.ui

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Kopiert die ONNX-Modelle aus den App-Assets
 * (`assets/speakerid/models/`: silero_vad.onnx, redimnet_b2.onnx +
 * redimnet_b2.onnx.data, pyannote_segmentation.onnx) beim ersten Start nach
 * `context.filesDir/speakerid/models` — ONNX Runtime braucht echte
 * Datei-Pfade (`OnnxSessionProvider(modelsDir: File)`).
 *
 * Idempotent: ein Versions-Marker plus Groessen-Check pro Datei; existierende
 * Dateien gleicher Groesse werden nicht neu kopiert. Bei einer neuen
 * [MODELS_VERSION] wird alles erneut geprueft/kopiert.
 *
 * Blockierende Datei-IO — auf einem Worker-Thread/Dispatchers.IO aufrufen.
 */
object AssetModelInstaller {

    private const val TAG = "AssetModelInstaller"

    /** Asset-Quellordner im APK. */
    const val ASSET_MODELS_DIR = "speakerid/models"

    /** Bei Modell-Updates hochzaehlen, dann wird neu installiert. */
    const val MODELS_VERSION = 1

    /** Zielverzeichnis (`filesDir/speakerid/models`) — ohne zu installieren. */
    fun modelsDir(context: Context): File =
        File(File(context.filesDir, "speakerid"), "models")

    /**
     * Stellt sicher, dass alle Modell-Assets installiert sind, und liefert
     * das Modellverzeichnis fuer den `OnnxSessionProvider`.
     */
    @Synchronized
    fun install(context: Context): File {
        val targetDir = modelsDir(context)
        targetDir.mkdirs()

        val assetNames = context.assets.list(ASSET_MODELS_DIR)?.toList().orEmpty()
        check(assetNames.isNotEmpty()) {
            "Keine Modell-Assets unter $ASSET_MODELS_DIR gefunden"
        }

        val versionFile = File(targetDir, ".version")
        val versionUpToDate =
            versionFile.isFile && versionFile.readText().trim() == MODELS_VERSION.toString()

        var copied = 0
        for (name in assetNames) {
            val outFile = File(targetDir, name)
            val assetPath = "$ASSET_MODELS_DIR/$name"
            val assetSize = assetSize(context, assetPath)

            val upToDate = versionUpToDate && outFile.isFile &&
                (assetSize < 0 || outFile.length() == assetSize)
            if (upToDate) continue

            // Atomar: erst in .tmp schreiben, dann umbenennen.
            val tmpFile = File(targetDir, "$name.tmp")
            context.assets.open(assetPath).use { input ->
                tmpFile.outputStream().use { output -> input.copyTo(output) }
            }
            if (outFile.exists()) outFile.delete()
            check(tmpFile.renameTo(outFile)) {
                "Modell-Asset konnte nicht installiert werden: $name"
            }
            copied++
            Log.i(TAG, "Installiert: $name (${outFile.length()} Bytes)")
        }

        versionFile.writeText(MODELS_VERSION.toString())
        if (copied > 0) {
            Log.i(TAG, "$copied Modell-Asset(s) nach $targetDir installiert.")
        }
        return targetDir
    }

    /** Asset-Groesse in Bytes; -1 falls nicht ermittelbar (komprimiert). */
    private fun assetSize(context: Context, assetPath: String): Long =
        try {
            context.assets.openFd(assetPath).use { it.length }
        } catch (_: Exception) {
            // Komprimierte Assets haben keinen FileDescriptor — dann zaehlen.
            try {
                context.assets.open(assetPath).use { input ->
                    var total = 0L
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                    }
                    total
                }
            } catch (_: Exception) {
                -1L
            }
        }
}
