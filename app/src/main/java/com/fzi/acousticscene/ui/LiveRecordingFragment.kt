package com.fzi.acousticscene.ui

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
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

    // Persistent in-app evaluation card — only visible while pendingEvaluation
    // is non-null (Interval mode + foreground after a finished cycle).
    private lateinit var evaluationCard: MaterialCardView
    private lateinit var evaluationTitle: TextView
    private lateinit var evaluationCountdown: TextView
    private lateinit var evaluationOpenButton: MaterialButton
    private lateinit var evaluationSkipButton: MaterialButton
    private var evaluationCountdownJob: Job? = null
    private var renderedEvaluationId: Long? = null
    // Re-renders the status label once per second while a pause-with-timer is
    // active, so the auto-resume countdown decreases visibly. The session timer
    // in the ViewModel does not emit while paused.
    private var pauseCountdownJob: Job? = null

    // Cached per-card UI handles, rebuilt only when the model set changes.
    private val cardsByModel = LinkedHashMap<String, ModelCardViews>()
    private var lastBuiltModelKey: String = ""
    private var liveDataExpanded: MutableMap<String, Boolean> = mutableMapOf()

    // One-shot auto-start guard. Without this, render() would re-trigger
    // startSession() the moment onSessionLoopExit() flips appState back to Ready
    // (right before the user-tapped Stop pops this fragment), spawning a second
    // recording loop and leaving the previous session orphaned but still spinning.
    private var hasAutoStarted = false
    // True while the user-initiated Stop is in flight. Suppresses any further
    // render() side-effects on this fragment so a tail uiState update can't
    // re-launch the session.
    private var stopInFlight = false

    private data class ModelCardViews(
        val container: LinearLayout,
        val methodSections: MutableMap<LongSubMode, MethodSectionViews> = mutableMapOf()
    )

    private data class MethodSectionViews(
        val bars: BarDistributionView,
        val liveDataToggle: MaterialButton?,
        val perSecondCircles: LinearLayout?,
        // Per-second class label (emoji) shown above each circle so the user
        // can read at a glance which class won that one-second clip — without
        // it, ten 28dp percentages are unreadable.
        val perSecondLabels: List<TextView> = emptyList()
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

        // The volume meter is permanent during a session — no toggle.
        volumeChart.setDrawingEnabled(true)

        backButton.setOnClickListener { handleBack() }
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { handleBack() }
            }
        )
        pauseResumeButton.setOnClickListener {
            if (viewModel.uiState.value.isPaused) {
                viewModel.resumeSession()
            } else {
                showPauseDurationPicker()
            }
        }
        stopButton.setOnClickListener {
            stopInFlight = true
            viewModel.stopSession()
            findNavController().navigate(R.id.action_live_to_results)
        }

        // Auto-start the session as soon as the model is loaded. The wizard
        // primary button calls applySessionConfig() which kicks off model
        // loading; we wait until appState becomes Ready, then call startSession().
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
        // While running, Stop is the only way out — otherwise pop back to wizard
        // and release the parked models so they don't pile up in memory across
        // wizard restarts.
        if (viewModel.isClassifying()) return
        viewModel.clearSessionResults()
        findNavController().popBackStack()
    }

    private fun render(state: UiState) {
        if (stopInFlight) return
        val config = state.sessionConfig ?: run {
            // Not configured — bounce back to welcome.
            findNavController().popBackStack(R.id.welcomeFragment, false)
            return
        }
        // Auto-start exactly once per fragment lifetime. After the user stops
        // and the loop's finally restores appState=Ready, this would otherwise
        // re-fire and launch a second concurrent session.
        if (!hasAutoStarted &&
            state.isModelLoaded &&
            !viewModel.isClassifying() &&
            state.appState !is AppState.Error
        ) {
            hasAutoStarted = true
            viewModel.startSession()
        }

        // Stopwatch + volume chart share the same frame clock so they stay in
        // lock-step — `frameElapsedMs` is 0..10000 for the active 10 s frame.
        stopwatch.sessionElapsedMs = state.sessionElapsedMs
        stopwatch.sessionTotalMs = config.sessionDuration.totalMs
        stopwatch.cycleProgress = state.frameElapsedMs / 10_000f
        stopwatch.paused = state.isPaused
        stopwatch.cycleSegments = state.frameSegments

        statusLabel.text = labelForState(state)
        managePauseCountdown(state)

        // Persistent config footer so the user always knows what's running.
        configLabel.text = config.shortLabel()

        renderEvaluationCard(state)

        pauseResumeButton.text = getString(
            if (state.isPaused || state.pausePending) R.string.live_resume else R.string.live_pause
        )

        // Volume — only sample while a frame is actively recording. During a
        // real pause we freeze the last drawn line on screen; during an
        // interval-pause the ViewModel resets frameElapsedMs to 0 and the
        // chart clears automatically on the next frame.
        if (!state.isPaused) {
            volumeChart.submitSample(state.currentVolume, state.frameElapsedMs)
        }

        // Build/update per-model cards.
        ensureCards(config)
        for (model in config.modelNames) {
            val card = cardsByModel[model] ?: continue
            val perMethod = state.liveResultsByModel[model].orEmpty()
            val activeMethods = methodsForModel(config, model)
            for ((index, sub) in activeMethods.withIndex()) {
                val section = card.methodSections[sub] ?: continue
                val result = perMethod[sub]
                if (result != null) {
                    section.bars.setProbabilities(result.allProbabilities)
                }
                // Live-data circles for AVG sections — every 1 s-trained model
                // streams its own per-second predictions, read independently
                // from perSecondResultsByModel.
                if (sub == LongSubMode.AVERAGE && section.perSecondCircles != null) {
                    val expanded = liveDataExpanded[cardKey(model, sub)] == true
                    section.perSecondCircles.visibility = if (expanded) View.VISIBLE else View.GONE
                    if (expanded) {
                        val slices = state.perSecondResultsByModel[model].orEmpty()
                        for (i in 0 until 10) {
                            val cell = section.perSecondCircles.getChildAt(i) as? LinearLayout ?: continue
                            val circle = cell.getChildAt(1) as? ConfidenceCircleView ?: continue
                            val r = slices.getOrNull(i)
                            circle.setConfidence(r?.confidence ?: 0f, animate = false)
                            section.perSecondLabels.getOrNull(i)?.text = r?.sceneClass?.emoji ?: "·"
                        }
                    }
                }
            }
        }
    }

    private fun ensureCards(config: SessionConfig) {
        fun methodsFingerprint(map: Map<String, Set<LongSubMode>>) = map.entries
            .sortedBy { it.key }
            .joinToString("|") { (k, v) -> "$k=" + v.joinToString(",") { it.name } }
        val key = config.modelNames.joinToString("|") + "::" + config.category.name +
                "::C:" + methodsFingerprint(config.continuousMethodsByModel) +
                "::I:" + methodsFingerprint(config.intervalMethodsByModel)
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
                radius = dp(12f).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1f).coerceAtLeast(1)
                strokeColor = ContextCompat.getColor(context, R.color.text_secondary)
                setCardBackgroundColor(ContextCompat.getColor(context, R.color.surface_dark))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = dp(12f)
                layoutParams = lp
            }
            // Card header
            container.addView(TextView(ctx).apply {
                text = "🧠 $model"
                textSize = 15f
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                setPadding(0, 0, 0, dp(8f))
            })
            val views = ModelCardViews(container = container)
            val methods = methodsForModel(config, model)
            for ((index, sub) in methods.withIndex()) {
                container.addView(TextView(ctx).apply {
                    text = "${index + 1} — ${sub.label}"
                    textSize = 13f
                    setTextColor(ContextCompat.getColor(context, R.color.accent_blue))
                    setPadding(0, dp(if (index == 0) 0f else 8f), 0, dp(4f))
                })
                val bars = BarDistributionView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                container.addView(bars)
                val perSecondLabelList = mutableListOf<TextView>()
                // Show Live Data row attaches to every AVG section — that's
                // every 1 s-trained model in the session, each streaming its
                // own per-second predictions.
                val (toggle, circles) = if (sub == LongSubMode.AVERAGE) {
                    val tog = MaterialButton(ctx, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                        text = getString(R.string.live_show_live_data)
                        textSize = 12f
                        setTextColor(ContextCompat.getColor(context, R.color.accent_blue))
                    }
                    val circleRow = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        visibility = View.GONE
                        setPadding(0, dp(6f), 0, 0)
                    }
                    repeat(10) {
                        val cell = LinearLayout(ctx).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = android.view.Gravity.CENTER_HORIZONTAL
                        }
                        val emoji = TextView(ctx).apply {
                            text = "·"
                            textSize = 13f
                            gravity = android.view.Gravity.CENTER
                            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                        }
                        val c = ConfidenceCircleView(ctx).apply { setTargetSize(28) }
                        cell.addView(emoji, LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dp(2f) })
                        cell.addView(c, LinearLayout.LayoutParams(dp(28f), dp(28f)))
                        perSecondLabelList += emoji
                        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        lp.marginEnd = dp(2f)
                        circleRow.addView(cell, lp)
                    }
                    val key = cardKey(model, LongSubMode.AVERAGE)
                    tog.setOnClickListener {
                        val expanded = !(liveDataExpanded[key] == true)
                        liveDataExpanded[key] = expanded
                        circleRow.visibility = if (expanded) View.VISIBLE else View.GONE
                    }
                    container.addView(tog)
                    container.addView(circleRow)
                    tog to circleRow
                } else null to null
                views.methodSections[sub] = MethodSectionViews(bars, toggle, circles, perSecondLabelList)
            }
            card.addView(container)
            modelCardsContainer.addView(card)
            cardsByModel[model] = views
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
        // Pending pause: user pressed Pause but the current 10 s frame is still
        // finishing. Show the chosen pause duration *frozen* (it doesn't tick
        // down yet) so the user sees the countdown is queued, not active.
        if (state.pausePending) {
            val total = state.pauseTotalMs
            return if (total != null) {
                "Paused · ${formatClockSeconds((total / 1000L).toInt())}"
            } else "Paused"
        }
        // Real pause: show the countdown deadline as a live ticker (or just
        // "Paused" for an indefinite pause).
        if (state.isPaused) {
            val deadline = state.userPauseDeadlineElapsedMs
            return if (deadline != null) {
                val remainingMs = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                "Paused · ${formatClockSeconds((remainingMs / 1000L).toInt())}"
            } else "Paused"
        }
        // Otherwise the status area stays empty — the stopwatch + bars already
        // tell the user everything they need.
        return when (val s = state.appState) {
            is AppState.Error -> "Error: ${s.message}"
            is AppState.Loading -> getString(R.string.live_loading_models)
            is AppState.Ready -> if (state.isModelLoaded) "" else getString(R.string.live_loading_models)
            else -> ""
        }
    }

    /**
     * Drives a 1 Hz refresh of [statusLabel] while a pause-with-timer is active,
     * since the ViewModel's session timer does not emit while paused.
     */
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
                statusLabel.text = labelForState(current)
                delay(1000L)
            }
        }
    }

    /**
     * Shows the pause-duration picker. Selecting "No timer" pauses indefinitely;
     * any other option schedules an auto-resume after that duration.
     */
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
        evaluationTitle.text = "${pending.modelClass.emoji} ${pending.modelClass.label} — ${getString(R.string.evaluation_question)}"
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

    /**
     * Renders an int second count as mm:ss when below an hour and hh:mm:ss
     * above. Used for both the running cycle countdown and the long pause
     * countdown (which can run into the multi-hour range for "every 3 h").
     */
    private fun formatClockSeconds(totalSeconds: Int): String {
        if (totalSeconds < 60) return "%d s".format(totalSeconds)
        val s = totalSeconds % 60
        val m = (totalSeconds / 60) % 60
        val h = totalSeconds / 3600
        return if (h > 0) "%d:%02d:%02d".format(h, m, s)
        else "%d:%02d".format(m, s)
    }

    private fun cardKey(model: String, sub: LongSubMode) = "$model::${sub.name}"

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
}
