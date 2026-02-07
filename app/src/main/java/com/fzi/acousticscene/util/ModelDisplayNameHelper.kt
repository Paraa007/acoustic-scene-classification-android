package com.fzi.acousticscene.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Helper for managing user-defined display names for models.
 * Display names are stored in SharedPreferences and fall back to the file name.
 */
object ModelDisplayNameHelper {

    private const val PREFS_NAME = "model_display_names"
    private const val KEY_PREFIX = "display_name_"

    fun getDisplayName(context: Context, modelFileName: String): String {
        val custom = getPrefs(context).getString(KEY_PREFIX + modelFileName, null)
        return if (!custom.isNullOrBlank()) custom else modelFileName
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
