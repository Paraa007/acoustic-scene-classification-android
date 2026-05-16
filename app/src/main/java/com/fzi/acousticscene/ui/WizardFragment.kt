package com.fzi.acousticscene.ui

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.fzi.acousticscene.R
import com.fzi.acousticscene.model.LongInterval
import com.fzi.acousticscene.model.LongSubMode
import com.fzi.acousticscene.model.ModelTrainingDuration
import com.fzi.acousticscene.model.RecordingCategory
import com.fzi.acousticscene.model.SessionDuration
import com.fzi.acousticscene.model.WizardStep
import com.fzi.acousticscene.util.stripModelSuffix
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import kotlinx.coroutines.launch

/**
 * One-fragment wizard. The active step is held on [MainViewModel.wizard]; this
 * fragment swaps its scrollable content area to render the right page for the
 * current step. The back arrow + system back walks one step at a time, leaving
 * the previously entered answers intact, so the user can revise without losing
 * progress (per UI_REDESIGN_WIZARD.md).
 */
class WizardFragment : Fragment(R.layout.fragment_wizard) {

    private val viewModel: MainViewModel by activityViewModels()

    private lateinit var headerText: TextView
    private lateinit var sectionLabel: TextView
    private lateinit var stepDots: LinearLayout
    private lateinit var contentRoot: LinearLayout
    private lateinit var primaryButton: MaterialButton
    private lateinit var backButton: ImageButton

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        headerText = view.findViewById(R.id.wizardHeader)
        sectionLabel = view.findViewById(R.id.wizardSectionLabel)
        stepDots = view.findViewById(R.id.wizardStepDots)
        contentRoot = view.findViewById(R.id.wizardContent)
        primaryButton = view.findViewById(R.id.wizardPrimary)
        backButton = view.findViewById(R.id.wizardBack)

        backButton.setOnClickListener { handleBack() }
        // Hardware back: same as the arrow.
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { handleBack() }
            }
        )

        primaryButton.setOnClickListener {
            val state = viewModel.wizard.value
            if (state.step == WizardStep.Summary) {
                val config = state.toSessionConfig()
                viewModel.applySessionConfig(config)
                findNavController().navigate(R.id.action_wizard_to_live)
            } else {
                viewModel.wizardAdvance()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.wizard.collect { state -> render(state) }
            }
        }
    }

    private fun handleBack() {
        // Quick Start lands directly on the Summary; back leaves the wizard
        // entirely instead of walking through steps the user never visited.
        if (viewModel.wizard.value.quickStartMode) {
            findNavController().popBackStack()
            return
        }
        if (!viewModel.wizardBack()) {
            findNavController().popBackStack()
        }
    }

    private fun render(state: WizardViewState) {
        if (state.quickStartMode) {
            // Quick Start: a single read-only page; "Quick Start" sits inline with
            // the back arrow (matching the History/Settings header pattern), and
            // the per-step description ("Ready to start.") is dropped.
            stepDots.visibility = View.GONE
            headerText.visibility = View.GONE
            sectionLabel.visibility = View.VISIBLE
            sectionLabel.text = getString(R.string.welcome_quick_start)
            contentRoot.setPadding(0, dp(20f), 0, dp(16f))
        } else {
            sectionLabel.visibility = View.GONE
            headerText.visibility = View.VISIBLE
            headerText.text = state.step.headerText
            stepDots.visibility = View.VISIBLE
            val order = state.stepOrder()
            val pos = order.indexOf(state.step) + 1
            renderStepDots(pos, order.size)
            contentRoot.setPadding(0, 0, 0, dp(16f))
        }
        primaryButton.text = if (state.step == WizardStep.Summary) {
            getString(R.string.wizard_start)
        } else {
            getString(R.string.wizard_next)
        }
        primaryButton.isEnabled = state.canAdvance()

        contentRoot.removeAllViews()
        when (state.step) {
            WizardStep.Models -> renderModels(state)
            WizardStep.Category -> renderCategory(state)
            WizardStep.ClipDuration -> renderClipDuration(state)
            WizardStep.IntervalPause -> renderIntervalPause(state)
            WizardStep.IntervalMethods -> renderIntervalMethods(state)
            WizardStep.SessionDuration -> renderSessionDuration(state)
            WizardStep.Summary -> renderSummary(state)
        }
    }

    private fun renderStepDots(active: Int, total: Int) {
        stepDots.removeAllViews()
        val ctx = requireContext()
        val gap = dp(4f)
        for (i in 1..total) {
            val isActive = i == active
            val sizePx = dp(if (isActive) 8f else 6f)
            val view = ImageView(ctx).apply {
                setImageDrawable(ContextCompat.getDrawable(ctx,
                    if (isActive) R.drawable.wizard_dot_active
                    else R.drawable.wizard_dot_inactive
                ))
                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                    if (i > 1) marginStart = gap
                    gravity = Gravity.CENTER_VERTICAL
                }
            }
            stepDots.addView(view)
        }
    }

    private fun renderModels(state: WizardViewState) {
        contentRoot.addView(hint(getString(R.string.wizard_models_hint)))
        val ctx = requireContext()
        val selected = state.selectedModels.toMutableSet()
        for (modelName in state.availableModels) {
            val checkbox = MaterialCheckBox(ctx).apply {
                text = modelName.stripModelSuffix()
                textSize = 15f
                isChecked = modelName in selected
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected.add(modelName) else selected.remove(modelName)
                    viewModel.wizardSetModels(selected.toList())
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = dp(4f)
                layoutParams = lp
            }
            contentRoot.addView(checkbox)
        }
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

    private fun renderClipDuration(state: WizardViewState) {
        // In Continuous, the same sub-mode runs against every selected model, so
        // a clip duration is only available if it matches every model's training
        // duration. Mixing 10 s + 1 s models leaves nothing valid — we surface
        // that explicitly via the hint instead of presenting broken choices.
        val durations = state.selectedModels.map { ModelTrainingDuration.secondsForFilename(it) }.toSet()
        val mixedDurations = durations.size > 1
        if (mixedDurations) {
            contentRoot.addView(hint(getString(R.string.wizard_clip_mixed_durations)))
        }
        for (sub in listOf(LongSubMode.FAST, LongSubMode.STANDARD, LongSubMode.AVERAGE)) {
            val compatibleAll = durations.all { sub.isCompatibleWith(it) }
            val labelRes = when (sub) {
                LongSubMode.FAST -> R.string.wizard_clip_fast
                LongSubMode.STANDARD -> R.string.wizard_clip_standard
                LongSubMode.AVERAGE -> R.string.wizard_clip_avg
            }
            val descRes = when (sub) {
                LongSubMode.FAST -> R.string.mode_desc_fast
                LongSubMode.STANDARD -> R.string.mode_desc_standard
                LongSubMode.AVERAGE -> R.string.mode_desc_avg
            }
            addPickRow(
                label = getString(labelRes),
                sub = if (compatibleAll) getString(descRes)
                else getString(R.string.wizard_clip_incompatible_for_models),
                selected = state.continuousSubMode == sub && compatibleAll,
                enabled = compatibleAll,
                onClick = { viewModel.wizardSetContinuousSubMode(sub) }
            )
        }
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

    private fun renderIntervalMethods(state: WizardViewState) {
        viewModel.wizardEnsureMethodDefaults()
        val current = viewModel.wizard.value.intervalMethodsByModel
        for (model in state.selectedModels) {
            val card = MaterialCardView(requireContext()).apply {
                radius = dp(12f).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1f)
                strokeColor = ContextCompat.getColor(context, R.color.text_secondary)
                setCardBackgroundColor(ContextCompat.getColor(context, R.color.surface_dark))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = dp(12f)
                layoutParams = lp
            }
            val inner = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            }
            val title = TextView(requireContext()).apply {
                text = getString(R.string.wizard_methods_per_model, model.stripModelSuffix())
                textSize = 15f
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            }
            inner.addView(title)
            val locked = ModelTrainingDuration.defaultSubMode(model)
            val modelDuration = ModelTrainingDuration.secondsForFilename(model)
            val checked = current[model].orEmpty()
            for (sub in LongSubMode.entries) {
                val compatible = sub.isCompatibleWith(modelDuration)
                val row = MaterialCheckBox(requireContext()).apply {
                    text = when {
                        sub == locked -> "${sub.label}  · ${getString(R.string.wizard_method_locked)}"
                        !compatible -> "${sub.label}  · ${getString(R.string.wizard_method_incompatible, modelDuration)}"
                        else -> sub.label
                    }
                    isChecked = sub in checked && compatible
                    // Locked default cannot be unchecked; incompatible methods are
                    // disabled so the user can't pick a Standard for a 1s-model
                    // (or vice versa).
                    isEnabled = sub != locked && compatible
                    setOnCheckedChangeListener { _, _ -> viewModel.wizardToggleIntervalMethod(model, sub) }
                }
                inner.addView(row)
            }
            card.addView(inner)
            contentRoot.addView(card)
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
        addSummaryRow(getString(R.string.wizard_summary_models), state.selectedModels.joinToString("\n") { it.stripModelSuffix() }, WizardStep.Models, tappable)
        addSummaryRow(getString(R.string.wizard_summary_category), state.category?.label.orEmpty(), WizardStep.Category, tappable)
        when (state.category) {
            RecordingCategory.CONTINUOUS -> addSummaryRow(
                getString(R.string.wizard_summary_clip),
                state.continuousSubMode?.label.orEmpty(),
                WizardStep.ClipDuration,
                tappable
            )
            RecordingCategory.INTERVAL -> {
                addSummaryRow(getString(R.string.wizard_summary_pause), state.intervalPause?.label.orEmpty(), WizardStep.IntervalPause, tappable)
                val methodLines = state.selectedModels.joinToString("\n") { name ->
                    "${name.stripModelSuffix()}: " + state.intervalMethodsByModel[name].orEmpty().joinToString(", ") { it.label }
                }
                addSummaryRow(getString(R.string.wizard_summary_methods), methodLines, WizardStep.IntervalMethods, tappable)
            }
            null -> Unit
        }
        addSummaryRow(getString(R.string.wizard_summary_session), state.sessionDuration.label, WizardStep.SessionDuration, tappable)
    }

    private fun addPickRow(
        label: String,
        sub: String?,
        selected: Boolean,
        enabled: Boolean = true,
        onClick: () -> Unit
    ) {
        val ctx = requireContext()
        val card = MaterialCardView(ctx).apply {
            radius = dp(12f).toFloat()
            cardElevation = 0f
            strokeWidth = dp(if (selected) 2f else 1f)
            strokeColor = ContextCompat.getColor(
                context,
                if (selected) R.color.accent_green else R.color.text_secondary
            )
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.surface_dark))
            isClickable = enabled
            isFocusable = enabled
            alpha = if (enabled) 1f else 0.4f
            if (enabled) setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(10f)
            layoutParams = lp
        }
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        }
        inner.addView(TextView(ctx).apply {
            text = label
            textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        })
        if (!sub.isNullOrBlank()) {
            inner.addView(TextView(ctx).apply {
                text = sub
                textSize = 12f
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setPadding(0, dp(4f), 0, 0)
            })
        }
        card.addView(inner)
        contentRoot.addView(card)
    }

    private fun addSummaryRow(label: String, value: String, step: WizardStep, tappable: Boolean = true) {
        val ctx = requireContext()
        val card = MaterialCardView(ctx).apply {
            radius = dp(10f).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1f)
            strokeColor = ContextCompat.getColor(context, R.color.text_secondary)
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.surface_dark))
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
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
        }
        inner.addView(TextView(ctx).apply {
            text = label
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.accent_blue))
        })
        inner.addView(TextView(ctx).apply {
            text = value.ifBlank { "—" }
            textSize = 15f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setPadding(0, dp(4f), 0, 0)
        })
        card.addView(inner)
        contentRoot.addView(card)
    }

    private fun hint(text: String): View {
        return TextView(requireContext()).apply {
            this.text = text
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(0, 0, 0, dp(12f))
        }
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
}
