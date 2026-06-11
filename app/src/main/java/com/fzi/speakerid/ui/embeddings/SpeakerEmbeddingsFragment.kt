package com.fzi.speakerid.ui.embeddings

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.fzi.acousticscene.R
import com.fzi.acousticscene.databinding.FragmentSpeakeridEmbeddingsBinding
import com.fzi.acousticscene.databinding.ViewSpeakeridEmbeddingsTableRowBinding
import com.fzi.speakerid.library.calculations.CosineSimilarity
import com.fzi.speakerid.library.data.Cluster
import com.fzi.speakerid.library.data.ClusterEmbedding
import com.fzi.speakerid.library.data.HistoryEntry
import com.fzi.speakerid.library.data.SpeakerManager
import com.fzi.speakerid.ui.SpeakerIdDataManager
import com.fzi.speakerid.ui.SpeakerIdTheme
import com.fzi.speakerid.ui.explorer.SpeakerExplorerFragment
import com.fzi.speakerid.ui.widgets.SpeakerIdExpertTabBar
import java.util.Locale
import java.util.Random
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Port von siqas `gui/screens/embeddings/embeddings.py` + `embeddings.kv`
 * (<EmbeddingsScreen>) — Dev-Tool "Sprecher-Projektion".
 *
 * Bindings 1:1 wie `on_enter_screen` (Kivy-bind = StateFlow-collect):
 *  - clusters / embedding_coords_2d / speaker_positions_2d / selected_points
 *    -> [refreshUi] (debounced wie `Clock.unschedule` + `schedule_once(0)`)
 *  - projection_method -> bei >= 3 Embeddings automatisch neu projizieren
 *
 * `selected_points` haelt im Original Dicts {"type": "point"|"centroid",
 * "id": int|str}; im Port werden sie als Strings "point:<idx>" bzw.
 * "centroid:<clusterId>" in [SpeakerIdDataManager.selectedPoints] kodiert
 * (Flow-Typ List<String?>); fremde/unbekannte Werte werden ignoriert.
 *
 * Lifecycle: `on_enter`/`on_leave` = `repeatOnLifecycle(STARTED)`;
 * Hardware-Back = Standard-Back der Navigation (BaseScreen key 27).
 */
class SpeakerEmbeddingsFragment : Fragment() {

    /** Pendant zum Auswahl-Dict {"type": ..., "id": ...}. */
    private data class Selection(val type: String, val id: String)

    /** Pendant zu den TableRow-Daten-Dicts aus `_do_refresh_ui`. */
    private data class TableRowData(
        val rowType: String,
        val sectionLabel: String = "",
        val clusterId: String = "",
        val indexStr: String = "0",
        val indexInt: Int = 0,
        val cluster: String = "",
        val timeStr: String = "",
        val timestampStr: String = "",
        val embStr: String = "",
        val isSelected: Boolean = false,
        val rowColor: Int = 0xFF999999.toInt(),
    )

    private var _binding: FragmentSpeakeridEmbeddingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var dm: SpeakerIdDataManager
    private var projectionService: SpeakerEmbeddingsProjectionService? = null

    /** `active_filter_ids` — leere Liste = alle anzeigen. */
    private val activeFilterIds = mutableListOf<String>()

    /** `is_processing` */
    private var isProcessing = false

    /** `_last_select_ts` (Debounce 0.35 s in `select_item`). */
    private var lastSelectTs = 0L

    private val rng = Random()

    private val refreshRunnable = Runnable { doRefreshUi() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSpeakeridEmbeddingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dm = SpeakerIdDataManager.getInstance(requireContext().applicationContext)

        // ── Experten-Tab-Leiste (main.kv tab_bar_container) ─────────────────
        binding.speakeridEmbeddingsTabBar.apply {
            // kv: state 'down' wenn screen_manager.current == 'embeddings'
            setActiveTab(SpeakerIdExpertTabBar.Tab.EMBEDDINGS)
            onTabSelected = { tab ->
                val nav = findNavController()
                when (tab) {
                    SpeakerIdExpertTabBar.Tab.STATUS ->
                        if (!nav.popBackStack(R.id.speakerDashboardFragment, false)) {
                            nav.navigate(R.id.speakerDashboardFragment)
                        }
                    SpeakerIdExpertTabBar.Tab.DIARIZATION ->
                        if (!nav.popBackStack(R.id.diarizationReportFragment, false)) {
                            nav.navigate(R.id.diarizationReportFragment)
                        }
                    SpeakerIdExpertTabBar.Tab.EXPLORER -> nav.navigate(
                        R.id.speakeridExplorerFragment,
                        bundleOf(
                            SpeakerExplorerFragment.ARG_SELECTION_MODE to
                                SpeakerExplorerFragment.MODE_ANALYSIS,
                        ),
                    )
                    SpeakerIdExpertTabBar.Tab.PIPELINE ->
                        nav.navigate(R.id.speakeridPipelineFragment)
                    SpeakerIdExpertTabBar.Tab.PERFORMANCE ->
                        nav.navigate(R.id.speakeridPerformanceTestFragment)
                    SpeakerIdExpertTabBar.Tab.SETTINGS ->
                        nav.navigate(R.id.speakeridExpertSettingsFragment)
                    SpeakerIdExpertTabBar.Tab.EMBEDDINGS -> Unit
                }
            }
            onClose = {
                // kv X-Button: app.expert_lab_active = False;
                //              screen_manager.current = 'main_menu'
                dm.expertModeActive.value = false
                findNavController().popBackStack(R.id.speakeridMenuFragment, false)
            }
            // Leiste nur bei expert_lab_active (main.kv)
            visibility = if (dm.expertModeActive.value) View.VISIBLE else View.GONE
        }

        // on_enter_screen: ProjectionService lazy anlegen (hasattr-Check)
        if (projectionService == null) {
            projectionService = SpeakerEmbeddingsProjectionService(dm)
        }

        applySplitOrientation()

        // PointMap: screen_ref-Callbacks + highlight_color (accent)
        binding.speakeridEmbeddingsPointMap.apply {
            highlightColor = ContextCompat.getColor(requireContext(), R.color.speakerid_accent)
            onSelectPoint = { index -> selectItem(TYPE_POINT, index.toString()) }
            onSelectCentroid = { cid -> selectItem(TYPE_CENTROID, cid) }
            onHoverChanged = { data -> updateHoverLabel(data) }
        }

        // Action-Bar
        binding.speakeridEmbeddingsBtnRefresh.setOnClickListener { runProjection() }
        binding.speakeridEmbeddingsBtnAddPoint.setOnClickListener { devAddTestPoint() }
        binding.speakeridEmbeddingsBtnAddCentroid.setOnClickListener { devAddTestCentroid() }
        binding.speakeridEmbeddingsBtnAddCluster.setOnClickListener { devAddTestCluster() }

        // font_size min(sp(20), width*0.05) bzw. min(sp(11), width*0.03)
        binding.root.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or2, _ ->
            if (r - l != or2 - ol && r - l > 0) applyResponsiveSizes((r - l).toFloat())
        }

        // on_enter_screen: _refresh_ui() + bind_to(...) — der jeweils erste
        // collect-Emit uebernimmt den initialen Refresh.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { dm.clustersVersion.collect { refreshUi() } }
                launch { dm.embeddingCoords2d.collect { refreshUi() } }
                launch { dm.speakerPositions2d.collect { refreshUi() } }
                launch { dm.selectedPoints.collect { refreshUi() } }
                launch {
                    // _on_projection_changed: nur bei Wechsel (Kivy bind feuert
                    // nicht initial), daher drop(1).
                    dm.projectionMethod.drop(1).collect {
                        if (dm.numEmbeddings >= 3) runProjection()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        binding.root.removeCallbacks(refreshRunnable)
        super.onDestroyView()
        _binding = null
    }

    // ── Responsive (Kivy-Ausdruecke mit root.width) ──────────────────────────

    private fun applyResponsiveSizes(widthPx: Float) {
        val b = _binding ?: return
        b.speakeridEmbeddingsTitle.setTextSize(
            TypedValue.COMPLEX_UNIT_PX, min(sp(20f), widthPx * 0.05f),
        )
        val btnSize = min(sp(11f), widthPx * 0.03f)
        b.speakeridEmbeddingsBtnRefresh.setTextSize(TypedValue.COMPLEX_UNIT_PX, btnSize)
        b.speakeridEmbeddingsBtnAddPoint.setTextSize(TypedValue.COMPLEX_UNIT_PX, btnSize)
        b.speakeridEmbeddingsBtnAddCentroid.setTextSize(TypedValue.COMPLEX_UNIT_PX, btnSize)
        b.speakeridEmbeddingsBtnAddCluster.setTextSize(TypedValue.COMPLEX_UNIT_PX, btnSize)
    }

    /**
     * `orientation: "vertical" if root.width < dp(750) else "horizontal"`;
     * vertikal: Karte size_hint_y 0.65, Details 1.0 — horizontal: 0.63/0.37.
     */
    private fun applySplitOrientation() {
        val b = _binding ?: return
        val vertical = resources.configuration.screenWidthDp < 750
        b.speakeridEmbeddingsMainSplit.orientation =
            if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL

        val mapLp: LinearLayout.LayoutParams
        val detailsLp: LinearLayout.LayoutParams
        if (vertical) {
            mapLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.65f)
            detailsLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            detailsLp.topMargin = dp(12f)
        } else {
            mapLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.63f)
            detailsLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.37f)
            detailsLp.marginStart = dp(12f)
        }
        b.speakeridEmbeddingsMapColumn.layoutParams = mapLp
        b.speakeridEmbeddingsDetailsColumn.layoutParams = detailsLp
    }

    // ── Filter (toggle_filter / _rebuild_filter_bar) ─────────────────────────

    private fun toggleFilter(clusterId: String) {
        if (!activeFilterIds.remove(clusterId)) activeFilterIds.add(clusterId)
        refreshUi()
    }

    private fun rebuildFilterBar(allClusterIds: List<String>) {
        val b = _binding ?: return
        val ctx = requireContext()
        val bar = b.speakeridEmbeddingsFilterBar
        bar.removeAllViews()
        val font = ResourcesCompat.getFont(ctx, R.font.speakerid_dejavu)
        val surfaceDark = ContextCompat.getColor(ctx, R.color.speakerid_surface_dark)
        val textSecondary = ContextCompat.getColor(ctx, R.color.speakerid_text_secondary)
        val white = ContextCompat.getColor(ctx, R.color.speakerid_white)

        for ((i, cid) in allClusterIds.sorted().withIndex()) {
            val color = SpeakerIdTheme.speakerColor(ctx, cid)
            val isActive = activeFilterIds.contains(cid) || activeFilterIds.isEmpty()
            val chip = AppCompatTextView(ctx).apply {
                text = cid
                setTypeface(font, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                gravity = Gravity.CENTER
                setTextColor(if (isActive) white else textSecondary)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(8f).toFloat() // dc.RADIUS_CARD
                    setColor(
                        if (isActive) {
                            ColorUtils.setAlphaComponent(color, 217) // [0.85] Alpha
                        } else {
                            surfaceDark
                        },
                    )
                }
                isClickable = true
                setOnClickListener { toggleFilter(cid) }
            }
            val lp = LinearLayout.LayoutParams(dp(46f), dp(26f))
            lp.gravity = Gravity.CENTER_VERTICAL
            if (i > 0) lp.marginStart = dp(6f) // spacing "6dp"
            bar.addView(chip, lp)
        }
    }

    // ── Refresh (Port von _refresh_ui / _do_refresh_ui) ──────────────────────

    /** Debounced UI-Refresh — verhindert mehrfache Aufrufe pro Frame. */
    private fun refreshUi() {
        val root = _binding?.root ?: return
        root.removeCallbacks(refreshRunnable)
        root.post(refreshRunnable)
    }

    /**
     * Port von `_get_flat_embeddings`: alle Embeddings mit validen Vektoren
     * als sortierte Flachliste — GLEICHE Reihenfolge wie der ProjectionService.
     */
    private fun flatEmbeddings(clusters: Map<String, Cluster>): List<Pair<String, ClusterEmbedding>> {
        val result = ArrayList<Pair<String, ClusterEmbedding>>()
        for (cid in clusters.keys.sorted()) {
            for (emb in clusters.getValue(cid).embeddings) {
                if (emb.embedding != null) result.add(cid to emb)
            }
        }
        return result
    }

    private fun decodeSelections(): MutableList<Selection> =
        dm.selectedPoints.value.mapNotNull { raw ->
            val parts = raw?.split(":", limit = 2) ?: return@mapNotNull null
            if (parts.size == 2 && (parts[0] == TYPE_POINT || parts[0] == TYPE_CENTROID)) {
                Selection(parts[0], parts[1])
            } else {
                null
            }
        }.toMutableList()

    private fun doRefreshUi() {
        val b = _binding ?: return
        val ctx = requireContext()

        isProcessing = projectionService?.isProcessing ?: false
        updateProcessingUi()

        val clusters = dm.clustersSnapshot()
        val coords = dm.embeddingCoords2d.value
        val flat = flatEmbeddings(clusters)
        // Nur Cluster mit tatsaechlichen Embeddings als Filter anbieten
        val clusterIdsWithEmbeddings = flat.map { it.first }.distinct()
        val active = activeFilterIds.toList() // leer = alle anzeigen
        val selected = decodeSelections()

        rebuildFilterBar(clusterIdsWithEmbeddings)

        val processedPoints = ArrayList<SpeakerEmbeddingsPointMapView.MapPoint>(flat.size)
        val tableList = ArrayList<TableRowData>()

        for ((i, entry) in flat.withIndex()) {
            val (cid, emb) = entry
            val pos = coords.getOrNull(i) ?: floatArrayOf(0.5f, 0.5f)
            val isSel = selected.any { it.type == TYPE_POINT && it.id == i.toString() }
            val color = SpeakerIdTheme.speakerColor(ctx, cid)

            val timeStr = if (emb.time != 0.0) {
                String.format(Locale.US, "%.1fs", emb.time)
            } else {
                "—" // "—"
            }
            val vec = emb.embedding
            val embPreview = if (vec != null && vec.size > 4) {
                String.format(
                    Locale.US, "[%.2f, %.2f, %.2f, %.2f, %.2f...]",
                    vec[0], vec[1], vec[2], vec[3], vec[4],
                )
            } else {
                "N/A"
            }

            processedPoints.add(
                SpeakerEmbeddingsPointMapView.MapPoint(
                    index = i,
                    xNorm = pos[0],
                    yNorm = pos[1],
                    color = color,
                    isSelected = isSel,
                    clusterLabel = cid,
                ),
            )

            if (active.isEmpty() || active.contains(cid)) {
                val hIdx = emb.historyIdx
                val tsSec = emb.startTime
                    ?: ((hIdx ?: i) * SpeakerIdDataManager.CHUNK_DURATION)
                val mins = (tsSec / 60.0).toInt()
                val secs = (tsSec % 60.0).toInt()
                tableList.add(
                    TableRowData(
                        rowType = ROW_POINT,
                        indexStr = i.toString(),
                        indexInt = i,
                        cluster = cid,
                        timeStr = timeStr,
                        timestampStr = String.format(Locale.US, "%d:%02d", mins, secs),
                        embStr = embPreview,
                        isSelected = isSel,
                        rowColor = color,
                    ),
                )
            }
        }

        // Centroid-Zeilen sammeln
        val processedCentroids = ArrayList<SpeakerEmbeddingsPointMapView.MapCentroid>()
        val centroidRows = ArrayList<TableRowData>()
        for ((cId, pos2d) in dm.speakerPositions2d.value) {
            val isSel = selected.any { it.type == TYPE_CENTROID && it.id == cId }
            val color = SpeakerIdTheme.speakerColor(ctx, cId)
            processedCentroids.add(
                SpeakerEmbeddingsPointMapView.MapCentroid(
                    clusterId = cId,
                    xNorm = pos2d.getOrElse(0) { 0.5f },
                    yNorm = pos2d.getOrElse(1) { 0.5f },
                    color = color,
                    isSelected = isSel,
                ),
            )
            if (active.isEmpty() || active.contains(cId)) {
                val centroid = clusters[cId]?.centroid
                if (centroid != null) {
                    val embPreview = if (centroid.size > 4) {
                        String.format(
                            Locale.US, "[%.2f, %.2f, %.2f, %.2f, %.2f...]",
                            centroid[0], centroid[1], centroid[2], centroid[3], centroid[4],
                        )
                    } else {
                        "N/A"
                    }
                    centroidRows.add(
                        TableRowData(
                            rowType = ROW_CENTROID,
                            clusterId = cId,
                            embStr = embPreview,
                            isSelected = isSel,
                            rowColor = color,
                        ),
                    )
                }
            }
        }

        // Tabelle zusammenbauen: Centroids oben, dann Embeddings
        val fullTable = ArrayList<TableRowData>()
        if (centroidRows.isNotEmpty()) {
            fullTable.add(
                TableRowData(
                    rowType = ROW_HEADER,
                    sectionLabel = getString(R.string.speakerid_embeddings_section_centroids),
                ),
            )
            fullTable.addAll(centroidRows.sortedBy { it.clusterId })
        }
        if (tableList.isNotEmpty()) {
            fullTable.add(
                TableRowData(
                    rowType = ROW_HEADER,
                    sectionLabel = getString(R.string.speakerid_embeddings_section_embeddings),
                ),
            )
            fullTable.addAll(tableList)
        }

        b.speakeridEmbeddingsPointMap.pointsData = processedPoints
        b.speakeridEmbeddingsPointMap.centroidsData = processedCentroids
        rebuildTable(fullTable)
        updateComparisonText(clusters, flat, selected)
    }

    // ── Tabelle (Port der <TableRow>-Rule) ───────────────────────────────────

    private fun rebuildTable(rows: List<TableRowData>) {
        val b = _binding ?: return
        val ctx = requireContext()
        val container = b.speakeridEmbeddingsTableContainer
        container.removeAllViews()
        val inflater = LayoutInflater.from(ctx)
        val surfaceDark = ContextCompat.getColor(ctx, R.color.speakerid_surface_dark)
        val selectedBg = ContextCompat.getColor(ctx, R.color.speakerid_embeddings_row_selected)
        val textSecondary = ContextCompat.getColor(ctx, R.color.speakerid_text_secondary)

        for (row in rows) {
            val item = ViewSpeakeridEmbeddingsTableRowBinding.inflate(inflater, container, false)
            val isHeader = row.rowType == ROW_HEADER
            // .kv-Hoehe (22dp Header / 42dp sonst) nur als Minimum: eine
            // zweizeilige Vektor-Vorschau clippte in der fixen Box.
            item.root.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            item.root.minimumHeight = if (isHeader) dp(22f) else dp(42f)

            if (isHeader) {
                item.root.setBackgroundColor(surfaceDark)
                item.speakeridEmbeddingsRowHeaderLabel.visibility = View.VISIBLE
                item.speakeridEmbeddingsRowHeaderLabel.text = row.sectionLabel
                item.speakeridEmbeddingsRowContent.visibility = View.GONE
                item.speakeridEmbeddingsRowColorBar.visibility = View.GONE
                item.speakeridEmbeddingsRowBorder.visibility = View.GONE
            } else {
                val isCentroid = row.rowType == ROW_CENTROID
                item.root.setBackgroundColor(if (row.isSelected) selectedBg else 0)
                item.speakeridEmbeddingsRowColorBar.setBackgroundColor(row.rowColor)

                // Spalte 1: "P{index}" bzw. "C"-Badge
                item.speakeridEmbeddingsRowColIndex.apply {
                    text = if (isCentroid) {
                        getString(R.string.speakerid_embeddings_row_centroid_badge)
                    } else {
                        getString(R.string.speakerid_embeddings_row_point_fmt, row.indexStr)
                    }
                    setTypeface(null, if (isCentroid) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(if (isCentroid) row.rowColor else textSecondary)
                }
                // Spalte 2: "C {id}" bzw. Cluster-Name (immer row_color, bold)
                item.speakeridEmbeddingsRowColCluster.apply {
                    text = if (isCentroid) {
                        getString(R.string.speakerid_embeddings_row_centroid_fmt, row.clusterId)
                    } else {
                        row.cluster
                    }
                    setTextColor(row.rowColor)
                }
                item.speakeridEmbeddingsRowColTimestamp.text = row.timestampStr
                item.speakeridEmbeddingsRowColVad.text = row.timeStr
                item.speakeridEmbeddingsRowColVector.text = row.embStr

                item.root.setOnClickListener {
                    if (isCentroid) {
                        selectCentroidRow(row.clusterId)
                    } else {
                        selectTableRow(row.indexInt)
                    }
                }
            }
            container.addView(item.root)
        }
    }

    // ── Auswahl (select_item / select_table_row / select_centroid_row) ───────

    /** Waehlt einen Punkt oder Centroid aus (max. 2 gleichzeitig). */
    private fun selectItem(itemType: String, itemId: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSelectTs < 350) return
        lastSelectTs = now

        val current = decodeSelections()
        val newItem = Selection(itemType, itemId)
        if (!current.remove(newItem)) {
            if (current.size >= 2) current.removeAt(0)
            current.add(newItem)
        }
        dm.selectedPoints.value = current.map { "${it.type}:${it.id}" }
    }

    private fun selectTableRow(index: Int) = selectItem(TYPE_POINT, index.toString())

    private fun selectCentroidRow(clusterId: String) = selectItem(TYPE_CENTROID, clusterId)

    // ── Vergleichs-Text (_update_comparison_text) ────────────────────────────

    private fun updateComparisonText(
        clusters: Map<String, Cluster>,
        flat: List<Pair<String, ClusterEmbedding>>,
        selected: List<Selection>,
    ) {
        val b = _binding ?: return
        if (selected.size != 2) {
            b.speakeridEmbeddingsComparisonLabel.text =
                getString(R.string.speakerid_embeddings_compare_select_two)
            return
        }

        try {
            val vecs = ArrayList<DoubleArray?>(2)
            val names = ArrayList<String>(2)
            for (item in selected) {
                if (item.type == TYPE_POINT) {
                    val idx = item.id.toIntOrNull() ?: -1
                    if (idx < 0 || idx >= flat.size) {
                        b.speakeridEmbeddingsComparisonLabel.text =
                            getString(R.string.speakerid_embeddings_compare_err_index)
                        return
                    }
                    vecs.add(flat[idx].second.embedding)
                    names.add("P$idx")
                } else {
                    vecs.add(clusters[item.id]?.centroid)
                    names.add("C${item.id.take(5)}")
                }
            }

            val v1 = vecs[0]
            val v2 = vecs[1]
            if (v1 == null || v2 == null) {
                b.speakeridEmbeddingsComparisonLabel.text =
                    getString(R.string.speakerid_embeddings_compare_err_no_vector)
                return
            }
            if (norm(v1) < 1e-9 || norm(v2) < 1e-9) {
                b.speakeridEmbeddingsComparisonLabel.text =
                    getString(R.string.speakerid_embeddings_compare_err_zero_vector)
                return
            }

            val sim = CosineSimilarity.normalizedCosine(v1, v2)
            // "{P0} vs {C1}\n[b]Cosinus-Ähnlichkeit: {sim:.4f}[/b]"
            val line1 = getString(
                R.string.speakerid_embeddings_compare_names_fmt, names[0], names[1],
            )
            val line2 = getString(
                R.string.speakerid_embeddings_compare_result_fmt,
                String.format(Locale.US, "%.4f", sim),
            )
            val text = SpannableStringBuilder(line1).append('\n')
            val boldStart = text.length
            text.append(line2)
            text.setSpan(
                StyleSpan(Typeface.BOLD), boldStart, text.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            b.speakeridEmbeddingsComparisonLabel.text = text
        } catch (exc: Exception) {
            b.speakeridEmbeddingsComparisonLabel.text = getString(
                R.string.speakerid_embeddings_compare_err_fmt,
                exc.message ?: exc.toString(),
            )
        }
    }

    private fun norm(v: DoubleArray): Double {
        var sq = 0.0
        for (x in v) sq += x * x
        return sqrt(sq)
    }

    // ── Hover (hovered_data -> Tooltip-Label) ────────────────────────────────

    private fun updateHoverLabel(data: SpeakerEmbeddingsPointMapView.HoverData?) {
        val b = _binding ?: return
        val ctx = requireContext()
        if (data != null) {
            b.speakeridEmbeddingsHoverLabel.text =
                getString(R.string.speakerid_embeddings_hover_fmt, data.type, data.cluster)
            b.speakeridEmbeddingsHoverLabel.setTextColor(
                ContextCompat.getColor(ctx, R.color.speakerid_secondary),
            )
        } else {
            b.speakeridEmbeddingsHoverLabel.text =
                getString(R.string.speakerid_embeddings_hover_placeholder)
            b.speakeridEmbeddingsHoverLabel.setTextColor(
                ContextCompat.getColor(ctx, R.color.speakerid_text_secondary),
            )
        }
    }

    // ── Projektion (run_projection) ──────────────────────────────────────────

    /** Startet die Dimensionsreduktion via ProjectionService (Non-Blocking). */
    private fun runProjection() {
        val service = projectionService ?: return
        isProcessing = true
        updateProcessingUi()
        service.runProjection {
            isProcessing = false
            if (_binding != null) {
                updateProcessingUi()
                refreshUi()
            }
        }
    }

    /** "Berechne..." if root.is_processing else "" + disabled-Zustand Refresh. */
    private fun updateProcessingUi() {
        val b = _binding ?: return
        b.speakeridEmbeddingsStatusLabel.text =
            if (isProcessing) getString(R.string.speakerid_embeddings_processing) else ""
        b.speakeridEmbeddingsBtnRefresh.isEnabled = !isProcessing
    }

    // ── Dev-Helfer: echte Embedding-Geometrie nachbilden ─────────────────────
    // ECAPA-TDNN-Embeddings sind L2-normalisiert; Embeddings desselben
    // Sprechers streuen mit ~sigma=0.05 um den Centroid (wie im Original).

    private fun unitVec(): DoubleArray {
        val v = DoubleArray(192) { rng.nextGaussian() }
        val n = maxOf(norm(v), 1e-9)
        for (i in v.indices) v[i] /= n
        return v
    }

    private fun perturbAround(base: DoubleArray, sigma: Double = 0.05): DoubleArray {
        val v = DoubleArray(192) { base[it] + rng.nextGaussian() * sigma }
        val n = maxOf(norm(v), 1e-9)
        for (i in v.indices) v[i] /= n
        return v
    }

    /** Haengt Eintrag an chunk_history an und triggert UI-Update. Liefert den Index. */
    private fun appendHistory(
        clusterId: String?,
        status: String = SpeakerManager.STATUS_SPEECH,
    ): Int {
        val history = dm.speakerManager().chunkHistory
        val hIdx = history.size
        history.add(HistoryEntry(id = clusterId, status = status))
        // Pendant zu `self.dm.property('chunk_history').dispatch(self.dm)`
        dm.dispatchClusters()
        return hIdx
    }

    private fun nextFreeClusterId(): String {
        val existing = dm.clustersSnapshot().keys
            .mapNotNull { it.toIntOrNull() }
            .filter { it > 0 }
        return if (existing.isNotEmpty()) (existing.max() + 1).toString() else "2"
    }

    /** Neuen Cluster mit 5 nah beieinander liegenden Testpunkten erzeugen. */
    private fun devAddTestCluster() {
        val newId = nextFreeClusterId()
        val baseVec = unitVec()
        repeat(5) {
            val vec = perturbAround(baseVec, sigma = 0.05)
            val hIdx = appendHistory(newId)
            dm.addNewEmbedding(vec, clusterId = newId, historyIdx = hIdx)
        }
        Log.i(TAG, "[EmbeddingsScreen] Test-Cluster $newId mit 5 Punkten hinzugefügt.")
    }

    /** Einzelner Punkt im Unlabeled-Pool — Status 'silence', nicht in Timeline. */
    private fun devAddTestPoint() {
        val vec = unitVec()
        val hIdx = appendHistory(null, status = SpeakerManager.STATUS_SILENCE)
        dm.addNewEmbedding(vec, clusterId = "0", historyIdx = hIdx)
    }

    /** Neuer Sprecher mit einem einzelnen Punkt (= initialer Centroid). */
    private fun devAddTestCentroid() {
        val newId = nextFreeClusterId()
        val vec = unitVec()
        val hIdx = appendHistory(newId)
        dm.addNewEmbedding(vec, clusterId = newId, historyIdx = hIdx)
    }

    // ── Helfer ───────────────────────────────────────────────────────────────

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics,
        )

    companion object {
        private const val TAG = "SpeakerEmbeddings"

        private const val TYPE_POINT = "point"
        private const val TYPE_CENTROID = "centroid"

        private const val ROW_HEADER = "header"
        private const val ROW_CENTROID = "centroid"
        private const val ROW_POINT = "point"
    }
}
