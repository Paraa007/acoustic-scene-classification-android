package com.fzi.acousticscene.util

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.edit
import com.fzi.acousticscene.R

/**
 * Battery-optimization gate used to keep background recordings alive on Android.
 *
 * Doze mode and App Standby will throttle the foreground service after a few
 * minutes once the screen is off, even with a held WakeLock. The only reliable
 * escape is the per-app exemption the user grants via the system settings
 * intent. This helper owns the first-run dialog, the re-check before each
 * recording start, and the small SharedPreferences flag that remembers the
 * intro has already been shown.
 */
object BatteryOptimizationHelper {

    private const val PREFS = "battery_optimization_prefs"
    private const val KEY_INTRO_SHOWN = "battery_opt_intro_shown"

    /**
     * True when the system reports the app is on the battery-optimization
     * allowlist. Always true on pre-Marshmallow devices (Doze did not exist).
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Has the user been shown the one-time intro dialog before? */
    private fun introShown(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_INTRO_SHOWN, false)

    private fun markIntroShown(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_INTRO_SHOWN, true) }
    }

    /**
     * First-run entry point. Shows the intro dialog once per install if the
     * exemption is still missing. After the dialog has been shown once, the
     * flag stays set even if the user picks Skip — the recording-start
     * re-check ([promptIfMissingBeforeRecording]) covers the rest.
     */
    fun maybeShowFirstRunDialog(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (introShown(activity)) return
        if (isIgnoringBatteryOptimizations(activity)) {
            // No need to nag if the user already granted it some other way.
            markIntroShown(activity)
            return
        }
        showExemptionDialog(activity, firstRun = true)
    }

    /**
     * Pre-recording gate. Reminds the user that background recording will be
     * cut short by Doze, then starts the recording either way — the exemption
     * is strongly recommended but not required for the app to work.
     *
     * Returns true if the dialog was shown so callers can decide whether to
     * defer their own follow-up actions. The recording itself starts via
     * [onProceed], invoked when the user picks Continue or Skip.
     */
    fun promptIfMissingBeforeRecording(
        activity: Activity,
        onProceed: () -> Unit
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            onProceed()
            return false
        }
        if (isIgnoringBatteryOptimizations(activity)) {
            onProceed()
            return false
        }
        showExemptionDialog(activity, firstRun = false, onProceed = onProceed)
        return true
    }

    /**
     * Opens the system intent that lets the user add this app to the
     * battery-optimization allowlist. Falls back to the global settings page
     * if the per-app intent is unavailable on the device.
     */
    @SuppressLint("BatteryLife")
    fun requestExemption(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
        } catch (_: Exception) {
            try {
                activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                Toast.makeText(
                    activity,
                    activity.getString(
                        R.string.battery_search_app,
                        activity.getString(R.string.app_name)
                    ),
                    Toast.LENGTH_LONG
                ).show()
            } catch (_: Exception) {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.battery_manual_disable),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showExemptionDialog(
        activity: Activity,
        firstRun: Boolean,
        onProceed: (() -> Unit)? = null
    ) {
        com.fzi.acousticscene.ui.ModernDialogHelper.showConfirmDialog(
            context = activity,
            title = activity.getString(R.string.battery_optimization_title),
            message = activity.getString(R.string.battery_optimization_message),
            confirmText = activity.getString(R.string.battery_optimization_open_settings),
            cancelText = activity.getString(
                if (firstRun) R.string.battery_optimization_skip
                else R.string.battery_optimization_continue_anyway
            ),
            onConfirm = {
                if (firstRun) markIntroShown(activity)
                requestExemption(activity)
                // For the pre-recording flow, keep going after the user heads
                // to settings. They can come back and the recording will run;
                // throttling only kicks in once Doze takes over, so the first
                // few minutes are fine either way.
                onProceed?.invoke()
            },
            onCancel = {
                if (firstRun) markIntroShown(activity)
                onProceed?.invoke()
            }
        )
    }
}
