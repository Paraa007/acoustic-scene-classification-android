package com.fzi.acousticscene

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fzi.acousticscene.data.PredictionRepository
import com.fzi.acousticscene.data.PredictionStatistics
import com.fzi.acousticscene.model.PredictionRecord
import com.fzi.acousticscene.model.SceneClass
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * History Screen - Zeigt alle gespeicherten Klassifikationen als Packages
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var repository: PredictionRepository
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyStateText: TextView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var adapter: PackageAdapter

    companion object {
        // Keine Zeit-Schwelle mehr - Packages basieren auf App-Sessions
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-Edge aktivieren für moderne Geräte
        enableEdgeToEdge()

        setContentView(R.layout.activity_history)

        // Window Insets für dynamisches Padding (Status Bar, Navigation Bar)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        repository = PredictionRepository(this)
        recyclerView = findViewById(R.id.historyRecyclerView)
        emptyStateText = findViewById(R.id.emptyStateText)
        toolbar = findViewById(R.id.toolbar)

        // Toolbar Setup
        toolbar.setNavigationOnClickListener {
            finish()
        }

        // Overflow Menu (Delete All)
        toolbar.inflateMenu(R.menu.history_menu)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.delete_all -> {
                    showDeleteAllDialog()
                    true
                }
                else -> false
            }
        }

        // RecyclerView Setup
        adapter = PackageAdapter { packageRecords ->
            showPackageDialog(packageRecords)
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        loadHistory()
    }

    private fun loadHistory() {
        val predictions = repository.getAllPredictions().sortedBy { it.timestamp }
        val packages = groupIntoPackages(predictions)
        
        adapter.submitList(packages.reversed()) // Neueste zuerst

        if (packages.isEmpty()) {
            emptyStateText.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyStateText.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
    }

    /**
     * Gruppiert Predictions zu Packages basierend auf App-Sessions
     * Alle Predictions mit derselben sessionStartTime gehören zu einem Package
     */
    private fun groupIntoPackages(predictions: List<PredictionRecord>): List<List<PredictionRecord>> {
        if (predictions.isEmpty()) return emptyList()

        // Gruppiere nach sessionStartTime
        val packagesBySession = predictions.groupBy { it.sessionStartTime }
        
        // Sortiere Packages nach sessionStartTime (älteste zuerst)
        return packagesBySession.values
            .map { it.sortedBy { record -> record.timestamp } }  // Innerhalb eines Packages nach Timestamp sortieren
            .sortedBy { it.first().sessionStartTime }  // Packages nach Session-Start sortieren
    }

    private fun showDeleteAllDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_all_history)
            .setMessage(R.string.delete_all_confirm)
            .setPositiveButton(R.string.clear_all) { _, _ ->
                repository.clearAll()
                Toast.makeText(this, R.string.predictions_cleared, Toast.LENGTH_SHORT).show()
                loadHistory()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Zeigt professionellen Dialog mit Statistiken und CSV Export für ein Package
     * Material Design 3 compliant mit custom layout
     */
    private fun showPackageDialog(packageRecords: List<PredictionRecord>) {
        val stats = calculatePackageStatistics(packageRecords)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        // Inflate custom dialog layout
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_history_details, null)

        // Find views
        val startTimeValue: TextView = dialogView.findViewById(R.id.startTimeValue)
        val endTimeValue: TextView = dialogView.findViewById(R.id.endTimeValue)
        val countValue: TextView = dialogView.findViewById(R.id.countValue)
        val modelValue: TextView = dialogView.findViewById(R.id.modelValue)
        val modeValue: TextView = dialogView.findViewById(R.id.modeValue)
        val avgConfidenceValue: TextView = dialogView.findViewById(R.id.avgConfidenceValue)
        val distributionContainer: LinearLayout = dialogView.findViewById(R.id.distributionContainer)
        val btnClose: MaterialButton = dialogView.findViewById(R.id.btnClose)
        val btnDelete: MaterialButton = dialogView.findViewById(R.id.btnDelete)
        val btnExport: MaterialButton = dialogView.findViewById(R.id.btnExport)

        // Populate metadata
        startTimeValue.text = dateFormat.format(Date(packageRecords.first().timestamp))
        endTimeValue.text = dateFormat.format(Date(packageRecords.last().timestamp))
        countValue.text = "${packageRecords.size} Aufnahmen"

        // Get model and mode from first record (constant per session)
        val firstRecord = packageRecords.first()
        val modelName = firstRecord.modelName
        val isDevMode = firstRecord.isDevMode
        val recordingMode = firstRecord.recordingMode

        // Determine number of classes based on model
        val numClasses = if (modelName.contains("model2") || isDevMode) 9 else 8
        modelValue.text = "$modelName.pt ($numClasses Classes)"

        // Mode display with recording mode
        val modeText = if (isDevMode) getString(R.string.dev_mode) else getString(R.string.user_mode)
        modeValue.text = "$modeText / ${recordingMode.name}"

        avgConfidenceValue.text = "${String.format(Locale.US, "%.1f", stats.averageConfidence)}%"

        // Populate distribution list with proper alignment
        populateDistributionList(distributionContainer, stats, packageRecords.size)

        // Create dialog
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .create()

        // Button click handlers
        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        btnDelete.setOnClickListener {
            dialog.dismiss()
            showDeletePackageDialog(packageRecords)
        }

        btnExport.setOnClickListener {
            dialog.dismiss()
            exportPackageCsv(packageRecords)
        }

        dialog.show()
    }

    /**
     * Populates the distribution container with properly aligned rows
     */
    private fun populateDistributionList(
        container: LinearLayout,
        stats: PredictionStatistics,
        totalCount: Int
    ) {
        container.removeAllViews()

        // Sort by count descending
        val sortedDistribution = stats.classDistribution.entries.sortedByDescending { it.value }

        // Scene color map
        val sceneColors = mapOf(
            SceneClass.TRANSIT_VEHICLES to R.color.transit_vehicles,
            SceneClass.URBAN_WAITING to R.color.urban_waiting,
            SceneClass.NATURE to R.color.nature,
            SceneClass.SOCIAL to R.color.social,
            SceneClass.WORK to R.color.work,
            SceneClass.COMMERCIAL to R.color.commercial,
            SceneClass.LEISURE_SPORT to R.color.leisure_sport,
            SceneClass.CULTURE_QUIET to R.color.culture_quiet,
            SceneClass.LIVING_ROOM to R.color.living_room
        )

        for ((sceneClass, count) in sortedDistribution) {
            // Inflate row layout
            val rowView = LayoutInflater.from(this)
                .inflate(R.layout.item_distribution_row, container, false)

            val emojiText: TextView = rowView.findViewById(R.id.emojiText)
            val classNameText: TextView = rowView.findViewById(R.id.classNameText)
            val countBadge: TextView = rowView.findViewById(R.id.countBadge)
            val progressBar: ProgressBar = rowView.findViewById(R.id.progressBar)

            // Set emoji
            emojiText.text = sceneClass.emoji

            // Set class name (short label)
            classNameText.text = sceneClass.labelShort

            // Set count badge
            countBadge.text = count.toString()

            // Calculate percentage and set progress
            val percentage = if (totalCount > 0) (count * 100) / totalCount else 0
            progressBar.progress = percentage

            // Set progress bar color based on scene class
            val colorRes = sceneColors[sceneClass] ?: R.color.accent_green
            progressBar.progressTintList = ContextCompat.getColorStateList(this, colorRes)

            container.addView(rowView)
        }
    }

    private fun showDeletePackageDialog(packageRecords: List<PredictionRecord>) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_package)
            .setMessage(R.string.delete_package_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                val sessionStartTime = packageRecords.first().sessionStartTime
                repository.deletePackage(sessionStartTime)
                Toast.makeText(this, R.string.package_deleted, Toast.LENGTH_SHORT).show()
                loadHistory()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun calculatePackageStatistics(records: List<PredictionRecord>): PredictionStatistics {
        val classDistribution = records.groupBy { it.sceneClass }
            .mapValues { it.value.size }
        val avgConfidence = records.map { it.confidence * 100 }.average()
        val avgInferenceTime = records.map { it.inferenceTimeMs }.average()

        return PredictionStatistics(
            totalCount = records.size,
            todayCount = records.size,
            classDistribution = classDistribution,
            averageConfidence = avgConfidence,
            averageInferenceTimeMs = avgInferenceTime,
            firstPrediction = records.first().timestamp,
            lastPrediction = records.last().timestamp
        )
    }

    private fun showDetailedStatistics(stats: PredictionStatistics) {
        val message = """
            📊 Detaillierte Statistiken
            
            Gesamt: ${stats.totalCount} Vorhersagen
            Ø Konfidenz: ${String.format(Locale.US, "%.1f", stats.averageConfidence)}%
            Ø Inferenz: ${String.format(Locale.US, "%.0f", stats.averageInferenceTimeMs)}ms
            
            Verteilung:
            ${stats.classDistribution.entries.sortedByDescending { it.value }
                .joinToString("\n") { "${it.key.emoji} ${it.key.label}: ${it.value}" }}
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle(R.string.statistics)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun exportPackageCsv(records: List<PredictionRecord>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val csvContent = StringBuilder()
                csvContent.appendLine(PredictionRecord.getCsvHeader())
                records.forEach { record ->
                    csvContent.appendLine(record.toCsvRow())
                }

                val timeFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                val startTime = timeFormat.format(Date(records.first().timestamp))
                val endTime = timeFormat.format(Date(records.last().timestamp))
                val fileName = "package_${startTime}_BIS_${endTime}.csv"
                val file = File(getExternalFilesDir(null), fileName)
                file.writeText(csvContent.toString())

                withContext(Dispatchers.Main) {
                    shareCsvFile(file)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@HistoryActivity, R.string.export_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun shareCsvFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Acoustic Scene Package")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_csv)))
            Toast.makeText(this, R.string.csv_exported, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.share_failed, e.message), Toast.LENGTH_LONG).show()
        }
    }

    /**
     * RecyclerView Adapter für Package Items
     */
    private class PackageAdapter(
        private val onPackageClick: (List<PredictionRecord>) -> Unit
    ) : RecyclerView.Adapter<PackageAdapter.ViewHolder>() {

        private var packages: List<List<PredictionRecord>> = emptyList()

        fun submitList(newList: List<List<PredictionRecord>>) {
            packages = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_package, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(packages[position], onPackageClick)
        }

        override fun getItemCount(): Int = packages.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val timeRangeText: TextView = itemView.findViewById(R.id.timeRangeText)
            private val countText: TextView = itemView.findViewById(R.id.countText)
            private val batteryConsumptionText: TextView = itemView.findViewById(R.id.batteryConsumptionText)

            fun bind(packageRecords: List<PredictionRecord>, onClick: (List<PredictionRecord>) -> Unit) {
                val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                val startTime = timeFormat.format(Date(packageRecords.first().timestamp))
                val endTime = timeFormat.format(Date(packageRecords.last().timestamp))

                timeRangeText.text = "$startTime - $endTime"
                countText.text = "${packageRecords.size} Aufnahmen"

                // Batterie-Verbrauch berechnen und anzeigen
                val firstRecord = packageRecords.first()
                val lastRecord = packageRecords.last()
                val startBattery = firstRecord.batteryLevel
                val endBattery = lastRecord.batteryLevel

                if (startBattery >= 0 && endBattery >= 0) {
                    val consumption = startBattery - endBattery
                    batteryConsumptionText.text = "🔋 Verbrauch: ${consumption}% ($startBattery% → $endBattery%)"
                    batteryConsumptionText.visibility = View.VISIBLE

                    // Rot färben wenn Verbrauch > 10%
                    if (consumption > 10) {
                        batteryConsumptionText.setTextColor(
                            itemView.context.getColor(android.R.color.holo_red_light)
                        )
                    } else {
                        batteryConsumptionText.setTextColor(
                            itemView.context.getColor(R.color.text_secondary)
                        )
                    }
                } else {
                    batteryConsumptionText.text = "🔋 Verbrauch: N/A"
                    batteryConsumptionText.visibility = View.VISIBLE
                    batteryConsumptionText.setTextColor(
                        itemView.context.getColor(R.color.text_secondary)
                    )
                }

                itemView.setOnClickListener {
                    onClick(packageRecords)
                }
            }
        }
    }
}
