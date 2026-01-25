package com.fzi.acousticscene.audio

/**
 * Sealed Class für Recording-Zustände
 */
sealed class RecordingState {
    /**
     * Recording wurde gestartet
     */
    object Started : RecordingState()
    
    /**
     * Recording läuft - Progress Update
     * @param progress Fortschritt von 0.0 (Start) bis 1.0 (Fertig)
     */
    data class Progress(val progress: Float) : RecordingState()
    
    /**
     * Recording erfolgreich abgeschlossen
     * @param samples FloatArray mit normalisierten Audio-Samples ([-1.0, 1.0])
     */
    data class Completed(val samples: FloatArray) : RecordingState()
    
    /**
     * Fehler während Recording
     * @param message Fehlermeldung
     */
    data class Error(val message: String) : RecordingState()
}
