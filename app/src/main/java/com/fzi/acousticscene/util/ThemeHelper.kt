package com.fzi.acousticscene.util

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

/**
 * Helper for persisting and applying the user's Light/Dark mode preference.
 */
object ThemeHelper {

    private const val PREFS_NAME = "theme_prefs"
    private const val KEY_DARK_MODE = "dark_mode"

    /**
     * Returns true if dark mode is currently enabled.
     * Default is true (dark mode) to match the original app appearance.
     */
    fun isDarkMode(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_DARK_MODE, true)
    }

    /**
     * Saves the dark mode preference and applies it immediately.
     */
    fun setDarkMode(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_DARK_MODE, enabled).apply()
        applyTheme(enabled)
    }

    /**
     * Applies the saved theme preference. Call this in every Activity's
     * onCreate() BEFORE super.onCreate() and setContentView().
     */
    fun applySavedTheme(context: Context) {
        applyTheme(isDarkMode(context))
    }

    private fun applyTheme(darkMode: Boolean) {
        val mode = if (darkMode) {
            AppCompatDelegate.MODE_NIGHT_YES
        } else {
            AppCompatDelegate.MODE_NIGHT_NO
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
