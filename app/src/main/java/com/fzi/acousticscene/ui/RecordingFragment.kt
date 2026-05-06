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
import com.fzi.acousticscene.databinding.FragmentRecordingBinding
import com.fzi.acousticscene.model.ClassificationResult
import com.fzi.acousticscene.model.LongInterval
import com.fzi.acousticscene.model.LongSubMode
import com.fzi.acousticscene.model.ModelConfig
import com.fzi.acousticscene.model.ModelTrainingDuration
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

    private var _binding: FragmentRecordingBinding? = null
    private val binding get() = _binding!!

    // Mode picker state (not view-bound)
    private val subModeButtons: MutableMap<RecordingMode, MaterialButton> = mutableMapOf()
    private var currentCategory: RecordingCategory = RecordingCategory.CONTINUOUS

    // Volume graph state
    private var volumeGraphJob: Job? = null
    private var isVolumeGraphActive: Boolean = false

    // Pending evaluation + user-pause timers
    private var pendingEvaluationTickerJob: Job? = null
    private var userPauseCountdownJob: Job? = null

    // Per-Second circles are dynamically created in initializeViews()
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
    ): View {
        _binding = FragmentRecordingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Configure model based on mode
        configureModel()

        initializeViews()
        setupObservers()
    }

    override fun onStart() {
        super.onStart()
        // Resume volume graph collection if active
        if (isVolumeGraphActive && viewModel.isClassifying()) {
            binding.volumeLineChartView.setDrawingEnabled(true)
            startVolumeGraphCollection()
        }
    }

    override fun onStop() {
        super.onStop()
        // Stop volume graph collection when not visible
        stopVolumeGraphCollection()
        _binding?.volumeLineChartView?.setDrawingEnabled(false)
        // Reset session state when navigating away without an active recording
        if (!viewModel.isClassifying()) {
            viewModel.resetSession()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pendingEvaluationTickerJob?.cancel()
        pendingEvaluationTickerJob = null
        userPauseCountdownJob?.cancel()
        userPauseCountdownJob = null
        volumeGraphJob?.cancel()
        volumeGraphJob = null
        _binding = null
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
                viewModel.setModelConfig(
                    activeDev.modelPath, activeDev.modelName, activeDev.numClasses, true,
                    activeDev.allInOneModels
                )
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

        // ALL IN ONE card first — lets the user compare several models on the same audio
        if (models.size >= 2) {
            modelListContainer.addView(createAllInOneItemView {
                dialog.dismiss()
                showAllInOneSelectionDialog(models)
            })
        }

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

    /**
     * "ALL IN ONE" card at the top of the Select Model dialog.
     */
    private fun createAllInOneItemView(onClick: () -> Unit): View {
        val ctx = requireContext()
        val card = MaterialCardView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 12 }
            setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.dialog_surface_light))
            radius = 16f * resources.displayMetrics.density
            cardElevation = 0f
            strokeWidth = 2
            strokeColor = ContextCompat.getColor(ctx, R.color.accent_green)
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
            text = "🧠🧠"
            textSize = 22f
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
            text = getString(R.string.all_in_one)
            textSize = 16f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val subText = TextView(ctx).apply {
            text = getString(R.string.all_in_one_subtitle_short)
            textSize = 12f
            setTextColor(ContextCompat.getColor(ctx, R.color.accent_green))
        }

        textContainer.addView(nameText)
        textContainer.addView(subText)
        content.addView(iconText)
        content.addView(textContainer)
        card.addView(content)
        return card
    }

    /**
     * Multi-select dialog for ALL IN ONE (Dev tab re-entry path).
     */
    private fun showAllInOneSelectionDialog(models: List<String>) {
        val ctx = context ?: return
        val dialog = Dialog(ctx, R.style.ModernDialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_all_in_one_selection)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)

        val container = dialog.findViewById<LinearLayout>(R.id.allInOneListContainer)
        val btnBack = dialog.findViewById<MaterialButton>(R.id.btnAllInOneBack)
        val btnStart = dialog.findViewById<MaterialButton>(R.id.btnAllInOneStart)

        val selected = linkedSetOf<String>()
        btnStart.isEnabled = false

        models.forEach { modelFileName ->
            val numClasses = ModelConfig.getClassCountForModel(modelFileName)
            val cb = MaterialCheckBox(ctx).apply {
                text = "$modelFileName · $numClasses Classes"
                textSize = 14f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = 4
                layoutParams = lp
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected.add(modelFileName) else selected.remove(modelFileName)
                    btnStart.isEnabled = selected.size >= 2
                }
            }
            container.addView(cb)
        }

        btnBack.setOnClickListener {
            dialog.dismiss()
            showModelSelectionDialog()
        }

        btnStart.setOnClickListener {
            if (selected.size < 2) return@setOnClickListener
            val config = ModelConfig.createAllInOne(selected.toList())
            Toast.makeText(ctx, getString(R.string.all_in_one_started, selected.size), Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            viewModel.setModelConfig(
                config.modelPath, config.modelName, config.numClasses, true,
                config.allInOneModels
            )
            viewModel.initializeSession()
        }

        dialog.show()
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

    private fun initializeViews() {
        binding.pauseResumeButton.setOnClickListener {
            if (viewModel.isUserPaused()) viewModel.resumeClassification()
            else showPauseDurationPicker()
        }

        binding.categoryContinuousButton.setOnClickListener { selectCategory(RecordingCategory.CONTINUOUS, persist = true) }
        binding.categoryIntervalButton.setOnClickListener { selectCategory(RecordingCategory.INTERVAL, persist = true) }

        binding.longFastCircle.setTargetSize(60)
        binding.longAvgCircle.setTargetSize(60)

        // Multi-Model Evaluation: restore persisted per-model sub-mode selection.
        // Built/refreshed lazily each time the active-model set changes (via observer
        // in setupObservers + rebuildMultiModelEvalRows).
        if (isDevMode) {
            val persisted = loadPersistedLongSubsByModel()
            viewModel.ensureLongSubsInitialized(persisted)
        }

        // Initialize volume graph with current recording mode duration
        binding.volumeLineChartView.setMaxDuration(viewModel.getRecordingMode().durationSeconds.toFloat())

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

            if (i < 5) binding.perSecondRow1.addView(itemLayout)
            else binding.perSecondRow2.addView(itemLayout)

            circles.add(circle)
            labels.add(label)
        }
        perSecondCircleViews = circles
        perSecondLabelViews = labels

        // Show per-second card only in Dev Mode when AVERAGE is selected
        if (isDevMode && viewModel.getRecordingMode() == RecordingMode.AVERAGE) {
            binding.perSecondCirclesCard.visibility = View.VISIBLE
        }

        // Per-second circles toggle listener
        binding.switchPerSecondCircles.setOnCheckedChangeListener { _, isChecked ->
            isPerSecondCirclesActive = isChecked
            binding.perSecondCirclesContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (!isChecked) {
                // Reset all circles
                perSecondCircleViews.forEach { it.setConfidence(0f, animate = false) }
                perSecondLabelViews.forEachIndexed { idx, tv -> tv.text = "${idx + 1}" }
            }
        }

        // Switch listener for volume graph
        binding.switchVolumeGraph.setOnCheckedChangeListener { buttonView, isChecked ->
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
                binding.volumeLineChartView.visibility = View.VISIBLE
                binding.volumeLineChartView.setDrawingEnabled(true)
                binding.volumeLineChartView.clearData()
            } else {
                stopVolumeGraphCollection()
                binding.volumeLineChartView.setDrawingEnabled(false)
                binding.volumeLineChartView.clearData()
                binding.volumeLineChartView.visibility = View.GONE
            }
        }

        binding.startStopButton.setOnClickListener {
            if (viewModel.isClassifying()) {
                viewModel.stopClassification()
                (activity as? MainActivity)?.stopClassificationService()
                stopVolumeGraphCollection()
                binding.volumeLineChartView.clearData()
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
                        binding.volumeLineChartView.clearData()
                        startVolumeGraphCollection()
                    }
                } else {
                    requestAudioPermission()
                }
            }
        }

        // Update header title based on mode
        binding.headerTitle.text = if (isDevMode) {
            getString(R.string.dev_mode)
        } else {
            getString(R.string.app_name_full)
        }

        // Restore last mode selection (category + mode) from prefs.
        // The LONG-mode interval is intentionally NOT persisted — the user must explicitly
        // pick one each time via the picker before LONG can start.
        val lastMode = loadPersistedMode()
        // Don't restore LONG as the active mode — interval is unset on launch and LONG
        // requires an explicit pick. Fall back to the default (Standard) if the last
        // persisted mode was LONG.
        val effectiveMode = if (lastMode == RecordingMode.LONG) RecordingMode.DEFAULT else lastMode
        selectCategory(effectiveMode.category, persist = false)
        selectMode(effectiveMode, persist = false)
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

    /**
     * Multi-Model Evaluation persistence: per-(model, sub-mode) entries flattened
     * into a single Set<String> as `"<modelName>::<SubMode>"`. Stays compatible with
     * SharedPreferences' string-set-only storage and survives Add/Remove of models
     * from the Multi-Model selection — entries for inactive models simply lay dormant.
     */
    private fun loadPersistedLongSubsByModel(): Map<String, Set<LongSubMode>> {
        val prefs = requireContext().getSharedPreferences("mode_prefs", Context.MODE_PRIVATE)
        val stored = prefs.getStringSet(longSubsPrefsKey(), null) ?: return emptyMap()
        val byModel = mutableMapOf<String, MutableSet<LongSubMode>>()
        stored.forEach { entry ->
            val sep = entry.indexOf("::")
            if (sep <= 0) return@forEach
            val name = entry.substring(0, sep)
            val subStr = entry.substring(sep + 2)
            val sub = runCatching { LongSubMode.valueOf(subStr) }.getOrNull() ?: return@forEach
            byModel.getOrPut(name) { mutableSetOf() } += sub
        }
        return byModel
    }

    private fun persistLongSubsByModel(byModel: Map<String, Set<LongSubMode>>) {
        val flat = byModel.flatMap { (name, subs) -> subs.map { "$name::${it.name}" } }.toSet()
        requireContext().getSharedPreferences("mode_prefs", Context.MODE_PRIVATE)
            .edit().putStringSet(longSubsPrefsKey(), flat).apply()
    }

    /** "29:47" if < 1 h, "1:05:30" otherwise. Used for the LONG-mode pause countdown. */
    private fun formatRemaining(totalSeconds: Int): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun showLongIntervalPicker() {
        if (viewModel.isClassifying()) {
            Toast.makeText(requireContext(), R.string.long_interval_locked_running, Toast.LENGTH_SHORT).show()
            return
        }
        ModernDialogHelper.showLongIntervalDialog(
            context = requireContext(),
            title = getString(R.string.long_interval_picker_title),
            subtitle = getString(R.string.long_interval_picker_subtitle),
            options = LongInterval.values().toList(),
            onSelected = { interval ->
                viewModel.setLongInterval(interval)
                // LONG mode is only activated after the user explicitly picked an interval —
                // tapping the "Every" button alone does not switch the mode.
                // Interval is intentionally not persisted across app launches.
                selectMode(RecordingMode.LONG, persist = true)
            }
        )
    }

    private fun selectCategory(cat: RecordingCategory, persist: Boolean) {
        currentCategory = cat
        val ctx = context ?: return
        val activeColor = ContextCompat.getColor(ctx, R.color.accent_green)
        val inactiveColor = ContextCompat.getColor(ctx, R.color.surface_variant)
        val activeTextColor = ContextCompat.getColor(ctx, R.color.on_primary)
        val inactiveTextColor = ContextCompat.getColor(ctx, R.color.text_secondary)

        val (sel, other) = if (cat == RecordingCategory.CONTINUOUS)
            binding.categoryContinuousButton to binding.categoryIntervalButton
        else
            binding.categoryIntervalButton to binding.categoryContinuousButton
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
            binding.modeTimeline.setMode(current)
            binding.modeDescription.text = descriptionFor(current)
        }
    }

    private fun rebuildSubModeButtons() {
        binding.subModeButtonRow.removeAllViews()
        subModeButtons.clear()
        val ctx = context ?: return
        val modes = RecordingMode.forCategory(currentCategory, isDevMode)
        val inactiveColor = ContextCompat.getColor(ctx, R.color.surface_variant)
        val inactiveTextColor = ContextCompat.getColor(ctx, R.color.text_secondary)
        modes.forEachIndexed { idx, mode ->
            val btn = MaterialButton(ctx).apply {
                text = labelForButton(mode)
                textSize = 11f
                cornerRadius = 12.dpToPx()
                backgroundTintList = ColorStateList.valueOf(inactiveColor)
                setTextColor(inactiveTextColor)
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = if (idx == 0) 0 else 3.dpToPx()
                    marginEnd = if (idx == modes.size - 1) 0 else 3.dpToPx()
                }
                setOnClickListener {
                    if (mode == RecordingMode.LONG && isDevMode) {
                        // Only open the picker — LONG mode activates from the picker callback.
                        showLongIntervalPicker()
                    } else {
                        selectMode(mode, persist = true)
                    }
                }
            }
            binding.subModeButtonRow.addView(btn)
            subModeButtons[mode] = btn
        }
    }

    /**
     * LONG button text in Dev Mode:
     *  - "Every"          while no interval has been picked yet (the user must tap to choose),
     *  - "Every <label>"  once an interval was explicitly chosen and LONG is active.
     */
    private fun labelForButton(mode: RecordingMode): String {
        if (mode == RecordingMode.LONG && isDevMode) {
            val state = viewModel.uiState.value
            val interval = state.selectedLongInterval
            return if (state.recordingMode == RecordingMode.LONG && interval != null) {
                "Every ${interval.label}"
            } else {
                "Every?"
            }
        }
        return mode.label
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
        binding.modeTimeline.setMode(mode)
        binding.modeDescription.text = descriptionFor(mode)
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
        if (_binding == null) return
        val isLong = state.recordingMode == RecordingMode.LONG
        // Chooser only for Dev Mode + LONG
        binding.longSubModeChooser.visibility = if (isDevMode && isLong) View.VISIBLE else View.GONE

        // Multi-Model Evaluation: keep `selectedLongSubsByModel` in sync with the
        // active-model set (Multi-Model picker can add/remove models mid-session).
        // ensureLongSubsInitialized is idempotent — only triggers a state update when
        // the model set or locked defaults actually changed.
        if (isDevMode) viewModel.ensureLongSubsInitialized()
        // Cheap rebuild — full container clear + re-add per state collect.
        if (isDevMode && isLong) rebuildMultiModelEvalRows(state)

        // The live Triangle UI only ever reflects the primary model's results — no
        // expansion to N triangles for Multi-Model (per design: live UI stays simple).
        val primaryName = viewModel.modelName
        val primarySub = ModelTrainingDuration.defaultSubMode(primaryName)
        val primarySubs = state.selectedLongSubsByModel[primaryName] ?: setOf(primarySub)
        val primaryResults = state.longSubResultsByModel[primaryName] ?: emptyMap()

        // Triangle circles: only when LONG is active and the primary has any non-default
        // sub-mode checked. The big circle shows the locked-default; small circles
        // (Fast / Avg slots) show whatever non-default Fast/Avg are checked.
        val showFastSmall = isLong && LongSubMode.FAST in primarySubs && primarySub != LongSubMode.FAST
        val showAvgSmall = isLong && LongSubMode.AVERAGE in primarySubs && primarySub != LongSubMode.AVERAGE
        val hasExtras = showFastSmall || showAvgSmall
        binding.longTriangleRow.visibility = if (hasExtras) View.VISIBLE else View.GONE
        binding.longFastCircleWrap.visibility = if (showFastSmall) View.VISIBLE else View.GONE
        binding.longAvgCircleWrap.visibility = if (showAvgSmall) View.VISIBLE else View.GONE

        // Shrink main circle when triangle is on
        val targetMain = if (hasExtras) 150 else 200
        binding.confidenceCircleView.setTargetSize(targetMain)

        // Shrink frame so the default sub-label sits close under the circle
        val frameDp = if (hasExtras) 170 else 240
        val density = resources.displayMetrics.density
        val framePx = (frameDp * density).toInt()
        if (binding.mainCircleFrame.layoutParams.height != framePx) {
            binding.mainCircleFrame.layoutParams = binding.mainCircleFrame.layoutParams.apply {
                width = framePx
                height = framePx
            }
        }

        // Hide Top Predictions card in triangle mode — it's ambiguous which circle it belongs to
        if (hasExtras) binding.predictionsCard.visibility = View.GONE

        // Reflect the union of all selected subs across models in the timeline schema.
        // The timeline doesn't differentiate by model — it only needs to know which
        // method labels appear at all, so the user sees a coherent footprint.
        val allActiveSubs = state.selectedLongSubsByModel.values.flatten().toSet()
        binding.modeTimeline.setLongSubs(allActiveSubs)
        binding.modeTimeline.setLongInterval(state.selectedLongInterval)

        // Dev Mode: keep the LONG button text in sync — "Every?" until the user
        // explicitly picked an interval, "Every <label>" while LONG is active.
        if (isDevMode) {
            subModeButtons[RecordingMode.LONG]?.let { btn ->
                val interval = state.selectedLongInterval
                btn.text = if (state.recordingMode == RecordingMode.LONG && interval != null) {
                    "Every ${interval.label}"
                } else {
                    "Every?"
                }
            }
        }

        // Inline prompt — visible only in Dev Mode + Interval category + no interval picked yet.
        val showPrompt = isDevMode &&
            currentCategory == RecordingCategory.INTERVAL &&
            state.selectedLongInterval == null
        binding.longIntervalPrompt.visibility = if (showPrompt) View.VISIBLE else View.GONE

        // Main circle sub-label: only in triangle mode. Renders the primary's locked
        // default method (Standard for 10 s models, Fast for 1 s models).
        binding.mainCircleSubLabel.visibility = if (hasExtras) View.VISIBLE else View.GONE
        val ctx = context
        if (hasExtras && ctx != null) {
            val defaultLabelRes = when (primarySub) {
                LongSubMode.STANDARD -> R.string.long_sub_label_standard
                LongSubMode.FAST -> R.string.long_sub_label_fast
                LongSubMode.AVERAGE -> R.string.long_sub_label_average
            }
            val defaultResult = primaryResults[primarySub] ?: state.currentResult
            if (defaultResult != null) {
                binding.mainCircleSubLabel.text =
                    "${getString(defaultLabelRes)}\n${defaultResult.sceneClass.emoji} ${defaultResult.sceneClass.labelShort} ${(defaultResult.confidence * 100).toInt()}%"
                val colorRes = sceneColors[defaultResult.sceneClass] ?: R.color.accent_green
                binding.mainCircleSubLabel.setTextColor(ContextCompat.getColor(ctx, colorRes))
            } else {
                binding.mainCircleSubLabel.text = getString(defaultLabelRes)
                binding.mainCircleSubLabel.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            }
            // Hide the big full-class label when triangle is active (compact label takes over)
            binding.currentSceneLabel.visibility = View.GONE
        }

        // Bind small-circle slots — Fast / Avg results from primary, only when those
        // sub-modes are checked AND aren't already serving as the locked default.
        bindSubCircle(binding.longFastCircle, binding.longFastLabel,
            if (showFastSmall) primaryResults[LongSubMode.FAST] else null,
            R.string.long_sub_label_fast)
        bindSubCircle(binding.longAvgCircle, binding.longAvgLabel,
            if (showAvgSmall) primaryResults[LongSubMode.AVERAGE] else null,
            R.string.long_sub_label_average)
    }

    /**
     * Multi-Model Evaluation: builds one row per active model inside
     * `multiModelEvalContainer`. Each row carries: brain icon + model filename
     * (ellipsised) + duration badge ([1s] blue, [10s] green) + 3 checkboxes
     * (Standard / Fast / Avg). The locked-default checkbox per row is disabled
     * and always checked; the others are user-toggleable.
     */
    private fun rebuildMultiModelEvalRows(state: UiState) {
        val ctx = context ?: return
        val container = binding.multiModelEvalContainer
        val activeModels = viewModel.activeModelNames()
        val locked = viewModel.isClassifying()

        // Cheap rebuild — full container clear + re-add. Only triggered while LONG is
        // visible and selection state changed; a few text views per model is nothing.
        container.removeAllViews()

        activeModels.forEach { modelName ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 8.dpToPx() }
            }

            // Header: 🧠 modelname [duration badge]
            val header = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val brain = TextView(ctx).apply {
                text = "🧠"
                textSize = 14f
                setPadding(0, 0, 8, 0)
            }
            val nameText = TextView(ctx).apply {
                text = modelName
                textSize = 12f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val durationSec = ModelTrainingDuration.secondsForFilename(modelName)
            val badge = TextView(ctx).apply {
                text = if (durationSec == 1) getString(R.string.model_badge_1s) else getString(R.string.model_badge_10s)
                textSize = 10f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
                val bgColor = ContextCompat.getColor(
                    ctx,
                    if (durationSec == 1) R.color.accent_blue else R.color.accent_green
                )
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 8f * resources.displayMetrics.density
                    setColor(bgColor)
                }
                setPadding(8.dpToPx(), 2.dpToPx(), 8.dpToPx(), 2.dpToPx())
            }
            header.addView(brain)
            header.addView(nameText)
            header.addView(badge)
            row.addView(header)

            // Three checkboxes
            val cbRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 2.dpToPx() }
            }
            val defaultSub = ModelTrainingDuration.defaultSubMode(modelName)
            val selectedForModel = state.selectedLongSubsByModel[modelName] ?: setOf(defaultSub)
            listOf(
                LongSubMode.STANDARD to R.string.long_sub_standard,
                LongSubMode.FAST to R.string.long_sub_fast,
                LongSubMode.AVERAGE to R.string.long_sub_average
            ).forEach { (sub, labelRes) ->
                val isLockedDefault = sub == defaultSub
                val cb = MaterialCheckBox(ctx).apply {
                    text = getString(labelRes)
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                    isChecked = sub in selectedForModel
                    isEnabled = !locked && !isLockedDefault
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                if (!isLockedDefault) {
                    cb.setOnCheckedChangeListener { _, _ ->
                        viewModel.toggleLongSubForModel(modelName, sub)
                        persistLongSubsByModel(viewModel.uiState.value.selectedLongSubsByModel)
                    }
                }
                cbRow.addView(cb)
            }
            row.addView(cbRow)

            container.addView(row)
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
        if (_binding == null) return
        val show = state.recordingMode == RecordingMode.LONG && viewModel.isClassifying()
        binding.pauseResumeButton.visibility = if (show) View.VISIBLE else View.GONE
        binding.pauseResumeButton.text = getString(
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
                binding.statusLabel.text = getString(R.string.user_paused_auto_resume, timeStr)
                binding.timerText.text = timeStr
                binding.timerText.visibility = View.VISIBLE
                if (remainingMs == 0L) break
                delay(1000L)
            }
        }
    }

    private fun updatePendingEvaluation(pending: PendingEvaluation?) {
        if (pending == null) {
            pendingEvaluationTickerJob?.cancel()
            pendingEvaluationTickerJob = null
            _binding?.pendingEvaluationCard?.visibility = View.GONE
            return
        }

        binding.pendingEvaluationCard.visibility = View.VISIBLE
        binding.pendingEvaluationTitle.text = getString(R.string.evaluation_inapp_message)
        val launchAction = View.OnClickListener {
            val intent = android.content.Intent(requireContext(), EvaluationActivity::class.java).apply {
                putExtra(EvaluationActivity.EXTRA_PREDICTION_ID, pending.predictionId)
                putExtra(EvaluationActivity.EXTRA_MODEL_PREDICTED_CLASS, pending.modelClass.name)
            }
            startActivity(intent)
        }
        binding.pendingEvaluationButton.setOnClickListener(launchAction)
        binding.pendingEvaluationCard.setOnClickListener(launchAction)

        // Start a per-second ticker for the countdown if not already running for this prediction
        pendingEvaluationTickerJob?.cancel()
        pendingEvaluationTickerJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val remainingMs = (pending.deadlineElapsedMs - android.os.SystemClock.elapsedRealtime())
                    .coerceAtLeast(0L)
                val minutes = (remainingMs / 1000) / 60
                val seconds = (remainingMs / 1000) % 60
                binding.pendingEvaluationSubtitle.text = "${pending.modelClass.emoji} ${pending.modelClass.labelShort} · %d:%02d".format(minutes, seconds)
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
        updateAllInOneCard(state)

        if (state.errorMessage != null) {
            // Error shown in status label
        }
    }

    /**
     * Builds the secondary label (under the model filename) for a LONG-mode
     * Multi-Model row. Returns null/empty until the user has picked an interval
     * via the "Every?" picker — that suppresses the misleading "every 30min ·
     * Standard (10 s)" label that used to flash up before any choice was made.
     *
     * Once an interval is set, the label includes both the chosen pause length
     * and the model's locked-default method (Standard for 10 s models, Fast for
     * 1 s models), so the row tells the full story at a glance.
     */
    private fun buildLongRowLabel(state: UiState, modelName: String): String {
        val interval = state.selectedLongInterval ?: return ""
        val defaultSub = ModelTrainingDuration.defaultSubMode(modelName)
        val subLabel = when (defaultSub) {
            LongSubMode.STANDARD -> getString(R.string.long_sub_label_standard)
            LongSubMode.FAST -> getString(R.string.long_sub_label_fast)
            LongSubMode.AVERAGE -> getString(R.string.long_sub_label_average)
        }
        return "every ${interval.label} · $subLabel"
    }

    /**
     * Renders the live per-model predictions while a Multi-Model Evaluation runs.
     * Card is hidden for regular single-model sessions.
     *
     * Each row: "🧠 <modelName>  <emoji> <Class> <XX%>" — colored by scene class,
     * greyed out until that model has finished inferring on the current round.
     */
    private fun updateAllInOneCard(state: UiState) {
        val ctx = context ?: return

        // Primary-model title above the big circle — only in ALL IN ONE, so the user
        // can see which model the big circle / triangle circles belong to. In regular
        // single-model sessions the header model label already serves that purpose.
        if (state.allInOneModelNames.size >= 2) {
            val primary = state.allInOneModelNames.first()
            binding.mainCircleModelTitle.text = getString(R.string.all_in_one_primary_title, primary)
            binding.mainCircleModelTitle.visibility = View.VISIBLE
        } else {
            binding.mainCircleModelTitle.visibility = View.GONE
        }

        if (state.allInOneModelNames.size < 2) {
            binding.allInOneCard.visibility = View.GONE
            return
        }
        binding.allInOneCard.visibility = View.VISIBLE
        binding.allInOneContainer.removeAllViews()

        // Recording-mode label per row. In LONG mode the row label reflects the
        // user-chosen interval (e.g. "every 15 min") AND the per-model locked-default
        // method — so each row's label is built individually below. Otherwise (Continuous
        // modes) all rows share the same global label.
        val sharedModeLabel = if (state.recordingMode != RecordingMode.LONG) state.recordingMode.label else null

        state.allInOneModelNames.forEach { name ->
            val result = state.allInOneResults[name]
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = 6.dpToPx()
                layoutParams = lp
            }

            val icon = TextView(ctx).apply {
                text = "🧠"
                textSize = 14f
                setPadding(0, 0, 10, 0)
            }

            val nameContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val nameText = TextView(ctx).apply {
                text = name
                textSize = 13f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }

            val modeText = TextView(ctx).apply {
                text = sharedModeLabel ?: buildLongRowLabel(state, name)
                textSize = 11f
                setTextColor(ContextCompat.getColor(ctx, R.color.accent_blue))
                visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
            }

            nameContainer.addView(nameText)
            nameContainer.addView(modeText)

            val predictionText = TextView(ctx).apply {
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                if (result != null) {
                    val colorRes = sceneColors[result.sceneClass] ?: R.color.accent_green
                    text = "${result.sceneClass.emoji} ${result.sceneClass.labelShort} ${(result.confidence * 100).toInt()}%"
                    setTextColor(ContextCompat.getColor(ctx, colorRes))
                } else {
                    text = getString(R.string.all_in_one_row_waiting)
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                }
            }

            row.addView(icon)
            row.addView(nameContainer)
            row.addView(predictionText)
            binding.allInOneContainer.addView(row)
        }
    }

    private fun updateVolumeDisplay(volume: Float, appState: AppState) {
        when (appState) {
            is AppState.Recording -> {
                binding.ripplePulseView.setVolume(volume)
                binding.volumeLevelText.visibility = View.VISIBLE
                val volumePercent = (volume * 100).toInt()
                binding.volumeLevelText.text = "${getString(R.string.volume)}: $volumePercent"
            }
            else -> {
                binding.ripplePulseView.clear()
                binding.volumeLevelText.visibility = View.GONE
            }
        }
    }

    private fun syncModeSelection(mode: RecordingMode) {
        if (_binding == null) return
        if (mode.category != currentCategory) {
            selectCategory(mode.category, persist = false)
        }
        highlightSubMode(mode)
        binding.modeTimeline.setMode(mode)
        binding.modeDescription.text = descriptionFor(mode)
    }

    private fun updateAppState(appState: AppState) {
        val ctx = context ?: return

        // Show processing indicator in header instead of status card
        binding.headerProcessingLabel.visibility = if (appState is AppState.Processing) View.VISIBLE else View.GONE

        when (appState) {
            is AppState.Idle -> {
                binding.statusLabel.text = getString(R.string.status_idle)
                binding.statusLabel.setTextColor(ContextCompat.getColor(ctx, R.color.status_idle))
                binding.startStopButton.isEnabled = false
                binding.recordingProgressBar.visibility = View.GONE
                binding.timerText.visibility = View.GONE
            }
            is AppState.Loading -> {
                binding.statusLabel.text = getString(R.string.status_idle)
                binding.statusLabel.setTextColor(ContextCompat.getColor(ctx, R.color.status_idle))
                binding.startStopButton.isEnabled = false
                binding.recordingProgressBar.visibility = View.GONE
                binding.timerText.visibility = View.GONE
            }
            is AppState.Ready -> {
                binding.statusLabel.text = getString(R.string.status_idle)
                binding.statusLabel.setTextColor(ContextCompat.getColor(ctx, R.color.status_idle))
                binding.startStopButton.isEnabled = true
                binding.startStopButton.text = getString(R.string.start_recording)
                binding.recordingProgressBar.visibility = View.GONE
                binding.timerText.visibility = View.GONE
            }
            is AppState.Recording -> {
                binding.statusLabel.text = getString(R.string.status_recording)
                binding.statusLabel.setTextColor(ContextCompat.getColor(ctx, R.color.status_recording))
                binding.startStopButton.isEnabled = true
                binding.startStopButton.text = getString(R.string.stop_recording)

                val seconds = appState.secondsRemaining
                binding.timerText.text = getString(R.string.timer_countdown, seconds)
                binding.timerText.visibility = View.VISIBLE

                val recordingProgress = viewModel.uiState.value.recordingProgress
                val progress = (recordingProgress * 100).toInt()
                setProgressAnimated(binding.recordingProgressBar, progress)
                binding.recordingProgressBar.visibility = View.VISIBLE
            }
            is AppState.Processing -> {
                // Status text stays as previous state (no change to binding.statusLabel)
                binding.startStopButton.isEnabled = true
                binding.startStopButton.text = getString(R.string.stop_recording)
                binding.recordingProgressBar.visibility = View.GONE
                binding.timerText.visibility = View.GONE
            }
            is AppState.Paused -> {
                val timeStr = formatRemaining(appState.secondsRemaining)
                binding.statusLabel.text = getString(R.string.pause_mmss, timeStr)
                binding.statusLabel.setTextColor(ContextCompat.getColor(ctx, R.color.status_idle))
                binding.startStopButton.isEnabled = true
                binding.startStopButton.text = getString(R.string.stop_recording)
                binding.recordingProgressBar.visibility = View.GONE
                binding.timerText.text = timeStr
                binding.timerText.visibility = View.VISIBLE
            }
            is AppState.UserPaused -> {
                val total = appState.secondsRemaining
                if (total > 0) {
                    val timeStr = formatRemaining(total)
                    binding.statusLabel.text = getString(R.string.user_paused_with_mmss, timeStr)
                    binding.timerText.text = timeStr
                    binding.timerText.visibility = View.VISIBLE
                } else {
                    binding.statusLabel.text = getString(R.string.user_paused)
                    binding.timerText.visibility = View.GONE
                }
                binding.statusLabel.setTextColor(ContextCompat.getColor(ctx, R.color.status_idle))
                binding.startStopButton.isEnabled = true
                binding.startStopButton.text = getString(R.string.stop_recording)
                binding.recordingProgressBar.visibility = View.GONE
            }
            is AppState.Error -> {
                binding.statusLabel.text = appState.message
                binding.statusLabel.setTextColor(ContextCompat.getColor(ctx, R.color.error))
                binding.startStopButton.isEnabled = true
                binding.startStopButton.text = getString(R.string.start_recording)
                binding.recordingProgressBar.visibility = View.GONE
                binding.timerText.visibility = View.GONE
            }
        }

        // Dev Mode + LONG without a chosen interval: lock Start until the user picks one.
        // Only override while idle/ready/error — don't touch a running session.
        val state = viewModel.uiState.value
        val needsIntervalChoice = isDevMode &&
            state.recordingMode == RecordingMode.LONG &&
            state.selectedLongInterval == null
        if (needsIntervalChoice && (appState is AppState.Ready || appState is AppState.Idle || appState is AppState.Error)) {
            binding.startStopButton.isEnabled = false
        }
    }

    private fun updateModelStatus(isLoaded: Boolean) {
        val ctx = context ?: return
        if (isLoaded) {
            if (isDevMode) {
                if (viewModel.isAllInOne) {
                    binding.modelStatusLabel.text = "\u2713 ${getString(R.string.all_in_one_header, viewModel.allInOneModelNames.size)}"
                } else {
                    val displayName = ModelDisplayNameHelper.getDisplayName(ctx, viewModel.modelName)
                    binding.modelStatusLabel.text = "\u2713 $displayName"
                }
            } else {
                binding.modelStatusLabel.text = "\u2713 ${getString(R.string.model_loaded)}"
            }
            binding.modelStatusLabel.setTextColor(ContextCompat.getColor(ctx, R.color.accent_green))
        } else {
            binding.modelStatusLabel.text = "\u26A0 ${getString(R.string.loading_model)}"
            binding.modelStatusLabel.setTextColor(ContextCompat.getColor(ctx, R.color.error))
        }
    }

    private fun updateCurrentResult(result: ClassificationResult?) {
        val ctx = context ?: return
        if (result != null) {
            binding.confidenceCircleView.setConfidence(result.confidence, animate = true)
            binding.currentSceneLabel.text = "${result.sceneClass.emoji} ${result.sceneClass.label}"
            binding.currentSceneLabel.visibility = View.VISIBLE
            val colorRes = sceneColors[result.sceneClass] ?: R.color.accent_green
            binding.currentSceneLabel.setTextColor(ContextCompat.getColor(ctx, colorRes))
        } else {
            binding.currentSceneLabel.visibility = View.GONE
            binding.confidenceCircleView.setConfidence(0f, animate = false)
        }
    }

    private fun updatePredictions(result: ClassificationResult?) {
        binding.predictionsContainer.removeAllViews()
        if (result != null) {
            binding.predictionsCard.visibility = View.VISIBLE
            val topPredictions = result.getTopPredictions(3)
            topPredictions.forEachIndexed { index, (scene, confidence) ->
                val predictionView = createPredictionView(scene, confidence, index + 1)
                binding.predictionsContainer.addView(predictionView)
            }
        } else {
            binding.predictionsCard.visibility = View.GONE
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
            binding.statisticsCard.visibility = View.VISIBLE
            binding.totalClassificationsText.text = total.toString()
            val seconds = avgTime / 1000.0
            binding.avgInferenceTimeText.text = String.format("%.2f s", seconds)
        } else {
            binding.statisticsCard.visibility = View.GONE
        }
    }

    private fun updateRecentPredictions(history: List<ClassificationResult>) {
        val ctx = context ?: return
        binding.recentPredictionsContainer.removeAllViews()

        if (history.isNotEmpty()) {
            binding.recentPredictionsCard.visibility = View.VISIBLE
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
                binding.recentPredictionsContainer.addView(row)
            }
        } else {
            binding.recentPredictionsCard.visibility = View.GONE
        }
    }

    private fun startVolumeGraphCollection() {
        volumeGraphJob?.cancel()
        volumeGraphJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isVolumeGraphActive) {
                val currentVolume = viewModel.uiState.value.currentVolume
                binding.volumeLineChartView.addDataPoint(currentVolume)
                delay(com.fzi.acousticscene.ui.VolumeLineChartView.DATA_INTERVAL_MS)
            }
        }
    }

    private fun stopVolumeGraphCollection() {
        volumeGraphJob?.cancel()
        volumeGraphJob = null
    }

    private fun updateVolumeGraphForMode(mode: RecordingMode) {
        binding.volumeLineChartView.setMaxDuration(mode.durationSeconds.toFloat())
        binding.volumeLineChartView.clearData()
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
        if (_binding == null) return
        // Per-second circles in LONG mode: only when the primary model has Avg checked.
        // Multi-Model Evaluation can have Avg active on additional models too, but the
        // live circles bind to the primary's per-second clips only — so this gate stays.
        val primaryName = viewModel.modelName
        val primarySubs = viewModel.uiState.value.selectedLongSubsByModel[primaryName].orEmpty()
        val longWithAvg = mode == RecordingMode.LONG && LongSubMode.AVERAGE in primarySubs
        if (isDevMode && (mode == RecordingMode.AVERAGE || longWithAvg)) {
            binding.perSecondCirclesCard.visibility = View.VISIBLE
        } else {
            binding.perSecondCirclesCard.visibility = View.GONE
            binding.switchPerSecondCircles.isChecked = false
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
                    binding.volumeLineChartView.clearData()
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
