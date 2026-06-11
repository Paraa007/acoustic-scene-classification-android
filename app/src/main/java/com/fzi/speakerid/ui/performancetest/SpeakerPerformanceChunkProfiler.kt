package com.fzi.speakerid.ui.performancetest

/**
 * Port von siqas `src/library/calculations/profiler.py::ChunkProfiler`.
 *
 * Misst die Wall-Time einzelner Pipeline-Phasen pro Chunk. Wie in Python lebt
 * die Profiling-Logik komplett beim Performance-Test — die library-Klassen
 * bleiben sauber, der Worker wrappt ihre Aufrufe in [measure].
 *
 * Semantik exakt wie das Original:
 *  - [startChunk] beginnt ein neues Timing-Dict, [endChunk] schreibt
 *    `_total_ms` (Gesamtzeit des Chunks) und haengt das Dict an die History.
 *  - [measure] addiert die Dauer in ms auf den Key (mehrere Segmente pro
 *    Chunk summieren sich, vgl. `self._current.get(name, 0.0) + ...`).
 *  - History ist auf [MAX_HISTORY] begrenzt; beim Ueberlauf bleibt die
 *    juengere Haelfte (`self._history[-MAX_HISTORY // 2:]`).
 *
 * Threading wie Python: Messung auf dem Worker-Thread, [history] liefert
 * unter Lock eine Kopie fuer den Main-Thread.
 */
class SpeakerPerformanceChunkProfiler {

    var enabled: Boolean = false
        private set

    private var current = LinkedHashMap<String, Double>()
    private val historyList = ArrayList<Map<String, Double>>()
    private val lock = Any()
    private var chunkStartNanos: Long? = null

    fun enable() {
        enabled = true
    }

    fun disable() {
        enabled = false
    }

    fun startChunk() {
        if (!enabled) return
        current = LinkedHashMap()
        chunkStartNanos = System.nanoTime()
    }

    fun endChunk() {
        if (!enabled) return
        val start = chunkStartNanos ?: return
        current[TOTAL_KEY] = (System.nanoTime() - start) / 1e6
        synchronized(lock) {
            historyList.add(LinkedHashMap(current))
            if (historyList.size > MAX_HISTORY) {
                val keep = ArrayList(historyList.subList(historyList.size - MAX_HISTORY / 2, historyList.size))
                historyList.clear()
                historyList.addAll(keep)
            }
        }
        chunkStartNanos = null
    }

    /** Pendant zum `with profiler.measure(name):`-Contextmanager. */
    inline fun <T> measure(name: String, block: () -> T): T {
        if (!enabled) return block()
        val t0 = System.nanoTime()
        try {
            return block()
        } finally {
            addMeasurement(name, (System.nanoTime() - t0) / 1e6)
        }
    }

    /** Nur fuer [measure] gedacht (public wegen inline). */
    fun addMeasurement(name: String, ms: Double) {
        current[name] = (current[name] ?: 0.0) + ms
    }

    /** Kopie der History (ein Map-Eintrag pro Chunk). */
    fun history(): List<Map<String, Double>> = synchronized(lock) { ArrayList(historyList) }

    fun clear() {
        synchronized(lock) { historyList.clear() }
        current.clear()
        chunkStartNanos = null
    }

    companion object {
        const val MAX_HISTORY = 1000

        /** Profiler-Key der Chunk-Gesamtzeit (`_total_ms`). */
        const val TOTAL_KEY = "_total_ms"
    }
}
