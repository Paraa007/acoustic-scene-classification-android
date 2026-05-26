package com.fzi.acousticscene.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.fzi.acousticscene.R
import com.fzi.acousticscene.service.ClassificationService
import com.fzi.acousticscene.util.BatteryOptimizationHelper
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
        // Show the battery-optimization intro once per install. Re-checks on
        // each recording start happen via BatteryOptimizationHelper, so we no
        // longer interrupt every launch with the same dialog.
        BatteryOptimizationHelper.maybeShowFirstRunDialog(this)
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
        val intent = Intent(this, ClassificationService::class.java).apply {
            action = ClassificationService.ACTION_STOP
        }
        startService(intent)
    }

}
