package com.fzi.acousticscene.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.fzi.acousticscene.R
import com.fzi.acousticscene.model.LongSubMode
import com.fzi.acousticscene.model.RecordingCategory
import com.fzi.acousticscene.model.SessionConfig
import com.fzi.acousticscene.util.stripModelSuffix
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/**
 * Renders a tight at-a-glance summary after a session stops: how long it ran,
 * how much of that was paused, how many 10 s recordings happened, and the top
 * predicted class for every (model, method) combo. Distributions and bar charts
 * live in History — this page is the headline view.
 */
class ResultsSummaryFragment : Fragment(R.layout.fragment_results_summary) {

    private val viewModel: MainViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val container = view.findViewById<LinearLayout>(R.id.resultsContainer)
        val state = viewModel.uiState.value
        val config = state.sessionConfig
        if (config != null) {
            buildResults(container, config, state)
        }

        view.findViewById<MaterialButton>(R.id.resultsBackHomeButton).setOnClickListener {
            viewModel.clearSessionResults()
            findNavController().popBackStack(R.id.welcomeFragment, false)
        }
        view.findViewById<MaterialButton>(R.id.resultsOpenHistoryButton).setOnClickListener {
            startActivity(Intent(requireContext(), HistoryActivity::class.java))
        }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    viewModel.clearSessionResults()
                    findNavController().popBackStack(R.id.welcomeFragment, false)
                }
            }
        )
    }

    private fun buildResults(container: LinearLayout, config: SessionConfig, state: UiState) {
        val ctx = requireContext()

        // Header strip: duration · paused · recordings · avg volume.
        val recordedFor = formatDurationMs(state.sessionElapsedMs)
        val planned = config.sessionDuration.totalMs?.let { formatDurationMs(it) }
        val recordings = state.cycleCountByModelMethod.values.maxOrNull() ?: 0
        val durationLine = if (planned != null) "Recorded for $recordedFor / $planned"
        else "Recorded for $recordedFor"
        container.addView(TextView(ctx).apply {
            text = durationLine
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        })
        if (state.sessionPausedMs > 0L) {
            container.addView(TextView(ctx).apply {
                text = "Paused for ${formatDurationMs(state.sessionPausedMs)}"
                textSize = 13f
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setPadding(0, dp(2f), 0, 0)
            })
        }
        container.addView(TextView(ctx).apply {
            text = if (recordings == 1) "1 recording" else "$recordings recordings"
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(0, dp(2f), 0, dp(14f))
        })

        for (model in config.modelNames) {
            val card = MaterialCardView(ctx).apply {
                radius = dp(12f).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1f)
                strokeColor = ContextCompat.getColor(context, R.color.text_secondary)
                setCardBackgroundColor(ContextCompat.getColor(context, R.color.surface_dark))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = dp(10f)
                layoutParams = lp
            }
            val inner = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            }
            inner.addView(TextView(ctx).apply {
                text = "🧠 ${model.stripModelSuffix()}"
                textSize = 14f
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                setPadding(0, 0, 0, dp(4f))
            })

            for (sub in methodsForModel(config, model)) {
                val key = model to sub
                val hist = state.topClassCountByModelMethod[key].orEmpty()
                val total = state.cycleCountByModelMethod[key] ?: 0
                if (hist.isEmpty() || total == 0) continue
                val topClass = pickTopClass(hist, state.aggregateResultsByModel[model]?.get(sub))
                val topCount = hist[topClass] ?: 0
                val klass = "${topClass.emoji} ${topClass.labelShort}"
                inner.addView(TextView(ctx).apply {
                    text = "Modus ist ${sub.label}"
                    textSize = 14f
                    setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                    setPadding(0, dp(6f), 0, dp(1f))
                })
                inner.addView(TextView(ctx).apply {
                    text = "Die meist erwähnte Klasse ist: $klass ($topCount / $total)"
                    textSize = 13f
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    setPadding(0, 0, 0, dp(2f))
                })
            }

            card.addView(inner)
            container.addView(card)
        }
    }

    /**
     * Most-mentioned class for a (model, method): highest cycle count wins,
     * ties broken by the higher mean confidence from [aggregateResultsByModel].
     * Falls back to the histogram's first key if no aggregate exists yet.
     */
    private fun pickTopClass(
        hist: Map<com.fzi.acousticscene.model.SceneClass, Int>,
        agg: com.fzi.acousticscene.model.ClassificationResult?
    ): com.fzi.acousticscene.model.SceneClass {
        val maxCount = hist.values.max()
        val tied = hist.filterValues { it == maxCount }.keys
        if (tied.size == 1) return tied.first()
        val probs = agg?.allProbabilities
        return tied.maxByOrNull { probs?.getOrNull(it.index) ?: 0f } ?: tied.first()
    }

    private fun methodsForModel(config: SessionConfig, modelName: String): List<LongSubMode> {
        val active = when (config.category) {
            RecordingCategory.CONTINUOUS -> config.continuousMethodsByModel[modelName].orEmpty()
            RecordingCategory.INTERVAL -> config.intervalMethodsByModel[modelName].orEmpty()
        }
        return listOf(LongSubMode.STANDARD, LongSubMode.FAST, LongSubMode.AVERAGE).filter { it in active }
    }

    private fun formatDurationMs(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val minutes = totalSec / 60
        val seconds = totalSec % 60
        return "%d:%02d".format(minutes, seconds)
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
}
