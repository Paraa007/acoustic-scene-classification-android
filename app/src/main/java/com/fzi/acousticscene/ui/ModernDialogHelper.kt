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
import com.fzi.acousticscene.model.ModelConfig
import com.fzi.acousticscene.model.PredictionRecord
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

        // Mode info
        val isDevMode = firstRecord?.isDevMode ?: false
        val recordingMode = firstRecord?.recordingMode?.label ?: "Standard"
        val modeText = if (isDevMode) "Development / $recordingMode" else "User / $recordingMode"
        dialog.findViewById<TextView>(R.id.modeText).text = modeText

        // Count and confidence
        dialog.findViewById<TextView>(R.id.countText).text = "${packageRecords.size} ${context.getString(R.string.recordings)}"
        dialog.findViewById<TextView>(R.id.avgConfidenceText).text =
            String.format(Locale.US, "%.1f%%", stats.averageConfidence)

        // Fill distribution container with progress bars
        val distributionContainer = dialog.findViewById<LinearLayout>(R.id.distributionContainer)
        val totalCount = packageRecords.size.toFloat()

        stats.classDistribution.entries
            .sortedByDescending { it.value }
            .forEach { (scene, count) ->
                val percentage = if (totalCount > 0) (count / totalCount * 100).toInt() else 0
                val itemView = createDistributionItemWithProgress(context, scene, count, percentage)
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

        dialog.findViewById<MaterialButton>(R.id.btnClose).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        return dialog
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
}
