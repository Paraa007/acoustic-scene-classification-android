package com.fzi.acousticscene.ml

import android.content.Context
import android.util.Log
import com.fzi.acousticscene.audio.MelSpectrogramProcessor
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
 * ModelInference Klasse für PyTorch Mobile Model
 * 
 * Lädt das PyTorch Model und führt Inferenz durch.
 * 
 * Das Modell erwartet Log-Mel-Spectrogram Input mit Shape [1, 1, 256, 641]
 * MelSpectrogram wird auf Android berechnet (nicht im Modell).
 * 
 * @param context Android Context
 * @param modelAssetName Name der Model-Datei in assets (Standard: "model1.pt")
 */
class ModelInference(
    private val context: Context,
    private val modelAssetName: String = "user_model/model1.pt"
) {
    companion object {
        private const val TAG = "ModelInference"
        private const val INPUT_AUDIO_SIZE = 320000 // 10 Sekunden * 32000 Hz
        private const val N_MELS = 256
        private const val EXPECTED_TIME_FRAMES = 641 // Für 10 Sekunden Audio
    }
    
    private var module: Module? = null
    private var isLoaded = false
    // Separate Processor pro Mode für optimale Performance
    private val standardProcessor = MelSpectrogramProcessor(
        nFft = RecordingMode.STANDARD.nFft,
        winLength = RecordingMode.STANDARD.winLength,
        hopLength = RecordingMode.STANDARD.hopLength,
        nMels = RecordingMode.STANDARD.nMels
    )
    private val fastProcessor = MelSpectrogramProcessor(
        nFft = RecordingMode.FAST.nFft,
        winLength = RecordingMode.FAST.winLength,
        hopLength = RecordingMode.FAST.hopLength,
        nMels = RecordingMode.FAST.nMels
    )
    private val mediumProcessor = MelSpectrogramProcessor(
        nFft = RecordingMode.MEDIUM.nFft,
        winLength = RecordingMode.MEDIUM.winLength,
        hopLength = RecordingMode.MEDIUM.hopLength,
        nMels = RecordingMode.MEDIUM.nMels
    )
    private val longProcessor = MelSpectrogramProcessor(
        nFft = RecordingMode.LONG.nFft,
        winLength = RecordingMode.LONG.winLength,
        hopLength = RecordingMode.LONG.hopLength,
        nMels = RecordingMode.LONG.nMels
    )
    
    /**
     * Lädt das PyTorch Model asynchron
     * @return true wenn erfolgreich geladen, false sonst
     */
    suspend fun loadModel(): Boolean {
        return try {
            if (isLoaded && module != null) {
                Log.d(TAG, "Model already loaded")
                return true
            }
            
            Log.d(TAG, "Loading model from assets: $modelAssetName")
            
            // Kopiere Model aus assets zu einem temporären File
            val modelFile = File(context.cacheDir, modelAssetName)
            if (!modelFile.exists()) {
                context.assets.open(modelAssetName).use { input ->
                    FileOutputStream(modelFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "Model copied to cache directory")
            }
            
            // Lade Model mit Module.load() (funktioniert mit Standard TorchScript .pt Dateien)
            module = Module.load(modelFile.absolutePath)
            isLoaded = true
            
            Log.d(TAG, "Model loaded successfully")
            true
        } catch (e: FileNotFoundException) {
            Log.e(TAG, "Model file not found: $modelAssetName", e)
            isLoaded = false
            module = null
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error loading model: ${e.javaClass.simpleName}", e)
            Log.e(TAG, "Error message: ${e.message}", e)
            e.printStackTrace()
            isLoaded = false
            module = null
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
        if (!isLoaded || module == null) {
            Log.e(TAG, "Model not loaded")
            return null
        }
        
        // Wähle Processor basierend auf Mode
        val processor = when (mode) {
            RecordingMode.FAST -> fastProcessor
            RecordingMode.MEDIUM -> mediumProcessor
            RecordingMode.STANDARD -> standardProcessor
            RecordingMode.LONG -> longProcessor
        }
        val expectedSize = mode.durationSeconds * 32000 // 32kHz sample rate
        
        // Normalisiere Audio-Länge
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
        
        // WICHTIG: MelSpectrogram-Berechnung auf dediziertem Computation-Thread (verhindert ANR)
        return withContext(ComputationDispatcher.dispatcher) {
            try {
                val totalStartTime = System.currentTimeMillis()
                
                // 1. Berechne Log-Mel-Spectrogram (der langsame Teil!)
                val t0 = System.currentTimeMillis()
                Log.d(TAG, "PERF: Starting Mel-Spectrogram computation...")
                val logMelSpectrogram = processor.computeLogMelSpectrogram(processedAudio)
                val t1 = System.currentTimeMillis()
                val melTime = t1 - t0
                Log.d(TAG, "PERF: Mel-Spectrogram computation: ${melTime}ms")
                
                val nMels = logMelSpectrogram.size
                val nTimeFrames = logMelSpectrogram[0].size
                
                Log.d(TAG, "Mel-Spectrogram shape: [$nMels, $nTimeFrames]")
                
                // 2. Flatten zu 1D Array: [mel0_frame0, mel0_frame1, ..., mel1_frame0, ...]
                val t2 = System.currentTimeMillis()
                val flattenedMel = processor.toTensorFormat(logMelSpectrogram)
                val t3 = System.currentTimeMillis()
                val tensorFormatTime = t3 - t2
                Log.d(TAG, "PERF: Tensor format conversion: ${tensorFormatTime}ms")
                
                // 3. Erstelle Input Tensor: Shape [1, 1, nMels, nTimeFrames]
                val t4 = System.currentTimeMillis()
                val inputTensor = Tensor.fromBlob(
                    flattenedMel,
                    longArrayOf(1, 1, nMels.toLong(), nTimeFrames.toLong())
                )
                val t5 = System.currentTimeMillis()
                val tensorCreationTime = t5 - t4
                Log.d(TAG, "PERF: Tensor creation: ${tensorCreationTime}ms")
                Log.d(TAG, "Input tensor created with shape [1, 1, $nMels, $nTimeFrames]")
                
                // 4. Führe Inferenz durch
                val t6 = System.currentTimeMillis()
                val output = module!!.forward(IValue.from(inputTensor))
                val t7 = System.currentTimeMillis()
                val modelInferenceTime = t7 - t6
                Log.d(TAG, "PERF: Model inference: ${modelInferenceTime}ms")
                
                // 5. Extrahiere Output Tensor
                val outputTensor = output.toTensor()
                val outputData = outputTensor.dataAsFloatArray
                
                Log.d(TAG, "Output tensor shape: ${outputTensor.shape().contentToString()}, size: ${outputData.size}")
                
                // 6. Wende Softmax an (Output sind Logits)
                val t8 = System.currentTimeMillis()
                val probabilities = softmax(outputData)
                val t9 = System.currentTimeMillis()
                val softmaxTime = t9 - t8
                Log.d(TAG, "PERF: Softmax: ${softmaxTime}ms")
                
                // 7. Finde argmax
                val maxIndex = probabilities.indices.maxByOrNull { probabilities[it] } ?: 0
                val maxProbability = probabilities[maxIndex]
                
                val totalTime = System.currentTimeMillis() - totalStartTime
                
                val sceneClass = SceneClass.fromIndex(maxIndex) ?: SceneClass.TRANSIT_VEHICLES
                
                Log.d(TAG, "PERF: ========== TOTAL INFERENCE TIME: ${totalTime}ms ==========")
                Log.d(TAG, "PERF:   - Mel-Spectrogram: ${melTime}ms (${(melTime * 100 / totalTime)}%)")
                Log.d(TAG, "PERF:   - Tensor Format: ${tensorFormatTime}ms (${(tensorFormatTime * 100 / totalTime)}%)")
                Log.d(TAG, "PERF:   - Tensor Creation: ${tensorCreationTime}ms (${(tensorCreationTime * 100 / totalTime)}%)")
                Log.d(TAG, "PERF:   - Model Inference: ${modelInferenceTime}ms (${(modelInferenceTime * 100 / totalTime)}%)")
                Log.d(TAG, "PERF:   - Softmax: ${softmaxTime}ms (${(softmaxTime * 100 / totalTime)}%)")
                Log.d(TAG, "Inference completed: ${sceneClass.label} (${(maxProbability * 100).toInt()}%) in ${totalTime}ms")
                
                ClassificationResult(
                    sceneClass = sceneClass,
                    confidence = maxProbability,
                    allProbabilities = probabilities,
                    inferenceTimeMs = totalTime
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error during inference: ${e.message}", e)
                e.printStackTrace()
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
     * Gibt die Modell-Informationen zurück
     */
    fun getModelInfo(): String {
        return if (isLoaded) {
            "Model loaded: $modelAssetName"
        } else {
            "Model not loaded"
        }
    }
    
    /**
     * Gibt die erwartete Audio-Input-Größe zurück (in Samples)
     */
    fun getInputSize(): Int = INPUT_AUDIO_SIZE
}