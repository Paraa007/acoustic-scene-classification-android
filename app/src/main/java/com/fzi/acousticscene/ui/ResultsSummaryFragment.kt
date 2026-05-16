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
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/**
 * Renders the per-(model, method) aggregates that built up over the session.
 * The user lands here after Stop or auto-stop and chooses one of:
 * - Zurück zur Home Page → Welcome
 * - Zur History → opens HistoryActivity (existing screen)
 *
 * Reads `aggregateResultsByModel` + `cycleCountByModelMethod` straight off the
 * UiState that the live screen left behind.
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

        // Back navigates to the welcome page (skip the live + wizard layers).
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
                lp.bottomMargin = dp(12f).toInt()
                layoutParams = lp
            }
            val inner = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14f).toInt(), dp(12f).toInt(), dp(14f).toInt(), dp(12f).toInt())
            }
            inner.addView(TextView(ctx).apply {
                text = "🧠 $model"
                textSize = 15f
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                setPadding(0, 0, 0, dp(8f).toInt())
            })

            val methods = methodsForModel(config, model)
            for (sub in methods) {
                val agg = state.aggregateResultsByModel[model]?.get(sub) ?: continue
                val cycles = state.cycleCountByModelMethod[model to sub] ?: 0
                inner.addView(TextView(ctx).apply {
                    text = "${sub.label} · ${getString(R.string.results_cycles, cycles)}"
                    textSize = 13f
                    setTextColor(ContextCompat.getColor(context, R.color.accent_blue))
                    setPadding(0, dp(6f).toInt(), 0, dp(4f).toInt())
                })
                val bars = BarDistributionView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    setProbabilities(agg.allProbabilities)
                }
                inner.addView(bars)
                inner.addView(TextView(ctx).apply {
                    text = getString(R.string.results_top_class, "${agg.sceneClass.emoji} ${agg.sceneClass.labelShort}")
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    setPadding(0, dp(4f).toInt(), 0, 0)
                })
            }

            inner.addView(TextView(ctx).apply {
                text = getString(R.string.results_avg_volume, state.sessionVolumeMean)
                textSize = 12f
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setPadding(0, dp(8f).toInt(), 0, 0)
            })

            card.addView(inner)
            container.addView(card)
        }
    }

    private fun methodsForModel(config: SessionConfig, modelName: String): List<LongSubMode> {
        val active = when (config.category) {
            RecordingCategory.CONTINUOUS -> config.continuousMethodsByModel[modelName].orEmpty()
            RecordingCategory.INTERVAL -> config.intervalMethodsByModel[modelName].orEmpty()
        }
        return listOf(LongSubMode.STANDARD, LongSubMode.FAST, LongSubMode.AVERAGE).filter { it in active }
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
}
