package com.fzi.acousticscene.ml

import android.content.Context
import android.util.Log
import com.fzi.acousticscene.audio.MelSpectrogramProcessor
import com.fzi.acousticscene.ml.ComputationDispatcher
import com.fzi.acousticscene.model.ClassificationResult
import com.fzi.acousticscene.model.RecordingMode
import com.fzi.acousticscene.model.SceneClass
import kotlinx.coroutines.withContext
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

/**
 * ModelInference Class for PyTorch Mobile Model
 *
 * Loads the PyTorch model and performs inference.
 *
 * The model expects Log-Mel-Spectrogram input with shape [1, 1, 256, 641]
 * MelSpectrogram is computed on Android (not in the model).
 *
 * @param context Android Context
 * @param modelAssetPath Full path to model in assets (e.g., "user_model/model1.pt")
 */
class ModelInference(
    private val context: Context,
    private var modelAssetPath: String = "user_model/model1.pt"
) {
    companion object {
        private const val TAG = "ModelInference"
        private const val SAMPLE_RATE_HZ = 32_000
        private const val STANDARD_AUDIO_SECONDS = 10
        private const val INPUT_AUDIO_SIZE = SAMPLE_RATE_HZ * STANDARD_AUDIO_SECONDS
        private const val N_MELS = 256
        private const val EXPECTED_TIME_FRAMES = 641 // For 10 seconds audio
    }

    // Extract model name from path
    val modelName: String
        get() = modelAssetPath.substringAfterLast("/")

    private var module: Module? = null
    private var isLoaded = false
    private var currentModelPath: String? = null
    // Ein Processor für alle Modi — FFT-Parameter sind immer gleich,
    // nur die Audio-Länge (und damit die Spektrogramm-Breite) ändert sich
    private val melProcessor = MelSpectrogramProcessor()
    
    /**
     * Sets a new model path and reloads the model
     * @param newPath Full path to model in assets (e.g., "dev_models/model2.pt")
     */
    fun setModelPath(newPath: String) {
        if (newPath != modelAssetPath) {
            // Free the native PyTorch module of the previous path before swapping —
            // otherwise the old C++ allocation stays around until GC happens.
            release()
            modelAssetPath = newPath
        }
    }

    /**
     * Loads the PyTorch model asynchronously
     * @return true if successfully loaded, false otherwise
     */
    suspend fun loadModel(): Boolean {
        return try {
            // Check if we need to reload (different path or not loaded)
            if (isLoaded && module != null && currentModelPath == modelAssetPath) {
                Log.d(TAG, "Model already loaded: $modelAssetPath")
                return true
            }

            Log.d(TAG, "Loading model from assets: $modelAssetPath")

            // Create cache file name from full path (replace / with _)
            val cacheFileName = modelAssetPath.replace("/", "_")
            val modelFile = File(context.cacheDir, cacheFileName)

            // Always copy if path changed or file doesn't exist
            if (!modelFile.exists() || currentModelPath != modelAssetPath) {
                context.assets.open(modelAssetPath).use { input ->
                    FileOutputStream(modelFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "Model copied to cache directory: ${modelFile.absolutePath}")
            }

            // Load model with Module.load()
            module = Module.load(modelFile.absolutePath)
            isLoaded = true
            currentModelPath = modelAssetPath

            Log.d(TAG, "Model loaded successfully: $modelName")
            true
        } catch (e: FileNotFoundException) {
            Log.e(TAG, "Model file not found: $modelAssetPath", e)
            isLoaded = false
            module = null
            currentModelPath = null
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error loading model: ${e.javaClass.simpleName}", e)
            Log.e(TAG, "Error message: ${e.message}", e)
            e.printStackTrace()
            isLoaded = false
            module = null
            currentModelPath = null
            false
        }
    }
    
    /**
     * Führt Inferenz auf Audio-Daten durch
     * 
     * Verarbeitungsschritte:
     * 1. Audio → MelSpectrogram (256 mels, ~641 time frames)
     * 2. Log-Transformation: log(mel + 1e-5)
     * 3. Tensor erstellen: [1, 1, 256, 641]
     * 4. Model Inference
     * 5. Softmax auf Output
     * 
     * WICHTIG: Läuft auf dediziertem Computation-Thread (verhindert ANR)
     * 
     * @param audioData FloatArray mit normalisierten Audio-Samples ([-1.0, 1.0])
     * @param mode RecordingMode für Parameter-Auswahl (STANDARD/FAST)
     * @return ClassificationResult mit Ergebnis oder null bei Fehler
     */
    suspend fun infer(audioData: FloatArray, mode: RecordingMode = RecordingMode.STANDARD): ClassificationResult? {
        val logMel = computeLogMel(audioData, mode) ?: return null
        return inferFromLogMel(logMel)
    }

    /**
     * Computes the log-mel spectrogram for [audioData] (the expensive step,
     * ~150 ms). Split out from [infer] so callers running several models on the
     * SAME audio can compute it once and feed each model via [inferFromLogMel].
     */
    suspend fun computeLogMel(
        audioData: FloatArray,
        mode: RecordingMode = RecordingMode.STANDARD
    ): Array<FloatArray>? {
        if (!isLoaded || module == null) {
            Log.e(TAG, "Model not loaded")
            return null
        }
        val expectedSize = mode.durationSeconds * SAMPLE_RATE_HZ
        val processedAudio = if (audioData.size != expectedSize) {
            Log.w(TAG, "Audio size mismatch: expected $expectedSize, got ${audioData.size}. Adjusting...")
            if (audioData.size < expectedSize) {
                audioData + FloatArray(expectedSize - audioData.size)
            } else {
                audioData.take(expectedSize).toFloatArray()
            }
        } else {
            audioData
        }
        return withContext(ComputationDispatcher.dispatcher) {
            try {
                melProcessor.computeLogMelSpectrogram(processedAudio)
            } catch (e: Exception) {
                Log.e(TAG, "Error computing mel-spectrogram: ${e.message}", e)
                null
            }
        }
    }

    /**
     * Runs the model on an already-computed [logMel] and returns the result.
     * [inferenceTimeMs] here is the model-only time (mel is computed upstream).
     */
    suspend fun inferFromLogMel(logMel: Array<FloatArray>): ClassificationResult? {
        val mod = module ?: run {
            Log.e(TAG, "Model not loaded")
            return null
        }
        return withContext(ComputationDispatcher.dispatcher) {
            try {
                val start = System.currentTimeMillis()
                val nMels = logMel.size
                val nTimeFrames = logMel[0].size
                val flattenedMel = melProcessor.toTensorFormat(logMel)
                val inputTensor = Tensor.fromBlob(
                    flattenedMel,
                    longArrayOf(1, 1, nMels.toLong(), nTimeFrames.toLong())
                )
                val output = mod.forward(IValue.from(inputTensor))
                val outputData = output.toTensor().dataAsFloatArray
                val probabilities = softmax(outputData)
                val maxIndex = probabilities.indices.maxByOrNull { probabilities[it] } ?: 0
                val totalTime = System.currentTimeMillis() - start
                val sceneClass = SceneClass.fromIndex(maxIndex) ?: SceneClass.TRANSIT_VEHICLES
                Log.d(TAG, "Inference: ${sceneClass.label} (${(probabilities[maxIndex] * 100).toInt()}%) in ${totalTime}ms")
                ClassificationResult(
                    sceneClass = sceneClass,
                    confidence = probabilities[maxIndex],
                    allProbabilities = probabilities,
                    inferenceTimeMs = totalTime
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error during inference: ${e.message}", e)
                null
            }
        }
    }
    
    /**
     * Wende Softmax auf Logits an
     */
    private fun softmax(logits: FloatArray): FloatArray {
        val max = logits.maxOrNull() ?: 0f
        val expValues = logits.map { kotlin.math.exp(it - max) }
        val sum = expValues.sum()
        return expValues.map { (it / sum).toFloat() }.toFloatArray()
    }
    
    /**
     * Prüft, ob das Model geladen ist
     */
    fun isModelLoaded(): Boolean = isLoaded
    
    /**
     * Returns the model information
     */
    fun getModelInfo(): String {
        return if (isLoaded) {
            "Model loaded: $modelName"
        } else {
            "Model not loaded"
        }
    }

    /**
     * Returns the current model path
     */
    fun getModelPath(): String = modelAssetPath

    /**
     * Gibt die erwartete Audio-Input-Größe zurück (in Samples)
     */
    fun getInputSize(): Int = INPUT_AUDIO_SIZE

    /**
     * Gibt die native PyTorch-Module-Referenz frei. Nach release() ist die Instanz
     * unbrauchbar — entweder wegwerfen oder loadModel() erneut rufen.
     */
    fun release() {
        try {
            module?.destroy()
        } catch (e: Throwable) {
            Log.w(TAG, "Module.destroy() failed: ${e.message}")
        }
        module = null
        isLoaded = false
        currentModelPath = null
    }
}