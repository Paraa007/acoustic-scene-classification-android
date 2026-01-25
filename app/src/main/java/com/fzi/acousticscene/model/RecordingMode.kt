package com.fzi.acousticscene.model

/**
 * Enum für Aufnahme-Modi mit FFT-Parametern
 */
enum class RecordingMode(
    val durationSeconds: Int,
    val label: String,
    val nFft: Int,
    val winLength: Int,
    val hopLength: Int,
    val nMels: Int
) {
    STANDARD(
        durationSeconds = 10,
        label = "Standard (10s)",
        nFft = 4096,
        winLength = 3072,
        hopLength = 500,
        nMels = 256
    ),
    FAST(
        durationSeconds = 1,
        label = "Fast (1s)",
        nFft = 1024,      // Reduziert für Performance
        winLength = 768,  // 75% von nFft
        hopLength = 256,  // Reduziert
        nMels = 64        // Reduziert für Performance
    ),
    MEDIUM(
        durationSeconds = 5,
        label = "Medium (5s)",
        nFft = 2048,      // Zwischen FAST und STANDARD
        winLength = 1536, // 75% von nFft
        hopLength = 400,  // Zwischen FAST und STANDARD
        nMels = 128       // Zwischen FAST und STANDARD
    ),
    LONG(
        durationSeconds = 1800,  // 30 Minuten = 1800 Sekunden
        label = "Long (30min)",
        nFft = 4096,      // Wie STANDARD für Detaillierung
        winLength = 3072, // 75% von nFft
        hopLength = 500,  // Wie STANDARD
        nMels = 256       // Wie STANDARD für volle Auflösung
    );
    
    companion object {
        /**
         * Gibt die Standard-Aufnahme-Dauer zurück
         */
        val DEFAULT = STANDARD
    }
}
