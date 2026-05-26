package com.fzi.acousticscene.ui

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.fzi.acousticscene.R
import com.fzi.acousticscene.service.ClassificationService
import com.fzi.acousticscene.util.ThemeHelper

/**
 * MainActivity — single Activity that hosts the new wizard-driven nav graph.
 *
 * Bottom navigation is gone (per UI_REDESIGN_WIZARD.md): every screen is reached
 * either from the welcome page or by walking forward/back through the wizard.
 * Battery optimization + foreground service binding are unchanged.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private var classificationService: ClassificationService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ClassificationService.LocalBinder
            classificationService = binder.getService()
            serviceBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            classificationService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applySavedTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        bindClassificationService()
        checkBatteryOptimization()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    private fun bindClassificationService() {
        val intent = Intent(this, ClassificationService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    /** Public so LiveRecordingFragment can ask the foreground service to start. */
    fun startClassificationService() {
        val intent = Intent(this, ClassificationService::class.java).apply {
            action = ClassificationService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    fun stopClassificationService() {
        // Use stopService() rather than startService(ACTION_STOP): on Android 12+
        // a foreground-service start from the background throws
        // ForegroundServiceStartNotAllowedException. stopService() reliably
        // triggers ClassificationService.onDestroy(), which already runs the
        // full teardown (WakeLock release, alarm cancel, stopForeground).
        val intent = Intent(this, ClassificationService::class.java)
        stopService(intent)
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                showBatteryOptimizationDialog()
            }
        }
    }

    private fun showBatteryOptimizationDialog() {
        ModernDialogHelper.showConfirmDialog(
            context = this,
            title = getString(R.string.battery_optimization_title),
            message = getString(R.string.battery_optimization_message),
            confirmText = getString(R.string.yes_disable),
            cancelText = getString(R.string.later),
            onConfirm = { requestBatteryOptimizationExemption() },
            onCancel = {
                Toast.makeText(this, getString(R.string.battery_warning), Toast.LENGTH_LONG).show()
            }
        )
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    Toast.makeText(
                        this,
                        getString(R.string.battery_search_app, getString(R.string.app_name)),
                        Toast.LENGTH_LONG
                    ).show()
                } catch (_: Exception) {
                    Toast.makeText(this, getString(R.string.battery_manual_disable), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
