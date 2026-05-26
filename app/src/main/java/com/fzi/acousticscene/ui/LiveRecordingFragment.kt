package com.fzi.acousticscene.ui

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.fzi.acousticscene.R
import com.fzi.acousticscene.model.LongSubMode
import com.fzi.acousticscene.model.RecordingCategory
import com.fzi.acousticscene.model.SessionConfig
import com.fzi.acousticscene.util.SceneClassColors
import com.fzi.acousticscene.util.stripModelSuffix
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The live session screen. Layout:
 *   - Concentric stopwatch (session + cycle progress)
 *   - One card per selected model, each card carrying a sub-section per active
 *     method with a sorted-descending bar distribution of the 9 classes
 *   - For AVERAGE sections: a permanent 10-circle slice strip above the
 *     distribution, one circle per second of the current 10 s cycle, coloured
 *     by the class each slice classified as (per v2 spec)
 *   - Permanent volume chart
 *   - Pause/Resume + Stop fixed at the bottom
 *
 * When the session ends (manual stop or soft-stop), navigates forward to the
 * Results Summary fragment.
 */
class LiveRecordingFragment : Fragment(R.layout.fragment_live_recording) {

    private val viewModel: MainViewModel by activityViewModels()

    private lateinit var statusLabel: TextView
    private lateinit var stopwatch: ConcentricStopwatchView
    private lateinit var modelCardsContainer: LinearLayout
    private lateinit var volumeChart: VolumeLineChartView
    private lateinit var configLabel: TextView
    private lateinit var pauseResumeButton: MaterialButton
    private lateinit var stopButton: MaterialButton
    private lateinit var backButton: ImageButton

    // New v2 header + tile views.
    private lateinit var elapsedBig: TextView
    private lateinit var elapsedSub: TextView
    private lateinit var legendSessionPct: TextView
    private lateinit var legendCyclePct: TextView
    private lateinit var sectionModelsCount: TextView
    private lateinit var volumeReadout: TextView

    // Inline pause-duration picker tile views.
    private lateinit var pausePickerCard: LinearLayout
    private lateinit var pauseDurationDisplay: TextView
    private lateinit var pauseSlider: Slider
    private lateinit var pausePickerCancelButton: MaterialButton
    private lateinit var pausePickerConfirmButton: MaterialButton

    // Persistent in-app evaluation card — only visible while pendingEvaluation
    // is non-null (Interval mode + foreground after a finished cycle).
    private lateinit var evaluationCard: MaterialCardView
    private lateinit var evaluationTitle: TextView
    private lateinit var evaluationCountdown: TextView
    private lateinit var evaluationOpenButton: MaterialButton
    private lateinit var evaluationSkipButton: MaterialButton
    private var evaluationCountdownJob: Job? = null
    private var renderedEvaluationId: Long? = null
    private var pauseCountdownJob: Job? = null

    // Cached per-card UI handles, rebuilt only when the model set changes.
    private val cardsByModel = LinkedHashMap<String, ModelCardViews>()
    private var lastBuiltModelKey: String = ""

    private var hasAutoStarted = false
    private var stopInFlight = false

    private data class ModelCardViews(
        val container: LinearLayout,
        val methodSections: MutableMap<LongSubMode, MethodSectionViews> = mutableMapOf()
    )

    private data class MethodSectionViews(
        val bars: BarDistributionView,
        /** 10 oval cells for the AVERAGE slice strip; null on STANDARD/FAST sections. */
        val sliceCells: List<View>? = null
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        statusLabel = view.findViewById(R.id.liveStatusLabel)
        stopwatch = view.findViewById(R.id.liveStopwatch)
        modelCardsContainer = view.findViewById(R.id.liveModelCardsContainer)
        volumeChart = view.findViewById(R.id.liveVolumeChart)
        configLabel = view.findViewById(R.id.liveConfigLabel)
        pauseResumeButton = view.findViewById(R.id.livePauseResumeButton)
        stopButton = view.findViewById(R.id.liveStopButton)
        backButton = view.findViewById(R.id.liveBackButton)
        elapsedBig = view.findViewById(R.id.liveElapsedBig)
        elapsedSub = view.findViewById(R.id.liveElapsedSub)
        legendSessionPct = view.findViewById(R.id.liveLegendSessionPct)
        legendCyclePct = view.findViewById(R.id.liveLegendCyclePct)
        sectionModelsCount = view.findViewById(R.id.liveSectionModelsCount)
        volumeReadout = view.findViewById(R.id.liveVolumeReadout)
        pausePickerCard = view.findViewById(R.id.livePausePickerCard)
        pauseDurationDisplay = view.findViewById(R.id.livePauseDurationDisplay)
        pauseSlider = view.findViewById(R.id.livePauseSlider)
        pausePickerCancelButton = view.findViewById(R.id.livePausePickerCancelButton)
        pausePickerConfirmButton = view.findViewById(R.id.livePausePickerConfirmButton)
        evaluationCard = view.findViewById(R.id.liveEvaluationCard)
        evaluationTitle = view.findViewById(R.id.liveEvaluationTitle)
        evaluationCountdown = view.findViewById(R.id.liveEvaluationCountdown)
        evaluationOpenButton = view.findViewById(R.id.liveEvaluationOpenButton)
        evaluationSkipButton = view.findViewById(R.id.liveEvaluationSkipButton)
        evaluationSkipButton.setOnClickListener { viewModel.dismissPendingEvaluation() }

        // Pause-duration slider: 0–48 steps × 15 min = 0..12 h. Default 13 (~3h 15min)
        pauseSlider.addOnChangeListener { _, value, _ ->
            pauseDurationDisplay.text = formatPauseDurationLabel(value.toInt())
        }
        pauseDurationDisplay.text = formatPauseDurationLabel(pauseSlider.value.toInt())
        pausePickerCancelButton.setOnClickListener { hidePausePickerCard() }
        pausePickerConfirmButton.setOnClickListener {
            val steps = pauseSlider.value.toInt()
            val durationMs: Long? = if (steps <= 0) null else steps * 15L * 60_000L
            hidePausePickerCard()
            viewModel.pauseSession(durationMs)
        }
        evaluationOpenButton.setOnClickListener {
            val pending = viewModel.uiState.value.pendingEvaluation ?: return@setOnClickListener
            val intent = Intent(requireContext(), EvaluationActivity::class.java).apply {
                putExtra(EvaluationActivity.EXTRA_PREDICTION_ID, pending.predictionId)
                putExtra(EvaluationActivity.EXTRA_MODEL_PREDICTED_CLASS, pending.modelClass.name)
            }
            startActivity(intent)
        }

        volumeChart.setDrawingEnabled(true)

        backButton.setOnClickListener { handleBack() }
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { handleBack() }
            }
        )
        pauseResumeButton.setOnClickListener {
            // Per v2 spec: tapping Pause only opens the duration picker; the
            // session keeps running and pausePending stays false until the user
            // hits Confirm inside the picker tile. Hitting the button again
            // while the request is already on its way (pausePending, frame
            // closing) acts as a Resume so the user can back out cleanly.
            val state = viewModel.uiState.value
            if (state.isPaused || state.pausePending) {
                viewModel.resumeSession()
            } else {
                showPausePickerCard()
            }
        }
        stopButton.setOnClickListener {
            stopInFlight = true
            viewModel.stopSession()
            findNavController().navigate(R.id.action_live_to_results)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> render(state) }
            }
        }
    }

    override fun onDestroyView() {
        evaluationCountdownJob?.cancel()
        evaluationCountdownJob = null
        pauseCountdownJob?.cancel()
        pauseCountdownJob = null
        super.onDestroyView()
    }

    private fun handleBack() {
        if (viewModel.isClassifying()) return
        viewModel.clearSessionResults()
        findNavController().popBackStack()
    }

    private fun render(state: UiState) {
        if (stopInFlight) return
        val config = state.sessionConfig ?: run {
            // No active config — bail back to whichever home is on the stack so
            // the user never lands on a recording screen with nothing to record.
            val nav = findNavController()
            if (!nav.popBackStack(R.id.welcomeFragment, false) &&
                !nav.popBackStack(R.id.testWelcomeFragment, false)) {
                nav.popBackStack(R.id.modeSelectFragment, false)
            }
            return
        }
        if (!hasAutoStarted &&
            state.isModelLoaded &&
            !viewModel.isClassifying() &&
            state.appState !is AppState.Error
        ) {
            hasAutoStarted = true
            viewModel.startSession()
        }

        stopwatch.sessionElapsedMs = state.sessionElapsedMs
        stopwatch.sessionTotalMs = config.sessionDuration.totalMs
        stopwatch.cycleProgress = state.frameElapsedMs / 10_000f
        stopwatch.paused = state.isPaused
        stopwatch.cycleSegments = state.frameSegments

        val statusText = labelForState(state)
        statusLabel.text = statusText
        // The label is gone by default in XML and only surfaces while something
        // is worth saying (loading, error, or — the v2-spec case — a confirmed
        // pause with its countdown ticking at the top of the screen).
        statusLabel.visibility = if (statusText.isEmpty()) View.GONE else View.VISIBLE
        managePauseCountdown(state)

        configLabel.text = config.shortLabel()

        renderEvaluationCard(state)

        pauseResumeButton.text = getString(
            if (state.isPaused || state.pausePending) R.string.live_resume else R.string.live_pause
        )

        if (!state.isPaused) {
            volumeChart.submitSample(state.currentVolume, state.frameElapsedMs)
        }

        ensureCards(config)
        for (model in config.modelNames) {
            val card = cardsByModel[model] ?: continue
            val perMethod = state.liveResultsByModel[model].orEmpty()
            val activeMethods = methodsForModel(config, model)
            for (sub in activeMethods) {
                val section = card.methodSections[sub] ?: continue
                val result = perMethod[sub]
                if (result != null) {
                    section.bars.setProbabilities(result.allProbabilities)
                }
                if (sub == LongSubMode.AVERAGE && section.sliceCells != null) {
                    val slices = state.perSecondResultsByModel[model].orEmpty()
                    updateSliceStrip(section.sliceCells, slices)
                }
            }
        }
    }

    /**
     * Walks the 10 oval cells, painting each one with the colour of the class
     * that slice classified as and laying its emoji inside the circle. Empty
     * slots (not yet filled in this cycle) read as dashed hairline circles.
     * The newest filled cell gets an accent border so the live edge is visible.
     */
    private fun updateSliceStrip(
        cells: List<View>,
        slices: List<com.fzi.acousticscene.model.ClassificationResult?>
    ) {
        val ctx = requireContext()
        val newestIdx = slices.indexOfLast { it != null }
        for (i in cells.indices) {
            val cell = cells[i] as? android.widget.FrameLayout ?: continue
            val emojiView = cell.getChildAt(0) as? TextView ?: continue
            val slice = slices.getOrNull(i)
            if (slice == null) {
                cell.background = emptySliceBackground(ctx)
                emojiView.text = ""
            } else {
                cell.background = filledSliceBackground(
                    ctx,
                    SceneClassColors.color(ctx, slice.sceneClass),
                    isLiveEdge = i == newestIdx
                )
                emojiView.text = slice.sceneClass.emoji
            }
        }
    }

    private fun ensureCards(config: SessionConfig) {
        val key = config.modelNames.joinToString("|") + "::" + config.category.name
        if (key == lastBuiltModelKey && cardsByModel.size == config.modelNames.size) return
        lastBuiltModelKey = key
        modelCardsContainer.removeAllViews()
        cardsByModel.clear()
        val ctx = requireContext()
        for (model in config.modelNames) {
            val container = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            }
            val card = MaterialCardView(ctx).apply {
                radius = dp(17f).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1f).coerceAtLeast(1)
                strokeColor = ContextCompat.getColor(context, R.color.hairline)
                setCardBackgroundColor(ContextCompat.getColor(context, R.color.surface_dark))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = dp(11f)
                layoutParams = lp
            }
            // Card header — mono filename
            container.addView(TextView(ctx).apply {
                text = model.stripModelSuffix()
                textSize = 11.5f
                typeface = Typeface.MONOSPACE
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                setPadding(0, 0, 0, dp(2f))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            val views = ModelCardViews(container = container)
            val methods = methodsForModel(config, model)
            for ((index, sub) in methods.withIndex()) {
                container.addView(TextView(ctx).apply {
                    text = "Method · ${sub.label}"
                    textSize = 10f
                    setTextColor(ContextCompat.getColor(context, R.color.text_faint))
                    setPadding(0, dp(if (index == 0) 2f else 12f), 0, dp(6f))
                })
                val bars = BarDistributionView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                val sliceCells: List<View>? = if (sub == LongSubMode.AVERAGE) {
                    val (sliceHeader, strip, axis, cells) = buildSliceStrip(ctx)
                    container.addView(sliceHeader)
                    container.addView(strip)
                    container.addView(axis)
                    cells
                } else null
                container.addView(bars)
                views.methodSections[sub] = MethodSectionViews(bars, sliceCells)
            }
            card.addView(container)
            modelCardsContainer.addView(card)
            cardsByModel[model] = views
        }
    }

    private fun buildSliceStrip(
        ctx: android.content.Context
    ): Quadruple<View, LinearLayout, View, List<View>> {
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6f))
        }
        header.addView(TextView(ctx).apply {
            text = "Last 10 s · per-second slices"
            textSize = 10f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_faint))
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        header.addView(TextView(ctx).apply {
            text = "avg →"
            isAllCaps = false
            textSize = 9.5f
            typeface = Typeface.MONOSPACE
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
        })

        val strip = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Distribute the 10 round cells across the full strip width.
            weightSum = 10f
            setPadding(dp(2f), dp(2f), dp(2f), dp(2f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val cells = mutableListOf<View>()
        val cellSize = dp(22f)
        for (i in 0 until 10) {
            // Wrapper takes the weight slot, the inner circle is fixed-square so it
            // always renders as a circle no matter the screen width.
            val slot = android.widget.FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, cellSize, 1f)
            }
            val cell = android.widget.FrameLayout(ctx).apply {
                background = emptySliceBackground(ctx)
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    cellSize,
                    cellSize
                ).apply { gravity = Gravity.CENTER }
            }
            val emoji = TextView(ctx).apply {
                text = ""
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            cell.addView(emoji)
            slot.addView(cell)
            strip.addView(slot)
            cells += cell
        }

        val axis = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(5f), 0, dp(8f))
        }
        val axisColor = ContextCompat.getColor(ctx, R.color.text_faint)
        axis.addView(axisLabel(ctx, "−10 s", axisColor).apply {
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).also { it.gravity = Gravity.START }
            gravity = Gravity.START
        })
        axis.addView(axisLabel(ctx, "−5 s", axisColor).apply {
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            gravity = Gravity.CENTER
        })
        axis.addView(axisLabel(ctx, "now", axisColor).apply {
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            gravity = Gravity.END
        })

        return Quadruple(header, strip, axis, cells)
    }

    private fun axisLabel(ctx: android.content.Context, text: String, color: Int): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = 8.5f
            typeface = Typeface.MONOSPACE
            setTextColor(color)
            letterSpacing = 0.04f
        }

    private fun emptySliceBackground(ctx: android.content.Context): GradientDrawable {
        // Empty slot: dashed hairline circle. The dashes must register against the
        // light card surface, so use text_faint (darker than hairline) and widen
        // both the stroke and the dash pattern so the dots are actually readable.
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(android.graphics.Color.TRANSPARENT)
            setStroke(
                dp(1.4f).coerceAtLeast(1),
                ContextCompat.getColor(ctx, R.color.text_faint),
                dp(2.5f).toFloat(),
                dp(2f).toFloat()
            )
        }
    }

    private fun filledSliceBackground(
        ctx: android.content.Context,
        fillColor: Int,
        isLiveEdge: Boolean
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fillColor)
            if (isLiveEdge) {
                // Newest slice: accent ring marks the live edge ("now").
                setStroke(dp(1.6f).coerceAtLeast(2), ContextCompat.getColor(ctx, R.color.accent_green))
            }
        }
    }

    private fun methodsForModel(config: SessionConfig, modelName: String): List<LongSubMode> {
        val active = when (config.category) {
            RecordingCategory.CONTINUOUS -> config.continuousMethodsByModel[modelName].orEmpty()
            RecordingCategory.INTERVAL -> config.intervalMethodsByModel[modelName].orEmpty()
        }
        return listOf(LongSubMode.STANDARD, LongSubMode.FAST, LongSubMode.AVERAGE).filter { it in active }
    }

    private fun labelForState(state: UiState): String {
        if (state.pausePending) {
            val total = state.pauseTotalMs
            return if (total != null) {
                "Paused · ${formatClockSeconds((total / 1000L).toInt())}"
            } else "Paused"
        }
        if (state.isPaused) {
            val deadline = state.userPauseDeadlineElapsedMs
            return if (deadline != null) {
                val remainingMs = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                "Paused · ${formatClockSeconds((remainingMs / 1000L).toInt())}"
            } else "Paused"
        }
        return when (val s = state.appState) {
            is AppState.Error -> "Error: ${s.message}"
            is AppState.Loading -> getString(R.string.live_loading_models)
            is AppState.Ready -> if (state.isModelLoaded) "" else getString(R.string.live_loading_models)
            else -> ""
        }
    }

    private fun managePauseCountdown(state: UiState) {
        val needsTicker = state.isPaused && state.userPauseDeadlineElapsedMs != null
        if (!needsTicker) {
            pauseCountdownJob?.cancel()
            pauseCountdownJob = null
            return
        }
        if (pauseCountdownJob?.isActive == true) return
        pauseCountdownJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val current = viewModel.uiState.value
                if (!current.isPaused || current.userPauseDeadlineElapsedMs == null) break
                val text = labelForState(current)
                statusLabel.text = text
                statusLabel.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
                delay(1000L)
            }
        }
    }

    private fun showPauseDurationPicker() {
        val options = listOf(
            ModernDialogHelper.PauseDurationOption(getString(R.string.pause_picker_no_timer), null),
            ModernDialogHelper.PauseDurationOption("5 min", 5L * 60_000L),
            ModernDialogHelper.PauseDurationOption("10 min", 10L * 60_000L),
            ModernDialogHelper.PauseDurationOption("30 min", 30L * 60_000L),
            ModernDialogHelper.PauseDurationOption("1 h", 60L * 60_000L)
        )
        ModernDialogHelper.showPauseDurationDialog(
            context = requireContext(),
            title = getString(R.string.pause_picker_title),
            subtitle = getString(R.string.pause_picker_subtitle),
            options = options
        ) { picked -> viewModel.pauseSession(picked.durationMs) }
    }

    private fun renderEvaluationCard(state: UiState) {
        val pending = state.pendingEvaluation
        if (pending == null) {
            evaluationCard.visibility = View.GONE
            evaluationCountdownJob?.cancel()
            evaluationCountdownJob = null
            renderedEvaluationId = null
            return
        }
        evaluationCard.visibility = View.VISIBLE
        evaluationTitle.text = "${pending.modelClass.emoji} ${pending.modelClass.label}: ${getString(R.string.evaluation_question)}"
        if (renderedEvaluationId != pending.predictionId) {
            renderedEvaluationId = pending.predictionId
            evaluationCountdownJob?.cancel()
            evaluationCountdownJob = viewLifecycleOwner.lifecycleScope.launch {
                while (isActive) {
                    val remainingMs = pending.deadlineElapsedMs - SystemClock.elapsedRealtime()
                    if (remainingMs <= 0L) {
                        evaluationCountdown.text = "0:00"
                        break
                    }
                    val totalSeconds = (remainingMs / 1000L).toInt()
                    evaluationCountdown.text = formatClockSeconds(totalSeconds)
                    delay(1000L)
                }
            }
        }
    }

    private fun formatClockSeconds(totalSeconds: Int): String {
        if (totalSeconds < 60) return String.format(java.util.Locale.US, "%d s", totalSeconds)
        val s = totalSeconds % 60
        val m = (totalSeconds / 60) % 60
        val h = totalSeconds / 3600
        return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(java.util.Locale.US, "%d:%02d", m, s)
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private fun hidePausePickerCard() {
        pausePickerCard.visibility = View.GONE
    }

    private fun showPausePickerCard() {
        pausePickerCard.visibility = View.VISIBLE
        pauseDurationDisplay.text = formatPauseDurationLabel(pauseSlider.value.toInt())
    }

    /**
     * Slider steps are 15-min increments from 0 up to 48 (= 12 h).
     * 0          → "No timer"
     * 1..3       → "X min"
     * 4..47      → "Xh Ymin" or "Xh"
     * 48         → "12 h"
     */
    private fun formatPauseDurationLabel(steps: Int): String {
        val totalMinutes = steps * 15
        if (totalMinutes <= 0) return getString(R.string.pause_picker_no_timer)
        if (totalMinutes < 60) return "$totalMinutes min"
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (minutes == 0) "${hours} h" else "${hours} h ${minutes} min"
    }

    private data class Quadruple<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
