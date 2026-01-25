package com.fzi.acousticscene.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/**
 * AudioRecorder für kontinuierliche Audio-Aufnahme
 *
 * OPTIMIERT: Läuft komplett auf IO Thread um Main Thread nicht zu blockieren!
 *
 * Features:
 * - Echtzeit-Lautstärke-Messung (RMS) via volumeFlow
 * - Progress-Updates während Aufnahme
 *
 * Aufnahme-Parameter:
 * - Sample Rate: 32000 Hz
 * - Channels: Mono
 * - Format: PCM 16-bit
 *
 * @param sampleRate Sample Rate in Hz (Standard: 32000)
 * @param durationSeconds Aufnahme-Dauer in Sekunden (Standard: 10)
 */
class AudioRecorder(
    private val sampleRate: Int = 32000,
    private val durationSeconds: Int = 10
) {
    companion object {
        private const val TAG = "AudioRecorder"
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // Empirischer Teiler für Lautstärke-Normalisierung (16-bit PCM max: 32767)
        private const val VOLUME_NORMALIZATION_DIVISOR = 5000.0

        // Smoothing-Faktor für Lautstärke (0.0 = nur neuer Wert, 1.0 = nur alter Wert)
        private const val VOLUME_SMOOTHING_FACTOR = 0.7f

        /**
         * Berechnet die Buffer-Größe für AudioRecord
         */
        private fun calculateBufferSize(sampleRate: Int): Int {
            return AudioRecord.getMinBufferSize(sampleRate, CHANNEL_CONFIG, AUDIO_FORMAT) * 2
        }
    }

    private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false
    private val totalSamples = sampleRate * durationSeconds

    // Echtzeit-Lautstärke Flow (0.0 = Stille, 1.0 = Sehr laut)
    private val _volumeFlow = MutableStateFlow(0f)
    val volumeFlow: StateFlow<Float> = _volumeFlow.asStateFlow()

    /**
     * Berechnet den RMS (Root Mean Square) Wert eines Audio-Buffers.
     * RMS ist ein guter Indikator für die "gefühlte" Lautstärke.
     *
     * @param buffer Audio-Samples als ShortArray (PCM 16-bit)
     * @param samplesRead Anzahl der gültigen Samples im Buffer
     * @return Normalisierter Lautstärke-Wert zwischen 0.0 und 1.0
     */
    private fun calculateRmsVolume(buffer: ShortArray, samplesRead: Int): Float {
        if (samplesRead <= 0) return 0f

        var sum = 0.0
        for (i in 0 until samplesRead) {
            val sample = buffer[i].toDouble()
            sum += sample * sample
        }
        val rms = sqrt(sum / samplesRead)

        // Normalisiere auf 0.0 - 1.0 und wende Smoothing an
        val targetVolume = (rms / VOLUME_NORMALIZATION_DIVISOR).toFloat().coerceIn(0f, 1f)

        // Low-Pass Filter für weichere Animation (verhindert "Zappeln")
        val smoothedVolume = _volumeFlow.value * VOLUME_SMOOTHING_FACTOR +
                targetVolume * (1f - VOLUME_SMOOTHING_FACTOR)

        return smoothedVolume
    }
    
    /**
     * Startet die Audio-Aufnahme und gibt einen Flow zurück
     * 
     * WICHTIG: Läuft komplett auf IO Thread (.flowOn(Dispatchers.IO))
     * um Main Thread nicht zu blockieren!
     * 
     * @return Flow von RecordingState Updates
     */
    fun startRecording(): Flow<RecordingState> = flow {
        if (isRecording) {
            Log.w(TAG, "Recording already in progress")
            return@flow
        }
        
        Log.d(TAG, "Starting recording for ${durationSeconds}s on IO thread")
        
        // Emit: Recording gestartet
        emit(RecordingState.Started)
        
        val bufferSize = calculateBufferSize(sampleRate)
        
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
            
            val recorder = audioRecord ?: throw IllegalStateException("AudioRecord is null")
            
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("AudioRecord initialization failed")
            }
            
            isRecording = true
            recorder.startRecording()
            Log.d(TAG, "Recording started, collecting $totalSamples samples")
            
            val audioBuffer = ShortArray(totalSamples)
            var samplesRead = 0
            
            // Progress Updates alle 0.5 Sekunden (nicht zu oft um UI nicht zu überlasten)
            val progressInterval = sampleRate / 2  // 0.5 Sekunden in Samples
            var lastProgressUpdate = 0
            
            // Lese Audio-Daten in Chunks
            val readBuffer = ShortArray(minOf(bufferSize, totalSamples))
            
            while (samplesRead < totalSamples && coroutineContext.isActive && isRecording) {
                val remaining = totalSamples - samplesRead
                val toRead = minOf(readBuffer.size, remaining)

                val read = recorder.read(readBuffer, 0, toRead)

                if (read > 0) {
                    // Kopiere gelesene Samples in Buffer
                    readBuffer.copyInto(audioBuffer, samplesRead, 0, read)
                    samplesRead += read

                    // Berechne Echtzeit-Lautstärke (für Visualisierung)
                    val volume = calculateRmsVolume(readBuffer, read)
                    _volumeFlow.value = volume

                    // Progress Update (nicht zu oft!)
                    if (samplesRead - lastProgressUpdate >= progressInterval) {
                        val progress = samplesRead.toFloat() / totalSamples
                        emit(RecordingState.Progress(progress))
                        lastProgressUpdate = samplesRead
                    }
                } else if (read < 0) {
                    Log.e(TAG, "AudioRecord read error: $read")
                    emit(RecordingState.Error("Audio read error: $read"))
                    break
                }
            }
            
            if (!isRecording) {
                Log.d(TAG, "Recording stopped manually")
                return@flow
            }
            
            recorder.stop()
            Log.d(TAG, "Recording stopped, got $samplesRead samples")
            
            // Konvertiere ShortArray zu FloatArray (normalisiert [-1.0, 1.0])
            val floatSamples = FloatArray(samplesRead) { i ->
                audioBuffer[i] / 32768.0f
            }
            
            emit(RecordingState.Completed(floatSamples))
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during recording", e)
            emit(RecordingState.Error(e.message ?: "Unknown error"))
        } finally {
            stopRecording()
        }
    }.flowOn(Dispatchers.IO)  // ← KRITISCH: Alles auf IO Thread!
    
    /**
     * Stoppt die Audio-Aufnahme
     */
    fun stopRecording() {
        isRecording = false
        // Setze Lautstärke auf 0 wenn gestoppt
        _volumeFlow.value = 0f
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
        }
        audioRecord = null
        Log.d(TAG, "Recording stopped")
    }
    
    /**
     * Prüft, ob aktuell aufgezeichnet wird
     */
    fun isCurrentlyRecording(): Boolean = isRecording
    
    /**
     * Gibt die Sample Rate zurück
     */
    fun getSampleRate(): Int = sampleRate
    
    /**
     * Gibt die Aufnahme-Dauer in Sekunden zurück
     */
    fun getDurationSeconds(): Int = durationSeconds
}