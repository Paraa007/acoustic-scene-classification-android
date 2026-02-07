package com.fzi.acousticscene

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
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.fzi.acousticscene.service.ClassificationService
import com.fzi.acousticscene.ui.MainViewModel
import com.fzi.acousticscene.ui.ModernDialogHelper
import com.fzi.acousticscene.util.ThemeHelper
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * MainActivity - Single Activity host with Bottom Navigation
 *
 * Hosts 4 fragments via NavHostFragment:
 * - User Mode (RecordingFragment with isDevMode=false)
 * - Dev Mode (RecordingFragment with isDevMode=true)
 * - History (HistoryFragment)
 * - Settings (SettingsFragment)
 *
 * Manages:
 * - ClassificationService binding
 * - Battery optimization
 * - Edge-to-Edge display
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val viewModel: MainViewModel by viewModels()

    // Service for background operation
    private var classificationService: ClassificationService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ClassificationService.LocalBinder
            classificationService = binder.getService()
            serviceBound = true
            android.util.Log.d(TAG, "Service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            classificationService = null
            serviceBound = false
            android.util.Log.d(TAG, "Service disconnected")
        }
    }

    private lateinit var navController: NavController
    private lateinit var bottomNav: BottomNavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply saved theme before super.onCreate()
        ThemeHelper.applySavedTheme(this)

        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        setContentView(R.layout.activity_main)

        // Window Insets for dynamic padding (don't add bottom padding - bottom nav handles it)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            insets
        }

        // Setup Navigation
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        bottomNav = findViewById(R.id.bottom_navigation)
        bottomNav.setupWithNavController(navController)

        // Bind service
        bindClassificationService()

        // Check battery optimization
        checkBatteryOptimization()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    /**
     * Binds the ClassificationService
     */
    private fun bindClassificationService() {
        val intent = Intent(this, ClassificationService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    /**
     * Starts the ClassificationService (for background operation)
     * Called by RecordingFragment
     */
    fun startClassificationService() {
        val intent = Intent(this, ClassificationService::class.java).apply {
            action = ClassificationService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    /**
     * Stops the ClassificationService
     * Called by RecordingFragment
     */
    fun stopClassificationService() {
        val intent = Intent(this, ClassificationService::class.java).apply {
            action = ClassificationService.ACTION_STOP
        }
        startService(intent)
    }

    /**
     * Checks battery optimization status
     */
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
            } catch (e: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(intent)
                    Toast.makeText(
                        this,
                        getString(R.string.battery_search_app, getString(R.string.app_name)),
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e2: Exception) {
                    Toast.makeText(
                        this,
                        getString(R.string.battery_manual_disable),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}
