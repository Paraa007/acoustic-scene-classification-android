package com.fzi.acousticscene.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.fzi.acousticscene.R
import com.fzi.acousticscene.data.QuickstartRepository
import com.fzi.acousticscene.model.LongInterval
import com.fzi.acousticscene.model.ModelMetadataRegistry
import com.fzi.acousticscene.model.ModelTrainingDuration
import com.fzi.acousticscene.model.QuickstartSlot
import com.fzi.acousticscene.model.RecordingCategory
import com.fzi.acousticscene.model.SessionConfig
import com.fzi.acousticscene.model.SessionDuration
import com.fzi.acousticscene.model.WizardIntent
import com.fzi.acousticscene.model.WizardStep
import com.fzi.acousticscene.util.BatteryOptimizationHelper
import com.fzi.acousticscene.util.stripModelSuffix
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/**
 * One-fragment wizard. The active step is held on [MainViewModel.wizard]; this
 * fragment swaps its scrollable content area to render the right page. Back
 * walks a step at a time, leaving the previously entered answers intact.
 *
 * The Summary CTA depends on [WizardIntent]:
 *   - StartRecording / QuickStart → "Start" → live recording
 *   - SaveAsSlot                  → "Save as Quick start" → quickstart slot
 */
class WizardFragment : Fragment(R.layout.fragment_wizard) {

    private val viewModel: MainViewModel by activityViewModels()

    private lateinit var headerText: TextView
    private lateinit var subHeader: TextView
    private lateinit var stepLabel: TextView
    private lateinit var sectionLabel: TextView
    private lateinit var stepHeaderColumn: LinearLayout
    private lateinit var stepDots: LinearLayout
    private lateinit var contentRoot: LinearLayout
    private lateinit var primaryButton: MaterialButton
    private lateinit var backButton: FrameLayout

    // Single source of truth for RECORD_AUDIO. The launcher is registered once
    // per fragment instance (must happen before the fragment hits STARTED).
    private val recordAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startRecordingSession()
            } else if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                // Second denial (or "Don't ask again"): system will no longer
                // surface the prompt, so point the user at app settings.
                showPermissionSettingsDialog()
            } else {
                Toast.makeText(
                    requireContext(),
                    R.string.permission_record_audio_denied_toast,
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        headerText = view.findViewById(R.id.wizardHeader)
        subHeader = view.findViewById(R.id.wizardSubheader)
        stepLabel = view.findViewById(R.id.wizardStepLabel)
        sectionLabel = view.findViewById(R.id.wizardSectionLabel)
        stepHeaderColumn = view.findViewById(R.id.wizardStepHeaderColumn)
        stepDots = view.findViewById(R.id.wizardStepDots)
        contentRoot = view.findViewById(R.id.wizardContent)
        primaryButton = view.findViewById(R.id.wizardPrimary)
        backButton = view.findViewById(R.id.wizardBack)

        backButton.setOnClickListener { handleBack() }
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { handleBack() }
            }
        )

        primaryButton.setOnClickListener { handlePrimary() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.wizard.collect { state -> render(state) }
            }
        }
    }

    private fun handleBack() {
        // Quick Start lands directly on Summary — back exits the wizard.
        if (viewModel.wizard.value.quickStartMode) {
            findNavController().popBackStack()
            return
        }
        if (!viewModel.wizardBack()) {
            findNavController().popBackStack()
        }
    }

    private fun handlePrimary() {
        val state = viewModel.wizard.value
        if (state.step != WizardStep.Summary) {
            viewModel.wizardAdvance()
            return
        }
        when (state.intent) {
            is WizardIntent.StartRecording,
            is WizardIntent.QuickStart,
            is WizardIntent.QuickStartTest -> {
                // Remind about the battery-optimization exemption if it was
                // skipped on first launch, then gate on the mic permission
                // before starting. Recording runs in the foreground service,
                // so it keeps going once Doze kicks in.
                BatteryOptimizationHelper.promptIfMissingBeforeRecording(requireActivity()) {
                    ensureRecordAudioThenStart()
                }
            }
            is WizardIntent.SaveAsSlot -> handleSaveAsSlot(state.toSessionConfig())
        }
    }

    /**
     * Permission gate for the recording flow. RECORD_AUDIO is a dangerous
     * permission and must be granted at runtime on every supported SDK (min 26).
     * Without the grant, AudioRecord delivers silent buffers and Android 14+
     * blocks the microphone foreground-service type.
     */
    private fun ensureRecordAudioThenStart() {
        val ctx = requireContext()
        val granted = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            startRecordingSession()
            return
        }
        if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            // User declined once but the system will still show the prompt —
            // explain why we need it, then re-trigger the system dialog.
            ModernDialogHelper.showConfirmDialog(
                context = ctx,
                title = getString(R.string.permission_record_audio_title),
                message = getString(R.string.permission_record_audio_rationale),
                confirmText = getString(R.string.permission_record_audio_grant),
                cancelText = getString(R.string.cancel),
                onConfirm = {
                    recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            )
            return
        }
        recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startRecordingSession() {
        val state = viewModel.wizard.value
        val config = state.toSessionConfig()
        viewModel.applySessionConfig(config)
        findNavController().navigate(R.id.action_wizard_to_live)
    }

    private fun showPermissionSettingsDialog() {
        ModernDialogHelper.showConfirmDialog(
            context = requireContext(),
            title = getString(R.string.permission_record_audio_title),
            message = getString(R.string.permission_record_audio_settings_message),
            confirmText = getString(R.string.permission_record_audio_open_settings),
            cancelText = getString(R.string.cancel),
            onConfirm = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", requireContext().packageName, null)
                }
                startActivity(intent)
            }
        )
    }

    private fun handleSaveAsSlot(config: SessionConfig) {
        val repo = QuickstartRepository.getInstance(requireContext())
        val free = repo.lowestFreeIndex()
        if (free != null) {
            commitSlot(repo, free, config)
            return
        }
        showSlotPickerDialog(repo, config)
    }

    private fun commitSlot(repo: QuickstartRepository, index: Int, config: SessionConfig) {
        repo.saveSlot(index, config)
        // Snackbar the success, then pop straight back to the welcome screen so
        // the developer is back at the hub. Nothing further to do.
        view?.let { v ->
            Snackbar.make(v, getString(R.string.quickstart_saved_to_slot, index), Snackbar.LENGTH_LONG).show()
        } ?: Toast.makeText(
            requireContext(),
            getString(R.string.quickstart_saved_to_slot, index),
            Toast.LENGTH_SHORT
        ).show()
        findNavController().popBackStack(R.id.welcomeFragment, false)
    }

    private fun showSlotPickerDialog(repo: QuickstartRepository, config: SessionConfig) {
        val existing = repo.getAll()
        val labels = existing.map { slot ->
            getString(R.string.test_welcome_slot_label, slot.index) +
                ": " + slot.config.slotDescription()
        }.toTypedArray()
        val ctx = requireContext()
        val dialog = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(R.string.quickstart_pick_slot_title)
            .setMessage(R.string.quickstart_pick_slot_subtitle)
            .setItems(labels) { _, which ->
                val slot = existing[which]
                commitSlot(repo, slot.index, config)
            }
            .setNegativeButton(R.string.cancel) { d, _ -> d.dismiss() }
            .create()
        dialog.show()
    }

    private fun render(state: WizardViewState) {
        if (state.quickStartMode) {
            // Quick Start: read-only single page.
            stepDots.visibility = View.GONE
            stepLabel.visibility = View.GONE
            sectionLabel.visibility = View.VISIBLE
            sectionLabel.text = sectionLabelFor(state.intent)
            headerText.visibility = View.GONE
            subHeader.visibility = View.GONE
        } else {
            stepDots.visibility = View.VISIBLE
            stepLabel.visibility = View.VISIBLE
            sectionLabel.visibility = View.VISIBLE
            val order = state.stepOrder()
            val pos = order.indexOf(state.step) + 1
            stepLabel.text = getString(R.string.wizard_step_label, pos, order.size)
            sectionLabel.text = sectionLabelFor(state.intent)
            renderStepDots(pos, order.size)
            headerText.visibility = View.VISIBLE
            headerText.text = state.step.headerText
            subHeader.visibility = if (state.step == WizardStep.Summary) View.VISIBLE else View.GONE
            subHeader.text = if (state.step == WizardStep.Summary) {
                "Tap any row to change it"
            } else ""
        }
        primaryButton.text = primaryButtonLabelFor(state)
        primaryButton.isEnabled = state.canAdvance()

        contentRoot.removeAllViews()
        when (state.step) {
            WizardStep.Models -> renderModels(state)
            WizardStep.Category -> renderCategory(state)
            WizardStep.IntervalPause -> renderIntervalPause(state)
            WizardStep.SessionDuration -> renderSessionDuration(state)
            WizardStep.Summary -> renderSummary(state)
        }
    }

    private fun primaryButtonLabelFor(state: WizardViewState): String {
        if (state.step != WizardStep.Summary) return getString(R.string.wizard_next)
        return when (state.intent) {
            is WizardIntent.SaveAsSlot -> getString(R.string.wizard_save_as_quickstart)
            else -> getString(R.string.wizard_start)
        }
    }

    private fun sectionLabelFor(intent: WizardIntent): String = when (intent) {
        is WizardIntent.SaveAsSlot -> getString(R.string.welcome_configure_test)
        is WizardIntent.QuickStart -> getString(R.string.welcome_quick_start)
        is WizardIntent.QuickStartTest -> getString(R.string.wizard_section_test_session)
        is WizardIntent.StartRecording -> "New session"
    }

    private fun renderStepDots(active: Int, total: Int) {
        // Mockup uses flex-weighted bars (not circles): completed and current
        // segments share the accent fill, current gets flex 2; upcoming
        // segments use the muted surface with a hairline border.
        stepDots.removeAllViews()
        val ctx = requireContext()
        val gap = dp(6f)
        val barHeight = dp(5f)
        for (i in 1..total) {
            val drawable = when {
                i == active -> R.drawable.wizard_dot_active
                i < active -> R.drawable.wizard_dot_filled
                else -> R.drawable.wizard_dot_inactive
            }
            val weight = if (i == active) 2f else 1f
            val view = View(ctx).apply {
                background = ContextCompat.getDrawable(ctx, drawable)
                layoutParams = LinearLayout.LayoutParams(0, barHeight, weight).apply {
                    if (i > 1) marginStart = gap
                }
            }
            stepDots.addView(view)
        }
    }

    private fun renderModels(state: WizardViewState) {
        val ctx = requireContext()
        // "N of M selected" counter row
        val counterRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(14f))
        }
        counterRow.addView(TextView(ctx).apply {
            text = "${state.selectedModels.size} / ${state.availableModels.size} selected"
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(10f) }
        })
        val segmentRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                0, dp(4f), 1f
            )
        }
        for (i in state.availableModels.indices) {
            val on = state.availableModels[i] in state.selectedModels
            val seg = View(ctx).apply {
                background = pillBackground(
                    if (on) ContextCompat.getColor(ctx, R.color.accent_green)
                    else ContextCompat.getColor(ctx, R.color.surface_variant),
                    dp(2f).toFloat()
                )
                layoutParams = LinearLayout.LayoutParams(0, dp(4f), 1f).apply {
                    if (i > 0) marginStart = dp(4f)
                }
            }
            segmentRow.addView(seg)
        }
        counterRow.addView(segmentRow)
        contentRoot.addView(counterRow)

        val selected = state.selectedModels.toMutableSet()
        for (modelName in state.availableModels) {
            val metadata = ModelMetadataRegistry.get(ctx, modelName)
            val seconds = ModelTrainingDuration.secondsForFilename(modelName)
            val testAcc = metadata?.testAccuracy
            contentRoot.addView(modelRow(
                modelName = modelName,
                selected = modelName in selected,
                trainingSeconds = seconds,
                testAccuracy = testAcc,
                onToggle = { checked ->
                    if (checked) selected.add(modelName) else selected.remove(modelName)
                    viewModel.wizardSetModels(selected.toList())
                }
            ))
        }
    }

    private fun modelRow(
        modelName: String,
        selected: Boolean,
        trainingSeconds: Int,
        testAccuracy: Double?,
        onToggle: (Boolean) -> Unit
    ): View {
        val ctx = requireContext()
        val accentLine = ContextCompat.getColor(ctx, R.color.accent_line)
        val hairline = ContextCompat.getColor(ctx, R.color.hairline)
        val surfaceDark = ContextCompat.getColor(ctx, R.color.surface_dark)
        val accentSoft = ContextCompat.getColor(ctx, R.color.accent_soft)
        val acc = ContextCompat.getColor(ctx, R.color.accent_green)
        val textPrimary = ContextCompat.getColor(ctx, R.color.text_primary)
        val textSecondary = ContextCompat.getColor(ctx, R.color.text_secondary)
        val textFaint = ContextCompat.getColor(ctx, R.color.text_faint)
        val accentRed = ContextCompat.getColor(ctx, R.color.accent_red)

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15f), dp(15f), dp(15f), dp(15f))
            background = makeBorderedRect(
                fill = if (selected) accentSoft else surfaceDark,
                stroke = if (selected) accentLine else hairline,
                strokeWidthPx = dp(if (selected) 1.6f else 1f),
                cornerRadiusPx = dp(13f).toFloat()
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onToggle(!selected) }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(10f)
            layoutParams = lp
        }

        val topRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        // Check dot — rounded square, accent fill with checkmark when selected
        val dotSize = dp(23f)
        val dotContainer = android.widget.FrameLayout(ctx).apply {
            background = squareCheckDot(selected, acc, surfaceDark, hairline)
            layoutParams = LinearLayout.LayoutParams(dotSize, dotSize)
        }
        if (selected) {
            dotContainer.addView(ImageView(ctx).apply {
                setImageDrawable(ContextCompat.getDrawable(ctx, R.drawable.ic_check_ink))
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    dp(12f), dp(12f), Gravity.CENTER
                )
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        }
        topRow.addView(dotContainer)

        // Right column — name + meta + track
        val rightCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { marginStart = dp(12f) }
        }
        rightCol.addView(TextView(ctx).apply {
            text = modelName.stripModelSuffix()
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(if (selected) textPrimary else textSecondary)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })

        val metaRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8f), 0, 0)
        }
        // Training-duration badge — mockup shows pill with mono text
        val badgeBg = ContextCompat.getColor(ctx, R.color.accent_soft)
        val badgeFg = ContextCompat.getColor(ctx, R.color.accent_green)
        metaRow.addView(TextView(ctx).apply {
            text = "${trainingSeconds}s"
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(badgeFg)
            background = pillBackground(badgeBg, dp(6f).toFloat())
            setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
        })
        if (testAccuracy != null) {
            metaRow.addView(TextView(ctx).apply {
                text = getString(R.string.test_acc_label)
                textSize = 10f
                setTextColor(if (selected) textSecondary else textFaint)
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(8f) }
            })
            metaRow.addView(TextView(ctx).apply {
                text = "%.1f%%".format(testAccuracy * 100)
                textSize = 12.5f
                typeface = Typeface.MONOSPACE
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (selected) textPrimary else textSecondary)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(8f) }
            })
        } else {
            metaRow.addView(TextView(ctx).apply {
                text = getString(R.string.test_acc_missing)
                isAllCaps = true
                textSize = 10f
                letterSpacing = 0.04f
                setTextColor(accentRed)
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(8f) }
            })
        }
        rightCol.addView(metaRow)

        if (testAccuracy == null) {
            rightCol.addView(TextView(ctx).apply {
                text = getString(R.string.test_acc_missing_hint)
                textSize = 10.5f
                setTextColor(accentRed)
                setPadding(0, dp(6f), 0, 0)
            })
        }
        topRow.addView(rightCol)
        card.addView(topRow)

        // Full-width track sits below the check-dot+content row.
        val trackLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(5f)
        ).apply { topMargin = dp(11f) }

        if (testAccuracy == null) {
            // Empty dashed red track
            card.addView(View(ctx).apply {
                background = makeBorderedRect(
                    fill = ContextCompat.getColor(ctx, R.color.surface_variant),
                    stroke = accentRed,
                    strokeWidthPx = dp(1f),
                    cornerRadiusPx = dp(3f).toFloat(),
                    dashed = true
                )
                layoutParams = trackLp
            })
        } else {
            // Two-segment track: filled portion + remainder
            val track = LinearLayout(ctx).apply {
                background = pillBackground(
                    ContextCompat.getColor(ctx, R.color.surface_variant),
                    dp(3f).toFloat()
                )
                layoutParams = trackLp
            }
            val pct = testAccuracy.toFloat().coerceIn(0f, 1f)
            track.addView(View(ctx).apply {
                background = pillBackground(
                    if (selected) acc else textFaint,
                    dp(3f).toFloat()
                )
                layoutParams = LinearLayout.LayoutParams(0, dp(5f), pct)
            })
            track.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(5f), 1f - pct)
            })
            card.addView(track)
        }
        return card
    }

    private fun renderCategory(state: WizardViewState) {
        addCategoryTile(
            label = getString(R.string.category_continuous),
            sub = getString(R.string.wizard_category_continuous_hint),
            iconRes = R.drawable.ic_mode_continuous,
            selected = state.category == RecordingCategory.CONTINUOUS,
            onClick = { viewModel.wizardSetCategory(RecordingCategory.CONTINUOUS) }
        )
        addCategoryTile(
            label = getString(R.string.category_interval),
            sub = getString(R.string.wizard_category_interval_hint),
            iconRes = R.drawable.ic_mode_interval,
            selected = state.category == RecordingCategory.INTERVAL,
            onClick = { viewModel.wizardSetCategory(RecordingCategory.INTERVAL) }
        )
    }

    /**
     * Recording-mode tile: 46dp leading icon, title with trailing radio circle,
     * one-line hint below. Selected state uses the accent_soft fill.
     */
    private fun addCategoryTile(
        label: String,
        sub: String,
        iconRes: Int,
        selected: Boolean,
        onClick: () -> Unit
    ) {
        val ctx = requireContext()
        val accentLine = ContextCompat.getColor(ctx, R.color.accent_line)
        val hairline = ContextCompat.getColor(ctx, R.color.hairline)
        val accent = ContextCompat.getColor(ctx, R.color.accent_green)
        val textPrimary = ContextCompat.getColor(ctx, R.color.text_primary)
        val textSecondary = ContextCompat.getColor(ctx, R.color.text_secondary)
        val textFaint = ContextCompat.getColor(ctx, R.color.text_faint)

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(18f), dp(18f), dp(18f), dp(18f))
            background = makeBorderedRect(
                fill = ContextCompat.getColor(
                    ctx,
                    if (selected) R.color.accent_soft else R.color.surface_dark
                ),
                stroke = if (selected) accentLine else hairline,
                strokeWidthPx = dp(if (selected) 1.6f else 1f),
                cornerRadiusPx = dp(13f).toFloat()
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(12f)
            layoutParams = lp
        }

        // Leading 46dp icon tile
        val iconTile = android.widget.FrameLayout(ctx).apply {
            background = makeBorderedRect(
                fill = ContextCompat.getColor(ctx, R.color.surface_dark),
                stroke = hairline,
                strokeWidthPx = dp(1f),
                cornerRadiusPx = dp(13f).toFloat()
            )
            layoutParams = LinearLayout.LayoutParams(dp(46f), dp(46f))
        }
        iconTile.addView(ImageView(ctx).apply {
            setImageDrawable(ContextCompat.getDrawable(ctx, iconRes))
            layoutParams = android.widget.FrameLayout.LayoutParams(
                dp(22f), dp(22f), Gravity.CENTER
            )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        card.addView(iconTile)

        // Right column: title row + sub
        val rightCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { marginStart = dp(13f) }
        }
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(ctx).apply {
            text = label
            textSize = 16f
            setTextColor(if (selected) textPrimary else textSecondary)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        })
        val radioSize = dp(21f)
        val radio = android.widget.FrameLayout(ctx).apply {
            background = circleCheckDot(selected, accent, hairline)
            layoutParams = LinearLayout.LayoutParams(radioSize, radioSize)
        }
        if (selected) {
            radio.addView(ImageView(ctx).apply {
                setImageDrawable(ContextCompat.getDrawable(ctx, R.drawable.ic_check_ink))
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    dp(11f), dp(11f), Gravity.CENTER
                )
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        }
        titleRow.addView(radio)
        rightCol.addView(titleRow)

        rightCol.addView(TextView(ctx).apply {
            text = sub
            textSize = 11.5f
            setTextColor(if (selected) textSecondary else textFaint)
            setLineSpacing(0f, 1.45f)
            setPadding(0, dp(6f), 0, 0)
        })

        card.addView(rightCol)
        contentRoot.addView(card)
    }

    /**
     * Interval-Pause step (slide 06b): a 15-min-step slider from 0 to 6 h.
     * Mirrors the visual language of the live-recording pause picker. The
     * wizard's own Next/Back chrome handles navigation, so this step has no
     * Confirm/Cancel buttons. The user picks any value (including 0) and
     * advances via the bottom primary button.
     */
    private fun renderIntervalPause(state: WizardViewState) {
        // 1..12 steps, each step = 15 min → total 15 min..3 h. A pause of 0
        // would be back-to-back recording (i.e. Continuous), so the slider
        // starts at 15 min and never reaches 0.
        val minSteps = 1
        val maxSteps = 12
        val initialSteps = (state.intervalPause?.pauseMinutes ?: 30)
            .coerceIn(minSteps * 15, maxSteps * 15) / 15
        val tickLabels = listOf("15min", "1h", "2h", "3h")
        addSliderStep(
            eyebrowRes = R.string.wizard_interval_pause_eyebrow,
            hintRes = R.string.wizard_interval_pause_hint,
            initialSteps = initialSteps,
            maxSteps = maxSteps,
            tickLabels = tickLabels,
            minSteps = minSteps,
            onChange = { steps ->
                val minutes = steps * 15
                viewModel.wizardSetIntervalPause(LongInterval.fromMinutes(minutes))
            }
        )
        // First time on this step (no pause picked yet) — push the slider's
        // default value into the wizard state so the next button enables.
        if (state.intervalPause == null) {
            viewModel.wizardSetIntervalPause(LongInterval.fromMinutes(initialSteps * 15))
        }
    }

    /**
     * Builds the shared slider-step card: accent-soft background, eyebrow row
     * with big duration display, slider, tick rail. Returns the slider so the
     * caller can update its enabled state independently (Session-Duration uses
     * that to grey the slider when "Stop manually" is active).
     */
    private fun addSliderStep(
        eyebrowRes: Int,
        hintRes: Int,
        initialSteps: Int,
        maxSteps: Int,
        tickLabels: List<String>,
        minSteps: Int = 0,
        displayOverride: String? = null,
        onChange: (Int) -> Unit
    ): Slider {
        val ctx = requireContext()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(16f), dp(18f), dp(18f))
            background = makeBorderedRect(
                fill = ContextCompat.getColor(ctx, R.color.accent_soft),
                stroke = ContextCompat.getColor(ctx, R.color.accent_line),
                strokeWidthPx = dp(1f),
                cornerRadiusPx = dp(13f).toFloat()
            )
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(12f)
            layoutParams = lp
        }

        // Header row: eyebrow on the left, big duration display on the right
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(TextView(ctx).apply {
            text = getString(eyebrowRes)
            isAllCaps = true
            textSize = 9.5f
            letterSpacing = 0.14f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.accent_green))
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        val durationDisplay = TextView(ctx).apply {
            textSize = 19f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            text = displayOverride ?: formatSliderValueLabel(initialSteps)
        }
        headerRow.addView(durationDisplay)
        card.addView(headerRow)

        card.addView(TextView(ctx).apply {
            text = getString(hintRes)
            textSize = 11f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            setPadding(0, dp(6f), 0, 0)
        })

        val slider = Slider(ctx).apply {
            valueFrom = minSteps.toFloat()
            valueTo = maxSteps.toFloat()
            stepSize = 1f
            value = initialSteps.toFloat().coerceIn(minSteps.toFloat(), maxSteps.toFloat())
            ContextCompat.getColorStateList(ctx, R.color.accent_green)?.let {
                thumbTintList = it
                trackActiveTintList = it
                tickActiveTintList = it
            }
            ContextCompat.getColorStateList(ctx, R.color.hairline)?.let {
                trackInactiveTintList = it
            }
            ContextCompat.getColorStateList(ctx, R.color.text_faint)?.let {
                tickInactiveTintList = it
            }
            ContextCompat.getColorStateList(ctx, R.color.accent_soft)?.let {
                haloTintList = it
            }
            labelBehavior = com.google.android.material.slider.LabelFormatter.LABEL_GONE
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(36f)
            )
            lp.topMargin = dp(10f)
            layoutParams = lp
            addOnChangeListener { _, v, _ ->
                val steps = v.toInt()
                durationDisplay.text = formatSliderValueLabel(steps)
                onChange(steps)
            }
        }
        card.addView(slider)

        // Tick-rail labels evenly distributed under the slider.
        val tickRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6f), 0, dp(6f), 0)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(2f)
            layoutParams = lp
        }
        tickLabels.forEachIndexed { i, lbl ->
            val grav = when (i) {
                0 -> Gravity.START
                tickLabels.lastIndex -> Gravity.END
                else -> Gravity.CENTER
            }
            tickRow.addView(TextView(ctx).apply {
                text = lbl
                textSize = 8.5f
                typeface = Typeface.MONOSPACE
                setTextColor(ContextCompat.getColor(ctx, R.color.text_faint))
                gravity = grav
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            })
        }
        card.addView(tickRow)

        contentRoot.addView(card)
        return slider
    }

    /**
     * Slider-value display: "No pause" at 0, "X min" under an hour,
     * "Xh" on the hour, otherwise "Xh Ymin".
     */
    private fun formatSliderValueLabel(steps: Int): String {
        val totalMinutes = steps * 15
        if (totalMinutes <= 0) return getString(R.string.wizard_slider_zero)
        if (totalMinutes < 60) return "$totalMinutes min"
        val hours = totalMinutes / 60
        val mins = totalMinutes % 60
        return if (mins == 0) "${hours}h" else "${hours}h ${mins}min"
    }

    /**
     * Session-Duration step (slide 07): 15-min-step slider from 0 to 12 h plus
     * a wide outlined "Stop manually" button below. The two are mutually
     * exclusive: tapping Stop manually greys the slider and selects the
     * MANUAL value; moving the slider unselects Stop manually.
     */
    private fun renderSessionDuration(state: WizardViewState) {
        // 1..48 steps, each step = 15 min → total 15 min..12 h. "Stop manually"
        // is the explicit button below; the slider itself never reaches 0.
        val minSteps = 1
        val maxSteps = 48
        val currentMin = state.sessionDuration.totalMinutes
        val initialSteps = (currentMin ?: 30).coerceIn(minSteps * 15, maxSteps * 15) / 15
        val tickLabels = listOf("15min", "3h", "6h", "9h", "12h")
        val manualSelected = state.sessionDuration.isManual

        val slider = addSliderStep(
            eyebrowRes = R.string.wizard_session_duration_eyebrow,
            hintRes = R.string.wizard_session_duration_hint,
            initialSteps = initialSteps,
            maxSteps = maxSteps,
            tickLabels = tickLabels,
            minSteps = minSteps,
            // When manual is active the big display reads "Stop manually" instead
            // of a stale duration, mirroring the greyed-out slider below.
            displayOverride = if (manualSelected) {
                getString(R.string.wizard_session_duration_stop_manually)
            } else null,
            onChange = { steps ->
                val minutes = steps * 15
                viewModel.wizardSetSessionDuration(SessionDuration.fromMinutes(minutes))
            }
        )

        slider.isEnabled = !manualSelected
        slider.alpha = if (manualSelected) 0.4f else 1f

        // Wide outlined "Stop manually" button below the slider. When active
        // (manual mode) the button uses the accent_soft fill + accent border;
        // otherwise it stays plain outlined.
        val ctx = requireContext()
        val manualBtn = MaterialButton(
            ctx,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = getString(R.string.wizard_session_duration_stop_manually)
            textSize = 14.5f
            isAllCaps = false
            setTextColor(
                ContextCompat.getColor(
                    ctx,
                    if (manualSelected) R.color.accent_green else R.color.text_primary
                )
            )
            strokeColor = ContextCompat.getColorStateList(
                ctx,
                if (manualSelected) R.color.accent_green else R.color.border_subtle
            )
            backgroundTintList = ContextCompat.getColorStateList(
                ctx,
                if (manualSelected) R.color.accent_soft else R.color.surface_dark
            )
            cornerRadius = dp(13f)
            icon = ContextCompat.getDrawable(ctx, R.drawable.ic_stop_square)
            iconSize = dp(18f)
            iconTint = ContextCompat.getColorStateList(
                ctx,
                if (manualSelected) R.color.accent_green else R.color.text_primary
            )
            iconPadding = dp(10f)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50f)
            )
            lp.topMargin = dp(6f)
            layoutParams = lp
            setOnClickListener {
                // Toggle: tapping the active "Stop manually" choice deselects it
                // and drops the user back on the slider's default (30 min) so
                // they're not stuck in manual mode without a back-button trip.
                if (manualSelected) {
                    viewModel.wizardSetSessionDuration(SessionDuration.fromMinutes(30))
                } else {
                    viewModel.wizardSetSessionDuration(SessionDuration.manual())
                }
            }
        }
        contentRoot.addView(manualBtn)
    }

    private fun renderSummary(state: WizardViewState) {
        val tappable = !state.quickStartMode
        addModelsSummaryRow(state, tappable)
        addCategorySummaryRow(state, tappable)
        if (state.category == RecordingCategory.INTERVAL) {
            addSummaryRow(
                label = getString(R.string.wizard_summary_pause),
                value = state.intervalPause?.let {
                    formatSliderValueLabel(it.pauseMinutes / 15)
                }.orEmpty(),
                step = WizardStep.IntervalPause,
                tappable = tappable
            )
        }
        addSessionDurationSummaryRow(state, tappable)
    }

    private fun addModelsSummaryRow(state: WizardViewState, tappable: Boolean) {
        val ctx = requireContext()
        val card = makeSummaryCard(WizardStep.Models, tappable)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        // Left: label + mono model list
        val left = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        left.addView(makeTileLabel(getString(R.string.wizard_summary_models)))
        val listCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(7f), 0, 0)
        }
        for (modelName in state.selectedModels) {
            listCol.addView(TextView(ctx).apply {
                text = modelName.stripModelSuffix()
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = dp(5f)
                layoutParams = lp
            })
            // Methods derived from training duration — read-only line under the
            // model name. Kept dim to mirror the mockup's hierarchy.
            val methods = ModelTrainingDuration.requiredMethodsForModel(modelName)
                .joinToString(" + ") { it.label }
            if (methods.isNotBlank()) {
                listCol.addView(TextView(ctx).apply {
                    text = getString(R.string.wizard_summary_runs, methods)
                    textSize = 10f
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(2f) }
                })
            }
        }
        left.addView(listCol)
        row.addView(left)

        // Right: "N selected" badge + chevron
        val right = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(10f) }
        }
        right.addView(TextView(ctx).apply {
            text = resources.getQuantityString(
                R.plurals.wizard_summary_selected_count,
                state.selectedModels.size,
                state.selectedModels.size
            )
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
        })
        right.addView(ImageView(ctx).apply {
            setImageDrawable(ContextCompat.getDrawable(ctx, R.drawable.ic_chevron_right_dim))
            layoutParams = LinearLayout.LayoutParams(dp(15f), dp(15f)).apply {
                marginStart = dp(8f)
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        row.addView(right)

        card.addView(row)
        contentRoot.addView(card)
    }

    private fun addCategorySummaryRow(state: WizardViewState, tappable: Boolean) {
        val ctx = requireContext()
        val card = makeSummaryCard(WizardStep.Category, tappable)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val left = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        left.addView(makeTileLabel(getString(R.string.wizard_summary_category)))
        left.addView(TextView(ctx).apply {
            text = state.category?.label.orEmpty().ifBlank { "—" }
            textSize = 14f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6f), 0, 0)
        })
        row.addView(left)

        // Mode icon row + chevron
        val right = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val iconRes = when (state.category) {
            RecordingCategory.INTERVAL -> R.drawable.ic_mode_interval
            else -> R.drawable.ic_mode_continuous
        }
        right.addView(ImageView(ctx).apply {
            setImageDrawable(ContextCompat.getDrawable(ctx, iconRes))
            layoutParams = LinearLayout.LayoutParams(dp(20f), dp(20f))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        right.addView(ImageView(ctx).apply {
            setImageDrawable(ContextCompat.getDrawable(ctx, R.drawable.ic_chevron_right_dim))
            layoutParams = LinearLayout.LayoutParams(dp(15f), dp(15f)).apply {
                marginStart = dp(9f)
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        row.addView(right)
        card.addView(row)
        contentRoot.addView(card)
    }

    private fun addSessionDurationSummaryRow(state: WizardViewState, tappable: Boolean) {
        val ctx = requireContext()
        val card = makeSummaryCard(WizardStep.SessionDuration, tappable)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val left = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        left.addView(makeTileLabel(getString(R.string.wizard_summary_session)))
        val valueRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(0, dp(6f), 0, 0)
        }
        if (state.sessionDuration.isManual) {
            valueRow.addView(TextView(ctx).apply {
                text = state.sessionDuration.label
                textSize = 14f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                setTypeface(typeface, Typeface.BOLD)
            })
        } else {
            // Slider-based label: full sentence ("1 h 30 min") instead of the
            // old (number, unit) pair the preset enum produced.
            val steps = (state.sessionDuration.totalMinutes ?: 0) / 15
            valueRow.addView(TextView(ctx).apply {
                text = formatSliderValueLabel(steps)
                textSize = 16f
                typeface = Typeface.MONOSPACE
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                letterSpacing = -0.01f
            })
        }
        left.addView(valueRow)
        row.addView(left)

        row.addView(ImageView(ctx).apply {
            setImageDrawable(ContextCompat.getDrawable(ctx, R.drawable.ic_chevron_right_dim))
            layoutParams = LinearLayout.LayoutParams(dp(15f), dp(15f)).apply {
                marginStart = dp(10f)
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        card.addView(row)
        contentRoot.addView(card)
    }

    private fun makeSummaryCard(step: WizardStep, tappable: Boolean): LinearLayout {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15f), dp(15f), dp(15f), dp(15f))
            background = makeBorderedRect(
                fill = ContextCompat.getColor(ctx, R.color.surface_dark),
                stroke = ContextCompat.getColor(ctx, R.color.hairline),
                strokeWidthPx = dp(1f),
                cornerRadiusPx = dp(13f).toFloat()
            )
            isClickable = tappable
            isFocusable = tappable
            if (tappable) setOnClickListener { viewModel.wizardGoToStep(step) }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(10f)
            layoutParams = lp
        }
    }

    private fun makeTileLabel(label: String): TextView {
        val ctx = requireContext()
        return TextView(ctx).apply {
            text = label
            isAllCaps = true
            textSize = 10f
            letterSpacing = 0.14f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_faint))
        }
    }

    /**
     * Generic summary row used by IntervalPause and any plain key/value pair.
     * Renders a chevron on the trailing edge whenever [tappable] is true, so
     * users get the same affordance as the model/category/session-duration
     * rows that have their own bespoke renderers.
     */
    private fun addSummaryRow(label: String, value: String, step: WizardStep, tappable: Boolean) {
        val ctx = requireContext()
        val card = makeSummaryCard(step, tappable)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val left = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        left.addView(makeTileLabel(label))
        left.addView(TextView(ctx).apply {
            text = value.ifBlank { "—" }
            textSize = 14f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6f), 0, 0)
        })
        row.addView(left)
        if (tappable) {
            row.addView(ImageView(ctx).apply {
                setImageDrawable(ContextCompat.getDrawable(ctx, R.drawable.ic_chevron_right_dim))
                layoutParams = LinearLayout.LayoutParams(dp(15f), dp(15f)).apply {
                    marginStart = dp(10f)
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        }
        card.addView(row)
        contentRoot.addView(card)
    }

    private fun pillBackground(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
        }
    }

    private fun makeBorderedRect(
        fill: Int,
        stroke: Int,
        strokeWidthPx: Int,
        cornerRadiusPx: Float,
        dashed: Boolean = false
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = cornerRadiusPx
            if (dashed) {
                setStroke(strokeWidthPx, stroke, dp(4f).toFloat(), dp(3f).toFloat())
            } else {
                setStroke(strokeWidthPx, stroke)
            }
        }
    }

    private fun checkDot(selected: Boolean, accent: Int, hairline: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (selected) accent else Color.TRANSPARENT)
            if (!selected) setStroke(dp(1.5f), hairline)
        }
    }

    /**
     * Rounded-square checkdot used by the Models step. Selected = accent fill,
     * unselected = surface fill with hairline border. Mockup: 23dp, 7dp radius.
     */
    private fun squareCheckDot(
        selected: Boolean,
        accent: Int,
        unselectedFill: Int,
        hairline: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(7f).toFloat()
            setColor(if (selected) accent else unselectedFill)
            if (!selected) setStroke(dp(1.5f), hairline)
        }
    }

    /** Circular check pill used by Recording Mode and Session Duration tiles. */
    private fun circleCheckDot(
        selected: Boolean,
        accent: Int,
        hairline: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (selected) accent else Color.TRANSPARENT)
            if (!selected) setStroke(dp(1.5f), hairline)
        }
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
}
