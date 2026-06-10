package com.fzi.acousticscene.ui.live

import android.app.Dialog
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
import com.fzi.acousticscene.data.ActiveSessionRegistry
import com.fzi.acousticscene.data.PredictionRepository
import com.fzi.acousticscene.model.LongSubMode
import com.fzi.acousticscene.model.RecordingCategory
import com.fzi.acousticscene.model.SessionConfig
import com.fzi.acousticscene.model.realOnly
import com.fzi.acousticscene.ui.MainViewModel
import com.fzi.acousticscene.ui.common.AppState
import com.fzi.acousticscene.ui.common.ModeBadge
import com.fzi.acousticscene.ui.common.ModernDialogHelper
import com.fzi.acousticscene.ui.common.UiState
import com.fzi.acousticscene.ui.views.BarDistributionView
import com.fzi.acousticscene.ui.views.ChartPoint
import com.fzi.acousticscene.ui.views.ConcentricStopwatchView
import com.fzi.acousticscene.ui.views.MetricLineChartView
import com.fzi.acousticscene.ui.views.VolumeLineChartView
import com.fzi.acousticscene.util.SceneClassColors
import com.fzi.acousticscene.util.stripModelSuffix
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
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

    companion object {
        /**
         * Boolean nav/bundle arg. true when this screen was opened to re-attach to
         * an already-running session (hub banner tap or notification), false on the
         * normal wizard → live path. Re-entry changes the back behaviour: instead
         * of being a no-op while classifying, back pops to the hub and leaves the
         * session running in the foreground service.
         */
        const val ARG_REENTRY = "reentry"
    }

    private val viewModel: MainViewModel by activityViewModels()

    /** Set in onViewCreated from [ARG_REENTRY]. */
    private var isReentry = false

    private lateinit var statusLabel: TextView
    private lateinit var headerTitle: TextView
    private lateinit var headerDot: View
    private lateinit var stopwatch: ConcentricStopwatchView
    private lateinit var modelCardsContainer: LinearLayout
    private lateinit var blindHint: TextView
    private lateinit var volumeChart: VolumeLineChartView
    private lateinit var tempChart: MetricLineChartView
    private lateinit var cpuChart: MetricLineChartView
    private lateinit var configLabel: TextView
    private lateinit var pauseResumeButton: MaterialButton
    private lateinit var stopButton: MaterialButton
    private lateinit var backButton: ImageButton

    // New v2 header + tile views.
    private lateinit var elapsedBig: TextView
    private lateinit var elapsedSub: TextView
    private lateinit var legendSessionPct: TextView
    private lateinit var legendCyclePct: TextView
    private lateinit var inferenceValue: TextView
    private lateinit var sectionModelsCount: TextView
    private lateinit var volumeReadout: TextView

    // Pause-duration dialog. Held so onDestroyView can dismiss it — a dialog
    // surviving a config change would leak its window.
    private var pauseDialog: Dialog? = null

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

    // Device-metric charts refresh once per finished cycle, keyed off the
    // identity of state.currentResult (the engine swaps it in right after the
    // record is persisted). false until the first render so re-attaching to a
    // running session backfills from the repository immediately.
    private var metricChartsPrimed = false
    private var lastChartedResult: Any? = null

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
        ModeBadge.bind(view.findViewById(R.id.screenModeBadge))
        isReentry = arguments?.getBoolean(ARG_REENTRY, false) == true
        if (isReentry) {
            // Re-attaching to a session that's already running — never trigger the
            // auto-start path, the foreground service is already recording.
            hasAutoStarted = true
        }
        statusLabel = view.findViewById(R.id.liveStatusLabel)
        headerTitle = view.findViewById(R.id.liveHeaderTitle)
        headerDot = view.findViewById(R.id.liveLiveDot)
        stopwatch = view.findViewById(R.id.liveStopwatch)
        modelCardsContainer = view.findViewById(R.id.liveModelCardsContainer)
        blindHint = view.findViewById(R.id.liveBlindHint)
        volumeChart = view.findViewById(R.id.liveVolumeChart)
        tempChart = view.findViewById(R.id.liveTempChart)
        cpuChart = view.findViewById(R.id.liveCpuChart)
        tempChart.configure(
            unitSuffix = "°C",
            yRangeMode = MetricLineChartView.YRangeMode.AUTO_PADDED,
            lineColor = ContextCompat.getColor(requireContext(), R.color.status_warning)
        )
        cpuChart.configure(
            unitSuffix = "%",
            yRangeMode = MetricLineChartView.YRangeMode.ZERO_BASED,
            lineColor = ContextCompat.getColor(requireContext(), R.color.status_info)
        )
        configLabel = view.findViewById(R.id.liveConfigLabel)
        pauseResumeButton = view.findViewById(R.id.livePauseResumeButton)
        stopButton = view.findViewById(R.id.liveStopButton)
        backButton = view.findViewById(R.id.liveBackButton)
        elapsedBig = view.findViewById(R.id.liveElapsedBig)
        elapsedSub = view.findViewById(R.id.liveElapsedSub)
        legendSessionPct = view.findViewById(R.id.liveLegendSessionPct)
        legendCyclePct = view.findViewById(R.id.liveLegendCyclePct)
        inferenceValue = view.findViewById(R.id.liveInferenceValue)
        sectionModelsCount = view.findViewById(R.id.liveSectionModelsCount)
        volumeReadout = view.findViewById(R.id.liveVolumeReadout)
        evaluationCard = view.findViewById(R.id.liveEvaluationCard)
        evaluationTitle = view.findViewById(R.id.liveEvaluationTitle)
        evaluationCountdown = view.findViewById(R.id.liveEvaluationCountdown)
        evaluationOpenButton = view.findViewById(R.id.liveEvaluationOpenButton)
        evaluationSkipButton = view.findViewById(R.id.liveEvaluationSkipButton)
        evaluationSkipButton.setOnClickListener { viewModel.dismissPendingEvaluation() }

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
            // Tapping Pause opens the duration dialog front and center; the
            // session keeps running and pausePending stays false until the user
            // hits "Start pause" inside the dialog. Hitting the button again
            // while the request is already on its way (pausePending, frame
            // closing) acts as a Resume so the user can back out cleanly.
            val state = viewModel.uiState.value
            if (state.isPaused || state.pausePending) {
                viewModel.resumeSession()
            } else {
                showPauseDialog()
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
        pauseDialog?.dismiss()
        pauseDialog = null
        super.onDestroyView()
    }

    private fun showPauseDialog() {
        if (pauseDialog?.isShowing == true) return
        pauseDialog = ModernDialogHelper.showPauseSliderDialog(requireContext()) { durationMs ->
            viewModel.pauseSession(durationMs)
        }
    }

    private fun handleBack() {
        // Re-entry: we walked in to peek at a running session. Back just returns
        // to the hub and leaves the session running in the foreground service —
        // it must NOT stop or clear anything.
        if (isReentry) {
            findNavController().popBackStack()
            return
        }
        if (viewModel.isClassifying()) return
        viewModel.clearSessionResults()
        findNavController().popBackStack()
    }

    private fun render(state: UiState) {
        if (stopInFlight) return
        // The back arrow only renders when pressing it would do something.
        // During an active recording on the normal wizard path handleBack() is
        // a no-op, so the arrow disappears instead of sitting there dead. On
        // re-entry it stays: there it pops to the hub and the session keeps
        // running in the foreground service.
        backButton.visibility =
            if (isReentry || !viewModel.isClassifying()) View.VISIBLE else View.GONE
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

        inferenceValue.text = formatInferenceLabel(state.lastCycleComputeMs, state.sessionComputeMs)

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

        // Header mirrors the pause state — "Recording" with a bright green dot
        // would contradict the paused status line right below it.
        val paused = state.isPaused || state.pausePending
        headerTitle.text = getString(
            if (paused) R.string.live_header_paused else R.string.live_header_title
        )
        headerDot.alpha = if (paused) 0.35f else 1f

        if (!state.isPaused) {
            volumeChart.submitSample(state.currentVolume, state.frameElapsedMs)
        }

        updateMetricCharts(state)

        // Anti-bias blinding gate (interval mode): while the current cycle is
        // earmarked for a "Rate now" prompt, none of its class-bearing UI may
        // render — across ALL model cards. Bars freeze on the previous cycle,
        // the slice strip shows neutral circles, and the hint tile explains
        // why. Timer, volume and device metrics above keep updating; they
        // carry no class information. Once the rating resolves (submit, skip
        // or 5-min expiry) blindCycleActive flips false and the held results,
        // still sitting in liveResultsByModel, paint on the next render.
        val blind = state.blindCycleActive
        blindHint.visibility = if (blind) View.VISIBLE else View.GONE

        ensureCards(config)
        for (model in config.modelNames) {
            val card = cardsByModel[model] ?: continue
            val perMethod = state.liveResultsByModel[model].orEmpty()
            val activeMethods = methodsForModel(config, model)
            for (sub in activeMethods) {
                val section = card.methodSections[sub] ?: continue
                val result = perMethod[sub]
                if (result != null && !blind) {
                    section.bars.setProbabilities(result.allProbabilities)
                }
                if (sub == LongSubMode.AVERAGE && section.sliceCells != null) {
                    val slices = state.perSecondResultsByModel[model].orEmpty()
                    updateSliceStrip(section.sliceCells, slices, blind)
                }
            }
        }
    }

    /**
     * Repaints the battery-temp + CPU charts from the running session's
     * persisted records. Cheap gate: the repository is only queried when a new
     * cycle record landed (state.currentResult identity changed) or on the
     * first render after (re-)attaching, which doubles as the backfill path
     * when the user walks back into an already-running session.
     */
    private fun updateMetricCharts(state: UiState) {
        if (metricChartsPrimed && state.currentResult === lastChartedResult) return
        metricChartsPrimed = true
        lastChartedResult = state.currentResult

        val sessionStart = ActiveSessionRegistry.get()?.sessionStartTime ?: return
        val records = PredictionRepository.getInstance(requireContext())
            .getAllPredictions()
            .filter { it.sessionStartTime == sessionStart }
            .realOnly()
            .sortedBy { it.timestamp }

        tempChart.setPoints(records.mapNotNull { r ->
            r.batteryTempC?.let { ChartPoint((r.timestamp - sessionStart) / 1000f, it) }
        })
        cpuChart.setPoints(records.mapNotNull { r ->
            r.cpuUsagePercent?.let { ChartPoint((r.timestamp - sessionStart) / 1000f, it) }
        })
    }

    /**
     * Walks the 10 oval cells, painting each one with the colour of the class
     * that slice classified as and laying its emoji inside the circle. Empty
     * slots (not yet filled in this cycle) read as dashed hairline circles.
     * The newest filled cell gets an accent border so the live edge is visible.
     *
     * [blind] = anti-bias mode: filled cells render as neutral grey circles
     * without emoji or accent ring, so the strip still shows recording
     * progress but leaks no class information for a to-be-rated cycle.
     */
    private fun updateSliceStrip(
        cells: List<View>,
        slices: List<com.fzi.acousticscene.model.ClassificationResult?>,
        blind: Boolean
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
            } else if (blind) {
                cell.background = filledSliceBackground(
                    ctx,
                    ContextCompat.getColor(ctx, R.color.text_faint),
                    isLiveEdge = false
                )
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
        // Blind self-rating: do not reveal the model's class here, only a neutral
        // prompt. The model guess is shown in EvaluationActivity after the user picks.
        evaluationTitle.text = getString(R.string.eval_blind_prompt)
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

    /** "3.4 s · Σ 1m 05s" — this cycle's compute time plus the session running total. */
    private fun formatInferenceLabel(lastMs: Long, totalMs: Long): String {
        val last = if (lastMs <= 0L) "—"
        else String.format(java.util.Locale.US, "%.1f s", lastMs / 1000.0)
        return "$last · Σ ${formatComputeTotal(totalMs)}"
    }

    private fun formatComputeTotal(ms: Long): String {
        val totalSec = ms / 1000.0
        if (totalSec < 60.0) return String.format(java.util.Locale.US, "%.0f s", totalSec)
        val m = (totalSec / 60).toInt()
        val s = (totalSec % 60).toInt()
        return String.format(java.util.Locale.US, "%dm %02ds", m, s)
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private data class Quadruple<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
