package com.fzi.acousticscene

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fzi.acousticscene.data.PredictionRepository
import com.fzi.acousticscene.data.PredictionStatistics
import com.fzi.acousticscene.model.PredictionRecord
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
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
        setContentView(R.layout.activity_history)

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
     * Zeigt Dialog mit Statistiken und CSV Export für ein Package
     */
    private fun showPackageDialog(packageRecords: List<PredictionRecord>) {
        val stats = calculatePackageStatistics(packageRecords)
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        
        val startTime = dateFormat.format(Date(packageRecords.first().timestamp))
        val endTime = dateFormat.format(Date(packageRecords.last().timestamp))
        
        val message = """
            Start: $startTime
            Ende: $endTime
            Anzahl: ${packageRecords.size}
            Ø Konfidenz: ${String.format(Locale.US, "%.1f", stats.averageConfidence)}%
            
            Verteilung:
            ${stats.classDistribution.entries.sortedByDescending { it.value }
                .take(3)
                .joinToString("\n") { "${it.key.emoji} ${it.key.labelShort}: ${it.value}" }}
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle(R.string.package_details)
            .setMessage(message)
            .setPositiveButton(R.string.statistics) { _, _ ->
                // Zeige erweiterte Statistiken
                showDetailedStatistics(stats)
            }
            .setNeutralButton(R.string.export_csv) { _, _ ->
                exportPackageCsv(packageRecords)
            }
            .setNegativeButton(R.string.delete) { _, _ ->
                // Zeige Bestätigungsdialog für Löschen
                showDeletePackageDialog(packageRecords)
            }
            .show()
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

            fun bind(packageRecords: List<PredictionRecord>, onClick: (List<PredictionRecord>) -> Unit) {
                val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                val startTime = timeFormat.format(Date(packageRecords.first().timestamp))
                val endTime = timeFormat.format(Date(packageRecords.last().timestamp))
                
                timeRangeText.text = "$startTime - $endTime"
                countText.text = "${packageRecords.size} Aufnahmen"

                itemView.setOnClickListener {
                    onClick(packageRecords)
                }
            }
        }
    }
}
