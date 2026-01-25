package com.fzi.acousticscene.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/**
 * AudioRecorder für kontinuierliche Audio-Aufnahme
 * 
 * OPTIMIERT: Läuft komplett auf IO Thread um Main Thread nicht zu blockieren!
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