package com.fzi.acousticscene.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.fzi.acousticscene.R
import com.fzi.acousticscene.data.PredictionStatistics
import com.fzi.acousticscene.model.LongInterval
import com.fzi.acousticscene.model.LongSubMode
import com.fzi.acousticscene.model.ModelConfig
import com.fzi.acousticscene.model.PredictionRecord
import com.fzi.acousticscene.model.RecordingMode
import com.fzi.acousticscene.model.SceneClass
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Helper class for creating modern Material 3 style dialogs
 * Dark theme with rounded corners (28dp) matching the app design
 */
object ModernDialogHelper {

    /**
     * Shows a modern confirmation dialog
     */
    fun showConfirmDialog(
        context: Context,
        title: String,
        message: String,
        confirmText: String = context.getString(R.string.ok),
        cancelText: String = context.getString(R.string.cancel),
        onConfirm: () -> Unit,
        onCancel: (() -> Unit)? = null
    ): Dialog {
        val dialog = Dialog(context, R.style.ModernDialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_modern_confirm)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        dialog.findViewById<TextView>(R.id.dialogTitle).text = title
        dialog.findViewById<TextView>(R.id.dialogMessage).text = message

        dialog.findViewById<MaterialButton>(R.id.btnConfirm).apply {
            text = confirmText
            setOnClickListener {
                onConfirm()
                dialog.dismiss()
            }
        }

        dialog.findViewById<MaterialButton>(R.id.btnCancel).apply {
            text = cancelText
            setOnClickListener {
                onCancel?.invoke()
                dialog.dismiss()
            }
        }

        dialog.show()
        return dialog
    }

    /**
     * Shows a modern delete confirmation dialog
     */
    fun showDeleteDialog(
        context: Context,
        title: String,
        message: String,
        deleteText: String = context.getString(R.string.delete),
        cancelText: String = context.getString(R.string.cancel),
        onDelete: () -> Unit,
        onCancel: (() -> Unit)? = null
    ): Dialog {
        val dialog = Dialog(context, R.style.ModernDialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_modern_delete)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        dialog.findViewById<TextView>(R.id.dialogTitle).text = title
        dialog.findViewById<TextView>(R.id.dialogMessage).text = message

        dialog.findViewById<MaterialButton>(R.id.btnDelete).apply {
            text = deleteText
            setOnClickListener {
                onDelete()
                dialog.dismiss()
            }
        }

        dialog.findViewById<MaterialButton>(R.id.btnCancel).apply {
            text = cancelText
            setOnClickListener {
                onCancel?.invoke()
                dialog.dismiss()
            }
        }

        dialog.show()
        return dialog
    }

    /**
     * Shows a modern package details dialog
     */
    fun showPackageDetailsDialog(
        context: Context,
        packageRecords: List<PredictionRecord>,
        stats: PredictionStatistics,
        onDelete: () -> Unit,
        onExport: () -> Unit,
        onStats: () -> Unit
    ): Dialog {
        val dialog = Dialog(context, R.style.ModernDialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_package_details)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val startTime = dateFormat.format(Date(packageRecords.first().timestamp))
        val endTime = dateFormat.format(Date(packageRecords.last().timestamp))

        dialog.findViewById<TextView>(R.id.startTimeText).text = startTime
        dialog.findViewById<TextView>(R.id.endTimeText).text = endTime
        dialog.findViewById<TextView>(R.id.countText).text = packageRecords.size.toString()
        dialog.findViewById<TextView>(R.id.avgConfidenceText).text =
            String.format(Locale.US, "%.1f%%", stats.averageConfidence)

        // Fill distribution container
        val distributionContainer = dialog.findViewById<LinearLayout>(R.id.distributionContainer)
        stats.classDistribution.entries
            .sortedByDescending { it.value }
            .take(3)
            .forEach { (scene, count) ->
                val itemView = createDistributionItem(context, scene, count)
                distributionContainer.addView(itemView)
            }

        dialog.findViewById<MaterialButton>(R.id.btnDelete).setOnClickListener {
            dialog.dismiss()
            onDelete()
        }

        dialog.findViewById<MaterialButton>(R.id.btnExport).setOnClickListener {
            dialog.dismiss()
            onExport()
        }

        dialog.findViewById<MaterialButton>(R.id.btnStats).setOnClickListener {
            dialog.dismiss()
            onStats()
        }

        dialog.show()
        return dialog
    }

    /**
     * Shows a modern statistics dialog
     */
    fun showStatisticsDialog(
        context: Context,
        stats: PredictionStatistics,
        onExport: () -> Unit,
        onClear: () -> Unit
    ): Dialog {
        val dialog = Dialog(context, R.style.ModernDialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_modern_confirm)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        dialog.findViewById<TextView>(R.id.dialogTitle).text = context.getString(R.string.statistics)

        val message = buildString {
            append("${context.getString(R.string.total)}: ${stats.totalCount} ${context.getString(R.string.predictions)}\n")
            append("${context.getString(R.string.today)}: ${stats.todayCount} ${context.getString(R.string.predictions)}\n\n")
            append("${context.getString(R.string.avg_confidence)}: ${String.format("%.1f", stats.averageConfidence)}%\n")
            append("${context.getString(R.string.avg_inference)}: ${String.format("%.0f", stats.averageInferenceTimeMs)}ms\n\n")
            append("${context.getString(R.string.first)}: ${stats.getFormattedFirstPrediction()}\n")
            append("${context.getString(R.string.last)}: ${stats.getFormattedLastPrediction()}\n\n")

            if (stats.classDistribution.isNotEmpty()) {
                append("${context.getString(R.string.distribution)}:\n")
                stats.classDistribution.entries.sortedByDescending { it.value }.forEach { (scene, count) ->
                    append("${scene.emoji} ${scene.labelShort}: $count\n")
                }
            }
        }

        dialog.findViewById<TextView>(R.id.dialogMessage).text = message

        dialog.findViewById<MaterialButton>(R.id.btnConfirm).apply {
            text = context.getString(R.string.export)
            setOnClickListener {
                dialog.dismiss()
                onExport()
            }
        }

        dialog.findViewById<MaterialButton>(R.id.btnCancel).apply {
            text = context.getString(R.string.clear)
            setOnClickListener {
                dialog.dismiss()
                onClear()
            }
        }

        dialog.show()
        return dialog
    }

    /**
     * Shows a modern history details dialog with model/mode metadata and progress bars
     */
    fun showHistoryDetailsDialog(
        context: Context,
        packageRecords: List<PredictionRecord>,
        stats: PredictionStatistics,
        sessionName: String = context.getString(R.string.session_details),
        onDelete: () -> Unit,
        onExport: () -> Unit,
        onRename: (() -> Unit)? = null
    ): Dialog {
        val dialog = Dialog(context, R.style.ModernDialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_history_details)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        // Session name as dialog title
        dialog.findViewById<TextView>(R.id.dialogTitle).text = sessionName

        // Rename button
        val btnRename = dialog.findViewById<MaterialButton>(R.id.btnRename)
        if (onRename != null) {
            btnRename.visibility = View.VISIBLE
            btnRename.setOnClickListener {
                dialog.dismiss()
                onRename()
            }
        } else {
            btnRename.visibility = View.GONE
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val firstRecord = packageRecords.firstOrNull()
        val lastRecord = packageRecords.lastOrNull()

        // Time info
        dialog.findViewById<TextView>(R.id.startTimeText).text =
            firstRecord?.let { dateFormat.format(Date(it.timestamp)) } ?: "N/A"
        dialog.findViewById<TextView>(R.id.endTimeText).text =
            lastRecord?.let { dateFormat.format(Date(it.timestamp)) } ?: "N/A"

        // Model info
        val modelName = firstRecord?.modelName ?: "model1.pt"
        val numClasses = ModelConfig.getClassCountForModel(modelName)
        dialog.findViewById<TextView>(R.id.modelText).text = "$modelName ($numClasses Classes)"

        // Mode info — for LONG records the label reflects the user-chosen pause interval
        // (e.g. "every 1h") instead of the static enum label.
        val isDevMode = firstRecord?.isDevMode ?: false
        val recordingMode = firstRecord?.let { rec ->
            if (rec.recordingMode == RecordingMode.LONG && rec.longIntervalMinutes != null) {
                "every ${formatLongIntervalMinutes(rec.longIntervalMinutes)}"
            } else {
                rec.recordingMode.label
            }
        } ?: "Standard"
        val modeText = if (isDevMode) "Development / $recordingMode" else "User / $recordingMode"
        dialog.findViewById<TextView>(R.id.modeText).text = modeText

        // Count and confidence
        dialog.findViewById<TextView>(R.id.countText).text = "${packageRecords.size} ${context.getString(R.string.recordings)}"
        dialog.findViewById<TextView>(R.id.avgConfidenceText).text =
            String.format(Locale.US, "%.1f%%", stats.averageConfidence)

        // Fill model distribution container with progress bars
        val distributionContainer = dialog.findViewById<LinearLayout>(R.id.distributionContainer)
        val totalCount = packageRecords.size.toFloat()

        stats.classDistribution.entries
            .sortedByDescending { it.value }
            .forEach { (scene, count) ->
                val percentage = if (totalCount > 0) (count / totalCount * 100).toInt() else 0
                val itemView = createDistributionItemWithProgress(context, scene, count, percentage)
                distributionContainer.addView(itemView)
            }

        // Method Comparison section - visible when any LONG record carries sub-mode results.
        // When present, it replaces the generic "Model predictions" distribution above —
        // the per-sub-mode stacks already cover the same information.
        val recordsWithSubs = packageRecords.filter { !it.longSubResults.isNullOrEmpty() }
        if (recordsWithSubs.isNotEmpty()) {
            dialog.findViewById<TextView>(R.id.modelDistributionHeader).visibility = View.GONE
            distributionContainer.visibility = View.GONE
            val mcDivider = dialog.findViewById<View>(R.id.methodComparisonDivider)
            val mcHeader = dialog.findViewById<TextView>(R.id.methodComparisonHeader)
            val mcContainer = dialog.findViewById<LinearLayout>(R.id.methodComparisonContainer)
            mcDivider.visibility = View.VISIBLE
            mcHeader.visibility = View.VISIBLE
            mcContainer.visibility = View.VISIBLE

            LongSubMode.entries.forEach { sub ->
                val subRecords = recordsWithSubs.mapNotNull { rec ->
                    rec.longSubResults?.firstOrNull { it.subMode == sub }
                }
                if (subRecords.isEmpty()) return@forEach

                val sectionLabel = TextView(context).apply {
                    text = "${sub.label} (${sub.hint})"
                    textSize = 14f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                    setPadding(0, 12, 0, 6)
                }
                mcContainer.addView(sectionLabel)

                val dist = subRecords.groupBy { it.sceneClass }.mapValues { it.value.size }
                val total = subRecords.size.toFloat()
                dist.entries.sortedByDescending { it.value }.forEach { (scene, count) ->
                    val percentage = if (total > 0) (count / total * 100).toInt() else 0
                    val row = createDistributionItemWithProgress(context, scene, count, percentage)
                    mcContainer.addView(row)
                }
            }
        }

        // ALL-IN-ONE: Model comparison section — visible when any record carries
        // per-model results. Shows one distribution stack per model, identical in
        // shape to the LONG sub-mode comparison.
        val recordsWithAllInOne = packageRecords.filter { !it.allInOneResults.isNullOrEmpty() }
        if (recordsWithAllInOne.isNotEmpty()) {
            dialog.findViewById<TextView>(R.id.modelDistributionHeader).visibility = View.GONE
            distributionContainer.visibility = View.GONE
            val mcDivider = dialog.findViewById<View>(R.id.methodComparisonDivider)
            val mcHeader = dialog.findViewById<TextView>(R.id.methodComparisonHeader)
            val mcContainer = dialog.findViewById<LinearLayout>(R.id.methodComparisonContainer)
            mcDivider.visibility = View.VISIBLE
            mcHeader.visibility = View.VISIBLE
            mcContainer.visibility = View.VISIBLE
            mcHeader.text = context.getString(R.string.all_in_one_comparison_header)

            val modelNames = recordsWithAllInOne
                .flatMap { it.allInOneResults ?: emptyList() }
                .map { it.modelName }
                .distinct()

            modelNames.forEach { name ->
                val perModelResults = recordsWithAllInOne.mapNotNull { rec ->
                    rec.allInOneResults?.firstOrNull { it.modelName == name }
                }
                if (perModelResults.isEmpty()) return@forEach

                val sectionLabel = TextView(context).apply {
                    text = "🧠 $name"
                    textSize = 14f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                    setPadding(0, 12, 0, 6)
                }
                mcContainer.addView(sectionLabel)

                val dist = perModelResults.groupBy { it.sceneClass }.mapValues { it.value.size }
                val total = perModelResults.size.toFloat()
                dist.entries.sortedByDescending { it.value }.forEach { (scene, count) ->
                    val percentage = if (total > 0) (count / total * 100).toInt() else 0
                    val row = createDistributionItemWithProgress(context, scene, count, percentage)
                    mcContainer.addView(row)
                }
            }
        }

        // Per-second clips section - only visible for AVERAGE mode sessions
        val isAverageMode = packageRecords.firstOrNull()?.recordingMode == RecordingMode.AVERAGE
        if (isAverageMode) {
            val perSecondDivider = dialog.findViewById<View>(R.id.perSecondDivider)
            val perSecondHeader = dialog.findViewById<TextView>(R.id.perSecondHeader)
            val perSecondContainer = dialog.findViewById<LinearLayout>(R.id.perSecondContainer)

            // Collect all per-second clips from all records in this session
            val allClips = packageRecords.flatMap { it.perSecondClips ?: emptyList() }

            if (allClips.isNotEmpty()) {
                perSecondDivider.visibility = View.VISIBLE
                perSecondHeader.visibility = View.VISIBLE
                perSecondContainer.visibility = View.VISIBLE

                // Distribution: how often each class appeared in 1s clips
                val clipDistribution = allClips.groupBy { it.sceneClass }.mapValues { it.value.size }
                val clipTotal = allClips.size.toFloat()

                clipDistribution.entries
                    .sortedByDescending { it.value }
                    .forEach { (scene, count) ->
                        val percentage = if (clipTotal > 0) (count / clipTotal * 100).toInt() else 0
                        val itemView = createDistributionItemWithProgress(context, scene, count, percentage)
                        perSecondContainer.addView(itemView)
                    }
            }
        }

        // User evaluation section - only visible for LONG mode sessions (evaluation notifications only sent there)
        val userDistributionContainer = dialog.findViewById<LinearLayout>(R.id.userDistributionContainer)
        val evaluationCountText = dialog.findViewById<TextView>(R.id.evaluationCountText)
        val userDivider = dialog.findViewById<View>(R.id.userDistributionDivider)
        val isLongMode = packageRecords.firstOrNull()?.recordingMode?.hasPauseAfterRecording() == true

        if (isLongMode) {
            val evaluatedRecords = packageRecords.filter { it.userSelectedClass != null }

            evaluationCountText.text = String.format(
                context.getString(R.string.n_of_m_evaluated),
                evaluatedRecords.size, packageRecords.size
            )

            if (evaluatedRecords.isNotEmpty()) {
                val userDistribution = evaluatedRecords.groupBy { it.userSelectedClass!! }
                    .mapValues { it.value.size }
                val userTotal = evaluatedRecords.size.toFloat()

                userDistribution.entries
                    .sortedByDescending { it.value }
                    .forEach { (scene, count) ->
                        val percentage = (count / userTotal * 100).toInt()
                        val itemView = createDistributionItemWithProgress(context, scene, count, percentage)
                        userDistributionContainer.addView(itemView)
                    }
            } else {
                val noDataText = TextView(context).apply {
                    text = context.getString(R.string.no_user_evaluations)
                    textSize = 14f
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    setPadding(0, 8, 0, 8)
                }
                userDistributionContainer.addView(noDataText)
            }
        } else {
            // Hide entire user evaluation section for non-LONG mode sessions
            userDivider.visibility = View.GONE
            dialog.findViewById<LinearLayout>(R.id.userEvaluationHeader).visibility = View.GONE
            userDistributionContainer.visibility = View.GONE
        }

        dialog.findViewById<MaterialButton>(R.id.btnDelete).setOnClickListener {
            dialog.dismiss()
            onDelete()
        }

        dialog.findViewById<MaterialButton>(R.id.btnExport).setOnClickListener {
            dialog.dismiss()
            onExport()
        }

        dialog.findViewById<MaterialButton>(R.id.btnClose).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        return dialog
    }

    /**
     * Option for the pause-duration picker dialog. `durationMs = null` means
     * "no timer" (pause stays active until the user presses Resume).
     */
    data class PauseDurationOption(
        val label: String,
        val durationMs: Long?
    )

    /**
     * Shows a picker so the user can choose how long the LONG-mode pause should
     * last. The first option is typically "No timer" (indefinite pause); the
     * rest are preset durations. Exactly one option is always selected on tap.
     */
    fun showPauseDurationDialog(
        context: Context,
        title: String,
        subtitle: String,
        options: List<PauseDurationOption>,
        onSelected: (PauseDurationOption) -> Unit
    ): Dialog {
        val dialog = Dialog(context, R.style.ModernDialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_pause_duration)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        dialog.findViewById<TextView>(R.id.dialogTitle).text = title
        dialog.findViewById<TextView>(R.id.dialogSubtitle).text = subtitle

        val container = dialog.findViewById<LinearLayout>(R.id.optionsContainer)
        val density = context.resources.displayMetrics.density
        options.forEach { option ->
            val btn = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = option.label
                textSize = 15f
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                strokeColor = ContextCompat.getColorStateList(context, R.color.accent_green)
                cornerRadius = (12 * density).toInt()
                gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * density).toInt() }
                setOnClickListener {
                    onSelected(option)
                    dialog.dismiss()
                }
            }
            container.addView(btn)
        }

        dialog.findViewById<MaterialButton>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        return dialog
    }

    /**
     * Picker for the LONG-mode recording interval. Visually identical to
     * [showPauseDurationDialog] (reuses the same layout) but typed against
     * [LongInterval]. The dialog has no preselection — the user must explicitly
     * tap an option, and only then the LONG mode is activated by the caller.
     */
    fun showLongIntervalDialog(
        context: Context,
        title: String,
        subtitle: String,
        options: List<LongInterval>,
        onSelected: (LongInterval) -> Unit
    ): Dialog {
        val dialog = Dialog(context, R.style.ModernDialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_pause_duration)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        dialog.findViewById<TextView>(R.id.dialogTitle).text = title
        dialog.findViewById<TextView>(R.id.dialogSubtitle).text = subtitle

        val container = dialog.findViewById<LinearLayout>(R.id.optionsContainer)
        val density = context.resources.displayMetrics.density
        options.forEach { option ->
            val btn = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = humanReadableInterval(option)
                textSize = 15f
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                strokeColor = ContextCompat.getColorStateList(context, R.color.accent_green)
                cornerRadius = (12 * density).toInt()
                gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * density).toInt() }
                setOnClickListener {
                    onSelected(option)
                    dialog.dismiss()
                }
            }
            container.addView(btn)
        }

        dialog.findViewById<MaterialButton>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        return dialog
    }

    /** "10 minutes" / "1 hour" / "3 hours" — display label for a LongInterval. */
    private fun humanReadableInterval(i: LongInterval): String {
        return if (i.pauseMinutes < 60) {
            "${i.pauseMinutes} minutes"
        } else {
            val h = i.pauseMinutes / 60
            if (h == 1) "1 hour" else "$h hours"
        }
    }

    /**
     * Creates a distribution item with emoji, name, count, and progress bar
     */
    private fun createDistributionItemWithProgress(
        context: Context,
        scene: SceneClass,
        count: Int,
        percentage: Int
    ): View {
        val inflater = LayoutInflater.from(context)
        val itemView = inflater.inflate(R.layout.item_distribution_row, null)

        itemView.findViewById<TextView>(R.id.emojiText).text = scene.emoji
        itemView.findViewById<TextView>(R.id.classNameText).text = scene.labelShort
        itemView.findViewById<TextView>(R.id.countText).text = "($count)"

        val progressBar = itemView.findViewById<LinearProgressIndicator>(R.id.progressBar)
        progressBar.progress = percentage

        // Set progress color based on scene
        val colorResId = when (scene) {
            SceneClass.TRANSIT_VEHICLES -> R.color.transit_vehicles
            SceneClass.URBAN_WAITING -> R.color.urban_waiting
            SceneClass.NATURE -> R.color.nature
            SceneClass.SOCIAL -> R.color.social
            SceneClass.WORK -> R.color.work
            SceneClass.COMMERCIAL -> R.color.commercial
            SceneClass.LEISURE_SPORT -> R.color.leisure_sport
            SceneClass.CULTURE_QUIET -> R.color.culture_quiet
            SceneClass.LIVING_ROOM -> R.color.living_room
        }
        progressBar.setIndicatorColor(ContextCompat.getColor(context, colorResId))

        return itemView
    }

    private fun createDistributionItem(context: Context, scene: SceneClass, count: Int): View {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 8
            }
        }

        val sceneText = TextView(context).apply {
            text = "${scene.emoji} ${scene.labelShort}"
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val countText = TextView(context).apply {
            text = count.toString()
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        }

        layout.addView(sceneText)
        layout.addView(countText)
        return layout
    }

    /** "30min" / "1h" / "1h30min" — used to format LONG-mode intervals from a stored minute count. */
    private fun formatLongIntervalMinutes(min: Int): String = when {
        min < 60 -> "${min}min"
        min % 60 == 0 -> "${min / 60}h"
        else -> "${min / 60}h${min % 60}min"
    }
}
