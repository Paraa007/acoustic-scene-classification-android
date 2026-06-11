package com.fzi.speakerid.audio

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlin.math.max

/**
 * Android-Pendant zum pyjnius-Pfad von siqas
 * `gui/services/audio_recorder.py::LiveRecorder._recording_loop_android`:
 * liest PCM-16 mono von [AudioRecord] (Default 16 kHz) und liefert
 * [chunkDurationS]-Sekunden-Chunks als FloatArray ueber [callback] —
 * Skalierung `int16 / 32767` wie der Phase-1-`WavReader`.
 *
 * Puffer-Mechanik identisch zu Python:
 *  - Hardware-Reads von mindestens 0,5 s (`max(min_buf/2, sample_rate/2)`),
 *  - sobald der Sammelpuffer >= `chunk_samples` enthaelt, wird ein Chunk
 *    emittiert und der Puffer um `step_samples` (= chunk - overlap)
 *    vorgerueckt; bei Overlap 0 sind das lueckenlose 1,0-s-Chunks.
 *
 * [callback] laeuft auf dem internen Worker-Thread ("SpeakerAudioRecorder").
 * Der Aufrufer muss die Laufzeit-Permission `RECORD_AUDIO` halten.
 */
class SpeakerAudioRecorder(
    val sampleRate: Int = 16000,
    val chunkDurationS: Double = 1.0,
    val chunkOverlapS: Double = 0.0,
    private val callback: (chunkIdx: Int, samples: FloatArray) -> Unit,
) {

    /** `chunk_samples = int(sample_rate * chunk_duration)` */
    val chunkSamples: Int = (sampleRate * chunkDurationS).toInt()

    /** `step_samples = int(sample_rate * (chunk_duration - overlap))` */
    val stepSamples: Int = (sampleRate * (chunkDurationS - chunkOverlapS)).toInt()

    @Volatile
    var isRecording: Boolean = false
        private set

    private var recordingThread: Thread? = null

    init {
        require(chunkSamples > 0 && stepSamples > 0) {
            "chunkDurationS/chunkOverlapS ergeben keine gueltige Chunk-Groesse"
        }
    }

    /** Port von `LiveRecorder.start`: startet den Worker-Thread (idempotent). */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (isRecording) return
        isRecording = true
        recordingThread = Thread(::recordingLoop, "SpeakerAudioRecorder").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "Mikrofon-Aufnahme gestartet.")
    }

    /**
     * Port von `LiveRecorder.stop`: beendet den Loop, wartet bis zu 1 s auf
     * den Thread und gibt den [AudioRecord] frei (im Loop-`finally`).
     */
    fun stop() {
        isRecording = false
        recordingThread?.let { thread ->
            try {
                thread.join(1000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        recordingThread = null
        Log.i(TAG, "Mikrofon-Aufnahme gestoppt.")
    }

    @SuppressLint("MissingPermission")
    private fun recordingLoop() {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            Log.e(TAG, "Ungueltige AudioRecord-Konfiguration (minBuf=$minBuf)")
            isRecording = false
            return
        }
        // Python: read_samples = max(min_buf // 2, sample_rate // 2)  (>= 0,5 s)
        //         buf_size     = max(min_buf, read_samples * 2)       (Bytes)
        val readSamples = max(minBuf / 2, sampleRate / 2)
        val bufSizeBytes = max(minBuf, readSamples * 2)

        val recorder: AudioRecord
        try {
            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSizeBytes,
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord konnte nicht erstellt werden: $e")
            isRecording = false
            return
        }

        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord nicht initialisiert.")
                isRecording = false
                return
            }
            recorder.startRecording()
            Log.i(TAG, "Android AudioRecord gestartet.")

            val readBuf = ShortArray(readSamples)
            var buffer = FloatArray(0)
            var bufferLen = 0
            var chunkIdx = 0

            while (isRecording) {
                val nRead = recorder.read(readBuf, 0, readBuf.size)
                if (nRead <= 0) {
                    Thread.sleep(10)
                    continue
                }

                // Anhaengen mit Skalierung /32767 (WavReader-Parity).
                if (bufferLen + nRead > buffer.size) {
                    buffer = buffer.copyOf(max(bufferLen + nRead, buffer.size * 2))
                }
                for (i in 0 until nRead) {
                    buffer[bufferLen + i] = readBuf[i] / INT16_MAX
                }
                bufferLen += nRead

                // `while len(buffer) >= chunk_samples: emit + step`
                while (bufferLen >= chunkSamples) {
                    val chunk = buffer.copyOfRange(0, chunkSamples)
                    val remaining = bufferLen - stepSamples
                    System.arraycopy(buffer, stepSamples, buffer, 0, remaining)
                    bufferLen = remaining
                    callback(chunkIdx, chunk)
                    chunkIdx++
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Schwerer Fehler im Recording-Loop: $e")
            isRecording = false
        } finally {
            try {
                recorder.stop()
            } catch (_: IllegalStateException) {
                // war nie im Recording-Zustand
            }
            recorder.release()
            Log.i(TAG, "Android AudioRecord gestoppt.")
        }
    }

    companion object {
        private const val TAG = "SpeakerAudioRecorder"

        /** Skalierung wie Phase-1-`WavReader` (np.iinfo(int16).max). */
        private const val INT16_MAX = 32767f
    }
}
