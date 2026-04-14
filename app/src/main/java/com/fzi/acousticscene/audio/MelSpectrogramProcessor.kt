package com.fzi.acousticscene.audio

import android.util.Log
import be.tarsos.dsp.util.fft.FFT
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Sparse Mel-Filter: Speichert nur Nicht-Null-Bereiche
 * 
 * OPTIMIERUNG: Statt alle 2049 Bins zu speichern (90% sind 0),
 * speichern wir nur den aktiven Bereich [startBin, endBin] mit Gewichten.
 */
data class SparseMelFilter(
    val startBin: Int,      // Erster Nicht-Null Bin
    val endBin: Int,        // Letzter Nicht-Null Bin (inklusiv)
    val weights: FloatArray // Nur die Nicht-Null Gewichte
)

/**
 * MelSpectrogram Processor für Android
 * 
 * Berechnet Log-Mel-Spectrogramme aus Audio-Samples.
 * Kompatibel mit dem PyTorch-Modell (DCASE 2025).
 * 
 * Input: FloatArray mit 320000 Samples (10 Sekunden @ 32kHz)
 * Output: Array<FloatArray> mit Shape [nMels, nFrames] (z.B. [256, 641] bei 10s @ 32kHz mit center=True)
 */
class MelSpectrogramProcessor(
    private val sampleRate: Int = 32000,
    private val nFft: Int = 4096,
    private val winLength: Int = 3072,
    private val hopLength: Int = 500,
    private val nMels: Int = 256,
    private val fMin: Float = 0f,
    private val fMax: Float = 16000f
) {
    companion object {
        private const val TAG = "MelSpectrogram"
        private const val LOG_OFFSET = 1e-5f
    }

    // FFT Instanz - TarsosDSP
    private val fft = FFT(nFft)
    
    // SPARSE Mel-Filterbank (OPTIMIERUNG: nur Nicht-Null-Bereiche)
    // Statt Array<FloatArray> mit 2049 Bins pro Mel (90% sind 0),
    // speichern wir nur die aktiven Bereiche [startBin, endBin]
    private val sparseFilters: Array<SparseMelFilter> by lazy { 
        createSparseFilterbank() 
    }
    
    // Hann-Window (wird einmal berechnet und gecached)
    private val hannWindow: FloatArray by lazy { 
        createHannWindow(winLength) 
    }
    
    // PRE-ALLOCATED BUFFERS - wiederverwendbar für Performance!
    // Reduziert GC-Druck erheblich
    private val fftBuffer = FloatArray(nFft)
    private val windowedFrame = FloatArray(nFft)
    private val powerSpectrumBuffer = FloatArray(nFft / 2 + 1)

    /**
     * Hauptfunktion: Berechnet Log-Mel-Spectrogram aus Audio-Samples
     * 
     * @param audioSamples Float Array mit Audio-Samples (normalisiert auf [-1, 1])
     * @return Log-Mel-Spectrogram als Array<FloatArray> mit Shape [nMels, nFrames]
     */
    fun computeLogMelSpectrogram(audioSamples: FloatArray): Array<FloatArray> {
        val totalStartTime = System.currentTimeMillis()
        Log.d(TAG, "PERF: Input samples: ${audioSamples.size}")
        
        // 1. STFT berechnen
        val t0 = System.currentTimeMillis()
        val powerSpectrum = computeSTFT(audioSamples)
        val t1 = System.currentTimeMillis()
        Log.d(TAG, "PERF: STFT computation: ${t1 - t0}ms (${powerSpectrum.size} frames, ${powerSpectrum[0].size} bins)")
        
        // 2. Mel-Filterbank anwenden
        val t2 = System.currentTimeMillis()
        val melSpectrogram = applyMelFilterbank(powerSpectrum)
        val t3 = System.currentTimeMillis()
        Log.d(TAG, "PERF: Mel filterbank application: ${t3 - t2}ms (${melSpectrogram.size} mels, ${melSpectrogram[0].size} frames)")
        
        // 3. Log-Transformation
        val t4 = System.currentTimeMillis()
        val logMelSpectrogram = applyLogTransform(melSpectrogram)
        val t5 = System.currentTimeMillis()
        val totalTime = t5 - totalStartTime
        Log.d(TAG, "PERF: Log transformation: ${t5 - t4}ms")
        Log.d(TAG, "PERF: Total Mel-Spectrogram computation: ${totalTime}ms")
        
        return logMelSpectrogram
    }

    /**
     * Konvertiert Log-Mel-Spectrogram zu Tensor-Format für PyTorch
     * 
     * @param logMel Log-Mel-Spectrogram [nMels, nFrames]
     * @return FloatArray für Tensor [1, 1, nMels, nFrames] (flattened)
     */
    fun toTensorFormat(logMel: Array<FloatArray>): FloatArray {
        val nMels = logMel.size
        val nFrames = logMel[0].size
        
        // Flatten: [mel0_frame0, mel0_frame1, ..., mel1_frame0, mel1_frame1, ...]
        val tensorData = FloatArray(nMels * nFrames)
        
        for (mel in 0 until nMels) {
            for (frame in 0 until nFrames) {
                tensorData[mel * nFrames + frame] = logMel[mel][frame]
            }
        }
        
        return tensorData
    }

    /**
     * Berechnet STFT und gibt Power Spectrum zurück
     *
     * OPTIMIERT: Verwendet Pre-allocated Buffers um GC-Druck zu reduzieren
     * Center-Padding: Signal wird mit winLength/2 reflect-gepaddet (wie torch.stft center=True, pad_mode='reflect')
     */
    private fun computeSTFT(audioSamples: FloatArray): Array<FloatArray> {
        // Center-Padding: reflect (wie torch.stft mit center=True, pad_mode='reflect')
        val pad = winLength / 2
        val paddedSamples = FloatArray(audioSamples.size + 2 * pad)
        // Originalsignal in die Mitte kopieren
        audioSamples.copyInto(paddedSamples, destinationOffset = pad)
        // Reflect-Padding links: signal[pad-1], signal[pad-2], ..., signal[0]
        for (i in 0 until pad) {
            paddedSamples[pad - 1 - i] = audioSamples[minOf(i + 1, audioSamples.size - 1)]
        }
        // Reflect-Padding rechts: signal[n-2], signal[n-3], ...
        val n = audioSamples.size
        for (i in 0 until pad) {
            paddedSamples[pad + n + i] = audioSamples[maxOf(n - 2 - i, 0)]
        }

        // Berechne Anzahl der Frames
        val nSamples = paddedSamples.size
        val nFrames = 1 + (nSamples - winLength) / hopLength

        // Anzahl der Frequenz-Bins (nur positive Frequenzen + DC + Nyquist)
        val nFreqBins = nFft / 2 + 1

        // Power Spectrum Array (muss neu erstellt werden, da Größe variabel)
        val powerSpectrum = Array(nFrames) { FloatArray(nFreqBins) }

        // Für jeden Frame - WICHTIG: Buffers wiederverwenden!
        for (frameIdx in 0 until nFrames) {
            val startSample = frameIdx * hopLength
            
            // Frame extrahieren und mit Window multiplizieren (in-place in windowedFrame)
            // Zuerst: Zero-padding (alles auf 0 setzen)
            windowedFrame.fill(0f)
            
            // Dann: Window anwenden
            for (i in 0 until winLength) {
                val sampleIdx = startSample + i
                if (sampleIdx < nSamples) {
                    windowedFrame[i] = paddedSamples[sampleIdx] * hannWindow[i]
                }
            }
            // Rest bleibt 0 (Zero-Padding bereits durch fill(0f))
            
            // FFT berechnen (verwendet fftBuffer - in-place)
            computeFFTInPlace(windowedFrame, fftBuffer)
            
            // Power Spectrum extrahieren (verwendet powerSpectrumBuffer)
            extractPowerSpectrumInPlace(fftBuffer, powerSpectrumBuffer)
            
            // Kopiere Ergebnis in Power Spectrum Array
            powerSpectrumBuffer.copyInto(powerSpectrum[frameIdx])
        }
        
        return powerSpectrum
    }

    /**
     * Berechnet FFT mit TarsosDSP (optimiert mit Pre-allocated Buffer)
     * 
     * TarsosDSP FFT Format nach forwardTransform():
     * - Array Größe: nFft (nicht nFft/2+1!)
     * - Format: [DC, Nyquist, Re1, Im1, Re2, Im2, ..., Re(N/2-1), Im(N/2-1)]
     * - Index 0: DC (Real, kein Imaginärteil)
     * - Index 1: Nyquist (Real, kein Imaginärteil)  
     * - Index 2,3: Bin 1 (Real, Imag)
     * - Index 4,5: Bin 2 (Real, Imag)
     * - ...
     * 
     * @param input Input-Frame (wird nicht modifiziert)
     * @param output Pre-allocated Buffer für FFT-Ergebnis (wird überschrieben)
     */
    private fun computeFFTInPlace(input: FloatArray, output: FloatArray) {
        // Kopiere Input in Pre-allocated Buffer (einzige Array-Allocation!)
        input.copyInto(output, endIndex = minOf(input.size, output.size))
        
        // Forward FFT (in-place auf output)
        fft.forwardTransform(output)
    }
    
    /**
     * Legacy-Methode für Kompatibilität (nicht mehr verwendet, aber behalten)
     */
    private fun computeFFT(input: FloatArray): FloatArray {
        val buffer = input.copyOf()
        fft.forwardTransform(buffer)
        return buffer
    }

    /**
     * Extrahiert Power Spectrum aus TarsosDSP FFT Ergebnis (optimiert mit Pre-allocated Buffer)
     * 
     * TarsosDSP Format: [DC, Nyquist, Re1, Im1, Re2, Im2, ...]
     * 
     * @param fftResult FFT Ergebnis (nicht modifiziert)
     * @param output Pre-allocated Buffer für Power Spectrum (wird überschrieben)
     */
    private fun extractPowerSpectrumInPlace(fftResult: FloatArray, output: FloatArray) {
        // DC Component (Bin 0): Index 0, nur Real
        output[0] = fftResult[0] * fftResult[0]
        
        // Nyquist Component (Bin nFft/2): Index 1, nur Real
        output[nFft / 2] = fftResult[1] * fftResult[1]
        
        // Alle anderen Bins (1 bis nFft/2-1)
        // Format: [DC, Nyquist, Re1, Im1, Re2, Im2, ...]
        // Bin k -> Real bei Index 2*k, Imag bei Index 2*k+1
        for (bin in 1 until nFft / 2) {
            val realIdx = 2 * bin
            val imagIdx = 2 * bin + 1
            
            val real = fftResult[realIdx]
            val imag = fftResult[imagIdx]
            
            // Power = Real² + Imag²
            output[bin] = real * real + imag * imag
        }
    }
    
    /**
     * Legacy-Methode für Kompatibilität (nicht mehr verwendet, aber behalten)
     */
    private fun extractPowerSpectrum(fftResult: FloatArray): FloatArray {
        val nFreqBins = nFft / 2 + 1
        val powerSpectrum = FloatArray(nFreqBins)
        extractPowerSpectrumInPlace(fftResult, powerSpectrum)
        return powerSpectrum
    }

    /**
     * Wendet SPARSE Mel-Filterbank auf Power Spectrum an
     * 
     * OPTIMIERUNG: Iteriert nur über Nicht-Null Bins (ca. 50 pro Filter statt 2049)
     * Erwartete Beschleunigung: 20-40x
     */
    private fun applyMelFilterbank(powerSpectrum: Array<FloatArray>): Array<FloatArray> {
        val nFrames = powerSpectrum.size
        val nFreqBins = powerSpectrum[0].size
        
        // Output: [nMels, nFrames]
        val melSpectrogram = Array(nMels) { FloatArray(nFrames) }
        
        for (frame in 0 until nFrames) {
            val spectrum = powerSpectrum[frame]
            
            for (mel in 0 until nMels) {
                val filter = sparseFilters[mel]
                var sum = 0f
                
                // NUR über Nicht-Null Bins iterieren!
                // Das ist der Kern der Optimierung!
                // Vorher: 2049 Bins × 256 Mels = 524,544 Ops/Frame
                // Jetzt: ~50 Bins × 256 Mels = 12,800 Ops/Frame (20-40x schneller!)
                for (i in filter.weights.indices) {
                    val bin = filter.startBin + i
                    if (bin >= 0 && bin < nFreqBins) {
                        sum += spectrum[bin] * filter.weights[i]
                    }
                }
                
                melSpectrogram[mel][frame] = sum
            }
        }
        
        return melSpectrogram
    }

    /**
     * Wendet Log-Transformation an: log(mel + offset)
     */
    private fun applyLogTransform(melSpectrogram: Array<FloatArray>): Array<FloatArray> {
        return Array(melSpectrogram.size) { mel ->
            FloatArray(melSpectrogram[mel].size) { frame ->
                ln(melSpectrogram[mel][frame] + LOG_OFFSET)
            }
        }
    }

    /**
     * Erstellt periodisches Hann Window (wie torch.hann_window(periodic=True))
     * Periodic: teilt durch N (nicht N-1 wie symmetric)
     */
    private fun createHannWindow(size: Int): FloatArray {
        return FloatArray(size) { i ->
            (0.5f * (1 - cos(2.0 * PI * i / size))).toFloat()
        }
    }

    /**
     * Erstellt SPARSE Mel-Filterbank — kompatibel mit torchaudio.functional.create_fb_matrix
     *
     * WICHTIG: torchaudio berechnet Filter im KONTINUIERLICHEN Frequenzraum,
     * nicht mit diskreten Bin-Indizes (wie librosa). Algorithmus:
     * 1. all_freqs = linspace(0, sr/2, n_fft/2+1) — exakte Frequenz pro Bin
     * 2. Mel-Punkte gleichmäßig in Mel-Skala verteilt
     * 3. Dreieck-Filter: up_slope und down_slope basierend auf Frequenz-Differenzen
     * 4. filter[mel][bin] = max(0, min(down_slope, up_slope))
     */
    private fun createSparseFilterbank(): Array<SparseMelFilter> {
        val t0 = System.currentTimeMillis()
        Log.d(TAG, "PERF: Creating SPARSE Mel filterbank (torchaudio-compatible): $nMels mels, fMin=$fMin, fMax=$fMax")

        val nFreqBins = nFft / 2 + 1

        // 1. Frequenz-Vektor: exakte Frequenz für jeden FFT-Bin (wie torchaudio)
        val allFreqs = FloatArray(nFreqBins) { i ->
            i.toFloat() * sampleRate / nFft
        }

        // 2. Mel-Punkte gleichmäßig verteilt (nMels + 2 Punkte für Start/Ende)
        val melMin = hzToMel(fMin)
        val melMax = hzToMel(fMax)
        val melPoints = FloatArray(nMels + 2) { i ->
            melMin + i * (melMax - melMin) / (nMels + 1)
        }

        // 3. Mel-Punkte zurück zu Hz
        val fPts = FloatArray(nMels + 2) { i -> melToHz(melPoints[i]) }

        // 4. Frequenz-Differenzen zwischen aufeinanderfolgenden Mel-Punkten
        val fDiff = FloatArray(nMels + 1) { i -> fPts[i + 1] - fPts[i] }

        // 5. SPARSE Filterbank erstellen
        val filters = mutableListOf<SparseMelFilter>()
        var totalNonZeroBins = 0

        for (mel in 0 until nMels) {
            // torchaudio Algorithmus:
            // down_slopes = (f_pts[mel+2] - all_freqs) / f_diff[mel+1]   (absteigende Flanke)
            // up_slopes   = (all_freqs - f_pts[mel])   / f_diff[mel]     (aufsteigende Flanke)
            // filter = max(0, min(down_slope, up_slope))

            val fLow = fPts[mel]
            val fCenter = fPts[mel + 1]
            val fHigh = fPts[mel + 2]
            val dLow = fDiff[mel]    // fCenter - fLow
            val dHigh = fDiff[mel + 1] // fHigh - fCenter

            // Finde aktiven Bereich (wo Filter > 0)
            var startBin = nFreqBins
            var endBin = 0

            for (bin in 0 until nFreqBins) {
                val freq = allFreqs[bin]
                val upSlope = if (dLow > 0f) (freq - fLow) / dLow else 0f
                val downSlope = if (dHigh > 0f) (fHigh - freq) / dHigh else 0f
                val weight = maxOf(0f, minOf(downSlope, upSlope))
                if (weight > 0f) {
                    if (bin < startBin) startBin = bin
                    endBin = bin
                }
            }

            // Erstelle Sparse-Filter
            if (startBin <= endBin) {
                val filterWidth = endBin - startBin + 1
                val weights = FloatArray(filterWidth)

                for (i in 0 until filterWidth) {
                    val bin = startBin + i
                    val freq = allFreqs[bin]
                    val upSlope = if (dLow > 0f) (freq - fLow) / dLow else 0f
                    val downSlope = if (dHigh > 0f) (fHigh - freq) / dHigh else 0f
                    weights[i] = maxOf(0f, minOf(downSlope, upSlope))
                }

                totalNonZeroBins += weights.count { it > 1e-10f }
                filters.add(SparseMelFilter(startBin = startBin, endBin = endBin, weights = weights))
            } else {
                // Leerer Filter (sollte nicht vorkommen)
                filters.add(SparseMelFilter(startBin = 0, endBin = 0, weights = floatArrayOf(0f)))
            }
        }

        val t1 = System.currentTimeMillis()
        val avgNonZeroBins = totalNonZeroBins.toFloat() / nMels

        Log.d(TAG, "PERF: SPARSE Mel filterbank created in ${t1 - t0}ms")
        Log.d(TAG, "PERF: Memory savings: ${nMels * nFreqBins * 4 / 1024}KB → ${totalNonZeroBins * 4 / 1024}KB")
        Log.d(TAG, "PERF: Avg non-zero bins per filter: ${String.format("%.1f", avgNonZeroBins)} (instead of $nFreqBins)")
        Log.d(TAG, "PERF: Expected speedup: ${String.format("%.1f", nFreqBins / avgNonZeroBins)}x")

        return filters.toTypedArray()
    }

    /**
     * Hz zu Mel Konvertierung
     */
    private fun hzToMel(hz: Float): Float {
        return 2595f * kotlin.math.log10(1f + hz / 700f)
    }

    /**
     * Mel zu Hz Konvertierung
     */
    private fun melToHz(mel: Float): Float {
        return 700f * (10f.pow(mel / 2595f) - 1f)
    }
}
