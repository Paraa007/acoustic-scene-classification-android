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
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.fzi.acousticscene.R
import com.fzi.acousticscene.data.ActiveSessionRegistry
import com.fzi.acousticscene.data.RecordingEngineHolder
import com.fzi.acousticscene.model.SessionConfig
import com.fzi.acousticscene.service.ClassificationService
import com.fzi.acousticscene.ui.live.LiveRecordingFragment
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

        // Tapped the foreground-service notification → jump straight to the live
        // screen and re-attach to the running session.
        maybeRouteToLive(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeRouteToLive(intent)
    }

    /**
     * If this Intent carries [ClassificationService.EXTRA_OPEN_LIVE] and a session
     * is actually running, navigate to the live recording screen marked as a
     * re-entry so back returns to the hub and the session keeps running. No-ops
     * when the extra is absent, when nothing is recording, or when we're already
     * on the live screen.
     */
    private fun maybeRouteToLive(intent: Intent?) {
        if (intent?.getBooleanExtra(ClassificationService.EXTRA_OPEN_LIVE, false) != true) return
        // Consume the flag so a later config change / recreation doesn't re-trigger.
        intent.removeExtra(ClassificationService.EXTRA_OPEN_LIVE)
        if (ActiveSessionRegistry.get() == null) return
        val navController = navControllerOrNull() ?: return
        if (navController.currentDestination?.id == R.id.liveRecordingFragment) return
        navController.navigate(
            R.id.liveRecordingFragment,
            bundleOf(LiveRecordingFragment.ARG_REENTRY to true)
        )
    }

    private fun navControllerOrNull(): NavController? {
        val host = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
        return host?.navController
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

    private fun launchServiceWithAction(action: String) {
        val intent = Intent(this, ClassificationService::class.java).apply {
            this.action = action
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    /**
     * Hands the config over to the [ClassificationService] and asks it to apply
     * it. The service will promote itself to foreground and start loading the
     * picked models. The config is passed via [RecordingEngineHolder] — same
     * process, so no serialization needed.
     */
    fun applySessionConfigViaService(config: SessionConfig) {
        RecordingEngineHolder.pendingConfig = config
        launchServiceWithAction(ClassificationService.ACTION_APPLY_CONFIG)
    }

    /** Public so LiveRecordingFragment can ask the foreground service to start. */
    fun startClassificationService() {
        launchServiceWithAction(ClassificationService.ACTION_START)
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

    fun pauseClassificationService(autoResumeAfterMs: Long?) {
        RecordingEngineHolder.pendingPauseAutoResumeMs = autoResumeAfterMs
        launchServiceWithAction(ClassificationService.ACTION_PAUSE)
    }

    fun resumeClassificationService() {
        launchServiceWithAction(ClassificationService.ACTION_RESUME)
    }

    fun clearRecordingResults() {
        launchServiceWithAction(ClassificationService.ACTION_CLEAR_RESULTS)
    }

}
