package com.fzi.acousticscene.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide registry of currently active recording sessions, keyed by mode (User vs Dev).
 * Survives fragment/activity destruction as long as the process is alive (e.g., foreground service).
 * Used to (a) skip the Dev Mode model picker when a session is already running and
 * (b) render an "active" badge next to the matching session in History.
 */
object ActiveSessionRegistry {
    data class Entry(
        val isDevMode: Boolean,
        val modelPath: String,
        val modelName: String,
        val numClasses: Int,
        val sessionStartTime: Long
    )

    private val _active = MutableStateFlow<Map<Boolean, Entry>>(emptyMap())
    val active: StateFlow<Map<Boolean, Entry>> = _active.asStateFlow()

    fun register(entry: Entry) {
        _active.value = _active.value + (entry.isDevMode to entry)
    }

    fun unregister(isDevMode: Boolean) {
        _active.value = _active.value - isDevMode
    }

    fun get(isDevMode: Boolean): Entry? = _active.value[isDevMode]

    fun activeSessionStartTimes(): Set<Long> =
        _active.value.values.map { it.sessionStartTime }.toSet()
}
