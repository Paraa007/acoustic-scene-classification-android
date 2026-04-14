package com.fzi.acousticscene.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Dialog
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.Gravity
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.fzi.acousticscene.R
import com.fzi.acousticscene.model.ClassificationResult
import com.fzi.acousticscene.model.ModelConfig
import com.fzi.acousticscene.model.RecordingMode
import com.fzi.acousticscene.model.SceneClass
import com.fzi.acousticscene.util.ModelDisplayNameHelper
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * RecordingFragment - Recording and classification UI
 * Shared between User Mode and Dev Mode tabs (via is_dev_mode argument)
 */
class RecordingFragment : Fragment() {

    companion object {
        private const val TAG = "RecordingFragment"
        private const val ARG_IS_DEV_MODE = "is_dev_mode"
    }

    // Separate ViewModel per mode - User Mode and Dev Mode don't share state
    private lateinit var viewModel: MainViewModel

    // UI Components
    private lateinit var modeStandardButton: MaterialButton
    private lateinit var modeFastButton: MaterialButton
    private lateinit var modeLongButton: MaterialButton
    private lateinit var modeAvgButton: MaterialButton
    private lateinit var startStopButton: MaterialButton
    private lateinit var statusLabel: TextView
    private lateinit var modelStatusLabel: TextView
    private lateinit var headerProcessingLabel: TextView
    private lateinit var recordingProgressBar: ProgressBar
    private lateinit var timerText: TextView
    private lateinit var confidenceCircleView: com.fzi.acousticscene.ui.ConfidenceCircleView
    private lateinit var ripplePulseView: com.fzi.acousticscene.ui.RipplePulseView
    private lateinit var volumeLevelText: TextView
    private lateinit var currentSceneLabel: TextView
    private lateinit var predictionsCard: MaterialCardView
    private lateinit var predictionsContainer: LinearLayout
    private lateinit var statisticsCard: MaterialCardView
    private lateinit var totalClassificationsText: TextView
    private lateinit var avgInferenceTimeText: TextView
    private lateinit var recentPredictionsCard: MaterialCardView
    private lateinit var recentPredictionsContainer: LinearLayout

    // Volume Graph Components
    private lateinit var volumeGraphCard: MaterialCardView
    private lateinit var switchVolumeGraph: MaterialSwitch
    private lateinit var volumeLineChartView: com.fzi.acousticscene.ui.VolumeLineChartView
    private var volumeGraphJob: Job? = null
    private var isVolumeGraphActive: Boolean = false

    // Per-Second Circles Components (AVERAGE mode only)
    private lateinit var perSecondCirclesCard: MaterialCardView
    private lateinit var switchPerSecondCircles: MaterialSwitch
    private lateinit var perSecondCirclesContainer: LinearLayout
    private lateinit var perSecondRow1: LinearLayout
    private lateinit var perSecondRow2: LinearLayout
    private var perSecondCircleViews: List<ConfidenceCircleView> = emptyList()
    private var perSecondLabelViews: List<TextView> = emptyList()
    private var isPerSecondCirclesActive: Boolean = false

    private var isDevMode: Boolean = false

    // Permission launcher for POST_NOTIFICATIONS (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result not critical - notification will silently fail if denied */ }

    // Scene Color Map (DCASE 2025 - 8+1 Classes)
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isDevMode = arguments?.getBoolean(ARG_IS_DEV_MODE, false) ?: false

        // Each mode gets its own ViewModel instance (keyed in Activity scope)
        // so User Mode and Dev Mode are completely independent
        val key = if (isDevMode) "dev_mode_vm" else "user_mode_vm"
        viewModel = ViewModelProvider(requireActivity())[key, MainViewModel::class.java]
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_recording, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Configure model based on mode
        configureModel()

        initializeViews(view)
        setupObservers()
    }

    override fun onStart() {
        super.onStart()
        // Resume volume graph collection if active
        if (isVolumeGraphActive && viewModel.isClassifying()) {
            volumeLineChartView.setDrawingEnabled(true)
            startVolumeGraphCollection()
        }
    }

    override fun onStop() {
        super.onStop()
        // Stop volume graph collection when not visible
        stopVolumeGraphCollection()
        if (::volumeLineChartView.isInitialized) {
            volumeLineChartView.setDrawingEnabled(false)
        }
        // Reset session state when navigating away without an active recording
        if (!viewModel.isClassifying()) {
            viewModel.resetSession()
        }
    }

    private fun configureModel() {
        if (isDevMode) {
            // If already recording in dev mode, just resume showing the session (no dialog)
            if (viewModel.isClassifying()) {
                return
            }
            // Must select a model before using Dev Mode
            showModelSelectionDialog()
        } else {
            // User mode - auto-load default model
            if (!viewModel.isClassifying()) {
                val userConfig = ModelConfig.createUserMode()
                viewModel.setModelConfig(userConfig.modelPath, userConfig.modelName, userConfig.numClasses, false)
                viewModel.initializeSession()
            }
        }
    }

    private fun showModelSelectionDialog() {
        val ctx = context ?: return
        val models = listDevModels()

        if (models.isEmpty()) {
            // No dev models found, navigate back to User Mode
            Toast.makeText(ctx, R.string.no_models_found, Toast.LENGTH_SHORT).show()
            navigateToUserMode()
            return
        }

        val dialog = Dialog(ctx, R.style.ModernDialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_model_selection)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        // User MUST choose a model - prevent dismissing by tapping outside or pressing back
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)

        val modelListContainer = dialog.findViewById<LinearLayout>(R.id.modelListContainer)
        val emptyStateText = dialog.findViewById<TextView>(R.id.emptyStateText)
        val btnCancel = dialog.findViewById<MaterialButton>(R.id.btnCancel)

        emptyStateText.visibility = View.GONE
        modelListContainer.visibility = View.VISIBLE

        // Change button text to "Back to User Mode"
        btnCancel.text = getString(R.string.back_to_user_mode)

        models.forEach { modelFileName ->
            val modelItem = createModelItemView(modelFileName) {
                dialog.dismiss()
                val config = ModelConfig.createDevMode(modelFileName)
                Toast.makeText(ctx, getString(R.string.model_info, config.modelName, config.numClasses), Toast.LENGTH_SHORT).show()
                viewModel.setModelConfig(config.modelPath, config.modelName, config.numClasses, true)
                viewModel.initializeSession()
            }
            modelListContainer.addView(modelItem)
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
            navigateToUserMode()
        }

        dialog.show()
    }

    private fun navigateToUserMode() {
        val bottomNav = activity?.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_navigation)
        bottomNav?.selectedItemId = R.id.nav_user_mode
    }

    private fun isOtherModeRecording(): Boolean {
        val otherKey = if (isDevMode) "user_mode_vm" else "dev_mode_vm"
        val otherVm = ViewModelProvider(requireActivity())[otherKey, MainViewModel::class.java]
        return otherVm.isClassifying()
    }

    private fun createModelItemView(modelFileName: String, onClick: () -> Unit): View {
        val ctx = requireContext()
        val numClasses = ModelConfig.getClassCountForModel(modelFileName)
        val displayName = ModelDisplayNameHelper.getDisplayName(ctx, modelFileName)

        val card = MaterialCardView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 12 }
            setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.dialog_surface_light))
            radius = 16f * resources.displayMetrics.density
            cardElevation = 0f
            strokeWidth = 1
            strokeColor = ContextCompat.getColor(ctx, R.color.accent_blue)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(48, 40, 48, 40)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val iconText = TextView(ctx).apply {
            text = "\uD83E\uDDE0"
            textSize = 24f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 32 }
        }

        val textContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val nameText = TextView(ctx).apply {
            text = displayName
            textSize = 16f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
        }

        val classText = TextView(ctx).apply {
            text = getString(R.string.model_classes, numClasses)
            textSize = 12f
            setTextColor(ContextCompat.getColor(ctx, R.color.accent_blue))
        }

        textContainer.addView(nameText)
        textContainer.addView(classText)
        content.addView(iconText)
        content.addView(textContainer)
        card.addView(content)
        return card
    }

    private fun listDevModels(): List<String> {
        return try {
            val assetManager: AssetManager = requireContext().assets
            val files = assetManager.list(ModelConfig.DEV_MODELS_DIR) ?: emptyArray()
            files.filter { it.endsWith(".pt") }.sorted()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun initializeViews(view: View) {
        modeStandardButton = view.findViewById(R.id.modeStandardButton)
        modeFastButton = view.findViewById(R.id.modeFastButton)
        modeLongButton = view.findViewById(R.id.modeLongButton)
        modeAvgButton = view.findViewById(R.id.modeAvgButton)
        startStopButton = view.findViewById(R.id.startStopButton)
        statusLabel = view.findViewById(R.id.statusLabel)
        modelStatusLabel = view.findViewById(R.id.modelStatusLabel)
        headerProcessingLabel = view.findViewById(R.id.headerProcessingLabel)

        modeStandardButton.setOnClickListener {
            viewModel.setRecordingMode(RecordingMode.STANDARD)
            updateModeButtons(RecordingMode.STANDARD)
            updateVolumeGraphForMode(RecordingMode.STANDARD)
        }

        modeFastButton.setOnClickListener {
            viewModel.setRecordingMode(RecordingMode.FAST)
            updateModeButtons(RecordingMode.FAST)
            updateVolumeGraphForMode(RecordingMode.FAST)
        }

        modeLongButton.setOnClickListener {
            viewModel.setRecordingMode(RecordingMode.LONG)
            updateModeButtons(RecordingMode.LONG)
            updateVolumeGraphForMode(RecordingMode.LONG)
            // Request notification permission for evaluation notifications (Android 13+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }

        modeAvgButton.setOnClickListener {
            viewModel.setRecordingMode(RecordingMode.AVERAGE)
            updateModeButtons(RecordingMode.AVERAGE)
            updateVolumeGraphForMode(RecordingMode.AVERAGE)
        }

        // Show Avg button only in Dev Mode
        if (isDevMode) {
            modeAvgButton.visibility = View.VISIBLE
        }

        recordingProgressBar = view.findViewById(R.id.recordingProgressBar)
        timerText = view.findViewById(R.id.timerText)
        confidenceCircleView = view.findViewById(R.id.confidenceCircleView)
        ripplePulseView = view.findViewById(R.id.ripplePulseView)
        volumeLevelText = view.findViewById(R.id.volumeLevelText)
        currentSceneLabel = view.findViewById(R.id.currentSceneLabel)
        predictionsCard = view.findViewById(R.id.predictionsCard)
        predictionsContainer = view.findViewById(R.id.predictionsContainer)
        statisticsCard = view.findViewById(R.id.statisticsCard)
        totalClassificationsText = view.findViewById(R.id.totalClassificationsText)
        avgInferenceTimeText = view.findViewById(R.id.avgInferenceTimeText)
        recentPredictionsCard = view.findViewById(R.id.recentPredictionsCard)
        recentPredictionsContainer = view.findViewById(R.id.recentPredictionsContainer)

        // Volume Graph Components
        volumeGraphCard = view.findViewById(R.id.volumeGraphCard)
        switchVolumeGraph = view.findViewById(R.id.switchVolumeGraph)
        volumeLineChartView = view.findViewById(R.id.volumeLineChartView)

        // Initialize volume graph with current recording mode duration
        volumeLineChartView.setMaxDuration(viewModel.getRecordingMode().durationSeconds.toFloat())

        // Per-Second Circles Components
        perSecondCirclesCard = view.findViewById(R.id.perSecondCirclesCard)
        switchPerSecondCircles = view.findViewById(R.id.switchPerSecondCircles)
        perSecondCirclesContainer = view.findViewById(R.id.perSecondCirclesContainer)
        perSecondRow1 = view.findViewById(R.id.perSecondRow1)
        perSecondRow2 = view.findViewById(R.id.perSecondRow2)

        // Create 10 small circle items programmatically
        val circles = mutableListOf<ConfidenceCircleView>()
        val labels = mutableListOf<TextView>()
        val ctx = requireContext()
        for (i in 0 until 10) {
            val itemLayout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 2.dpToPx()
                    marginEnd = 2.dpToPx()
                }
            }
            val circle = ConfidenceCircleView(ctx).apply {
                setTargetSize(52)
            }
            val label = TextView(ctx).apply {
                text = "${i + 1}"
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            }
            itemLayout.addView(circle)
            itemLayout.addView(label)

            if (i < 5) perSecondRow1.addView(itemLayout)
            else perSecondRow2.addView(itemLayout)

            circles.add(circle)
            labels.add(label)
        }
        perSecondCircleViews = circles
        perSecondLabelViews = labels

        // Show per-second card only in Dev Mode when AVERAGE is selected
        if (isDevMode && viewModel.getRecordingMode() == RecordingMode.AVERAGE) {
            perSecondCirclesCard.visibility = View.VISIBLE
        }

        // Per-second circles toggle listener
        switchPerSecondCircles.setOnCheckedChangeListener { _, isChecked ->
            isPerSecondCirclesActive = isChecked
            perSecondCirclesContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (!isChecked) {
                // Reset all circles
                perSecondCircleViews.forEach { it.setConfidence(0f, animate = false) }
                perSecondLabelViews.forEachIndexed { idx, tv -> tv.text = "${idx + 1}" }
            }
        }

        // Switch listener for volume graph
        switchVolumeGraph.setOnCheckedChangeListener { buttonView, isChecked ->
            val isRecordingActive = viewModel.isClassifying()

            if (isRecordingActive) {
                if (isChecked) {
                    buttonView.isChecked = false
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.live_analysis_late_open),
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnCheckedChangeListener
                } else {
                    buttonView.isChecked = true
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.live_analysis_close_warning),
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnCheckedChangeListener
                }
            }

            isVolumeGraphActive = isChecked
            if (isChecked) {
                volumeLineChartView.visibility = View.VISIBLE
                volumeLineChartView.setDrawingEnabled(true)
                volumeLineChartView.clearData()
            } else {
                stopVolumeGraphCollection()
                volumeLineChartView.setDrawingEnabled(false)
                volumeLineChartView.clearData()
                volumeLineChartView.visibility = View.GONE
            }
        }

        startStopButton.setOnClickListener {
            if (viewModel.isClassifying()) {
                viewModel.stopClassification()
                (activity as? MainActivity)?.stopClassificationService()
                stopVolumeGraphCollection()
                volumeLineChartView.clearData()
                resetPerSecondCircles()
            } else {
                // Block if the other mode already has an active recording
                if (isOtherModeRecording()) {
                    Toast.makeText(requireContext(), R.string.recording_active_other_mode, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (hasAudioPermission()) {
                    viewModel.setAnalysisViewStateAtRecordingStart(isVolumeGraphActive)
                    (activity as? MainActivity)?.startClassificationService()
                    viewModel.startClassification()
                    if (isVolumeGraphActive) {
                        volumeLineChartView.clearData()
                        startVolumeGraphCollection()
                    }
                } else {
                    requestAudioPermission()
                }
            }
        }

        // Update header title based on mode
        val headerTitle = view.findViewById<TextView>(R.id.headerTitle)
        headerTitle.text = if (isDevMode) {
            getString(R.string.dev_mode)
        } else {
            getString(R.string.app_name_full)
        }
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                updateUI(state)
            }
        }
    }

    private fun updateUI(state: UiState) {
        updateAppState(state.appState)
        updateModelStatus(state.isModelLoaded)

        // In AVERAGE mode during processing, show running average in the big circle
        if (state.recordingMode == RecordingMode.AVERAGE
            && state.appState is AppState.Processing
            && state.runningAverageResult != null
        ) {
            updateCurrentResult(state.runningAverageResult)
            updatePredictions(state.runningAverageResult)
        } else {
            updateCurrentResult(state.currentResult)
            updatePredictions(state.currentResult)
        }

        updateStatistics(state.totalClassifications, state.averageInferenceTime)
        updateRecentPredictions(state.history)
        updateModeButtons(state.recordingMode)
        updateVolumeDisplay(state.currentVolume, state.appState)
        updatePerSecondCircles(state.perSecondResults)
        updatePerSecondCardVisibility(state.recordingMode)

        if (state.errorMessage != null) {
            // Error shown in status label
        }
    }

    private fun updateVolumeDisplay(volume: Float, appState: AppState) {
        when (appState) {
            is AppState.Recording -> {
                ripplePulseView.setVolume(volume)
                volumeLevelText.visibility = View.VISIBLE
                val volumePercent = (volume * 100).toInt()
                volumeLevelText.text = "${getString(R.string.volume)}: $volumePercent"
            }
            else -> {
                ripplePulseView.clear()
                volumeLevelText.visibility = View.GONE
            }
        }
    }

    private fun updateModeButtons(mode: RecordingMode) {
        val ctx = context ?: return
        val activeColor = ContextCompat.getColor(ctx, R.color.accent_green)
        val inactiveColor = ContextCompat.getColor(ctx, R.color.surface_variant)
        val activeTextColor = ContextCompat.getColor(ctx, R.color.on_primary)
        val inactiveTextColor = ContextCompat.getColor(ctx, R.color.text_secondary)

        modeStandardButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(inactiveColor))
        modeStandardButton.setTextColor(inactiveTextColor)
        modeFastButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(inactiveColor))
        modeFastButton.setTextColor(inactiveTextColor)
        modeLongButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(inactiveColor))
        modeLongButton.setTextColor(inactiveTextColor)
        modeAvgButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(inactiveColor))
        modeAvgButton.setTextColor(inactiveTextColor)

        when (mode) {
            RecordingMode.STANDARD -> {
                modeStandardButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(activeColor))
                modeStandardButton.setTextColor(activeTextColor)
            }
            RecordingMode.FAST -> {
                modeFastButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(activeColor))
                modeFastButton.setTextColor(activeTextColor)
            }
            RecordingMode.LONG -> {
                modeLongButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(activeColor))
                modeLongButton.setTextColor(activeTextColor)
            }
            RecordingMode.AVERAGE -> {
                modeAvgButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(activeColor))
                modeAvgButton.setTextColor(activeTextColor)
            }
        }
    }

    private fun updateAppState(appState: AppState) {
        val ctx = context ?: return

        // Show processing indicator in header instead of status card
        headerProcessingLabel.visibility = if (appState is AppState.Processing) View.VISIBLE else View.GONE

        when (appState) {
            is AppState.Idle -> {
                statusLabel.text = getString(R.string.status_idle)
                statusLabel.setTextColor(ContextCompat.getColor(ctx, R.color.status_idle))
                startStopButton.isEnabled = false
                recordingProgressBar.visibility = View.GONE
                timerText.visibility = View.GONE
            }
            is AppState.Loading -> {
                statusLabel.text = getString(R.string.status_idle)
                statusLabel.setTextColor(ContextCompat.getColor(ctx, R.color.status_idle))
                startStopButton.isEnabled = false
                recordingProgressBar.visibility = View.GONE
                timerText.visibility = View.GONE
            }
            is AppState.Ready -> {
                statusLabel.text = getString(R.string.status_idle)
                statusLabel.setTextColor(ContextCompat.getColor(ctx, R.color.status_idle))
                startStopButton.isEnabled = true
                startStopButton.text = getString(R.string.start_recording)
                recordingProgressBar.visibility = View.GONE
                timerText.visibility = View.GONE
            }
            is AppState.Recording -> {
                statusLabel.text = getString(R.string.status_recording)
                statusLabel.setTextColor(ContextCompat.getColor(ctx, R.color.status_recording))
                startStopButton.isEnabled = true
                startStopButton.text = getString(R.string.stop_recording)

                val seconds = appState.secondsRemaining
                timerText.text = getString(R.string.timer_countdown, seconds)
                timerText.visibility = View.VISIBLE

                val recordingProgress = viewModel.uiState.value.recordingProgress
                val progress = (recordingProgress * 100).toInt()
                setProgressAnimated(recordingProgressBar, progress)
                recordingProgressBar.visibility = View.VISIBLE
            }
            is AppState.Processing -> {
                // Status text stays as previous state (no change to statusLabel)
                startStopButton.isEnabled = true
                startStopButton.text = getString(R.string.stop_recording)
                recordingProgressBar.visibility = View.GONE
                timerText.visibility = View.GONE
            }
            is AppState.Paused -> {
                val minutes = appState.minutesRemaining
                statusLabel.text = getString(R.string.pause_minutes, minutes)
                statusLabel.setTextColor(ContextCompat.getColor(ctx, R.color.status_idle))
                startStopButton.isEnabled = true
                startStopButton.text = getString(R.string.stop_recording)
                recordingProgressBar.visibility = View.GONE
                timerText.text = "$minutes ${getString(R.string.min)}"
                timerText.visibility = View.VISIBLE
            }
            is AppState.Error -> {
                statusLabel.text = appState.message
                statusLabel.setTextColor(ContextCompat.getColor(ctx, R.color.error))
                startStopButton.isEnabled = true
                startStopButton.text = getString(R.string.start_recording)
                recordingProgressBar.visibility = View.GONE
                timerText.visibility = View.GONE
            }
        }
    }

    private fun updateModelStatus(isLoaded: Boolean) {
        val ctx = context ?: return
        if (isLoaded) {
            if (isDevMode) {
                val displayName = ModelDisplayNameHelper.getDisplayName(ctx, viewModel.modelName)
                modelStatusLabel.text = "\u2713 $displayName"
            } else {
                modelStatusLabel.text = "\u2713 ${getString(R.string.model_loaded)}"
            }
            modelStatusLabel.setTextColor(ContextCompat.getColor(ctx, R.color.accent_green))
        } else {
            modelStatusLabel.text = "\u26A0 ${getString(R.string.loading_model)}"
            modelStatusLabel.setTextColor(ContextCompat.getColor(ctx, R.color.error))
        }
    }

    private fun updateCurrentResult(result: ClassificationResult?) {
        val ctx = context ?: return
        if (result != null) {
            confidenceCircleView.setConfidence(result.confidence, animate = true)
            currentSceneLabel.text = "${result.sceneClass.emoji} ${result.sceneClass.label}"
            currentSceneLabel.visibility = View.VISIBLE
            val colorRes = sceneColors[result.sceneClass] ?: R.color.accent_green
            currentSceneLabel.setTextColor(ContextCompat.getColor(ctx, colorRes))
        } else {
            currentSceneLabel.visibility = View.GONE
            confidenceCircleView.setConfidence(0f, animate = false)
        }
    }

    private fun updatePredictions(result: ClassificationResult?) {
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

    private fun createPredictionView(scene: SceneClass, confidence: Float, rank: Int): View {
        val ctx = requireContext()
        val card = com.google.android.material.card.MaterialCardView(ctx)
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        card.setPadding(16, 12, 16, 12)
        card.cardElevation = 0f
        card.radius = 12f
        card.strokeWidth = 0
        card.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.surface_variant))

        val container = LinearLayout(ctx)
        container.orientation = LinearLayout.VERTICAL

        val topRow = LinearLayout(ctx)
        topRow.orientation = LinearLayout.HORIZONTAL
        topRow.gravity = android.view.Gravity.CENTER_VERTICAL

        val rankText = TextView(ctx)
        rankText.text = "$rank."
        rankText.textSize = 16f
        rankText.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
        rankText.setPadding(0, 0, 12, 0)

        val sceneText = TextView(ctx)
        sceneText.text = "${scene.emoji} ${scene.label}"
        sceneText.textSize = 14f
        val colorRes = sceneColors[scene] ?: R.color.accent_green
        sceneText.setTextColor(ContextCompat.getColor(ctx, colorRes))
        sceneText.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

        val confidenceText = TextView(ctx)
        confidenceText.text = "${(confidence * 100).toInt()}%"
        confidenceText.textSize = 16f
        confidenceText.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
        confidenceText.setTypeface(null, android.graphics.Typeface.BOLD)
        confidenceText.setPadding(12, 0, 0, 0)

        topRow.addView(rankText)
        topRow.addView(sceneText)
        topRow.addView(confidenceText)

        val progressBar = android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal)
        progressBar.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            8.dpToPx()
        )
        progressBar.max = 100
        progressBar.progress = (confidence * 100).toInt()
        progressBar.progressDrawable = ContextCompat.getDrawable(ctx, android.R.drawable.progress_horizontal)
        progressBar.progressTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(ctx, colorRes)
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

    private fun setProgressAnimated(progressBar: ProgressBar, progressTo: Int) {
        ObjectAnimator.ofInt(progressBar, "progress", progressBar.progress, progressTo)
            .setDuration(300)
            .apply {
                interpolator = DecelerateInterpolator()
                start()
            }
    }

    private fun updateStatistics(total: Int, avgTime: Long) {
        if (total > 0) {
            statisticsCard.visibility = View.VISIBLE
            totalClassificationsText.text = total.toString()
            val seconds = avgTime / 1000.0
            avgInferenceTimeText.text = String.format("%.2f s", seconds)
        } else {
            statisticsCard.visibility = View.GONE
        }
    }

    private fun updateRecentPredictions(history: List<ClassificationResult>) {
        val ctx = context ?: return
        recentPredictionsContainer.removeAllViews()

        if (history.isNotEmpty()) {
            recentPredictionsCard.visibility = View.VISIBLE
            val last5 = history.reversed().take(5)

            last5.forEach { result ->
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, 8, 0, 8)
                }

                val sceneText = TextView(ctx).apply {
                    text = "${result.sceneClass.emoji} ${result.sceneClass.label}"
                    textSize = 13f
                    val colorRes = sceneColors[result.sceneClass] ?: R.color.primary
                    setTextColor(ContextCompat.getColor(ctx, colorRes))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    maxLines = 1
                }

                val confText = TextView(ctx).apply {
                    text = "${(result.confidence * 100).toInt()}%"
                    textSize = 13f
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(16, 0, 0, 0)
                }

                row.addView(sceneText)
                row.addView(confText)
                recentPredictionsContainer.addView(row)
            }
        } else {
            recentPredictionsCard.visibility = View.GONE
        }
    }

    private fun startVolumeGraphCollection() {
        volumeGraphJob?.cancel()
        volumeGraphJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isVolumeGraphActive) {
                val currentVolume = viewModel.uiState.value.currentVolume
                volumeLineChartView.addDataPoint(currentVolume)
                delay(com.fzi.acousticscene.ui.VolumeLineChartView.DATA_INTERVAL_MS)
            }
        }
    }

    private fun stopVolumeGraphCollection() {
        volumeGraphJob?.cancel()
        volumeGraphJob = null
    }

    private fun updateVolumeGraphForMode(mode: RecordingMode) {
        volumeLineChartView.setMaxDuration(mode.durationSeconds.toFloat())
        volumeLineChartView.clearData()
    }

    private fun updatePerSecondCircles(perSecondResults: List<ClassificationResult?>) {
        if (!isPerSecondCirclesActive) return

        perSecondResults.forEachIndexed { index, result ->
            if (index < perSecondCircleViews.size) {
                if (result != null) {
                    perSecondCircleViews[index].setConfidence(result.confidence, animate = true)
                    perSecondLabelViews[index].text = "${result.sceneClass.emoji}\n${(result.confidence * 100).toInt()}%"
                } else {
                    perSecondCircleViews[index].setConfidence(0f, animate = false)
                    perSecondLabelViews[index].text = "${index + 1}"
                }
            }
        }
    }

    private fun resetPerSecondCircles() {
        perSecondCircleViews.forEach { it.setConfidence(0f, animate = false) }
        perSecondLabelViews.forEachIndexed { idx, tv -> tv.text = "${idx + 1}" }
    }

    private fun updatePerSecondCardVisibility(mode: RecordingMode) {
        if (!::perSecondCirclesCard.isInitialized) return
        if (isDevMode && mode == RecordingMode.AVERAGE) {
            perSecondCirclesCard.visibility = View.VISIBLE
        } else {
            perSecondCirclesCard.visibility = View.GONE
            if (::switchPerSecondCircles.isInitialized) {
                switchPerSecondCircles.isChecked = false
            }
        }
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestAudioPermission() {
        requestPermissions(
            arrayOf(Manifest.permission.RECORD_AUDIO),
            PERMISSION_REQUEST_CODE
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (isOtherModeRecording()) {
                    Toast.makeText(requireContext(), R.string.recording_active_other_mode, Toast.LENGTH_LONG).show()
                    return
                }
                viewModel.setAnalysisViewStateAtRecordingStart(isVolumeGraphActive)
                (activity as? MainActivity)?.startClassificationService()
                viewModel.startClassification()
                if (isVolumeGraphActive) {
                    volumeLineChartView.clearData()
                    startVolumeGraphCollection()
                }
            } else {
                ModernDialogHelper.showConfirmDialog(
                    context = requireContext(),
                    title = getString(R.string.permission_denied),
                    message = getString(R.string.permission_audio_explanation),
                    confirmText = getString(R.string.ok),
                    cancelText = "",
                    onConfirm = {}
                )
            }
        }
    }
}

private const val PERMISSION_REQUEST_CODE = 1001
