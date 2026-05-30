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
import com.fzi.acousticscene.model.LongSubResult
import com.fzi.acousticscene.model.ModelConfig
import com.fzi.acousticscene.model.ModelTrainingDuration
import com.fzi.acousticscene.model.PredictionRecord
import com.fzi.acousticscene.model.RecordingMode
import com.fzi.acousticscene.model.SceneClass
import com.fzi.acousticscene.model.SessionMode
import com.fzi.acousticscene.model.realOnly
import com.fzi.acousticscene.util.SceneClassColors
import com.fzi.acousticscene.util.stripModelSuffix
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
            append("${context.getString(R.string.avg_confidence)}: ${String.format(java.util.Locale.US, "%.1f", stats.averageConfidence)}%\n")
            append("${context.getString(R.string.avg_inference)}: ${String.format(java.util.Locale.US, "%.0f", stats.averageInferenceTimeMs)}ms\n\n")
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

        // Close chevron (back arrow icon in the header) — dismisses the dialog.
        dialog.findViewById<android.widget.ImageButton>(R.id.dialogCloseButton).setOnClickListener {
            dialog.dismiss()
        }

        // Mode badge (TEST MODE / CONFIG MODE). Reads off the first record's
        // sessionMode tag. Legacy records (sessionMode == null) hide the badge
        // — those were saved before the tag landed and we don't want to lie
        // about the entry point.
        val modeBadge = dialog.findViewById<TextView>(R.id.detailModeBadge)
        when (packageRecords.firstOrNull()?.sessionMode) {
            SessionMode.TEST -> {
                modeBadge.text = context.getString(R.string.detail_mode_test)
                modeBadge.visibility = View.VISIBLE
            }
            SessionMode.CONFIG -> {
                modeBadge.text = context.getString(R.string.detail_mode_config)
                modeBadge.visibility = View.VISIBLE
            }
            null -> modeBadge.visibility = View.GONE
        }

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

        // Pause-Records sind synthetisch — Model/Mode/Avg-Detection orientiert sich
        // ausschließlich an echten Aufnahmen, sonst zeigt der Header bei einer Session,
        // die mit einer Pause endet, einen Pause-Placeholder statt des Modus.
        val realRecords = packageRecords.realOnly()
        val firstRecord = realRecords.firstOrNull() ?: packageRecords.firstOrNull()
        val lastRecord = realRecords.lastOrNull() ?: packageRecords.lastOrNull()
        val sourceRecords = realRecords.ifEmpty { packageRecords }

        // v2 meta grid: 8 cells. Each include's root view has a distinct id; we
        // scope findViewById to that root so the shared inner ids resolve.
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dayFmt = SimpleDateFormat("MMM d", Locale.getDefault())
        fun relativeDay(ts: Long): String {
            val now = System.currentTimeMillis()
            val nowCal = java.util.Calendar.getInstance().apply { timeInMillis = now }
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = ts }
            val sameDay = cal.get(java.util.Calendar.YEAR) == nowCal.get(java.util.Calendar.YEAR) &&
                cal.get(java.util.Calendar.DAY_OF_YEAR) == nowCal.get(java.util.Calendar.DAY_OF_YEAR)
            if (sameDay) return "today"
            nowCal.add(java.util.Calendar.DAY_OF_YEAR, -1)
            val yesterday = cal.get(java.util.Calendar.YEAR) == nowCal.get(java.util.Calendar.YEAR) &&
                cal.get(java.util.Calendar.DAY_OF_YEAR) == nowCal.get(java.util.Calendar.DAY_OF_YEAR)
            if (yesterday) return "yesterday"
            return dayFmt.format(Date(ts))
        }

        val startTs = firstRecord?.timestamp
        val endTs = lastRecord?.timestamp
        bindMetaCell(
            dialog.findViewById(R.id.detailMetaStart),
            context.getString(R.string.detail_meta_start),
            startTs?.let { timeFmt.format(Date(it)) } ?: "—",
            startTs?.let { relativeDay(it) }
        )
        bindMetaCell(
            dialog.findViewById(R.id.detailMetaEnd),
            context.getString(R.string.detail_meta_end),
            endTs?.let { timeFmt.format(Date(it)) } ?: "—",
            endTs?.let { relativeDay(it) }
        )

        val durationMs = (endTs ?: 0L) - (startTs ?: 0L)
        val pauseSec = packageRecords.filter { it.isPause }.sumOf { it.pauseDurationSec ?: 0L }
        bindMetaCell(
            dialog.findViewById(R.id.detailMetaDuration),
            context.getString(R.string.detail_meta_duration),
            HistoryActivity.formatDuration(durationMs)
        )
        bindMetaCell(
            dialog.findViewById(R.id.detailMetaRecordings),
            context.getString(R.string.detail_meta_recordings),
            realRecords.size.toString()
        )

        val pauseCount = packageRecords.count { it.isPause }
        bindMetaCell(
            dialog.findViewById(R.id.detailMetaPauses),
            context.getString(R.string.detail_meta_pauses),
            pauseCount.toString()
        )

        val modelNames = HistoryActivity.extractModelNames(sourceRecords)
        bindMetaCell(
            dialog.findViewById(R.id.detailMetaModels),
            context.getString(R.string.detail_meta_models),
            modelNames.size.toString()
        )

        bindMetaCell(
            dialog.findViewById(R.id.detailMetaConfig),
            context.getString(R.string.detail_meta_config),
            HistoryActivity.pathLabel(sourceRecords).ifBlank { "—" }
        )

        val startBattery = firstRecord?.batteryLevel ?: -1
        val endBattery = lastRecord?.batteryLevel ?: -1
        val batteryDisplay = if (startBattery >= 0 && endBattery >= 0) {
            val diff = endBattery - startBattery
            val sign = if (diff > 0) "+" else if (diff < 0) "" else "±"
            "$sign$diff%"
        } else {
            "—"
        }
        bindMetaCell(
            dialog.findViewById(R.id.detailMetaBattery),
            context.getString(R.string.detail_meta_battery),
            batteryDisplay
        )

        // Most-heard highlight tile — uses the top class from the real records.
        // Only meaningful for single-model sessions; aggregating the top class
        // across multiple models would be misleading, so hide it otherwise.
        if (modelNames.size <= 1) {
            renderMostHeardHighlight(context, dialog, realRecords)
        } else {
            dialog.findViewById<View>(R.id.detailMostHeard).visibility = View.GONE
        }

        // Fill model distribution container with progress bars.
        // Wichtig: nur echte Records im Nenner — sonst werden die Prozente durch
        // Pause-Records aufgebläht (Zähler kommt aus stats.classDistribution, die
        // intern bereits realOnly() filtert).
        val distributionContainer = dialog.findViewById<LinearLayout>(R.id.distributionContainer)
        val totalCount = realRecords.size.toFloat()

        stats.classDistribution.entries
            .sortedByDescending { it.value }
            .forEach { (scene, count) ->
                val percentage = if (totalCount > 0) (count / totalCount * 100).toInt() else 0
                val itemView = createDistributionItemWithProgress(context, scene, count, percentage)
                distributionContainer.addView(itemView)
            }

        // Model comparison section — a SINGLE render pass over the union of all
        // models that carry either LONG sub-mode results or ALL-IN-ONE results.
        // Both kinds used to render their own per-model header into this same
        // container, so a record carrying BOTH made each model appear twice. We
        // now emit exactly one header per model and hang both kinds of
        // distribution underneath it.
        val recordsWithSubs = packageRecords.filter { !it.longSubResults.isNullOrEmpty() }
        val recordsWithAllInOne = packageRecords.filter { !it.allInOneResults.isNullOrEmpty() }
        if (recordsWithSubs.isNotEmpty() || recordsWithAllInOne.isNotEmpty()) {
            dialog.findViewById<TextView>(R.id.modelDistributionHeader).visibility = View.GONE
            distributionContainer.visibility = View.GONE
            val mcDivider = dialog.findViewById<View>(R.id.methodComparisonDivider)
            val mcHeader = dialog.findViewById<TextView>(R.id.methodComparisonHeader)
            val mcContainer = dialog.findViewById<LinearLayout>(R.id.methodComparisonContainer)
            mcDivider.visibility = View.VISIBLE
            mcHeader.visibility = View.VISIBLE
            mcContainer.visibility = View.VISIBLE
            // When all-in-one results are present the section is genuinely a model
            // comparison; otherwise the XML default ("Method comparison") still fits.
            if (recordsWithAllInOne.isNotEmpty()) {
                mcHeader.text = context.getString(R.string.all_in_one_comparison_header)
            }

            val density = context.resources.displayMetrics.density

            // Effective model name for a LONG sub-result: legacy records persisted
            // before the Multi-Model migration carry modelName == null, so fall back
            // to the owning record's primary model name. This lets a null-named LONG
            // group merge with an all-in-one group that names the same model.
            fun effectiveLongName(rec: PredictionRecord, sub: LongSubResult): String =
                sub.modelName ?: rec.modelName

            // Union of model names across BOTH sources, first-seen order preserved.
            val modelNames = LinkedHashSet<String>().apply {
                recordsWithSubs.forEach { rec ->
                    rec.longSubResults?.forEach { add(effectiveLongName(rec, it)) }
                }
                recordsWithAllInOne.forEach { rec ->
                    rec.allInOneResults?.forEach { add(it.modelName) }
                }
            }.toList()

            modelNames.forEachIndexed { index, modelName ->
                // Thin divider before every model section except the first.
                if (index > 0) {
                    val divider = View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            (1 * density).toInt()
                        ).apply {
                            topMargin = (12 * density).toInt()
                            bottomMargin = (6 * density).toInt()
                        }
                        setBackgroundColor(ContextCompat.getColor(context, R.color.hairline))
                    }
                    mcContainer.addView(divider)
                }

                // One bold, larger header per model + a clip-length badge ([10s]/[1s]).
                val clipSeconds = ModelTrainingDuration.secondsForFilename(modelName)
                val modelHeader = TextView(context).apply {
                    text = "🧠 ${modelName.stripModelSuffix()}  [${clipSeconds}s]"
                    textSize = 16f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                    setPadding(0, if (index == 0) 4 else 0, 0, 4)
                }
                mcContainer.addView(modelHeader)

                // LONG sub-mode distributions for this model (Standard / Fast / Avg).
                LongSubMode.entries.forEach { sub ->
                    val subRecords = recordsWithSubs.mapNotNull { rec ->
                        rec.longSubResults?.firstOrNull {
                            it.subMode == sub && effectiveLongName(rec, it) == modelName
                        }
                    }
                    if (subRecords.isEmpty()) return@forEach

                    val sectionLabel = TextView(context).apply {
                        text = "${sub.label} (${sub.hint})"
                        textSize = 12f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                        setPadding((10 * density).toInt(), 8, 0, 4)
                    }
                    mcContainer.addView(sectionLabel)

                    val dist = subRecords.groupBy { it.sceneClass }.mapValues { it.value.size }
                    val total = subRecords.size.toFloat()
                    dist.entries.sortedByDescending { it.value }.forEach { (scene, count) ->
                        val percentage = if (total > 0) (count / total * 100).toInt() else 0
                        mcContainer.addView(buildModelClassRow(context, scene, count, percentage, density))
                    }
                }

                // ALL-IN-ONE distribution for this model.
                val allInOneResults = recordsWithAllInOne.mapNotNull { rec ->
                    rec.allInOneResults?.firstOrNull { it.modelName == modelName }
                }
                if (allInOneResults.isNotEmpty()) {
                    val sectionLabel = TextView(context).apply {
                        text = "All-in-one distribution"
                        textSize = 12f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                        setPadding((10 * density).toInt(), 8, 0, 4)
                    }
                    mcContainer.addView(sectionLabel)

                    val dist = allInOneResults.groupBy { it.sceneClass }.mapValues { it.value.size }
                    val total = allInOneResults.size.toFloat()
                    dist.entries.sortedByDescending { it.value }.forEach { (scene, count) ->
                        val percentage = if (total > 0) (count / total * 100).toInt() else 0
                        mcContainer.addView(buildModelClassRow(context, scene, count, percentage, density))
                    }
                }
            }
        }

        // Per-second clips section - only visible for AVERAGE mode sessions
        val isAverageMode = realRecords.firstOrNull()?.recordingMode == RecordingMode.AVERAGE
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
        val isLongMode = realRecords.firstOrNull()?.recordingMode?.hasPauseAfterRecording() == true

        if (isLongMode) {
            val evaluatedRecords = realRecords.filter { it.userSelectedClass != null }

            evaluationCountText.text = String.format(
                context.getString(R.string.n_of_m_evaluated),
                evaluatedRecords.size, realRecords.size
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

        // Pauses section — shown whenever the session contains synthetic PAUSE records.
        // Each pause renders as a thin grey divider with the duration centered on it,
        // matching the spec ("graue Trennlinie zwischen Recording-Kacheln").
        renderPausesSection(context, dialog, packageRecords)

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
     * Fills one of the 8 meta-grid cells in the v2 session detail dialog. The cell
     * is an `<include>` whose root keeps its own id while the inner labels/values
     * share scoped ids — calling `findViewById` on the included root resolves
     * within that subtree so all 8 cells stay independent.
     */
    private fun bindMetaCell(
        root: LinearLayout?,
        label: String,
        value: String,
        sub: String? = null
    ) {
        if (root == null) return
        root.findViewById<TextView>(R.id.cellLabel).text = label
        root.findViewById<TextView>(R.id.cellValue).text = value
        val subView = root.findViewById<TextView>(R.id.cellSub)
        if (sub.isNullOrBlank()) {
            subView.visibility = View.GONE
        } else {
            subView.visibility = View.VISIBLE
            subView.text = sub
        }
    }

    /**
     * Renders the v2 "Most heard" tile under the model distributions. The class
     * picked is the most-frequent SceneClass across the realRecords; if no class
     * dominates (empty session) the tile stays hidden.
     */
    private fun renderMostHeardHighlight(
        context: Context,
        dialog: Dialog,
        realRecords: List<PredictionRecord>
    ) {
        val tile = dialog.findViewById<LinearLayout>(R.id.detailMostHeard)
        if (realRecords.isEmpty()) {
            tile.visibility = View.GONE
            return
        }
        val top = realRecords.groupingBy { it.sceneClass }
            .eachCount()
            .maxByOrNull { it.value }?.key
        if (top == null) {
            tile.visibility = View.GONE
            return
        }
        tile.visibility = View.VISIBLE
        dialog.findViewById<TextView>(R.id.detailMostHeardEmoji).text = top.emoji
        val nameView = dialog.findViewById<TextView>(R.id.detailMostHeardName)
        nameView.text = top.labelShort
        nameView.setTextColor(SceneClassColors.color(context, top))
    }

    private fun renderPausesSection(
        context: Context,
        dialog: Dialog,
        packageRecords: List<PredictionRecord>
    ) {
        val divider = dialog.findViewById<View>(R.id.pausesDivider)
        val header = dialog.findViewById<TextView>(R.id.pausesHeader)
        val container = dialog.findViewById<LinearLayout>(R.id.pausesContainer)

        val pauses = packageRecords.filter { it.isPause }
        if (pauses.isEmpty()) {
            divider.visibility = View.GONE
            header.visibility = View.GONE
            container.visibility = View.GONE
            return
        }

        divider.visibility = View.VISIBLE
        header.visibility = View.VISIBLE
        container.visibility = View.VISIBLE
        container.removeAllViews()

        val totalSec = pauses.sumOf { it.pauseDurationSec ?: 0L }
        header.text = String.format(
            context.getString(R.string.history_pauses_header),
            pauses.size,
            formatPauseDuration(totalSec)
        )

        val dividerColor = ContextCompat.getColor(context, R.color.surface_variant)
        val labelColor = ContextCompat.getColor(context, R.color.text_secondary)

        pauses.forEach { pause ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 6, 0, 6) }
            }
            val leftLine = View(context).apply {
                setBackgroundColor(dividerColor)
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            }
            val label = TextView(context).apply {
                text = String.format(
                    context.getString(R.string.history_pause_row),
                    formatPauseDuration(pause.pauseDurationSec ?: 0L)
                )
                textSize = 12f
                setTextColor(labelColor)
                setPadding(12, 0, 12, 0)
            }
            val rightLine = View(context).apply {
                setBackgroundColor(dividerColor)
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            }
            row.addView(leftLine)
            row.addView(label)
            row.addView(rightLine)
            container.addView(row)
        }
    }

    private fun formatPauseDuration(totalSec: Long): String {
        if (totalSec <= 0L) return "0s"
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 && m > 0 -> "${h}h ${m}min"
            h > 0 -> "${h}h"
            m > 0 -> "${m} min"
            else -> "${s}s"
        }
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
    /**
     * Compact, slightly-indented class row for the per-model comparison stacks.
     * Reuses [createDistributionItemWithProgress] and only tightens the type sizes
     * and adds a left inset so these rows read as subordinate to the model header.
     */
    private fun buildModelClassRow(
        context: Context,
        scene: SceneClass,
        count: Int,
        percentage: Int,
        density: Float
    ): View {
        val row = createDistributionItemWithProgress(context, scene, count, percentage)
        row.findViewById<TextView>(R.id.classNameText)?.textSize = 11f
        row.findViewById<TextView>(R.id.countText)?.textSize = 10f
        row.setPadding(
            (10 * density).toInt(),
            row.paddingTop,
            row.paddingRight,
            row.paddingBottom
        )
        return row
    }

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
