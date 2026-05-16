package com.fzi.acousticscene.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fzi.acousticscene.R
import com.fzi.acousticscene.data.PredictionRepository
import com.fzi.acousticscene.data.PredictionStatistics
import com.fzi.acousticscene.model.LongSubMode
import com.fzi.acousticscene.model.PredictionRecord
import com.fzi.acousticscene.model.RecordingMode
import com.fzi.acousticscene.util.ThemeHelper
import com.fzi.acousticscene.util.stripModelSuffix
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * History Screen - Shows all saved classifications as packages
 * Supports multi-selection mode with long-press trigger
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var repository: PredictionRepository
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyStateText: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var adapter: PackageAdapter

    // Selection Mode UI
    private lateinit var normalToolbar: LinearLayout
    private lateinit var selectionToolbar: LinearLayout
    private lateinit var selectionCountText: TextView
    private lateinit var btnCloseSelection: ImageButton
    private lateinit var btnSelectAll: MaterialButton
    private lateinit var btnExportSelected: MaterialButton
    private lateinit var btnDeleteSelected: MaterialButton

    /** All session start times sorted chronologically (oldest first) */
    private var allSessionStartTimes: List<Long> = emptyList()

    /** All grouped packages (newest first for display) */
    private var allPackages: List<List<PredictionRecord>> = emptyList()

    /** Selection mode state */
    private var isSelectionMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply saved theme before super.onCreate()
        ThemeHelper.applySavedTheme(this)

        super.onCreate(savedInstanceState)

        // Enable Edge-to-Edge for modern devices
        enableEdgeToEdge()

        setContentView(R.layout.activity_history)

        // Window Insets for dynamic padding (Status Bar, Navigation Bar)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        repository = PredictionRepository.getInstance(this)

        // Normal toolbar views
        recyclerView = findViewById(R.id.historyRecyclerView)
        emptyStateText = findViewById(R.id.emptyStateText)
        btnBack = findViewById(R.id.btnBack)
        normalToolbar = findViewById(R.id.normalToolbar)

        // Selection toolbar views
        selectionToolbar = findViewById(R.id.selectionToolbar)
        selectionCountText = findViewById(R.id.selectionCountText)
        btnCloseSelection = findViewById(R.id.btnCloseSelection)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnExportSelected = findViewById(R.id.btnExportSelected)
        btnDeleteSelected = findViewById(R.id.btnDeleteSelected)

        // Material 3 Back Button
        btnBack.setOnClickListener {
            finish()
        }

        // Selection toolbar actions
        btnCloseSelection.setOnClickListener { exitSelectionMode() }
        btnSelectAll.setOnClickListener { selectAll() }
        btnExportSelected.setOnClickListener { exportSelected() }
        btnDeleteSelected.setOnClickListener { deleteSelected() }

        // RecyclerView Setup
        adapter = PackageAdapter(
            repository = repository,
            onPackageClick = { packageRecords -> onItemClick(packageRecords) },
            onPackageLongClick = { packageRecords -> onItemLongClick(packageRecords) }
        )

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        loadHistory()
    }

    override fun onBackPressed() {
        if (isSelectionMode) {
            exitSelectionMode()
        } else {
            super.onBackPressed()
        }
    }

    // --- Selection Mode ---

    private fun onItemClick(packageRecords: List<PredictionRecord>) {
        if (isSelectionMode) {
            val sessionStartTime = packageRecords.first().sessionStartTime
            adapter.toggleSelection(sessionStartTime)
            updateSelectionCount()
        } else {
            showPackageDialog(packageRecords)
        }
    }

    private fun onItemLongClick(packageRecords: List<PredictionRecord>) {
        if (!isSelectionMode) {
            val sessionStartTime = packageRecords.first().sessionStartTime
            enterSelectionMode(sessionStartTime)
        }
    }

    private fun enterSelectionMode(firstSelectedSession: Long) {
        isSelectionMode = true
        adapter.startSelectionWithItem(firstSelectedSession)
        normalToolbar.visibility = View.GONE
        selectionToolbar.visibility = View.VISIBLE
        updateSelectionCount()
    }

    private fun exitSelectionMode() {
        isSelectionMode = false
        adapter.clearSelection()
        adapter.setSelectionMode(false)
        selectionToolbar.visibility = View.GONE
        normalToolbar.visibility = View.VISIBLE
    }

    private fun updateSelectionCount() {
        val count = adapter.getSelectedCount()
        selectionCountText.text = getString(R.string.n_selected, count)
        if (count == 0 && isSelectionMode) {
            exitSelectionMode()
        }
    }

    private fun selectAll() {
        adapter.selectAll()
        updateSelectionCount()
    }

    private fun deleteSelected() {
        val selectedIds = adapter.getSelectedSessionIds()
        if (selectedIds.isEmpty()) return

        ModernDialogHelper.showDeleteDialog(
            context = this,
            title = getString(R.string.delete_selected),
            message = getString(R.string.delete_selected_confirm, selectedIds.size),
            onDelete = {
                repository.deletePackages(selectedIds)
                Toast.makeText(this, getString(R.string.sessions_deleted, selectedIds.size), Toast.LENGTH_SHORT).show()
                exitSelectionMode()
                loadHistory()
            }
        )
    }

    private fun exportSelected() {
        val selectedIds = adapter.getSelectedSessionIds()
        if (selectedIds.isEmpty()) return

        // Sammle alle Records der ausgewählten Sessions
        val allRecords = allPackages
            .filter { it.first().sessionStartTime in selectedIds }
            .flatten()
            .sortedBy { it.timestamp }

        if (allRecords.isEmpty()) return

        exitSelectionMode()
        exportPackageCsv(allRecords)
    }

    // --- Existing functionality ---

    private fun loadHistory() {
        val predictions = repository.getAllPredictions().sortedBy { it.timestamp }
        val packages = groupIntoPackages(predictions)

        // Alle Session-Startzeiten chronologisch sammeln
        allSessionStartTimes = packages.map { it.first().sessionStartTime }.sorted()
        allPackages = packages.reversed() // Neueste zuerst

        adapter.submitList(allPackages, allSessionStartTimes)

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

    /**
     * Shows modern dialog with statistics, model/mode info, and CSV export for a package
     */
    private fun showPackageDialog(packageRecords: List<PredictionRecord>) {
        val stats = calculatePackageStatistics(packageRecords)
        val sessionStartTime = packageRecords.first().sessionStartTime
        val sessionName = repository.resolveSessionDisplayName(sessionStartTime, allSessionStartTimes)

        ModernDialogHelper.showHistoryDetailsDialog(
            context = this,
            packageRecords = packageRecords,
            stats = stats,
            sessionName = sessionName,
            onDelete = { showDeletePackageDialog(packageRecords) },
            onExport = { exportPackageCsv(packageRecords) },
            onRename = { showRenameDialog(packageRecords) }
        )
    }

    /**
     * Shows a rename dialog for the given session
     */
    private fun showRenameDialog(packageRecords: List<PredictionRecord>) {
        val sessionStartTime = packageRecords.first().sessionStartTime
        val currentName = repository.resolveSessionDisplayName(sessionStartTime, allSessionStartTimes)

        val input = EditText(this).apply {
            setText(currentName)
            setSelection(text.length)
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_secondary))
            hint = getString(R.string.session_name_hint)
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this, R.style.ModernDialog)
            .setTitle(getString(R.string.session_rename))
            .setView(input)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    repository.setSessionName(sessionStartTime, newName)
                    Toast.makeText(this, R.string.session_renamed, Toast.LENGTH_SHORT).show()
                    loadHistory()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showDeletePackageDialog(packageRecords: List<PredictionRecord>) {
        ModernDialogHelper.showDeleteDialog(
            context = this,
            title = getString(R.string.delete_package),
            message = getString(R.string.delete_package_confirm),
            onDelete = {
                val sessionStartTime = packageRecords.first().sessionStartTime
                repository.deletePackage(sessionStartTime)
                Toast.makeText(this, R.string.package_deleted, Toast.LENGTH_SHORT).show()
                loadHistory()
            }
        )
    }

    private fun calculatePackageStatistics(records: List<PredictionRecord>): PredictionStatistics {
        // Pause-Records sind synthetisch und gehören nicht in die Aggregate.
        val real = records.filterNot { it.isPause }
        if (real.isEmpty()) return PredictionStatistics()
        val classDistribution = real.groupBy { it.sceneClass }
            .mapValues { it.value.size }
        val avgConfidence = real.map { it.confidence * 100 }.average()
        val avgInferenceTime = real.map { it.inferenceTimeMs }.average()

        return PredictionStatistics(
            totalCount = real.size,
            todayCount = real.size,
            classDistribution = classDistribution,
            averageConfidence = avgConfidence,
            averageInferenceTimeMs = avgInferenceTime,
            firstPrediction = real.first().timestamp,
            lastPrediction = real.last().timestamp
        )
    }

    private fun showDetailedStatistics(stats: PredictionStatistics) {
        val message = buildString {
            append("${getString(R.string.total)}: ${stats.totalCount} ${getString(R.string.predictions)}\n")
            append("${getString(R.string.avg_confidence)}: ${String.format(Locale.US, "%.1f", stats.averageConfidence)}%\n")
            append("${getString(R.string.avg_inference)}: ${String.format(Locale.US, "%.0f", stats.averageInferenceTimeMs)}ms\n\n")
            append("${getString(R.string.distribution)}:\n")
            stats.classDistribution.entries.sortedByDescending { it.value }
                .forEach { append("${it.key.emoji} ${it.key.label}: ${it.value}\n") }
        }

        ModernDialogHelper.showConfirmDialog(
            context = this,
            title = getString(R.string.detailed_statistics),
            message = message,
            confirmText = getString(R.string.ok),
            cancelText = "",
            onConfirm = {}
        )
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
                val fileName = "package_${startTime}_TO_${endTime}.csv"
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
     * RecyclerView Adapter für Package Items mit Multi-Selection Support
     */
    private class PackageAdapter(
        private val repository: PredictionRepository,
        private val onPackageClick: (List<PredictionRecord>) -> Unit,
        private val onPackageLongClick: (List<PredictionRecord>) -> Unit
    ) : RecyclerView.Adapter<PackageAdapter.ViewHolder>() {

        private var packages: List<List<PredictionRecord>> = emptyList()
        private var allSessionStartTimes: List<Long> = emptyList()
        private val selectedSessions = mutableSetOf<Long>()
        private var selectionMode = false

        fun submitList(newList: List<List<PredictionRecord>>, sessionStartTimes: List<Long>) {
            packages = newList
            allSessionStartTimes = sessionStartTimes
            notifyDataSetChanged()
        }

        fun startSelectionWithItem(sessionStartTime: Long) {
            selectionMode = true
            selectedSessions.add(sessionStartTime)
            notifyDataSetChanged()
        }

        fun setSelectionMode(enabled: Boolean) {
            selectionMode = enabled
            notifyDataSetChanged()
        }

        fun toggleSelection(sessionStartTime: Long) {
            if (selectedSessions.contains(sessionStartTime)) {
                selectedSessions.remove(sessionStartTime)
            } else {
                selectedSessions.add(sessionStartTime)
            }
            // Only update the affected item
            val position = packages.indexOfFirst { it.first().sessionStartTime == sessionStartTime }
            if (position >= 0) notifyItemChanged(position)
        }

        fun selectAll() {
            selectedSessions.clear()
            packages.forEach { selectedSessions.add(it.first().sessionStartTime) }
            notifyDataSetChanged()
        }

        fun clearSelection() {
            selectedSessions.clear()
            notifyDataSetChanged()
        }

        fun getSelectedCount(): Int = selectedSessions.size

        fun getSelectedSessionIds(): Set<Long> = selectedSessions.toSet()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_package, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(packages[position])
        }

        override fun getItemCount(): Int = packages.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val packageCard: MaterialCardView = itemView.findViewById(R.id.packageCard)
            private val sessionNameText: TextView = itemView.findViewById(R.id.sessionNameText)
            private val modelListLayout: LinearLayout = itemView.findViewById(R.id.modelListLayout)
            private val recordingsText: TextView = itemView.findViewById(R.id.recordingsText)
            private val durationText: TextView = itemView.findViewById(R.id.durationText)
            private val pauseText: TextView = itemView.findViewById(R.id.pauseText)
            private val configText: TextView = itemView.findViewById(R.id.configText)
            private val batteryConsumptionText: TextView = itemView.findViewById(R.id.batteryConsumptionText)
            private val selectionCheckbox: CheckBox = itemView.findViewById(R.id.selectionCheckbox)

            fun bind(packageRecords: List<PredictionRecord>) {
                val ctx = itemView.context
                val sessionStartTime = packageRecords.first().sessionStartTime
                val displayName = repository.resolveSessionDisplayName(sessionStartTime, allSessionStartTimes)
                sessionNameText.text = displayName

                // Pause synthetic records inflate counts and don't represent actual
                // recordings, so they're filtered out of the headline numbers and the
                // config-label derivation.
                val recordingRecords = packageRecords.filterNot { it.isPause }
                val sourceRecords = recordingRecords.ifEmpty { packageRecords }

                // Modell-Liste dynamisch befüllen (eine Zeile pro Modell, ohne .pt-Endung)
                modelListLayout.removeAllViews()
                val modelNames = extractModelNames(sourceRecords)
                modelNames.forEach { name ->
                    val tv = TextView(ctx).apply {
                        text = "🧠 ${name.stripModelSuffix()}"
                        textSize = 14f
                        setTextColor(ctx.getColor(R.color.text_primary))
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                    }
                    modelListLayout.addView(tv)
                }

                // Stats: Recordings + Duration + Pause in eigenen Zeilen, fett
                val durationMs = packageRecords.last().timestamp - packageRecords.first().timestamp
                val pauseSec = packageRecords
                    .filter { it.isPause }
                    .sumOf { it.pauseDurationSec ?: 0L }
                recordingsText.text = "${ctx.getString(R.string.recordings)}: ${recordingRecords.size}"
                durationText.text = "${ctx.getString(R.string.duration)}: ${formatDuration(durationMs)}"
                pauseText.text = "${ctx.getString(R.string.pause)}: ${formatPauseDuration(pauseSec)}"

                // Konfiguration kompakt (nur Pfad-Teil, ohne Modell-Doppelung)
                configText.text = pathLabel(sourceRecords)

                // Battery: ± Differenz, rot bei Drain, grün bei Gain, grau bei N/A
                val firstRecord = recordingRecords.firstOrNull() ?: packageRecords.first()
                val lastRecord = recordingRecords.lastOrNull() ?: packageRecords.last()
                val startBattery = firstRecord.batteryLevel
                val endBattery = lastRecord.batteryLevel

                val drainLabel = ctx.getString(R.string.battery_drain)
                if (startBattery >= 0 && endBattery >= 0) {
                    val diff = endBattery - startBattery  // positiv = Gain (geladen), negativ = Drain
                    val sign = if (diff > 0) "+" else if (diff < 0) "" else "±"
                    batteryConsumptionText.text = "$drainLabel: $sign$diff%"
                    val colorRes = when {
                        diff < 0 -> R.color.status_error
                        diff > 0 -> R.color.accent_green_light
                        else -> R.color.text_secondary
                    }
                    batteryConsumptionText.setTextColor(ctx.getColor(colorRes))
                } else {
                    batteryConsumptionText.text = "$drainLabel: N/A"
                    batteryConsumptionText.setTextColor(ctx.getColor(R.color.text_secondary))
                }

                // Selection mode UI
                val isSelected = selectedSessions.contains(sessionStartTime)
                selectionCheckbox.visibility = if (selectionMode) View.VISIBLE else View.GONE
                selectionCheckbox.isChecked = isSelected
                packageCard.isChecked = isSelected
                packageCard.strokeWidth = if (isSelected) 2 else 0
                packageCard.strokeColor = if (isSelected) {
                    ctx.getColor(R.color.accent_green_light)
                } else {
                    0
                }

                // Click handlers
                itemView.setOnClickListener {
                    onPackageClick(packageRecords)
                }

                itemView.setOnLongClickListener {
                    onPackageLongClick(packageRecords)
                    true
                }
            }
        }
    }

    companion object {
        /**
         * Extracts the distinct list of model names actually used in this session.
         * Falls back to the primary modelName when no multi-model record is found.
         * Pause records are expected to be filtered out by the caller.
         */
        fun extractModelNames(records: List<PredictionRecord>): List<String> {
            if (records.isEmpty()) return emptyList()
            // All-In-One mode: each record carries one entry per active model.
            val allInOne = records
                .mapNotNull { it.allInOneResults }
                .firstOrNull { it.isNotEmpty() }
            if (allInOne != null) {
                return allInOne.map { it.modelName }.distinct()
            }
            // Interval Multi-Model: per-(model, sub-mode) entries.
            val intervalModels = records
                .flatMap { it.longSubResults.orEmpty() }
                .mapNotNull { it.modelName }
                .distinct()
            if (intervalModels.isNotEmpty()) return intervalModels
            // Single-model fallback.
            return listOf(records.first().modelName)
        }

        /**
         * Compact one-line label describing the recording path only — model info is
         * rendered separately in the tile, so we don't double it up here.
         * Examples:
         *   Continuous · Standard (10s)
         *   Interval every 30 min · 2 methods
         */
        fun pathLabel(records: List<PredictionRecord>): String {
            if (records.isEmpty()) return ""
            val first = records.first()

            return if (first.recordingMode == RecordingMode.LONG) {
                val interval = first.longIntervalMinutes
                val intervalStr = interval?.let { mins ->
                    if (mins >= 60 && mins % 60 == 0) "every ${mins / 60} h"
                    else "every $mins min"
                } ?: "Interval"
                val methodCount = records
                    .mapNotNull { it.longSubResults }
                    .flatten()
                    .map { it.subMode }
                    .toSet()
                    .size
                val methodsStr = when {
                    methodCount <= 1 -> ""
                    else -> " · $methodCount methods"
                }
                "Interval $intervalStr$methodsStr"
            } else {
                "Continuous · ${first.recordingMode.label}"
            }
        }

        /**
         * Kept for callers outside the tile UI (dialogs, headers) that still want
         * the combined "model · path" label.
         */
        fun configLabel(records: List<PredictionRecord>): String {
            if (records.isEmpty()) return ""
            val names = extractModelNames(records)
            val modelLabel = if (names.size >= 2) "🧠 ${names.size} models"
            else "🧠 ${names.first().stripModelSuffix()}"
            return "$modelLabel · ${pathLabel(records)}"
        }

        /**
         * Formatiert die aufsummierte Pause-Dauer (Sekunden) kompakt.
         * Bei 0 → "0s"; deckt sich mit dem Detail-Dialog (ModernDialogHelper).
         */
        fun formatPauseDuration(totalSec: Long): String {
            if (totalSec <= 0L) return "0s"
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return when {
                h > 0 && m > 0 -> "${h}h ${m}min"
                h > 0 -> "${h}h"
                m > 0 && s > 0 -> "${m}min ${s}s"
                m > 0 -> "${m}min"
                else -> "${s}s"
            }
        }

        /**
         * Formatiert eine Dauer in Millisekunden als lesbaren deutschen String.
         * Beispiele: "12 s", "5 min 30 s", "1 h 15 min"
         */
        fun formatDuration(durationMs: Long): String {
            val totalSeconds = durationMs / 1000
            if (totalSeconds < 1) return "< 1 s"

            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60

            return buildString {
                if (hours > 0) {
                    append("$hours h")
                    if (minutes > 0) append(" $minutes min")
                } else if (minutes > 0) {
                    append("$minutes min")
                    if (seconds > 0) append(" $seconds s")
                } else {
                    append("$seconds s")
                }
            }
        }
    }
}
