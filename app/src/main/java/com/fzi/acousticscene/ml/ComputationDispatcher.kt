package com.fzi.acousticscene.ml

import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * Dedizierter Thread-Pool für schwere Berechnungen (Mel-Spectrogram)
 * 
 * Verhindert Konkurrenz mit anderen Coroutines auf Dispatchers.Default
 */
object ComputationDispatcher {
    // Eigener Thread-Pool NUR für Mel-Spectrogram Berechnung
    // 2 Threads: Einer für aktuelle Berechnung, einer als Reserve
    val dispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
}
