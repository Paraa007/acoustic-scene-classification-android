package com.fzi.acousticscene.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Drops the PyTorch file extension when a model name is rendered to the user.
 * The actual file on disk keeps `.pt` (loaders still need the full name); only
 * UI strings should run through this.
 */
fun String.stripModelSuffix(): String = removeSuffix(".pt")

/**
 * Helper for managing user-defined display names for models.
 * Display names are stored in SharedPreferences and fall back to the file name.
 */
object ModelDisplayNameHelper {

    private const val PREFS_NAME = "model_display_names"
    private const val KEY_PREFIX = "display_name_"

    fun getDisplayName(context: Context, modelFileName: String): String {
        val custom = getPrefs(context).getString(KEY_PREFIX + modelFileName, null)
        val raw = if (!custom.isNullOrBlank()) custom else modelFileName
        return raw.stripModelSuffix()
    }

    /**
     * True iff the user has set a custom display name for this file. Callers that
     * used to compare `displayName != fileName` to detect a custom rename should
     * use this instead — `getDisplayName` now always strips the `.pt` suffix, so
     * the equality check no longer means what it used to.
     */
    fun hasCustomDisplayName(context: Context, modelFileName: String): Boolean {
        val custom = getPrefs(context).getString(KEY_PREFIX + modelFileName, null)
        return !custom.isNullOrBlank()
    }

    fun setDisplayName(context: Context, modelFileName: String, displayName: String) {
        getPrefs(context).edit().putString(KEY_PREFIX + modelFileName, displayName).apply()
    }

    fun clearDisplayName(context: Context, modelFileName: String) {
        getPrefs(context).edit().remove(KEY_PREFIX + modelFileName).apply()
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
