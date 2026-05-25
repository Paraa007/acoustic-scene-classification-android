package com.fzi.acousticscene.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
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
import com.fzi.acousticscene.util.stripModelSuffix
import com.google.android.material.button.MaterialButton
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
    private lateinit var backButton: ImageButton

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
            is WizardIntent.StartRecording, is WizardIntent.QuickStart -> {
                val config = state.toSessionConfig()
                viewModel.applySessionConfig(config)
                findNavController().navigate(R.id.action_wizard_to_live)
            }
            is WizardIntent.SaveAsSlot -> handleSaveAsSlot(state.toSessionConfig())
        }
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
                " — " + slot.config.slotDescription()
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
            sectionLabel.text = getString(R.string.welcome_quick_start)
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
                "Tap any row to change it."
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
        is WizardIntent.StartRecording -> "New session"
    }

    private fun renderStepDots(active: Int, total: Int) {
        stepDots.removeAllViews()
        val ctx = requireContext()
        val gap = dp(5f)
        for (i in 1..total) {
            val drawable = when {
                i == active -> R.drawable.wizard_dot_active
                i < active -> R.drawable.wizard_dot_filled
                else -> R.drawable.wizard_dot_inactive
            }
            val sizePx = dp(if (i == active) 9f else 6f)
            val view = ImageView(ctx).apply {
                setImageDrawable(ContextCompat.getDrawable(ctx, drawable))
                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                    if (i > 1) marginStart = gap
                    gravity = Gravity.CENTER_VERTICAL
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
        val acc = ContextCompat.getColor(ctx, R.color.accent_green)

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
            background = makeBorderedRect(
                fill = surfaceDark,
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
            gravity = Gravity.CENTER_VERTICAL
        }
        // Check dot
        val dot = View(ctx).apply {
            background = checkDot(selected, acc, hairline)
            layoutParams = LinearLayout.LayoutParams(dp(18f), dp(18f))
        }
        topRow.addView(dot)
        topRow.addView(TextView(ctx).apply {
            text = modelName.stripModelSuffix()
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = dp(11f) }
        })
        card.addView(topRow)

        val metaRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(29f), dp(9f), 0, 0)
        }
        // Training-duration badge
        val badgeBg = ContextCompat.getColor(ctx,
            if (trainingSeconds == 1) R.color.accent_green else R.color.text_faint
        )
        metaRow.addView(TextView(ctx).apply {
            text = "${trainingSeconds}s"
            textSize = 9.5f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = pillBackground(badgeBg, dp(6f).toFloat())
            setPadding(dp(7f), dp(3f), dp(7f), dp(3f))
        })
        if (testAccuracy != null) {
            metaRow.addView(TextView(ctx).apply {
                text = getString(R.string.test_acc_label)
                textSize = 10f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(10f) }
            })
            metaRow.addView(TextView(ctx).apply {
                text = "%.1f%%".format(testAccuracy * 100)
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(acc)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(6f) }
            })
        } else {
            metaRow.addView(TextView(ctx).apply {
                text = getString(R.string.test_acc_missing)
                isAllCaps = true
                textSize = 10f
                letterSpacing = 0.04f
                setTextColor(ContextCompat.getColor(ctx, R.color.accent_red))
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(10f) }
            })
        }
        card.addView(metaRow)

        if (testAccuracy == null) {
            card.addView(TextView(ctx).apply {
                text = getString(R.string.test_acc_missing_hint)
                textSize = 10.5f
                setTextColor(ContextCompat.getColor(ctx, R.color.accent_red))
                setPadding(dp(29f), dp(4f), 0, 0)
            })
            // Empty dashed track in red
            card.addView(View(ctx).apply {
                background = makeBorderedRect(
                    fill = ContextCompat.getColor(ctx, R.color.surface_variant),
                    stroke = ContextCompat.getColor(ctx, R.color.accent_red),
                    strokeWidthPx = dp(1f),
                    cornerRadiusPx = dp(3f).toFloat(),
                    dashed = true
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(5f)
                ).apply { topMargin = dp(11f); marginStart = dp(29f) }
            })
        } else {
            // Filled progress bar
            val track = LinearLayout(ctx).apply {
                background = pillBackground(
                    ContextCompat.getColor(ctx, R.color.surface_variant),
                    dp(3f).toFloat()
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(5f)
                ).apply { topMargin = dp(11f); marginStart = dp(29f) }
            }
            val fill = View(ctx).apply {
                background = pillBackground(
                    if (selected) acc else ContextCompat.getColor(ctx, R.color.text_faint),
                    dp(3f).toFloat()
                )
                val pct = testAccuracy.toFloat().coerceIn(0f, 1f)
                layoutParams = LinearLayout.LayoutParams(
                    0, dp(5f), pct
                )
            }
            val rest = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, dp(5f), 1f - testAccuracy.toFloat().coerceIn(0f, 1f)
                )
            }
            track.addView(fill)
            track.addView(rest)
            card.addView(track)
        }
        return card
    }

    private fun renderCategory(state: WizardViewState) {
        addPickRow(
            label = getString(R.string.category_continuous),
            sub = getString(R.string.wizard_category_continuous_hint),
            selected = state.category == RecordingCategory.CONTINUOUS,
            onClick = { viewModel.wizardSetCategory(RecordingCategory.CONTINUOUS) }
        )
        addPickRow(
            label = getString(R.string.category_interval),
            sub = getString(R.string.wizard_category_interval_hint),
            selected = state.category == RecordingCategory.INTERVAL,
            onClick = { viewModel.wizardSetCategory(RecordingCategory.INTERVAL) }
        )
    }

    private fun renderIntervalPause(state: WizardViewState) {
        for (interval in LongInterval.entries) {
            addPickRow(
                label = interval.label,
                sub = null,
                selected = state.intervalPause == interval,
                onClick = { viewModel.wizardSetIntervalPause(interval) }
            )
        }
    }

    private fun renderSessionDuration(state: WizardViewState) {
        for (dur in SessionDuration.entries) {
            addPickRow(
                label = dur.label,
                sub = null,
                selected = state.sessionDuration == dur,
                onClick = { viewModel.wizardSetSessionDuration(dur) }
            )
        }
    }

    private fun renderSummary(state: WizardViewState) {
        val tappable = !state.quickStartMode
        val modelsLine = state.selectedModels.joinToString("\n") { name ->
            val methods = ModelTrainingDuration.requiredMethodsForModel(name)
                .joinToString(" + ") { it.label }
            "${name.stripModelSuffix()}\n  Runs: $methods"
        }
        addSummaryRow(getString(R.string.wizard_summary_models), modelsLine, WizardStep.Models, tappable)
        addSummaryRow(getString(R.string.wizard_summary_category), state.category?.label.orEmpty(), WizardStep.Category, tappable)
        if (state.category == RecordingCategory.INTERVAL) {
            addSummaryRow(getString(R.string.wizard_summary_pause), state.intervalPause?.label.orEmpty(), WizardStep.IntervalPause, tappable)
        }
        addSummaryRow(getString(R.string.wizard_summary_session), state.sessionDuration.label, WizardStep.SessionDuration, tappable)
    }

    private fun addPickRow(
        label: String,
        sub: String?,
        selected: Boolean,
        onClick: () -> Unit
    ) {
        val ctx = requireContext()
        val accentLine = ContextCompat.getColor(ctx, R.color.accent_line)
        val hairline = ContextCompat.getColor(ctx, R.color.hairline)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            background = makeBorderedRect(
                fill = ContextCompat.getColor(ctx, R.color.surface_dark),
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
            lp.bottomMargin = dp(10f)
            layoutParams = lp
        }
        card.addView(TextView(ctx).apply {
            text = label
            textSize = 16f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            setTypeface(typeface, Typeface.BOLD)
        })
        if (!sub.isNullOrBlank()) {
            card.addView(TextView(ctx).apply {
                text = sub
                textSize = 12f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                setPadding(0, dp(4f), 0, 0)
            })
        }
        contentRoot.addView(card)
    }

    private fun addSummaryRow(label: String, value: String, step: WizardStep, tappable: Boolean) {
        val ctx = requireContext()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
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
        card.addView(TextView(ctx).apply {
            text = label
            isAllCaps = true
            textSize = 10.5f
            letterSpacing = 0.1f
            typeface = Typeface.MONOSPACE
            setTextColor(ContextCompat.getColor(ctx, R.color.text_faint))
        })
        card.addView(TextView(ctx).apply {
            text = value.ifBlank { "—" }
            textSize = 15f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            setPadding(0, dp(4f), 0, 0)
        })
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

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
}
