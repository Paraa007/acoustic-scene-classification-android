package com.fzi.acousticscene

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.fzi.acousticscene.model.LabelProvider
import com.fzi.acousticscene.model.RecordingMode
import com.fzi.acousticscene.model.SceneClass
import com.fzi.acousticscene.ui.AppState
import com.fzi.acousticscene.service.ClassificationService
import com.fzi.acousticscene.ui.MainViewModel
import com.fzi.acousticscene.ui.MainViewModelFactory
import com.fzi.acousticscene.ui.UiState
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.fzi.acousticscene.data.PredictionStatistics

/**
 * MainActivity für Acoustic Scene Classification App
 * 
 * Features:
 * - Audio Recording Permission Handling
 * - Model Loading
 * - Kontinuierliche Klassifikation
 * - Echtzeit-Anzeige der Ergebnisse
 * - History und Statistiken
 * 
 * @author FZI
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val DEFAULT_MODEL_PATH = "user_models/model1.pt"
        private const val DEFAULT_MODEL_NAME = "model1"
    }

    // Model configuration from Intent
    private lateinit var modelPath: String
    private lateinit var modelName: String
    private var isDevMode: Boolean = false

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(application, modelPath, modelName, isDevMode)
    }
    
    // Service für Hintergrund-Betrieb
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
    
    // UI Components
    private lateinit var backButton: android.widget.ImageButton
    private lateinit var modeStandardButton: MaterialButton
    private lateinit var modeFastButton: MaterialButton
    private lateinit var modeMediumButton: MaterialButton
    private lateinit var modeLongButton: MaterialButton
    private lateinit var startStopButton: MaterialButton
    private lateinit var exportButton: MaterialButton
    private lateinit var statsButton: MaterialButton
    private lateinit var statusLabel: TextView
    private lateinit var modelStatusLabel: TextView
    private lateinit var timerProgress: LinearProgressIndicator
    private lateinit var timerText: TextView
    private lateinit var confidenceCircleView: com.fzi.acousticscene.ui.ConfidenceCircleView
    private lateinit var ripplePulseView: com.fzi.acousticscene.ui.RipplePulseView
    private lateinit var volumeLevelText: TextView
    private lateinit var currentSceneLabel: TextView
    private lateinit var infoCard: MaterialCardView
    private lateinit var recordingDurationText: TextView
    private lateinit var modelConfidenceText: TextView
    private lateinit var predictionsCard: MaterialCardView
    private lateinit var predictionsContainer: LinearLayout
    private lateinit var statisticsCard: MaterialCardView
    private lateinit var totalClassificationsText: TextView
    private lateinit var avgInferenceTimeText: TextView
    private lateinit var historyCard: MaterialCardView
    private lateinit var historyContainer: LinearLayout
    private lateinit var noHistoryText: TextView
    private lateinit var saveHistoryButton: MaterialButton
    
    // Scene Color Map (Extended DCASE 2025 - 9 classes)
    private val sceneColors = mapOf(
        SceneClass.TRANSIT_VEHICLES to R.color.transit_vehicles,
        SceneClass.URBAN_WAITING to R.color.urban_waiting,
        SceneClass.NATURE to R.color.nature,
        SceneClass.SOCIAL to R.color.social,
        SceneClass.WORK to R.color.work,
        SceneClass.COMMERCIAL to R.color.commercial,
        SceneClass.LEISURE_SPORT to R.color.leisure_sport,
        SceneClass.CULTURE_QUIET to R.color.culture_quiet,
        SceneClass.LIVING_ROOM to R.color.living_room
    )

    // Color map for dynamic classes (including 9th class LIVING_ROOM)
    private val dynamicSceneColors = mapOf(
        "TRANSIT_VEHICLES" to R.color.transit_vehicles,
        "URBAN_WAITING" to R.color.urban_waiting,
        "NATURE" to R.color.nature,
        "SOCIAL" to R.color.social,
        "WORK" to R.color.work,
        "COMMERCIAL" to R.color.commercial,
        "LEISURE_SPORT" to R.color.leisure_sport,
        "CULTURE_QUIET" to R.color.culture_quiet,
        "LIVING_ROOM" to R.color.living_room
    )

    /**
     * Gets the color resource for a dynamic scene class
     */
    private fun getColorForDynamicClass(classId: String): Int {
        return dynamicSceneColors[classId] ?: R.color.accent_green
    }

    /**
     * Gets the color resource for a dynamic scene class by index
     */
    private fun getColorForClassIndex(index: Int): Int {
        val dynamicClass = viewModel.getClassByIndex(index)
        return if (dynamicClass != null) {
            getColorForDynamicClass(dynamicClass.id)
        } else {
            R.color.accent_green
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Extract model configuration from Intent BEFORE accessing viewModel
        modelPath = intent.getStringExtra(WelcomeActivity.EXTRA_MODEL_PATH) ?: DEFAULT_MODEL_PATH
        modelName = intent.getStringExtra(WelcomeActivity.EXTRA_MODEL_NAME) ?: DEFAULT_MODEL_NAME
        isDevMode = intent.getBooleanExtra(WelcomeActivity.EXTRA_IS_DEV_MODE, false)

        android.util.Log.d(TAG, "Starting with model: $modelName, path: $modelPath, devMode: $isDevMode")

        // Edge-to-Edge aktivieren für moderne Geräte
        enableEdgeToEdge()

        try {
        setContentView(R.layout.activity_main)

        // Window Insets für dynamisches Padding (Status Bar, Navigation Bar)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

            // Session initialisieren (neues Package startet hier)
            viewModel.initializeSession()

            // Service binden
            bindClassificationService()

            initializeViews()
            setupObservers()
            checkPermissions()

            // Show model info Toast
            showModelInfoToast()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error in onCreate", e)
            // Zeige Fehler-Dialog statt zu crashen
            AlertDialog.Builder(this)
                .setTitle(R.string.error_processing)
                .setMessage("Fehler beim Initialisieren der App: ${e.message}")
                .setPositiveButton(R.string.ok) { _, _ -> finish() }
                .show()
        }
    }

    /**
     * Shows a Toast with model information
     */
    private fun showModelInfoToast() {
        val modelInfo = viewModel.getModelInfoString()
        Toast.makeText(this, modelInfo, Toast.LENGTH_LONG).show()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Service entbinden
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }
    
    /**
     * Bindet den Classification Service
     */
    private fun bindClassificationService() {
        val intent = Intent(this, ClassificationService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }
    
    /**
     * Startet den Classification Service (für Hintergrund-Betrieb)
     */
    private fun startClassificationService() {
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
     * Stoppt den Classification Service
     */
    private fun stopClassificationService() {
        val intent = Intent(this, ClassificationService::class.java).apply {
            action = ClassificationService.ACTION_STOP
        }
        startService(intent)
    }
    
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        navigateToWelcome()
    }

    /**
     * Navigiert zurück zur WelcomeActivity
     */
    private fun navigateToWelcome() {
        val intent = Intent(this, WelcomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }
    
    /**
     * Initialisiert alle UI-Komponenten
     */
    private fun initializeViews() {
        // Zurück-Button
        backButton = findViewById(R.id.backButton)
        backButton.setOnClickListener {
            navigateToWelcome()
        }

        modeStandardButton = findViewById(R.id.modeStandardButton)
        modeFastButton = findViewById(R.id.modeFastButton)
        modeMediumButton = findViewById(R.id.modeMediumButton)
        modeLongButton = findViewById(R.id.modeLongButton)
        startStopButton = findViewById(R.id.startStopButton)
        statusLabel = findViewById(R.id.statusLabel)
        modelStatusLabel = findViewById(R.id.modelStatusLabel)
        
        // Modus-Auswahl Buttons
        modeStandardButton.setOnClickListener {
            viewModel.setRecordingMode(RecordingMode.STANDARD)
            updateModeButtons(RecordingMode.STANDARD)
        }
        
        modeFastButton.setOnClickListener {
            viewModel.setRecordingMode(RecordingMode.FAST)
            updateModeButtons(RecordingMode.FAST)
        }
        
        modeMediumButton.setOnClickListener {
            viewModel.setRecordingMode(RecordingMode.MEDIUM)
            updateModeButtons(RecordingMode.MEDIUM)
        }
        
        modeLongButton.setOnClickListener {
            viewModel.setRecordingMode(RecordingMode.LONG)
            updateModeButtons(RecordingMode.LONG)
        }
        timerProgress = findViewById(R.id.timerProgress)
        timerText = findViewById(R.id.timerText)
        confidenceCircleView = findViewById(R.id.confidenceCircleView)
        ripplePulseView = findViewById(R.id.ripplePulseView)
        volumeLevelText = findViewById(R.id.volumeLevelText)
        currentSceneLabel = findViewById(R.id.currentSceneLabel)
        infoCard = findViewById(R.id.infoCard)
        recordingDurationText = findViewById(R.id.recordingDurationText)
        modelConfidenceText = findViewById(R.id.modelConfidenceText)
        predictionsCard = findViewById(R.id.predictionsCard)
        predictionsContainer = findViewById(R.id.predictionsContainer)
        statisticsCard = findViewById(R.id.statisticsCard)
        totalClassificationsText = findViewById(R.id.totalClassificationsText)
        avgInferenceTimeText = findViewById(R.id.avgInferenceTimeText)
        historyCard = findViewById(R.id.historyCard)
        historyContainer = findViewById(R.id.historyContainer)
        noHistoryText = findViewById(R.id.noHistoryText)
        saveHistoryButton = findViewById(R.id.saveHistoryButton)
        exportButton = findViewById(R.id.exportButton)
        statsButton = findViewById(R.id.statsButton)
        
        saveHistoryButton.setOnClickListener {
            saveHistoryToFile()
        }
        
        exportButton.setOnClickListener {
            exportAllPredictions()
        }
        
        statsButton.setOnClickListener {
            showStatisticsDialog()
        }
        
        startStopButton.setOnClickListener {
            if (viewModel.isClassifying()) {
                viewModel.stopClassification()
                stopClassificationService()
            } else {
                if (hasAudioPermission()) {
                    // Starte Service für Hintergrund-Betrieb
                    startClassificationService()
                    viewModel.startClassification()
                } else {
                    requestAudioPermission()
                }
            }
        }
    }
    
    /**
     * Setzt die Observer für ViewModel State
     */
    private fun setupObservers() {
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                updateUI(state)
            }
        }
    }
    
    /**
     * Aktualisiert die UI basierend auf dem aktuellen State
     */
    private fun updateUI(state: UiState) {
        updateAppState(state.appState)
        updateModelStatus(state.isModelLoaded)
        updateCurrentResult(state.currentResult)
        updatePredictions(state.currentResult)
        updateStatistics(state.totalClassifications, state.averageInferenceTime)
        updateHistory(state.history)
        updateModeButtons(state.recordingMode)
        updateVolumeDisplay(state.currentVolume, state.appState)

        if (state.errorMessage != null) {
            showError(state.errorMessage)
        }
    }

    /**
     * Aktualisiert die Lautstärke-Anzeige und Ripple-Animation
     */
    private fun updateVolumeDisplay(volume: Float, appState: AppState) {
        // Ripple-Animation nur während der Aufnahme zeigen
        when (appState) {
            is AppState.Recording -> {
                ripplePulseView.setVolume(volume)
                volumeLevelText.visibility = View.VISIBLE
                val volumePercent = (volume * 100).toInt()
                volumeLevelText.text = "Vol: $volumePercent"
            }
            else -> {
                ripplePulseView.clear()
                volumeLevelText.visibility = View.GONE
            }
        }
    }
    
    /**
     * Aktualisiert die Modus-Auswahl Buttons
     */
    private fun updateModeButtons(mode: RecordingMode) {
        // Reset alle Buttons zu inaktivem Zustand
        val activeColor = ContextCompat.getColor(this, R.color.accent_green)
        val inactiveColor = ContextCompat.getColor(this, R.color.surface_variant)
        val activeTextColor = ContextCompat.getColor(this, R.color.text_primary)
        val inactiveTextColor = ContextCompat.getColor(this, R.color.text_secondary)
        
        modeStandardButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(inactiveColor))
        modeStandardButton.setTextColor(inactiveTextColor)
        modeFastButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(inactiveColor))
        modeFastButton.setTextColor(inactiveTextColor)
        modeMediumButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(inactiveColor))
        modeMediumButton.setTextColor(inactiveTextColor)
        modeLongButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(inactiveColor))
        modeLongButton.setTextColor(inactiveTextColor)
        
        // Setze aktiven Button
        when (mode) {
            RecordingMode.STANDARD -> {
                modeStandardButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(activeColor))
                modeStandardButton.setTextColor(activeTextColor)
            }
            RecordingMode.FAST -> {
                modeFastButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(activeColor))
                modeFastButton.setTextColor(activeTextColor)
            }
            RecordingMode.MEDIUM -> {
                modeMediumButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(activeColor))
                modeMediumButton.setTextColor(activeTextColor)
            }
            RecordingMode.LONG -> {
                modeLongButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(activeColor))
                modeLongButton.setTextColor(activeTextColor)
            }
        }
    }
    
    /**
     * Aktualisiert den App-Status
     */
    private fun updateAppState(appState: AppState) {
        when (appState) {
            is AppState.Idle -> {
                statusLabel.text = getString(R.string.status_idle)
                statusLabel.setTextColor(ContextCompat.getColor(this, R.color.status_idle))
                startStopButton.isEnabled = false
                timerProgress.visibility = View.GONE
                timerText.visibility = View.GONE
            }
            is AppState.Loading -> {
                statusLabel.text = getString(R.string.status_idle)
                statusLabel.setTextColor(ContextCompat.getColor(this, R.color.status_idle))
                startStopButton.isEnabled = false
                timerProgress.visibility = View.GONE
                timerText.visibility = View.GONE
            }
            is AppState.Ready -> {
                statusLabel.text = getString(R.string.status_idle)
                statusLabel.setTextColor(ContextCompat.getColor(this, R.color.status_idle))
                startStopButton.isEnabled = true
                startStopButton.text = getString(R.string.start_recording)
                timerProgress.visibility = View.GONE
                timerText.visibility = View.GONE
            }
            is AppState.Recording -> {
                statusLabel.text = getString(R.string.status_recording)
                statusLabel.setTextColor(ContextCompat.getColor(this, R.color.status_recording))
                startStopButton.isEnabled = true
                startStopButton.text = getString(R.string.stop_recording)
                
                val seconds = appState.secondsRemaining
                timerText.text = getString(R.string.timer_countdown, seconds)
                timerText.visibility = View.VISIBLE
                
                // Progress basierend auf recordingProgress aus UiState (0.0 - 1.0)
                val recordingProgress = viewModel.uiState.value.recordingProgress
                val progress = (recordingProgress * 100).toInt()
                timerProgress.progress = progress
                timerProgress.visibility = View.VISIBLE
            }
            is AppState.Processing -> {
                statusLabel.text = getString(R.string.status_processing)
                statusLabel.setTextColor(ContextCompat.getColor(this, R.color.status_processing))
                startStopButton.isEnabled = true
                startStopButton.text = getString(R.string.stop_recording)
                timerProgress.visibility = View.GONE
                timerText.visibility = View.GONE
            }
            is AppState.Paused -> {
                val minutes = appState.minutesRemaining
                statusLabel.text = "Pause: $minutes Min. bis zur nächsten Aufnahme"
                statusLabel.setTextColor(ContextCompat.getColor(this, R.color.status_idle))
                startStopButton.isEnabled = true
                startStopButton.text = getString(R.string.stop_recording)
                timerProgress.visibility = View.GONE
                timerText.text = "$minutes Min."
                timerText.visibility = View.VISIBLE
            }
            is AppState.Error -> {
                statusLabel.text = appState.message
                statusLabel.setTextColor(ContextCompat.getColor(this, R.color.error))
                startStopButton.isEnabled = true
                startStopButton.text = getString(R.string.start_recording)
                timerProgress.visibility = View.GONE
                timerText.visibility = View.GONE
            }
        }
    }
    
    /**
     * Aktualisiert den Model-Status
     */
    private fun updateModelStatus(isLoaded: Boolean) {
        if (isLoaded) {
            // Show model name and class count instead of generic "Model loaded"
            modelStatusLabel.text = viewModel.getModelInfoString()
            modelStatusLabel.setTextColor(ContextCompat.getColor(this, R.color.status_recording))
        } else {
            modelStatusLabel.text = getString(R.string.loading_model)
            modelStatusLabel.setTextColor(ContextCompat.getColor(this, R.color.status_idle))
        }
    }
    
    /**
     * Aktualisiert das aktuelle Klassifikations-Ergebnis
     */
    private fun updateCurrentResult(result: com.fzi.acousticscene.model.ClassificationResult?) {
        if (result != null) {
            // Circular Progress Indicator aktualisieren
            confidenceCircleView.setConfidence(result.confidence, animate = true)
            
            // Scene Label mit Emoji
            currentSceneLabel.text = "${result.sceneClass.emoji} ${result.sceneClass.label}"
            currentSceneLabel.visibility = View.VISIBLE
            val colorRes = sceneColors[result.sceneClass] ?: R.color.accent_green
            currentSceneLabel.setTextColor(ContextCompat.getColor(this, colorRes))
            
            // Info Card anzeigen
            infoCard.visibility = View.VISIBLE
            val currentMode = viewModel.getRecordingMode()
            recordingDurationText.text = "${currentMode.durationSeconds}.0 s"
            modelConfidenceText.text = "${(result.confidence * 100).toInt()}%"
        } else {
            currentSceneLabel.visibility = View.GONE
            infoCard.visibility = View.GONE
            confidenceCircleView.setConfidence(0f, animate = false)
        }
    }
    
    /**
     * Aktualisiert die Top-Predictions
     */
    private fun updatePredictions(result: com.fzi.acousticscene.model.ClassificationResult?) {
        predictionsContainer.removeAllViews()
        
        if (result != null) {
            predictionsCard.visibility = View.VISIBLE
            val topPredictions = result.getTopPredictions(3)
            
            topPredictions.forEachIndexed { index, (scene, confidence) ->
                val predictionView = createPredictionView(scene, confidence, index + 1)
                predictionsContainer.addView(predictionView)
            }
        } else {
            predictionsCard.visibility = View.GONE
        }
    }
    
    /**
     * Erstellt eine View für eine Prediction mit Progress Bar
     */
    private fun createPredictionView(scene: SceneClass, confidence: Float, rank: Int): View {
        val card = com.google.android.material.card.MaterialCardView(this)
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        card.setPadding(16, 12, 16, 12)
        card.cardElevation = 0f
        card.radius = 12f
        card.strokeWidth = 0
        card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.surface_variant))
        
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(0, 0, 0, 0)
        
        // Top Row: Rank, Emoji + Name, Confidence
        val topRow = LinearLayout(this)
        topRow.orientation = LinearLayout.HORIZONTAL
        topRow.gravity = android.view.Gravity.CENTER_VERTICAL
        
        val rankText = TextView(this)
        rankText.text = "$rank."
        rankText.textSize = 16f
        rankText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        rankText.setPadding(0, 0, 12, 0)
        
        val sceneText = TextView(this)
        sceneText.text = "${scene.emoji} ${scene.label}"
        sceneText.textSize = 14f
        val colorRes = sceneColors[scene] ?: R.color.accent_green
        sceneText.setTextColor(ContextCompat.getColor(this, colorRes))
        sceneText.layoutParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        )
        
        val confidenceText = TextView(this)
        confidenceText.text = "${(confidence * 100).toInt()}%"
        confidenceText.textSize = 16f
        confidenceText.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        confidenceText.setTypeface(null, android.graphics.Typeface.BOLD)
        confidenceText.setPadding(12, 0, 0, 0)
        
        topRow.addView(rankText)
        topRow.addView(sceneText)
        topRow.addView(confidenceText)
        
        // Progress Bar
        val progressBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progressBar.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            8.dpToPx()
        )
        progressBar.max = 100
        progressBar.progress = (confidence * 100).toInt()
        progressBar.progressDrawable = ContextCompat.getDrawable(this, android.R.drawable.progress_horizontal)
        progressBar.progressTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, colorRes)
        )
        progressBar.setPadding(0, 8, 0, 0)
        
        container.addView(topRow)
        container.addView(progressBar)
        card.addView(container)
        
        return card
    }
    
    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }
    
    /**
     * Aktualisiert die Statistiken
     */
    private fun updateStatistics(total: Int, avgTime: Long) {
        if (total > 0) {
            statisticsCard.visibility = View.VISIBLE
            totalClassificationsText.text = total.toString()
            // Format: Sekunden statt Millisekunden
            val seconds = avgTime / 1000.0
            avgInferenceTimeText.text = String.format("%.2f s", seconds)
        } else {
            statisticsCard.visibility = View.GONE
        }
    }
    
    /**
     * Aktualisiert die History
     */
    private fun updateHistory(history: List<com.fzi.acousticscene.model.ClassificationResult>) {
        historyContainer.removeAllViews()
        
        if (history.isNotEmpty()) {
            historyCard.visibility = View.VISIBLE
            noHistoryText.visibility = View.GONE
            
            saveHistoryButton.isEnabled = true
            
            // Reverse, damit neueste zuerst
            history.reversed().forEach { result ->
                val historyItemView = createHistoryItemView(result)
                val layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                layoutParams.setMargins(0, 0, 0, 12)
                historyItemView.layoutParams = layoutParams
                historyContainer.addView(historyItemView)
            }
        } else {
            historyCard.visibility = View.VISIBLE  // Card immer sichtbar
            noHistoryText.visibility = View.VISIBLE
            saveHistoryButton.isEnabled = false
        }
    }
    
    /**
     * Erstellt eine View für ein History-Item - Schöneres Design
     */
    private fun createHistoryItemView(result: com.fzi.acousticscene.model.ClassificationResult): View {
        // Container Card für jedes History-Item
        val card = com.google.android.material.card.MaterialCardView(this)
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        card.setPadding(16, 12, 16, 12)
        card.cardElevation = 2f
        card.radius = 12f
        card.strokeWidth = 0
        card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.surface))
        
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(0, 0, 0, 0)
        
        // Scene Name - Kleiner geschrieben
        val sceneText = TextView(this)
        sceneText.text = result.sceneClass.label  // Vollständiger Name
        sceneText.textSize = 13f  // Kleiner geschrieben
        val colorRes = sceneColors[result.sceneClass] ?: R.color.primary
        sceneText.setTextColor(ContextCompat.getColor(this, colorRes))
        sceneText.setPadding(0, 0, 0, 4)
        sceneText.maxLines = 2
        sceneText.setSingleLine(false)
        
        // Nur Zeit (ohne Konfidenz)
        val timeText = TextView(this)
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        timeText.text = timeFormat.format(Date(result.timestamp))
        timeText.textSize = 11f
        timeText.setTextColor(ContextCompat.getColor(this, R.color.status_idle))
        
        container.addView(sceneText)
        container.addView(timeText)
        card.addView(container)
        
        return card
    }
    
    /**
     * Speichert die History als CSV-Datei und teilt sie
     */
    private fun saveHistoryToFile() {
        val history = viewModel.uiState.value.history
        if (history.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_history), Toast.LENGTH_SHORT).show()
            return
        }
        
        try {
            // Erstelle CSV-Inhalt
            val csvContent = StringBuilder()
            csvContent.append("Timestamp,Scene,Confidence,Inference Time (s)\n")
            
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            history.forEach { result ->
                csvContent.append("${dateFormat.format(Date(result.timestamp))},")
                csvContent.append("\"${result.sceneClass.label}\",")
                csvContent.append("${(result.confidence * 100).toFixed(2)},")
                // Format: Sekunden statt Millisekunden
                val seconds = result.inferenceTimeMs / 1000.0
                csvContent.append("${String.format("%.2f", seconds)}\n")
            }
            
            // Speichere in Datei
            val fileName = "acoustic_scene_history_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.csv"
            val file = File(getExternalFilesDir(null), fileName)
            FileWriter(file).use { writer ->
                writer.write(csvContent.toString())
            }
            
            // Teile die Datei
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Acoustic Scene Classification History")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            startActivity(Intent.createChooser(shareIntent, getString(R.string.history_export)))
            Toast.makeText(this, getString(R.string.history_saved), Toast.LENGTH_SHORT).show()
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error saving history", e)
            Toast.makeText(this, getString(R.string.history_save_error), Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Helper-Funktion für Float-Formatierung
     */
    private fun Float.toFixed(decimals: Int): String {
        return String.format(Locale.getDefault(), "%.${decimals}f", this)
    }
    
    /**
     * Exportiert alle Vorhersagen als CSV und teilt sie (inkl. Email)
     */
    private fun exportAllPredictions() {
        viewModel.exportPredictions { file ->
            if (file != null) {
                shareCsvFile(file)
            } else {
                Toast.makeText(this, getString(R.string.export_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * Teilt CSV-Datei (Email, Drive, etc.)
     */
    private fun shareCsvFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Acoustic Scene Predictions - ${file.nameWithoutExtension}")
                putExtra(Intent.EXTRA_TEXT, "Anbei die exportierten Vorhersagen.\n\nGesamt: ${viewModel.totalPredictionsCount.value} Vorhersagen")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_csv)))
            Toast.makeText(this, getString(R.string.csv_exported), Toast.LENGTH_SHORT).show()
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Share failed", e)
            Toast.makeText(this, getString(R.string.share_failed, e.message), Toast.LENGTH_LONG).show()
        }
    }
    
    /**
     * Zeigt Statistik-Dialog
     */
    private fun showStatisticsDialog() {
        val stats = viewModel.statistics.value
        
        val message = buildString {
            append("📊 Statistiken\n\n")
            append("Gesamt: ${stats.totalCount} Vorhersagen\n")
            append("Heute: ${stats.todayCount} Vorhersagen\n\n")
            append("Ø Konfidenz: ${String.format("%.1f", stats.averageConfidence)}%\n")
            append("Ø Inferenz: ${String.format("%.0f", stats.averageInferenceTimeMs)}ms\n\n")
            append("Erste: ${stats.getFormattedFirstPrediction()}\n")
            append("Letzte: ${stats.getFormattedLastPrediction()}\n\n")
            
            if (stats.classDistribution.isNotEmpty()) {
                append("Verteilung:\n")
                stats.classDistribution.entries.sortedByDescending { it.value }.forEach { (scene, count) ->
                    append("${scene.emoji} ${scene.labelShort}: $count\n")
                }
            }
        }
        
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.statistics))
            .setMessage(message)
            .setPositiveButton(getString(R.string.ok), null)
            .setNeutralButton(getString(R.string.export)) { _, _ -> exportAllPredictions() }
            .setNegativeButton(getString(R.string.clear)) { _, _ -> showClearDialog() }
            .show()
    }
    
    /**
     * Zeigt Dialog zum Löschen von Vorhersagen
     */
    private fun showClearDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.clear_predictions))
            .setMessage(getString(R.string.clear_predictions_message))
            .setPositiveButton(getString(R.string.clear_all)) { _, _ ->
                viewModel.clearAllPredictions()
                Toast.makeText(this, getString(R.string.predictions_cleared), Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(getString(R.string.clear_old)) { _, _ ->
                viewModel.clearOldPredictions(7)
                Toast.makeText(this, getString(R.string.old_predictions_cleared), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    
    /**
     * Zeigt eine Fehlermeldung an
     */
    private fun showError(message: String) {
        // Fehlermeldung wird bereits im Status-Label angezeigt
        // Optional: Snackbar oder Toast für zusätzliches Feedback
    }
    
    /**
     * Prüft, ob Audio-Permission vorhanden ist
     */
    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Prüft Permissions beim Start
     */
    private fun checkPermissions() {
        if (!hasAudioPermission()) {
            // Permission wird beim Klick auf Start angefordert
        }
        // Prüfe Batterie-Optimierung
        checkBatteryOptimization()
    }

    /**
     * Prüft, ob die App von der Batterie-Optimierung ausgenommen ist.
     * KRITISCH für lückenlose Hintergrund-Aufnahmen!
     *
     * Ohne diese Ausnahme wird Android die App im Doze-Mode einschränken,
     * was zu Datenlücken führt.
     */
    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val packageName = packageName

            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                // App ist NICHT von Batterie-Optimierung ausgenommen
                showBatteryOptimizationDialog()
            } else {
                android.util.Log.d(TAG, "Battery optimization already disabled - good!")
            }
        }
    }

    /**
     * Zeigt einen Dialog, der den Benutzer bittet, die Batterie-Optimierung zu deaktivieren.
     */
    private fun showBatteryOptimizationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Wichtig: Batterie-Optimierung")
            .setMessage(
                "Für lückenlose Aufnahmen im Hintergrund muss die Batterie-Optimierung " +
                "für diese App deaktiviert werden.\n\n" +
                "Ohne diese Einstellung wird Android die App nach einiger Zeit einschränken " +
                "und es entstehen Datenlücken.\n\n" +
                "Möchtest du die Einstellung jetzt ändern?"
            )
            .setPositiveButton("Ja, deaktivieren") { _, _ ->
                requestBatteryOptimizationExemption()
            }
            .setNegativeButton("Später") { dialog, _ ->
                dialog.dismiss()
                Toast.makeText(
                    this,
                    "Hinweis: Ohne diese Einstellung können Datenlücken entstehen!",
                    Toast.LENGTH_LONG
                ).show()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Öffnet den System-Dialog zum Deaktivieren der Batterie-Optimierung.
     */
    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Could not open battery optimization settings", e)
                // Fallback: Öffne die allgemeinen Batterie-Einstellungen
                try {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(intent)
                    Toast.makeText(
                        this,
                        "Bitte suche '${getString(R.string.app_name)}' und wähle 'Nicht optimieren'",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e2: Exception) {
                    android.util.Log.e(TAG, "Could not open battery settings", e2)
                    Toast.makeText(
                        this,
                        "Bitte deaktiviere die Batterie-Optimierung manuell in den Einstellungen",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
    
    /**
     * Fordert Audio-Permission an
     */
    private fun requestAudioPermission() {
        if (ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                Manifest.permission.RECORD_AUDIO
            )
        ) {
            // Zeige Erklärung
            AlertDialog.Builder(this)
                .setTitle(R.string.permission_audio_required)
                .setMessage(R.string.permission_audio_explanation)
                .setPositiveButton(R.string.ok) { _, _ ->
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.RECORD_AUDIO),
                        PERMISSION_REQUEST_CODE
                    )
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                PERMISSION_REQUEST_CODE
            )
        }
    }
    
    /**
     * Callback für Permission-Request
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted, starte Klassifikation
                viewModel.startClassification()
            } else {
                // Permission denied
                AlertDialog.Builder(this)
                    .setTitle(R.string.permission_denied)
                    .setMessage(R.string.permission_audio_explanation)
                    .setPositiveButton(R.string.ok, null)
                    .show()
            }
        }
    }
}