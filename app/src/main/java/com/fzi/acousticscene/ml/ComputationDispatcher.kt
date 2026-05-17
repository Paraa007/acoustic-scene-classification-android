package com.fzi.acousticscene.ml

import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Dedizierter Thread-Pool für schwere Berechnungen (Mel-Spectrogram).
 *
 * Verhindert Konkurrenz mit anderen Coroutines auf Dispatchers.Default.
 * Im laufenden App-Prozess bleibt der Pool live; [shutdown] ist für
 * Unit-Tests gedacht, damit Test-Threads nicht über die Test-Suite
 * hinweg bestehen bleiben.
 */
object ComputationDispatcher {
    private val pool: ExecutorService = Executors.newFixedThreadPool(2)
    val dispatcher: ExecutorCoroutineDispatcher = pool.asCoroutineDispatcher()

    /**
     * Shuts down the underlying thread pool. After this call the dispatcher
     * rejects new work — only call from test teardown or process exit.
     */
    fun shutdown() {
        dispatcher.close()
    }
}
