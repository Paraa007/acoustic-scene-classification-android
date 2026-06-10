package com.fzi.acousticscene.ui.history

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
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
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fzi.acousticscene.R
import com.fzi.acousticscene.data.ActiveSessionRegistry
import com.fzi.acousticscene.data.PredictionRepository
import com.fzi.acousticscene.data.PredictionStatistics
import com.fzi.acousticscene.data.RecordingEngineHolder
import com.fzi.acousticscene.model.LongSubMode
import com.fzi.acousticscene.model.PredictionRecord
import com.fzi.acousticscene.model.RecordingMode
import com.fzi.acousticscene.model.SceneClass
import com.fzi.acousticscene.model.SessionMode
import com.fzi.acousticscene.model.realOnly
import com.fzi.acousticscene.ui.common.ModeBadge
import com.fzi.acousticscene.ui.common.ModernDialogHelper
import com.fzi.acousticscene.util.SceneClassColors
import com.fzi.acousticscene.util.ThemeHelper
import com.fzi.acousticscene.util.stripModelSuffix
import com.google.android.material.button.MaterialButton
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    // v2 header chips
    private lateinit var totalChip: TextView
    private lateinit var summaryStrip: LinearLayout
    private lateinit var totalRecordingsText: TextView

    // Selection Mode UI
    private lateinit var normalToolbar: LinearLayout
    private lateinit var selectionToolbar: LinearLayout
    private lateinit var selectionCountText: TextView
    private lateinit var btnCloseSelection: ImageButton
    private lateinit var btnSelectAll: MaterialButton
    private lateinit var btnExportSelected: MaterialButton
    private lateinit var btnDeleteSelected: MaterialButton

    // Filter chip row (Config-mode launch only)
    private lateinit var filterChipRow: LinearLayout
    private lateinit var filterChipAll: TextView
    private lateinit var filterChipConfig: TextView
    private lateinit var filterChipTest: TextView

    /** All session start times sorted chronologically (oldest first) */
    private var allSessionStartTimes: List<Long> = emptyList()

    /** All grouped packages (newest first for display) */
    private var allPackages: List<List<PredictionRecord>> = emptyList()

    /** Selection mode state */
    private var isSelectionMode = false

    /**
     * Active filter from the chip row. `null` = All (no filter, the default).
     * Persisted across config changes via [onSaveInstanceState].
     * Only used when the chip row is visible (i.e. no intent-level filter).
     */
    private var activeChipFilter: SessionMode? = null

    /**
     * `true` when the intent forced a mode filter (Test welcome) — in that case
     * the chip row stays hidden and [activeChipFilter] is ignored.
     */
    private var intentFilterLocked = false

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

        // Persistent mode badge — may be reached from a notification, so the
        // helper falls back to the running session's mode when needed.
        ModeBadge.bind(findViewById(R.id.screenModeBadge))

        // Normal toolbar views
        recyclerView = findViewById(R.id.historyRecyclerView)
        emptyStateText = findViewById(R.id.emptyStateText)
        btnBack = findViewById(R.id.btnBack)
        normalToolbar = findViewById(R.id.normalToolbar)
        totalChip = findViewById(R.id.totalChip)
        summaryStrip = findViewById(R.id.summaryStrip)
        totalRecordingsText = findViewById(R.id.totalRecordingsText)

        // Selection toolbar views
        selectionToolbar = findViewById(R.id.selectionToolbar)
        selectionCountText = findViewById(R.id.selectionCountText)
        btnCloseSelection = findViewById(R.id.btnCloseSelection)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnExportSelected = findViewById(R.id.btnExportSelected)
        btnDeleteSelected = findViewById(R.id.btnDeleteSelected)

        // Filter chip row
        filterChipRow = findViewById(R.id.filterChipRow)
        filterChipAll = findViewById(R.id.filterChipAll)
        filterChipConfig = findViewById(R.id.filterChipConfig)
        filterChipTest = findViewById(R.id.filterChipTest)

        // Material 3 Back Button
        btnBack.setOnClickListener {
            finish()
        }

        // Selection toolbar actions
        btnCloseSelection.setOnClickListener { exitSelectionMode() }
        btnSelectAll.setOnClickListener { selectAll() }
        btnExportSelected.setOnClickListener { exportSelected() }
        btnDeleteSelected.setOnClickListener { deleteSelected() }

        // Decide whether the intent forced a mode filter. If it did (TEST from
        // TestWelcomeFragment), the chip row stays hidden and the user has no
        // way to broaden the view. Otherwise (Config Welcome), the chip row is
        // visible and starts on "All".
        val intentFilterRaw = intent?.getStringExtra(EXTRA_MODE_FILTER)
        val parsedIntentFilter = intentFilterRaw?.let {
            runCatching { SessionMode.valueOf(it) }.getOrNull()
        }
        intentFilterLocked = parsedIntentFilter != null

        if (intentFilterLocked) {
            filterChipRow.visibility = View.GONE
        } else {
            filterChipRow.visibility = View.VISIBLE
            // Restore chip selection across rotations.
            activeChipFilter = savedInstanceState?.getString(STATE_CHIP_FILTER)?.let {
                runCatching { SessionMode.valueOf(it) }.getOrNull()
            }
            applyChipSelectionVisual()
            filterChipAll.setOnClickListener { onChipFilterChanged(null) }
            filterChipConfig.setOnClickListener { onChipFilterChanged(SessionMode.CONFIG) }
            filterChipTest.setOnClickListener { onChipFilterChanged(SessionMode.TEST) }
        }

        // RecyclerView Setup
        adapter = PackageAdapter(
            repository = repository,
            onPackageClick = { packageRecords -> onItemClick(packageRecords) },
            onPackageLongClick = { packageRecords -> onItemLongClick(packageRecords) }
        )

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        // Anti-bias blind: while a "Rate now" rating is open, its record stays
        // out of History entirely (list, detail dialog, aggregates — see
        // loadHistory). The pending id lives in the engine's process-wide
        // state, so this collector reloads whenever it changes: prompt fired,
        // rating submitted/skipped, or the 5-min window expired. Because
        // repeatOnLifecycle restarts the flow on every return to STARTED, the
        // first emission also covers the initial load and any change that
        // happened while this screen was in the background.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RecordingEngineHolder.uiState
                    .map { it.pendingEvaluation?.predictionId }
                    .distinctUntilChanged()
                    .collect { loadHistory() }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Only meaningful when the chip row is in play.
        if (!intentFilterLocked) {
            activeChipFilter?.let { outState.putString(STATE_CHIP_FILTER, it.name) }
        }
    }

    /**
     * Repaints the three filter chips so the active one uses the accent-soft
     * background + primary text colour while the others fall back to the
     * hairline border + secondary text.
     */
    private fun applyChipSelectionVisual() {
        val selectedBg = R.drawable.bg_chip_accent_soft
        val idleBg = R.drawable.bg_history_chip
        val selectedTextColor = getColor(R.color.text_primary)
        val idleTextColor = getColor(R.color.text_secondary)

        val pairs = listOf(
            filterChipAll to (activeChipFilter == null),
            filterChipConfig to (activeChipFilter == SessionMode.CONFIG),
            filterChipTest to (activeChipFilter == SessionMode.TEST),
        )
        pairs.forEach { (chip, isSelected) ->
            chip.setBackgroundResource(if (isSelected) selectedBg else idleBg)
            chip.setTextColor(if (isSelected) selectedTextColor else idleTextColor)
        }
    }

    private fun onChipFilterChanged(newFilter: SessionMode?) {
        if (activeChipFilter == newFilter) return
        activeChipFilter = newFilter
        applyChipSelectionVisual()
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
        // A record whose rating prompt is still unresolved is invisible here —
        // the subject must not peek at the prediction before rating it. The id
        // comes from the engine's published state (no persistence): once the
        // rating resolves or the process restarts, the record simply reappears.
        val pendingBlindId = RecordingEngineHolder.uiState.value.pendingEvaluation?.predictionId
        val rawPredictions = repository.getAllPredictions()
            .filterNot { pendingBlindId != null && it.id == pendingBlindId }
            .sortedBy { it.timestamp }
        val predictions = applyModeFilter(rawPredictions)
        val packages = groupIntoPackages(predictions)

        // Alle Session-Startzeiten chronologisch sammeln
        allSessionStartTimes = packages.map { it.first().sessionStartTime }.sorted()
        allPackages = packages.reversed() // Neueste zuerst

        // v2 header: "23 total" chip + summary recordings count
        totalChip.text = getString(R.string.history_total_chip, packages.size)
        val totalRecordings = packages.sumOf { it.realOnly().size }
        totalRecordingsText.text = String.format(Locale.US, "%,d", totalRecordings)
        summaryStrip.visibility = if (packages.isEmpty()) View.GONE else View.VISIBLE

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
     * Filters predictions down to the active mode. Resolution order:
     *  1. Intent extra (TestWelcome locks the activity to TEST-only).
     *  2. Chip-row selection from Config Welcome (`activeChipFilter`).
     *  3. No filter (== All).
     *
     * TEST keeps only records with `sessionMode == TEST`; CONFIG keeps
     * `sessionMode == CONFIG` plus legacy null records (saved before the tag
     * existed — those have always been a developer flow).
     */
    private fun applyModeFilter(predictions: List<PredictionRecord>): List<PredictionRecord> {
        val target: SessionMode? = if (intentFilterLocked) {
            intent?.getStringExtra(EXTRA_MODE_FILTER)?.let {
                runCatching { SessionMode.valueOf(it) }.getOrNull()
            }
        } else {
            activeChipFilter
        }
        return when (target) {
            null -> predictions
            SessionMode.TEST -> predictions.filter { it.sessionMode == SessionMode.TEST }
            SessionMode.CONFIG -> predictions.filter {
                it.sessionMode == SessionMode.CONFIG || it.sessionMode == null
            }
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
        val real = records.realOnly()
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
                // Eigener Unterordner statt Wurzel von getExternalFilesDir: der
                // FileProvider gibt nur noch csv_exports/ frei (file_provider_paths.xml).
                // Landet später etwas anderes im External-Files-Verzeichnis, ist es
                // damit nicht automatisch teilbar.
                val exportDir = File(getExternalFilesDir(null), "csv_exports").apply { mkdirs() }
                val file = File(exportDir, fileName)
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
            // Exception-Details gehören ins Log, nicht in den Toast — e.message
            // kann auf manchen ROMs absolute Pfade oder Provider-Authorities tragen.
            Log.w("HistoryActivity", "CSV share failed", e)
            Toast.makeText(this, R.string.share_failed, Toast.LENGTH_LONG).show()
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
            private val packageCard: LinearLayout = itemView.findViewById(R.id.packageCard)
            private val sessionNameText: TextView = itemView.findViewById(R.id.sessionNameText)
            private val sessionTimestampText: TextView = itemView.findViewById(R.id.sessionTimestampText)
            private val activeBadgeContainer: LinearLayout = itemView.findViewById(R.id.activeBadgeContainer)
            private val minibarContainer: LinearLayout = itemView.findViewById(R.id.minibarContainer)
            private val chipRowContainer: LinearLayout = itemView.findViewById(R.id.chipRowContainer)
            private val statsRow: LinearLayout = itemView.findViewById(R.id.statsRow)
            private val selectionCheckbox: CheckBox = itemView.findViewById(R.id.selectionCheckbox)

            fun bind(packageRecords: List<PredictionRecord>) {
                val ctx = itemView.context
                val sessionStartTime = packageRecords.first().sessionStartTime
                val displayName = repository.resolveSessionDisplayName(sessionStartTime, allSessionStartTimes)
                sessionNameText.text = displayName
                sessionTimestampText.text = formatRelativeTimestamp(sessionStartTime)

                // Pause synthetic records inflate counts and don't represent actual recordings,
                // so the v2 minibar + chip row drive off the real subset only.
                val recordingRecords = packageRecords.realOnly()
                val sourceRecords = recordingRecords.ifEmpty { packageRecords }

                // ACTIVE badge — set if this session is the registered running one
                val isActive = ActiveSessionRegistry.activeSessionStartTimes().contains(sessionStartTime)
                activeBadgeContainer.visibility = if (isActive) View.VISIBLE else View.GONE
                packageCard.background = ctx.getDrawable(
                    if (isActive) R.drawable.bg_tile_accent else R.drawable.bg_tile
                )

                // Minibar — proportional class distribution
                buildMinibar(ctx, minibarContainer, recordingRecords)

                // Chip row — models / mode / methods
                buildChipRow(ctx, chipRowContainer, sourceRecords)

                // Stats row — bignum + label cluster
                buildStatsRow(ctx, statsRow, recordingRecords, packageRecords)

                // Selection state
                val isSelected = selectedSessions.contains(sessionStartTime)
                selectionCheckbox.visibility = if (selectionMode) View.VISIBLE else View.GONE
                selectionCheckbox.isChecked = isSelected

                packageCard.setOnClickListener { onPackageClick(packageRecords) }
                packageCard.setOnLongClickListener {
                    onPackageLongClick(packageRecords)
                    true
                }
            }

            private fun buildMinibar(
                ctx: android.content.Context,
                container: LinearLayout,
                recordingRecords: List<PredictionRecord>
            ) {
                container.removeAllViews()
                val density = ctx.resources.displayMetrics.density
                val gapPx = (2 * density).toInt()
                val byClass = recordingRecords.groupingBy { it.sceneClass }.eachCount()
                val total = byClass.values.sum().coerceAtLeast(1)
                val entries = byClass.entries.sortedByDescending { it.value }
                // Mockup minibar style: thin 2dp gap between segments + hairline
                // base color so an empty bar still reads as a track.
                entries.forEachIndexed { index, (scene, count) ->
                    val pct = count.toFloat() / total.toFloat()
                    container.addView(View(ctx).apply {
                        setBackgroundColor(SceneClassColors.color(ctx, scene))
                        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, pct)
                        if (index < entries.size - 1) lp.marginEnd = gapPx
                        layoutParams = lp
                    })
                }
            }

            private fun buildChipRow(
                ctx: android.content.Context,
                container: LinearLayout,
                sourceRecords: List<PredictionRecord>
            ) {
                container.removeAllViews()
                if (sourceRecords.isEmpty()) return
                val first = sourceRecords.first()

                val models = extractModelNames(sourceRecords)
                container.addView(chip(ctx, modelsChipLabel(models.size)))

                val modeChipLabel = if (first.recordingMode == RecordingMode.LONG) {
                    val interval = first.longIntervalMinutes
                    val txt = interval?.let { mins ->
                        if (mins >= 60 && mins % 60 == 0) "Interval ${mins / 60}h"
                        else "Interval ${mins}min"
                    } ?: "Interval"
                    txt
                } else {
                    "Continuous"
                }
                container.addView(chip(ctx, modeChipLabel))

                val methodCount = sourceRecords
                    .mapNotNull { it.longSubResults }
                    .flatten()
                    .map { it.subMode }
                    .toSet()
                    .size
                if (methodCount >= 2) {
                    container.addView(chip(ctx, ctx.getString(R.string.history_chip_methods_n, methodCount)))
                } else {
                    val singleMethodLabel = when {
                        first.recordingMode == RecordingMode.STANDARD -> "Standard 10s"
                        first.recordingMode == RecordingMode.FAST -> "Fast 1s"
                        first.recordingMode == RecordingMode.AVERAGE -> "Avg"
                        else -> null
                    }
                    if (singleMethodLabel != null) container.addView(chip(ctx, singleMethodLabel))
                }
            }

            private fun modelsChipLabel(count: Int): CharSequence {
                val res = itemView.resources
                return if (count == 1) {
                    androidx.core.text.HtmlCompat.fromHtml(
                        res.getString(R.string.history_chip_model_one),
                        androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY
                    )
                } else {
                    androidx.core.text.HtmlCompat.fromHtml(
                        res.getString(R.string.history_chip_models_n, count),
                        androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY
                    )
                }
            }

            private fun chip(ctx: android.content.Context, text: CharSequence): TextView {
                val density = ctx.resources.displayMetrics.density
                return TextView(ctx).apply {
                    this.text = text
                    textSize = 10.5f
                    setTextColor(ctx.getColor(R.color.text_secondary))
                    // bg_history_chip uses surface_variant + hairline border so the
                    // chip cluster reads as a distinct group inside the tile.
                    background = ctx.getDrawable(R.drawable.bg_history_chip)
                    setPadding(
                        (9 * density).toInt(),
                        (4 * density).toInt(),
                        (9 * density).toInt(),
                        (4 * density).toInt()
                    )
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.marginEnd = (6 * density).toInt()
                    layoutParams = lp
                }
            }

            private fun buildStatsRow(
                ctx: android.content.Context,
                container: LinearLayout,
                recordingRecords: List<PredictionRecord>,
                allRecords: List<PredictionRecord>
            ) {
                container.removeAllViews()
                val density = ctx.resources.displayMetrics.density

                container.addView(statCluster(ctx, recordingRecords.size.toString(), ctx.getString(R.string.history_stat_recordings)))

                val firstReal = recordingRecords.firstOrNull()
                val lastReal = recordingRecords.lastOrNull()
                if (firstReal != null && lastReal != null) {
                    val durMin = ((lastReal.timestamp - firstReal.timestamp) / 60_000L).toInt()
                    val durHours = durMin / 60
                    if (durHours >= 1) {
                        addSpacing(container, (16 * density).toInt())
                        container.addView(statCluster(ctx, durHours.toString(), ctx.getString(R.string.history_stat_h)))
                    } else {
                        addSpacing(container, (16 * density).toInt())
                        container.addView(statCluster(ctx, durMin.coerceAtLeast(1).toString(), ctx.getString(R.string.history_stat_min)))
                    }
                }

                val pauseSec = allRecords.filter { it.isPause }.sumOf { it.pauseDurationSec ?: 0L }
                if (pauseSec > 0L) {
                    val pauseMin = (pauseSec / 60L).coerceAtLeast(1L)
                    addSpacing(container, (16 * density).toInt())
                    container.addView(statCluster(ctx, pauseMin.toString(), ctx.getString(R.string.history_stat_min_pause)))
                }
            }

            private fun statCluster(ctx: android.content.Context, big: String, label: String): LinearLayout {
                val density = ctx.resources.displayMetrics.density
                return LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.BOTTOM
                    addView(TextView(ctx).apply {
                        text = big
                        textSize = 14f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setTextColor(ctx.getColor(R.color.text_primary))
                        typeface = android.graphics.Typeface.MONOSPACE
                    })
                    addView(TextView(ctx).apply {
                        text = label
                        textSize = 9f
                        setTextColor(ctx.getColor(R.color.text_faint))
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        lp.marginStart = (5 * density).toInt()
                        layoutParams = lp
                    })
                }
            }

            private fun addSpacing(parent: LinearLayout, widthPx: Int) {
                parent.addView(View(parent.context).apply {
                    layoutParams = LinearLayout.LayoutParams(widthPx, 1)
                })
            }

            private fun formatRelativeTimestamp(ts: Long): String {
                val now = System.currentTimeMillis()
                val cal = java.util.Calendar.getInstance().apply { timeInMillis = ts }
                val nowCal = java.util.Calendar.getInstance().apply { timeInMillis = now }
                val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
                val sameDay = cal.get(java.util.Calendar.YEAR) == nowCal.get(java.util.Calendar.YEAR) &&
                    cal.get(java.util.Calendar.DAY_OF_YEAR) == nowCal.get(java.util.Calendar.DAY_OF_YEAR)
                if (sameDay) return "today $timeFmt"
                nowCal.add(java.util.Calendar.DAY_OF_YEAR, -1)
                val yesterday = cal.get(java.util.Calendar.YEAR) == nowCal.get(java.util.Calendar.YEAR) &&
                    cal.get(java.util.Calendar.DAY_OF_YEAR) == nowCal.get(java.util.Calendar.DAY_OF_YEAR)
                if (yesterday) return "yesterday $timeFmt"
                val daysAgo = ((now - ts) / (24L * 60L * 60L * 1000L)).toInt().coerceAtLeast(2)
                return "$daysAgo days ago"
            }
        }
    }

    companion object {
        /**
         * Intent extra used by Test Welcome / Config Welcome to restrict the
         * history to sessions launched in a given [SessionMode]. Accepted
         * values: SessionMode enum names. Anything else (including absence)
         * means "show all sessions".
         */
        const val EXTRA_MODE_FILTER = "history_mode_filter"

        /** Bundle key for the chip-row filter selection (Config-mode launches). */
        private const val STATE_CHIP_FILTER = "history_chip_filter"

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
