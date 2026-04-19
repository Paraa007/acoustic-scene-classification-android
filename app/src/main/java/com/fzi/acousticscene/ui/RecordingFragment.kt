package com.fzi.acousticscene.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.ColorStateList
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
import com.fzi.acousticscene.model.LongSubMode
import com.fzi.acousticscene.model.ModelConfig
import com.fzi.acousticscene.model.RecordingCategory
import com.fzi.acousticscene.model.RecordingMode
import com.fzi.acousticscene.model.SceneClass
import com.google.android.material.checkbox.MaterialCheckBox
import com.fzi.acousticscene.util.ModelDisplayNameHelper
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    private lateinit var categoryContinuousButton: MaterialButton
    private lateinit var categoryIntervalButton: MaterialButton
    private lateinit var subModeButtonRow: LinearLayout
    private lateinit var modeTimeline: ModeTimelineView
    private lateinit var modeDescription: TextView
    private val subModeButtons: MutableMap<RecordingMode, MaterialButton> = mutableMapOf()
    private var currentCategory: RecordingCategory = RecordingCategory.CONTINUOUS
    private lateinit var startStopButton: MaterialButton
    private lateinit var pauseResumeButton: MaterialButton
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

    // Pending evaluation card (foreground LONG-mode prompt, 5-min countdown)
    private lateinit var pendingEvaluationCard: MaterialCardView
    private lateinit var pendingEvaluationTitle: TextView
    private lateinit var pendingEvaluationSubtitle: TextView
    private lateinit var pendingEvaluationButton: MaterialButton
    private var pendingEvaluationTickerJob: Job? = null
    private var userPauseCountdownJob: Job? = null

    // Per-Second Circles Components (AVERAGE mode only)
    private lateinit var perSecondCirclesCard: MaterialCardView
    private lateinit var switchPerSecondCircles: MaterialSwitch
    private lateinit var perSecondCirclesContainer: LinearLayout
    private lateinit var perSecondRow1: LinearLayout
    private lateinit var perSecondRow2: LinearLayout
    private var perSecondCircleViews: List<ConfidenceCircleView> = emptyList()
    private var perSecondLabelViews: List<TextView> = emptyList()
    private var isPerSecondCirclesActive: Boolean = false

    // LONG sub-mode chooser + triangle circles
    private lateinit var longSubModeChooser: LinearLayout
    private lateinit var cbLongStandard: MaterialCheckBox
    private lateinit var cbLongFast: MaterialCheckBox
    private lateinit var cbLongAverage: MaterialCheckBox
    private lateinit var mainCircleSubLabel: TextView
    private lateinit var mainCircleFrame: android.widget.FrameLayout
    private lateinit var longTriangleRow: LinearLayout
    private lateinit var longFastCircleWrap: LinearLayout
    private lateinit var longAvgCircleWrap: LinearLayout
    private lateinit var longFastCircle: ConfidenceCircleView
    private lateinit var longAvgCircle: ConfidenceCircleView
    private lateinit var longFastLabel: TextView
    private lateinit var longAvgLabel: TextView

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
            // Registry fallback: process survived but ViewModel was cleared — restore config
            // from the active-session registry and skip the picker.
            val activeDev = com.fzi.acousticscene.data.ActiveSessionRegistry.get(isDevMode = true)
            if (activeDev != null) {
                viewModel.setModelConfig(activeDev.modelPath, activeDev.modelName, activeDev.numClasses, true)
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
        categoryContinuousButton = view.findViewById(R.id.categoryContinuousButton)
        categoryIntervalButton = view.findViewById(R.id.categoryIntervalButton)
        subModeButtonRow = view.findViewById(R.id.subModeButtonRow)
        modeTimeline = view.findViewById(R.id.modeTimeline)
        modeDescription = view.findViewById(R.id.modeDescription)
        startStopButton = view.findViewById(R.id.startStopButton)
        pauseResumeButton = view.findViewById(R.id.pauseResumeButton)
        pauseResumeButton.setOnClickListener {
            if (viewModel.isUserPaused()) viewModel.resumeClassification()
            else showPauseDurationPicker()
        }
        statusLabel = view.findViewById(R.id.statusLabel)
        modelStatusLabel = view.findViewById(R.id.modelStatusLabel)
        headerProcessingLabel = view.findViewById(R.id.headerProcessingLabel)

        categoryContinuousButton.setOnClickListener { selectCategory(RecordingCategory.CONTINUOUS, persist = true) }
        categoryIntervalButton.setOnClickListener { selectCategory(RecordingCategory.INTERVAL, persist = true) }

        // LONG sub-mode chooser + triangle circles
        longSubModeChooser = view.findViewById(R.id.longSubModeChooser)
        cbLongStandard = view.findViewById(R.id.cbLongStandard)
        cbLongFast = view.findViewById(R.id.cbLongFast)
        cbLongAverage = view.findViewById(R.id.cbLongAverage)
        mainCircleSubLabel = view.findViewById(R.id.mainCircleSubLabel)
        mainCircleFrame = view.findViewById(R.id.mainCircleFrame)
        longTriangleRow = view.findViewById(R.id.longTriangleRow)
        longFastCircleWrap = view.findViewById(R.id.longFastCircleWrap)
        longAvgCircleWrap = view.findViewById(R.id.longAvgCircleWrap)
        longFastCircle = view.findViewById(R.id.longFastCircle)
        longAvgCircle = view.findViewById(R.id.longAvgCircle)
        longFastCircle.setTargetSize(60)
        longAvgCircle.setTargetSize(60)
        longFastLabel = view.findViewById(R.id.longFastLabel)
        longAvgLabel = view.findViewById(R.id.longAvgLabel)

        // Restore persisted LONG sub-mode selection (Dev Mode only feature for now)
        if (isDevMode) {
            val persisted = loadPersistedLongSubs()
            viewModel.setLongSubs(persisted)
            cbLongFast.isChecked = LongSubMode.FAST in persisted
            cbLongAverage.isChecked = LongSubMode.AVERAGE in persisted
        }
        cbLongFast.setOnCheckedChangeListener { _, _ ->
            if (suppressSubModeListener) return@setOnCheckedChangeListener
            viewModel.toggleLongSub(LongSubMode.FAST)
            persistLongSubs(viewModel.uiState.value.selectedLongSubs)
        }
        cbLongAverage.setOnCheckedChangeListener { _, _ ->
            if (suppressSubModeListener) return@setOnCheckedChangeListener
            viewModel.toggleLongSub(LongSubMode.AVERAGE)
            persistLongSubs(viewModel.uiState.value.selectedLongSubs)
        }

        pendingEvaluationCard = view.findViewById(R.id.pendingEvaluationCard)
        pendingEvaluationTitle = view.findViewById(R.id.pendingEvaluationTitle)
        pendingEvaluationSubtitle = view.findViewById(R.id.pendingEvaluationSubtitle)
        pendingEvaluationButton = view.findViewById(R.id.pendingEvaluationButton)

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

        // Restore last mode selection (category + mode) from prefs
        val lastMode = loadPersistedMode()
        selectCategory(lastMode.category, persist = false)
        selectMode(lastMode, persist = false)
    }

    private fun prefsKey(): String = if (isDevMode) "last_mode_dev" else "last_mode_user"

    private fun loadPersistedMode(): RecordingMode {
        val prefs = requireContext().getSharedPreferences("mode_prefs", Context.MODE_PRIVATE)
        val name = prefs.getString(prefsKey(), null) ?: return RecordingMode.DEFAULT
        return try {
            val m = RecordingMode.valueOf(name)
            if (!isDevMode && m.devOnly) RecordingMode.DEFAULT else m
        } catch (e: Exception) {
            RecordingMode.DEFAULT
        }
    }

    private fun persistMode(mode: RecordingMode) {
        requireContext().getSharedPreferences("mode_prefs", Context.MODE_PRIVATE)
            .edit().putString(prefsKey(), mode.name).apply()
    }

    private fun longSubsPrefsKey(): String =
        if (isDevMode) "long_sub_modes_dev" else "long_sub_modes_user"

    private fun loadPersistedLongSubs(): Set<LongSubMode> {
        val prefs = requireContext().getSharedPreferences("mode_prefs", Context.MODE_PRIVATE)
        val stored = prefs.getStringSet(longSubsPrefsKey(), null) ?: return setOf(LongSubMode.STANDARD)
        val parsed = stored.mapNotNull {
            runCatching { LongSubMode.valueOf(it) }.getOrNull()
        }.toSet()
        return parsed + LongSubMode.STANDARD
    }

    private fun persistLongSubs(subs: Set<LongSubMode>) {
        requireContext().getSharedPreferences("mode_prefs", Context.MODE_PRIVATE)
            .edit().putStringSet(longSubsPrefsKey(), subs.map { it.name }.toSet()).apply()
    }

    private fun selectCategory(cat: RecordingCategory, persist: Boolean) {
        currentCategory = cat
        val ctx = context ?: return
        val activeColor = ContextCompat.getColor(ctx, R.color.accent_green)
        val inactiveColor = ContextCompat.getColor(ctx, R.color.surface_variant)
        val activeTextColor = ContextCompat.getColor(ctx, R.color.on_primary)
        val inactiveTextColor = ContextCompat.getColor(ctx, R.color.text_secondary)

        val (sel, other) = if (cat == RecordingCategory.CONTINUOUS)
            categoryContinuousButton to categoryIntervalButton
        else
            categoryIntervalButton to categoryContinuousButton
        sel.backgroundTintList = ColorStateList.valueOf(activeColor)
        sel.setTextColor(activeTextColor)
        other.backgroundTintList = ColorStateList.valueOf(inactiveColor)
        other.setTextColor(inactiveTextColor)

        rebuildSubModeButtons()

        val current = viewModel.getRecordingMode()
        if (current.category != cat) {
            val first = RecordingMode.forCategory(cat, isDevMode).firstOrNull() ?: return
            selectMode(first, persist = persist)
        } else {
            highlightSubMode(current)
            modeTimeline.setMode(current)
            modeDescription.text = descriptionFor(current)
        }
    }

    private fun rebuildSubModeButtons() {
        subModeButtonRow.removeAllViews()
        subModeButtons.clear()
        val ctx = context ?: return
        val modes = RecordingMode.forCategory(currentCategory, isDevMode)
        val inactiveColor = ContextCompat.getColor(ctx, R.color.surface_variant)
        val inactiveTextColor = ContextCompat.getColor(ctx, R.color.text_secondary)
        modes.forEachIndexed { idx, mode ->
            val btn = MaterialButton(ctx).apply {
                text = mode.label
                textSize = 11f
                cornerRadius = 12.dpToPx()
                backgroundTintList = ColorStateList.valueOf(inactiveColor)
                setTextColor(inactiveTextColor)
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = if (idx == 0) 0 else 3.dpToPx()
                    marginEnd = if (idx == modes.size - 1) 0 else 3.dpToPx()
                }
                setOnClickListener { selectMode(mode, persist = true) }
            }
            subModeButtonRow.addView(btn)
            subModeButtons[mode] = btn
        }
    }

    private fun highlightSubMode(mode: RecordingMode) {
        val ctx = context ?: return
        val activeColor = ContextCompat.getColor(ctx, R.color.accent_green)
        val inactiveColor = ContextCompat.getColor(ctx, R.color.surface_variant)
        val activeTextColor = ContextCompat.getColor(ctx, R.color.on_primary)
        val inactiveTextColor = ContextCompat.getColor(ctx, R.color.text_secondary)
        subModeButtons.forEach { (m, btn) ->
            if (m == mode) {
                btn.backgroundTintList = ColorStateList.valueOf(activeColor)
                btn.setTextColor(activeTextColor)
            } else {
                btn.backgroundTintList = ColorStateList.valueOf(inactiveColor)
                btn.setTextColor(inactiveTextColor)
            }
        }
    }

    private fun selectMode(mode: RecordingMode, persist: Boolean) {
        viewModel.setRecordingMode(mode)
        highlightSubMode(mode)
        modeTimeline.setMode(mode)
        modeDescription.text = descriptionFor(mode)
        updateVolumeGraphForMode(mode)
        if (persist && mode == RecordingMode.LONG
            && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (persist) persistMode(mode)
    }

    private fun descriptionFor(mode: RecordingMode): String = getString(
        when (mode) {
            RecordingMode.FAST -> R.string.mode_desc_fast
            RecordingMode.STANDARD -> R.string.mode_desc_standard
            RecordingMode.LONG -> R.string.mode_desc_long
            RecordingMode.AVERAGE -> R.string.mode_desc_avg
        }
    )

    private fun updateLongSubModeUi(state: UiState) {
        if (!::longSubModeChooser.isInitialized) return
        val isLong = state.recordingMode == RecordingMode.LONG
        // Chooser only for Dev Mode + LONG
        longSubModeChooser.visibility = if (isDevMode && isLong) View.VISIBLE else View.GONE

        // Reflect selection into checkboxes (don't trigger listener recursion)
        val subs = state.selectedLongSubs
        syncCheckbox(cbLongFast, LongSubMode.FAST in subs)
        syncCheckbox(cbLongAverage, LongSubMode.AVERAGE in subs)

        // Lock sub-mode selection while a session is active
        val locked = viewModel.isClassifying()
        cbLongFast.isEnabled = !locked
        cbLongAverage.isEnabled = !locked

        // Triangle circles: only when LONG is active and at least one extra sub-mode selected
        val hasExtras = isLong && (LongSubMode.FAST in subs || LongSubMode.AVERAGE in subs)
        longTriangleRow.visibility = if (hasExtras) View.VISIBLE else View.GONE
        longFastCircleWrap.visibility = if (isLong && LongSubMode.FAST in subs) View.VISIBLE else View.GONE
        longAvgCircleWrap.visibility = if (isLong && LongSubMode.AVERAGE in subs) View.VISIBLE else View.GONE

        // Shrink main circle when triangle is on
        val targetMain = if (hasExtras) 150 else 200
        confidenceCircleView.setTargetSize(targetMain)

        // Shrink frame so the Standard sub-label sits close under the circle
        val frameDp = if (hasExtras) 170 else 240
        val density = resources.displayMetrics.density
        val framePx = (frameDp * density).toInt()
        if (mainCircleFrame.layoutParams.height != framePx) {
            mainCircleFrame.layoutParams = mainCircleFrame.layoutParams.apply {
                width = framePx
                height = framePx
            }
        }

        // Hide Top Predictions card in triangle mode — it's ambiguous which circle it belongs to
        if (hasExtras) predictionsCard.visibility = View.GONE

        // Reflect selected subs in the timeline summary
        if (::modeTimeline.isInitialized) modeTimeline.setLongSubs(subs)

        // Main circle sub-label: only in triangle mode (compact colored label like the side circles).
        // When Standard is alone, hide this compact label — the existing big `currentSceneLabel`
        // keeps showing the full "Emoji Class" style.
        mainCircleSubLabel.visibility = if (hasExtras) View.VISIBLE else View.GONE
        val ctx = context
        if (hasExtras && ctx != null) {
            val stdResult = state.longSubResults[LongSubMode.STANDARD] ?: state.currentResult
            if (stdResult != null) {
                mainCircleSubLabel.text =
                    "${getString(R.string.long_sub_label_standard)}\n${stdResult.sceneClass.emoji} ${stdResult.sceneClass.labelShort} ${(stdResult.confidence * 100).toInt()}%"
                val colorRes = sceneColors[stdResult.sceneClass] ?: R.color.accent_green
                mainCircleSubLabel.setTextColor(ContextCompat.getColor(ctx, colorRes))
            } else {
                mainCircleSubLabel.text = getString(R.string.long_sub_label_standard)
                mainCircleSubLabel.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            }
            // Hide the big full-class label when triangle is active (compact label takes over)
            currentSceneLabel.visibility = View.GONE
        }

        // Bind per-sub results
        bindSubCircle(longFastCircle, longFastLabel, state.longSubResults[LongSubMode.FAST], R.string.long_sub_label_fast)
        bindSubCircle(longAvgCircle, longAvgLabel, state.longSubResults[LongSubMode.AVERAGE], R.string.long_sub_label_average)
    }

    private var suppressSubModeListener = false

    private fun syncCheckbox(cb: MaterialCheckBox, checked: Boolean) {
        if (cb.isChecked != checked) {
            suppressSubModeListener = true
            cb.isChecked = checked
            suppressSubModeListener = false
        }
    }

    private fun bindSubCircle(
        circle: ConfidenceCircleView,
        label: TextView,
        result: ClassificationResult?,
        baseLabelRes: Int
    ) {
        val ctx = context ?: return
        if (result != null) {
            circle.setConfidence(result.confidence, animate = true)
            label.text = "${getString(baseLabelRes)}\n${result.sceneClass.emoji} ${result.sceneClass.labelShort} ${(result.confidence * 100).toInt()}%"
            val colorRes = sceneColors[result.sceneClass] ?: R.color.accent_green
            label.setTextColor(ContextCompat.getColor(ctx, colorRes))
        } else {
            circle.setConfidence(0f, animate = false)
            label.text = getString(baseLabelRes)
            label.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
        }
    }

    private fun updatePauseButton(state: UiState) {
        if (!::pauseResumeButton.isInitialized) return
        val show = state.recordingMode == RecordingMode.LONG && viewModel.isClassifying()
        pauseResumeButton.visibility = if (show) View.VISIBLE else View.GONE
        pauseResumeButton.text = getString(
            if (state.isPaused) R.string.resume_recording else R.string.pause_recording
        )
    }

    private fun showPauseDurationPicker() {
        val ctx = context ?: return
        val options = listOf(
            ModernDialogHelper.PauseDurationOption(getString(R.string.pause_duration_no_timer), null),
            ModernDialogHelper.PauseDurationOption(getString(R.string.pause_duration_15_min), 15L * 60_000L),
            ModernDialogHelper.PauseDurationOption(getString(R.string.pause_duration_30_min), 30L * 60_000L),
            ModernDialogHelper.PauseDurationOption(getString(R.string.pause_duration_1_hour), 60L * 60_000L),
            ModernDialogHelper.PauseDurationOption(getString(R.string.pause_duration_2_hours), 2L * 60L * 60_000L),
            ModernDialogHelper.PauseDurationOption(getString(R.string.pause_duration_4_hours), 4L * 60L * 60_000L),
            ModernDialogHelper.PauseDurationOption(getString(R.string.pause_duration_8_hours), 8L * 60L * 60_000L),
            ModernDialogHelper.PauseDurationOption(getString(R.string.pause_duration_16_hours), 16L * 60L * 60_000L)
        )
        ModernDialogHelper.showPauseDurationDialog(
            context = ctx,
            title = getString(R.string.pause_duration_title),
            subtitle = getString(R.string.pause_duration_subtitle),
            options = options
        ) { option ->
            viewModel.pauseClassification(option.durationMs)
        }
    }

    /**
     * While the user is paused with an auto-resume timer, tick every second so
     * the status label and timer text show a live countdown to auto-resume.
     * Cancelled when the state leaves UserPaused or the deadline is cleared.
     */
    private fun updateUserPauseCountdown(state: UiState) {
        val deadline = state.userPauseDeadlineElapsedMs
        val isUserPaused = state.appState is AppState.UserPaused

        if (!isUserPaused || deadline == null) {
            userPauseCountdownJob?.cancel()
            userPauseCountdownJob = null
            return
        }

        if (userPauseCountdownJob?.isActive == true) return

        userPauseCountdownJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val remainingMs = (deadline - android.os.SystemClock.elapsedRealtime())
                    .coerceAtLeast(0L)
                val totalSeconds = (remainingMs / 1000L).toInt()
                val hours = totalSeconds / 3600
                val minutes = (totalSeconds % 3600) / 60
                val seconds = totalSeconds % 60
                val timeStr = if (hours > 0) {
                    "%d:%02d:%02d".format(hours, minutes, seconds)
                } else {
                    "%d:%02d".format(minutes, seconds)
                }
                statusLabel.text = getString(R.string.user_paused_auto_resume, timeStr)
                timerText.text = timeStr
                timerText.visibility = View.VISIBLE
                if (remainingMs == 0L) break
                delay(1000L)
            }
        }
    }

    private fun updatePendingEvaluation(pending: PendingEvaluation?) {
        if (pending == null) {
            pendingEvaluationTickerJob?.cancel()
            pendingEvaluationTickerJob = null
            if (::pendingEvaluationCard.isInitialized) {
                pendingEvaluationCard.visibility = View.GONE
            }
            return
        }

        pendingEvaluationCard.visibility = View.VISIBLE
        pendingEvaluationTitle.text = getString(R.string.evaluation_inapp_message)
        val launchAction = View.OnClickListener {
            val intent = android.content.Intent(requireContext(), EvaluationActivity::class.java).apply {
                putExtra(EvaluationActivity.EXTRA_PREDICTION_ID, pending.predictionId)
                putExtra(EvaluationActivity.EXTRA_MODEL_PREDICTED_CLASS, pending.modelClass.name)
            }
            startActivity(intent)
        }
        pendingEvaluationButton.setOnClickListener(launchAction)
        pendingEvaluationCard.setOnClickListener(launchAction)

        // Start a per-second ticker for the countdown if not already running for this prediction
        pendingEvaluationTickerJob?.cancel()
        pendingEvaluationTickerJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val remainingMs = (pending.deadlineElapsedMs - android.os.SystemClock.elapsedRealtime())
                    .coerceAtLeast(0L)
                val minutes = (remainingMs / 1000) / 60
                val seconds = (remainingMs / 1000) % 60
                pendingEvaluationSubtitle.text = "${pending.modelClass.emoji} ${pending.modelClass.labelShort} · %d:%02d".format(minutes, seconds)
                if (remainingMs == 0L) break
                delay(1000L)
            }
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
        syncModeSelection(state.recordingMode)
        updateVolumeDisplay(state.currentVolume, state.appState)
        updatePerSecondCircles(state.perSecondResults)
        updatePerSecondCardVisibility(state.recordingMode)
        updatePendingEvaluation(state.pendingEvaluation)
        updatePauseButton(state)
        updateUserPauseCountdown(state)
        updateLongSubModeUi(state)

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

    private fun syncModeSelection(mode: RecordingMode) {
        if (!::modeTimeline.isInitialized) return
        if (mode.category != currentCategory) {
            selectCategory(mode.category, persist = false)
        }
        highlightSubMode(mode)
        modeTimeline.setMode(mode)
        modeDescription.text = descriptionFor(mode)
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
                val total = appState.secondsRemaining
                val minutes = total / 60
                val seconds = total % 60
                val mmss = "%d:%02d".format(minutes, seconds)
                statusLabel.text = getString(R.string.pause_mmss, mmss)
                statusLabel.setTextColor(ContextCompat.getColor(ctx, R.color.status_idle))
                startStopButton.isEnabled = true
                startStopButton.text = getString(R.string.stop_recording)
                recordingProgressBar.visibility = View.GONE
                timerText.text = mmss
                timerText.visibility = View.VISIBLE
            }
            is AppState.UserPaused -> {
                val total = appState.secondsRemaining
                if (total > 0) {
                    val minutes = total / 60
                    val seconds = total % 60
                    val mmss = "%d:%02d".format(minutes, seconds)
                    statusLabel.text = getString(R.string.user_paused_with_mmss, mmss)
                    timerText.text = mmss
                    timerText.visibility = View.VISIBLE
                } else {
                    statusLabel.text = getString(R.string.user_paused)
                    timerText.visibility = View.GONE
                }
                statusLabel.setTextColor(ContextCompat.getColor(ctx, R.color.status_idle))
                startStopButton.isEnabled = true
                startStopButton.text = getString(R.string.stop_recording)
                recordingProgressBar.visibility = View.GONE
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
        val longWithAvg = mode == RecordingMode.LONG &&
            LongSubMode.AVERAGE in viewModel.uiState.value.selectedLongSubs
        if (isDevMode && (mode == RecordingMode.AVERAGE || longWithAvg)) {
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
