package com.fzi.acousticscene.ui.history

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
import com.fzi.acousticscene.model.SceneClass
import com.fzi.acousticscene.model.SessionConfig
import com.fzi.acousticscene.ui.MainViewModel
import com.fzi.acousticscene.ui.common.UiState
import com.fzi.acousticscene.util.SceneClassColors
import com.fzi.acousticscene.util.stripModelSuffix
import com.google.android.material.button.MaterialButton

/**
 * v2 Session-Ended summary. Layout is fixed (eyebrow + 3 meta tiles + section
 * label), the per-model report tiles are inserted programmatically since their count
 * depends on the session config — one tile per (model, method) pair.
 */
class ResultsSummaryFragment : Fragment(R.layout.fragment_results_summary) {

    private val viewModel: MainViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val state = viewModel.uiState.value
        val config = state.sessionConfig
        if (config != null) {
            renderHeader(view, config, state)
            renderModelReports(view, config, state)
        }

        view.findViewById<MaterialButton>(R.id.resultsBackHomeButton).setOnClickListener {
            viewModel.clearSessionResults()
            popToHome()
        }
        view.findViewById<MaterialButton>(R.id.resultsOpenHistoryButton).setOnClickListener {
            startActivity(Intent(requireContext(), HistoryActivity::class.java))
        }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    viewModel.clearSessionResults()
                    popToHome()
                }
            }
        )
    }

    /**
     * Returns to whichever "home" the user came from: Welcome (config mode path)
     * or Test Welcome (test mode path). Either Welcome lives in the back stack
     * but not both — falling through to Mode Select if neither is present means
     * the user is never stuck on the results screen.
     */
    private fun popToHome() {
        val nav = findNavController()
        if (nav.popBackStack(R.id.welcomeFragment, false)) return
        if (nav.popBackStack(R.id.testWelcomeFragment, false)) return
        nav.popBackStack(R.id.modeSelectFragment, false)
    }

    private fun renderHeader(view: View, config: SessionConfig, state: UiState) {
        val minutes = (state.sessionElapsedMs / 60_000L).coerceAtLeast(0L)
        view.findViewById<TextView>(R.id.resultsMinutes).text = minutes.toString()

        val recordings = state.cycleCountByModelMethod.values.maxOrNull() ?: 0
        view.findViewById<TextView>(R.id.resultsRecordings).text = recordings.toString()

        val pauseMin = (state.sessionPausedMs / 60_000L).coerceAtLeast(0L)
        view.findViewById<TextView>(R.id.resultsPauses).text = pauseMin.toString()
    }

    private fun renderModelReports(view: View, config: SessionConfig, state: UiState) {
        val container = view.findViewById<LinearLayout>(R.id.resultsContainer)
        container.removeAllViews()
        val ctx = requireContext()

        for (model in config.modelNames) {
            for (sub in methodsForModel(config, model)) {
                val key = model to sub
                val cycles = state.cycleCountByModelMethod[key] ?: 0
                if (cycles == 0) continue
                val hist = state.topClassCountByModelMethod[key].orEmpty()
                val agg = state.aggregateResultsByModel[model]?.get(sub)
                container.addView(buildModelTile(ctx, model, sub, cycles, hist, agg))
            }
        }
    }

    private fun buildModelTile(
        ctx: android.content.Context,
        modelName: String,
        sub: LongSubMode,
        cycles: Int,
        hist: Map<SceneClass, Int>,
        agg: com.fzi.acousticscene.model.ClassificationResult?
    ): View {
        val tile = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_tile)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(9)
            layoutParams = lp
        }

        // Filename (mono)
        tile.addView(TextView(ctx).apply {
            text = modelName.stripModelSuffix()
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxLines = 1
        })

        // Badge + cycles row
        val badgeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, 0)
        }
        badgeRow.addView(TextView(ctx).apply {
            text = sub.label.uppercase()
            textSize = 8.5f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_method_badge)
            letterSpacing = 0.04f
            setPadding(dp(6), dp(2), dp(6), dp(2))
        })
        badgeRow.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.results_cycles_count, cycles)
            textSize = 9f
            typeface = Typeface.MONOSPACE
            setTextColor(ContextCompat.getColor(ctx, R.color.text_faint))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.marginStart = dp(7)
            layoutParams = lp
        })
        tile.addView(badgeRow)

        // Most-frequent inner tile
        val topClass = pickTopClass(hist, agg)
        val innerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(11)
            lp.bottomMargin = dp(12)
            layoutParams = lp
        }
        innerRow.addView(buildMostFrequent(ctx, topClass))
        tile.addView(innerRow)

        // Distribution rows — show raw count of times this class was predicted
        val maxCount = hist.values.maxOrNull()?.coerceAtLeast(1) ?: 1
        hist.entries.sortedByDescending { it.value }.forEach { (scene, count) ->
            tile.addView(buildDistRow(ctx, scene, count, maxCount))
        }

        return tile
    }

    private fun buildMostFrequent(ctx: android.content.Context, top: SceneClass): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_tile_inner)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        box.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.results_most_frequent)
            textSize = 8f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_faint))
            letterSpacing = 0.06f
        })
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        row.addView(TextView(ctx).apply {
            text = top.emoji
            textSize = 11f
        })
        row.addView(TextView(ctx).apply {
            text = top.labelShort
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(SceneClassColors.color(ctx, top))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.marginStart = dp(5)
            layoutParams = lp
        })
        box.addView(row)
        return box
    }

    private fun buildDistRow(ctx: android.content.Context, scene: SceneClass, count: Int, maxCount: Int): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(4)
            lp.bottomMargin = dp(4)
            layoutParams = lp
        }
        row.addView(TextView(ctx).apply {
            text = scene.emoji
            textSize = 11.5f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(18), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        row.addView(TextView(ctx).apply {
            text = scene.labelShort
            textSize = 10.5f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            val lp = LinearLayout.LayoutParams(dp(82), ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginStart = dp(7)
            layoutParams = lp
        })

        // Track + filled bar — width proportional to count / maxCount across this model
        val safeMax = maxCount.coerceAtLeast(1)
        val fillWeight = count.coerceAtLeast(0).toFloat()
        val restWeight = (safeMax - count).coerceAtLeast(0).toFloat()
        val track = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_dist_track)
            val lp = LinearLayout.LayoutParams(0, dp(6), 1f)
            lp.marginStart = dp(7)
            layoutParams = lp
        }
        val fill = View(ctx).apply {
            setBackgroundColor(SceneClassColors.color(ctx, scene))
            val lp = LinearLayout.LayoutParams(0, dp(6), fillWeight.coerceAtLeast(0.001f))
            layoutParams = lp
        }
        val rest = View(ctx).apply {
            setBackgroundColor(Color.TRANSPARENT)
            val lp = LinearLayout.LayoutParams(0, dp(6), restWeight)
            layoutParams = lp
        }
        track.addView(fill)
        track.addView(rest)
        row.addView(track)

        row.addView(TextView(ctx).apply {
            text = count.toString()
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(
                ContextCompat.getColor(
                    ctx,
                    if (count <= 0) R.color.text_faint else R.color.text_primary
                )
            )
            letterSpacing = -0.01f
            gravity = Gravity.END
            val lp = LinearLayout.LayoutParams(dp(26), ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginStart = dp(8)
            layoutParams = lp
        })
        return row
    }

    private fun pickTopClass(
        hist: Map<SceneClass, Int>,
        agg: com.fzi.acousticscene.model.ClassificationResult?
    ): SceneClass {
        if (hist.isEmpty()) return SceneClass.NATURE
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
