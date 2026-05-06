package com.fzi.acousticscene.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide registry of the currently active recording session. Survives
 * fragment/activity destruction as long as the process is alive (foreground
 * service). With the wizard redesign there's only ever one session at a time;
 * this registry exists so the History tile can render an "active" badge and
 * survive process revival.
 */
object ActiveSessionRegistry {
    data class Entry(
        val modelPath: String,
        val modelName: String,
        val numClasses: Int,
        val sessionStartTime: Long,
        // Filenames (not full paths) of every model when Multi-Model is active.
        // null / empty = single-model session.
        val allInOneModels: List<String>? = null
    ) {
        val isAllInOne: Boolean
            get() = allInOneModels != null && allInOneModels.size >= 2
    }

    private val _active = MutableStateFlow<Entry?>(null)
    val active: StateFlow<Entry?> = _active.asStateFlow()

    fun register(entry: Entry) {
        _active.value = entry
    }

    fun unregister() {
        _active.value = null
    }

    fun get(): Entry? = _active.value

    fun activeSessionStartTimes(): Set<Long> =
        _active.value?.let { setOf(it.sessionStartTime) } ?: emptySet()
}
