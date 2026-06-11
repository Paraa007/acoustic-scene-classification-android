package com.fzi.speakerid.ui.diarizationreport

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.fzi.acousticscene.R
import com.fzi.acousticscene.databinding.FragmentSpeakeridDiarizationReportBinding
import com.fzi.speakerid.library.LiveProcessorConfig
import com.fzi.speakerid.library.pipeline.OnnxSessionProvider
import com.fzi.speakerid.ui.AssetModelInstaller
import com.fzi.speakerid.ui.SpeakerIdDataManager
import com.fzi.speakerid.ui.SpeakerIdTheme
import com.fzi.speakerid.ui.explorer.SpeakerExplorerFragment
import com.fzi.speakerid.ui.widgets.SpeakerIdExpertTabBar
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Port von siqas `src/gui/screens/diarization_report/diarization_report.py`
 * (+ `.kv`): Vergleich Referenz-RTTM vs. Prediction-RTTM mit Timeline,
 * DER-Metriken und eingefrorenem 2D-Cluster-Snapshot.
 *
 * Kivy -> Android:
 *  - StringProperty/BooleanProperty -> private Felder + apply*-Methoden,
 *    welche die kv-Bindings (Hoehen/Opacity/Farben) nachziehen. Kollabieren
 *    (Hoehe 0 + opacity 0 + disabled) = Hoehe 0 + INVISIBLE, damit die
 *    BoxLayout-Spacings wie in Kivy erhalten bleiben.
 *  - `on_enter_screen`/`on_leave_screen` (Metrik-Bindings) -> onResume/onPause.
 *  - `choose_file` wechselte zum Explorer-Screen (existiert im Port nicht) ->
 *    System-Dateiauswahl (SAF); die Datei wird in den Cache kopiert.
 *  - `dm.PROJECT_ROOT/data/...` (GT-RTTM, Target-Audio + Sidecar) ->
 *    gleiche relative Struktur unter `getExternalFilesDir(null)`.
 *  - Hintergrund-Threads + `Clock.schedule_once` -> Coroutines auf dem
 *    viewLifecycleOwner-Scope (Apply auf Main).
 */
class DiarizationReportFragment : Fragment() {

    private var _binding: FragmentSpeakeridDiarizationReportBinding? = null
    private val binding get() = _binding!!

    private lateinit var dataManager: SpeakerIdDataManager

    // ── Kivy-Properties ──────────────────────────────────────────────────────
    private var mode = "live_file"           // "live_file" | "select_file"
    private var selectedFile = ""
    private var warningMessage = ""
    private var isProcessing = false
    private var isProjecting = false

    private var splitIsVertical: Boolean? = null
    private var provider: OnnxSessionProvider? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSpeakeridDiarizationReportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dataManager = SpeakerIdDataManager.getInstance(requireContext())

        // ScreenHeader: on_back -> Standard-Back
        binding.speakeridDiarizationHeader.onBack = { findNavController().popBackStack() }

        // ── Experten-Tab-Leiste (main.kv tab_bar_container) ─────────────────
        binding.speakeridDiarizationTabBar.apply {
            // kv: state: 'down' if screen_manager.current == 'diarization_report'
            setActiveTab(SpeakerIdExpertTabBar.Tab.DIARIZATION)
            onTabSelected = { tab ->
                // kv on_release: screen_manager.current = '<screen>' — das
                // Dashboard liegt meist im Backstack (Einstieg ins Labor),
                // andernfalls frisch dorthin navigieren. Der Explorer-Tab
                // setzt zusaetzlich selection_mode = "analysis" (main.kv).
                val nav = findNavController()
                when (tab) {
                    SpeakerIdExpertTabBar.Tab.STATUS ->
                        if (!nav.popBackStack(R.id.speakerDashboardFragment, false)) {
                            nav.navigate(R.id.speakerDashboardFragment)
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
                    SpeakerIdExpertTabBar.Tab.EMBEDDINGS ->
                        nav.navigate(R.id.speakeridEmbeddingsFragment)
                    SpeakerIdExpertTabBar.Tab.PERFORMANCE ->
                        nav.navigate(R.id.speakeridPerformanceTestFragment)
                    SpeakerIdExpertTabBar.Tab.SETTINGS ->
                        nav.navigate(R.id.speakeridExpertSettingsFragment)
                    SpeakerIdExpertTabBar.Tab.DIARIZATION -> Unit
                }
            }
            onClose = {
                // kv X-Button: app.expert_lab_active = False;
                //              screen_manager.current = 'main_menu'
                dataManager.expertModeActive.value = false
                findNavController().popBackStack(R.id.speakeridMenuFragment, false)
            }
        }

        binding.speakeridDiarizationChooseFileButton.setOnClickListener { chooseFile() }
        binding.speakeridDiarizationRefreshButton.setOnClickListener { updateReport() }

        // Explorer-Rueckgabe ("diarization"-Mode) — Port von `on_selected_file`:
        // bei NEU gewaehlter Datei automatisch rechnen, beim reinen
        // Screen-Wechsel (kein Ergebnis im Handle) nicht.
        val backStackEntry = findNavController().currentBackStackEntry
        backStackEntry?.savedStateHandle
            ?.getLiveData<String>(SpeakerExplorerFragment.RESULT_SELECTED_FILE)
            ?.observe(viewLifecycleOwner) { path ->
                if (!path.isNullOrEmpty()) {
                    backStackEntry.savedStateHandle
                        .remove<String>(SpeakerExplorerFragment.RESULT_SELECTED_FILE)
                    selectedFile = path
                    binding.speakeridDiarizationFileNameLabel.text = File(path).name
                    updateReport()
                }
            }

        // "SPEAKER-CLUSTER STANDBILD (<projection_method>)" — live an dm gebunden
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                dataManager.projectionMethod.collect { method ->
                    binding.speakeridDiarizationSnapshotTitle.text =
                        getString(R.string.speakerid_diarization_report_snapshot_title, method)
                }
            }
        }

        // main_split: "vertical" if root.width < dp(800) else "horizontal"
        binding.root.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val width = r - l
            if (width > 0 && _binding != null) applySplitOrientation(width)
        }

        applyModeVisibility()
        setWarning("")
        setProcessing(false)
        setProjecting(false)

        // SegmentedChoice: options + on_value; das initiale value-Setzen feuert
        // wie Kivys kv-Bindung einmal select_mode -> update_report.
        binding.speakeridDiarizationModeChoice.apply {
            options = listOf(
                getString(R.string.speakerid_diarization_report_mode_live) to "live_file",
                getString(R.string.speakerid_diarization_report_mode_select) to "select_file",
            )
            onValueChanged = { selectMode(it) }
            value = this@DiarizationReportFragment.mode
        }
    }

    /** `on_enter_screen`: Metrik-Properties des Timeline-Widgets binden. */
    override fun onResume() {
        super.onResume()
        binding.speakeridDiarizationTimelineView.onMetricsChanged = { onTimelineMetricsChanged(it) }
    }

    /** `on_leave_screen`: Bindings loesen. */
    override fun onPause() {
        _binding?.speakeridDiarizationTimelineView?.onMetricsChanged = null
        super.onPause()
    }

    override fun onDestroyView() {
        _binding?.speakeridDiarizationTimelineView?.onMetricsChanged = null
        _binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        // ONNX-Sessions nur freigeben, wenn keine Hintergrund-Analyse mehr auf
        // ihnen rechnet (die IO-Arbeit laeuft nach dem Cancel noch zu Ende).
        if (!isProcessing) {
            provider?.close()
            provider = null
        }
        super.onDestroy()
    }

    // ── Kivy-Handler ─────────────────────────────────────────────────────────

    /** Port von `select_mode`. */
    private fun selectMode(modeCode: String) {
        mode = modeCode
        setWarning("")
        applyModeVisibility()
        updateReport()
    }

    /**
     * Port von `choose_file`: "Switches to the ExplorerScreen in diarization
     * mode." — `explorer.selection_mode = "diarization"`, Rueckgabe des
     * gewaehlten Pfads via savedStateHandle (siehe [onViewCreated]).
     */
    private fun chooseFile() {
        findNavController().navigate(
            R.id.speakeridExplorerFragment,
            bundleOf(
                SpeakerExplorerFragment.ARG_SELECTION_MODE to
                    SpeakerExplorerFragment.MODE_DIARIZATION,
            ),
        )
    }

    /** Port von `update_report`. */
    private fun updateReport() {
        setWarning("")
        val timeline = binding.speakeridDiarizationTimelineView

        if (mode == "live_file") {
            // 1. Live-Datei-Modus (kein Ground-Truth-Vergleich)
            val wavPath = dataManager.currentAudioPath.value
            binding.speakeridDiarizationStatusLabel.text =
                if (wavPath.isEmpty() || !File(wavPath).exists()) {
                    getString(R.string.speakerid_diarization_report_status_live_mic)
                } else {
                    getString(
                        R.string.speakerid_diarization_report_status_live_file, File(wavPath).name
                    )
                }

            setWarning("")
            timeline.refLines = emptyList()
            timeline.enrollmentSegments = emptyList()  // kein Enrollment-Ausschluss im Live-Modus
            timeline.targetRefSpeaker = ""

            // Prediction aus dem DataManager-Zustand laden
            val stem =
                if (wavPath.isNotEmpty()) File(wavPath).nameWithoutExtension else "live_session"
            timeline.hypLines = dataManager.exportHistoryToRttm(stem)

            clearMetricLabels()
            runSnapshotProjection()
        } else {
            // 2. Dateiauswahl-Modus
            val wavPath = selectedFile
            if (wavPath.isEmpty() || !File(wavPath).exists()) {
                setWarning(getString(R.string.speakerid_diarization_report_warning_pick))
                timeline.refLines = emptyList()
                timeline.hypLines = emptyList()
                clearMetricLabels()
                return
            }

            binding.speakeridDiarizationStatusLabel.text =
                getString(R.string.speakerid_diarization_report_status_loaded, File(wavPath).name)

            // Ground Truth finden
            val gtPath = findGtRttm(wavPath)
            if (gtPath == null) {
                setWarning(getString(R.string.speakerid_diarization_report_warning_no_gt))
                timeline.refLines = emptyList()
                timeline.hypLines = emptyList()
                clearMetricLabels()
                return
            }

            // Ground Truth laden
            try {
                timeline.refLines = File(gtPath).readLines()
            } catch (e: Exception) {
                setWarning(
                    getString(
                        R.string.speakerid_diarization_report_warning_gt_error, errorText(e)
                    )
                )
                clearMetricLabels()
                return
            }

            // Vorhersage im Hintergrund rechnen
            setProcessing(true)
            viewLifecycleOwner.lifecycleScope.launch { runFilePredictionBackground(wavPath) }
        }
    }

    /** Port von `_run_file_prediction_background`. */
    private suspend fun runFilePredictionBackground(wavPath: String) {
        val appContext = requireContext().applicationContext
        val dataRoot = projectDataRoot()
        try {
            val outcome = withContext(Dispatchers.IO) {
                val modelsDir = AssetModelInstaller.install(appContext)
                val prov = provider ?: OnnxSessionProvider(modelsDir).also { provider = it }

                // PipelineConfig-Pendant mit den Nutzer-Schwellwerten (dm-Werte
                // beim Aufruf eingefroren). Denoise wurde in Phase 1 nicht
                // portiert; der GUI-Toggle hat hier wie dokumentiert keine Wirkung.
                val cfg = LiveProcessorConfig(
                    chunkDurationS = dataManager.chunkDuration.value,
                    chunkOverlapS = SpeakerIdDataManager.CHUNK_OVERLAP,
                    useSileroVad = dataManager.useSileroVad.value,
                    usePyannote = dataManager.usePyannote.value,
                    cleanerMinDurationS = dataManager.cleanerMinDuration.value,
                    cleanerMinIslandDurationS = dataManager.cleanerMinIslandDuration.value,
                    assignmentStrategy = dataManager.clusterAssignmentStrategy.value,
                    targetThreshold = dataManager.thresholdTarget.value,
                    normalThreshold = dataManager.thresholdNormal.value,
                    centroidUpdateStrategy = dataManager.centroidUpdateStrategy.value,
                    emaAlpha = dataManager.emaAlpha.value,
                    movingAverageWindowSize = dataManager.movingAverageWindowSize.value,
                    minSamplesForNewSpeaker = SpeakerIdDataManager.MIN_SAMPLES_FOR_NEW_SPEAKER,
                )

                // Target-Seeding wie im echten System (wav-target/<stem>.wav)
                val targetCentroid = buildTargetCentroid(prov, dataRoot, wavPath)
                val sidecar = loadTargetSidecar(dataRoot, wavPath)
                val prediction =
                    DiarizationFilePrediction.runOnWav(prov, File(wavPath), cfg, targetCentroid)
                Triple(prediction, sidecar.first, sidecar.second)
            }

            // `Clock.schedule_once(_apply)`-Pendant: Apply auf dem Main-Thread
            val timeline = binding.speakeridDiarizationTimelineView
            timeline.enrollmentSegments = outcome.second
            timeline.targetRefSpeaker = outcome.third
            timeline.hypLines = outcome.first.hypLines
            setProcessing(false)
            runSnapshotProjectionDirect(outcome.first.flatEmbeddings, outcome.first.centroidEmbeddings)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Analyse fehlgeschlagen", e)
            setWarning(
                getString(
                    R.string.speakerid_diarization_report_warning_analysis_error, errorText(e)
                )
            )
            setProcessing(false)
            binding.speakeridDiarizationTimelineView.hypLines = emptyList()
            clearMetricLabels()
        }
    }

    // ── Snapshot-Projektion ──────────────────────────────────────────────────

    /** Port von `run_snapshot_projection`: aktiven DataManager-Zustand einfrieren. */
    private fun runSnapshotProjection() {
        val flat = ArrayList<Pair<String, DoubleArray>>()
        val cents = ArrayList<Pair<String, DoubleArray>>()
        synchronized(dataManager) {
            for ((cid, cluster) in dataManager.clustersSnapshot()) {
                if (cid == "-1") continue
                cluster.centroid?.let { cents.add(cid to it.copyOf()) }
                for (emb in cluster.embeddings.toList()) {
                    emb.embedding?.let { flat.add(cid to it.copyOf()) }
                }
            }
        }
        runSnapshotProjectionDirect(flat, cents)
    }

    /** Port von `_run_snapshot_projection_direct` + `_project_snapshot_background`. */
    private fun runSnapshotProjectionDirect(
        flatEmbeddings: List<Pair<String, DoubleArray>>,
        centroidEmbeddings: List<Pair<String, DoubleArray>>,
    ) {
        if (flatEmbeddings.size < 3) {
            binding.speakeridDiarizationSnapshotMap.setData(emptyList(), emptyList())
            return
        }

        setProjecting(true)
        val appContext = requireContext().applicationContext
        val method = dataManager.projectionMethod.value
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val (points, centroids) = withContext(Dispatchers.Default) {
                    if (method != "PCA") {
                        // Mobile-Registry kennt nur PCA (reduce_dimensions-Fallback)
                        Log.w(TAG, "[Projektion] WARNUNG: Methode '$method' nicht verfuegbar. Nutze Fallback: PCA.")
                    }
                    val allVecs = ArrayList<DoubleArray>(flatEmbeddings.size + centroidEmbeddings.size)
                    flatEmbeddings.mapTo(allVecs) { it.second }
                    centroidEmbeddings.mapTo(allVecs) { it.second }

                    val coords = SnapshotProjection.normalizeToUnitSquare(
                        SnapshotProjection.reduceDimensionsPca(allVecs)
                    )

                    val n = flatEmbeddings.size
                    val pts = flatEmbeddings.mapIndexed { i, (cid, _) ->
                        SnapshotPointMapView.MapPoint(
                            coords[i][0].toFloat(),
                            coords[i][1].toFloat(),
                            SpeakerIdTheme.speakerColor(appContext, cid),
                        )
                    }
                    val cds = centroidEmbeddings.mapIndexed { i, (cid, _) ->
                        SnapshotPointMapView.MapCentroid(
                            cid,
                            coords[n + i][0].toFloat(),
                            coords[n + i][1].toFloat(),
                            SpeakerIdTheme.speakerColor(appContext, cid),
                        )
                    }
                    pts to cds
                }
                binding.speakeridDiarizationSnapshotMap.setData(points, centroids)
                setProjecting(false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "[SnapshotProjection] Fehler: $e")
                setProjecting(false)
            }
        }
    }

    // ── Datei-/Pfad-Helfer (Port von `_find_gt_rttm` & Co.) ─────────────────

    /** `dm.PROJECT_ROOT`-Pendant: App-eigener externer Speicher. */
    private fun projectDataRoot(): File =
        requireContext().getExternalFilesDir(null) ?: requireContext().filesDir

    /** Port von `_find_gt_rttm`: data/<dataset>/rttm/label_truth/<stem>.rttm */
    private fun findGtRttm(wavPath: String): String? {
        if (wavPath.isEmpty() || !File(wavPath).exists()) return null
        val stem = File(wavPath).nameWithoutExtension
        for (dataset in DATASETS) {
            val gt = File(projectDataRoot(), "data/$dataset/rttm/label_truth/$stem.rttm")
            if (gt.exists()) return gt.absolutePath
        }
        return null
    }

    /** Port von `_find_target_audio`: data/<dataset>/wav-target/<stem>.wav */
    private fun findTargetAudio(dataRoot: File, wavPath: String): String? {
        if (wavPath.isEmpty()) return null
        val stem = File(wavPath).nameWithoutExtension
        for (dataset in DATASETS) {
            val target = File(dataRoot, "data/$dataset/wav-target/$stem.wav")
            if (target.exists()) return target.absolutePath
        }
        return null
    }

    /** Port von `_build_target_centroid`. */
    private fun buildTargetCentroid(
        provider: OnnxSessionProvider,
        dataRoot: File,
        wavPath: String,
    ): DoubleArray? {
        val targetPath = findTargetAudio(dataRoot, wavPath) ?: return null
        return try {
            DiarizationFilePrediction.generateTargetCentroidFromFile(provider, File(targetPath))
        } catch (e: Exception) {
            Log.w(TAG, "[DiarizationReport] Target-Centroid fehlgeschlagen: $e")
            null
        }
    }

    /** Port von `_load_target_sidecar`: (enroll_segments, target_speaker_label). */
    private fun loadTargetSidecar(
        dataRoot: File,
        wavPath: String,
    ): Pair<List<Pair<Double, Double>>, String> {
        val targetPath = findTargetAudio(dataRoot, wavPath)
            ?: return emptyList<Pair<Double, Double>>() to ""
        val sidecar = File(targetPath.removeSuffix(".wav") + ".json")
        if (!sidecar.exists()) return emptyList<Pair<Double, Double>>() to ""
        return try {
            val data = JSONObject(sidecar.readText())
            val segments = ArrayList<Pair<Double, Double>>()
            val arr = data.optJSONArray("used_segments")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val seg = arr.getJSONArray(i)
                    segments.add(seg.getDouble(0) to seg.getDouble(1))
                }
            }
            val label = data.optString("target_speaker_label", "")
            segments to label
        } catch (e: Exception) {
            Log.w(TAG, "[DiarizationReport] Target-Sidecar laden fehlgeschlagen: $e")
            emptyList<Pair<Double, Double>>() to ""
        }
    }

    // ── Metriken (Port von `_on_metrics_changed` / `_clear_metrics`) ────────

    private fun onTimelineMetricsChanged(m: DiarizationTimelineView.Metrics) {
        if (mode == "live_file") {
            clearMetricLabels()
        } else {
            binding.speakeridDiarizationDerOverall.text = pct(m.der)
            binding.speakeridDiarizationMissOverall.text = pct(m.miss)
            binding.speakeridDiarizationFaOverall.text = pct(m.fa)
            binding.speakeridDiarizationConfOverall.text = pct(m.conf)
            binding.speakeridDiarizationDerTarget.text = pct(m.derTarget)
            binding.speakeridDiarizationMissTarget.text = pct(m.missTarget)
            binding.speakeridDiarizationFaTarget.text = pct(m.faTarget)
            binding.speakeridDiarizationConfTarget.text = pct(m.confTarget)
            applyDerColors()
        }
    }

    private fun pct(value: Double): String =
        String.format(Locale.US, "%.1f%%", value * 100.0)

    private fun clearMetricLabels() {
        val placeholder = getString(R.string.speakerid_diarization_report_metric_placeholder)
        listOf(
            binding.speakeridDiarizationDerOverall,
            binding.speakeridDiarizationMissOverall,
            binding.speakeridDiarizationFaOverall,
            binding.speakeridDiarizationConfOverall,
            binding.speakeridDiarizationDerTarget,
            binding.speakeridDiarizationMissTarget,
            binding.speakeridDiarizationFaTarget,
            binding.speakeridDiarizationConfTarget,
        ).forEach { it.text = placeholder }
        applyDerColors()
    }

    /** kv: DER-Wert accent, solange != "—", sonst text_secondary. */
    private fun applyDerColors() {
        val placeholder = getString(R.string.speakerid_diarization_report_metric_placeholder)
        val accent = ContextCompat.getColor(requireContext(), R.color.speakerid_accent)
        val secondary = ContextCompat.getColor(requireContext(), R.color.speakerid_text_secondary)
        binding.speakeridDiarizationDerOverall.setTextColor(
            if (binding.speakeridDiarizationDerOverall.text.toString() == placeholder) secondary else accent
        )
        binding.speakeridDiarizationDerTarget.setTextColor(
            if (binding.speakeridDiarizationDerTarget.text.toString() == placeholder) secondary else accent
        )
    }

    // ── kv-Bindings (Sichtbarkeit/Opacity/Status) ────────────────────────────

    /** Dateiauswahl-Zeile + Metriken-Karte nur im "select_file"-Modus. */
    private fun applyModeVisibility() {
        val collapsed = mode != "select_file"
        setCollapsed(binding.speakeridDiarizationFileRow, collapsed, dp(40))
        setCollapsed(binding.speakeridDiarizationMetricsCard, collapsed, dp(168))
        applyTimelineAlpha()
    }

    private fun setWarning(message: String) {
        warningMessage = message
        binding.speakeridDiarizationWarningLabel.text = message
        setCollapsed(binding.speakeridDiarizationWarningBanner, message.isEmpty(), dp(40))
        applyTimelineAlpha()
    }

    private fun setProcessing(value: Boolean) {
        isProcessing = value
        binding.speakeridDiarizationProcessingLabel.visibility =
            if (value) View.VISIBLE else View.GONE
        binding.speakeridDiarizationRefreshButton.isEnabled = !value
        applyTimelineAlpha()
    }

    /** kv: opacity 0.1 bei (warning && select_file) || is_processing. */
    private fun applyTimelineAlpha() {
        binding.speakeridDiarizationTimelineView.alpha =
            if ((warningMessage.isNotEmpty() && mode == "select_file") || isProcessing) 0.1f else 1.0f
    }

    private fun setProjecting(value: Boolean) {
        isProjecting = value
        binding.speakeridDiarizationSnapshotStatus.text = getString(
            if (value) R.string.speakerid_diarization_report_snapshot_projecting
            else R.string.speakerid_diarization_report_snapshot_frozen
        )
        binding.speakeridDiarizationSnapshotStatus.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (value) R.color.speakerid_accent else R.color.speakerid_text_secondary,
            )
        )
    }

    /**
     * Kivy-Kollabieren (height 0 + opacity 0 + disabled): Hoehe 0 + INVISIBLE,
     * damit Margins (= BoxLayout-Spacing) wie in Kivy erhalten bleiben.
     */
    private fun setCollapsed(view: View, collapsed: Boolean, expandedHeightPx: Int) {
        val lp = view.layoutParams
        lp.height = if (collapsed) 0 else expandedHeightPx
        view.layoutParams = lp
        view.visibility = if (collapsed) View.INVISIBLE else View.VISIBLE
    }

    /** main_split-Orientierung: links 0.60 / rechts 0.40, vertikal je 1.0. */
    private fun applySplitOrientation(rootWidthPx: Int) {
        val vertical = rootWidthPx < dp(800)
        if (splitIsVertical == vertical) return
        splitIsVertical = vertical

        val split = binding.speakeridDiarizationMainSplit
        val left = binding.speakeridDiarizationLeftPanel
        val right = binding.speakeridDiarizationRightPanel
        if (vertical) {
            split.orientation = LinearLayout.VERTICAL
            left.layoutParams =
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            right.layoutParams =
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                    topMargin = dp(12)
                }
        } else {
            split.orientation = LinearLayout.HORIZONTAL
            left.layoutParams =
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.60f)
            right.layoutParams =
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.40f).apply {
                    marginStart = dp(12)
                }
        }
    }

    /** Python `str(e)`-Pendant fuer die Warnbanner-Texte. */
    private fun errorText(e: Exception): String = e.message ?: e.toString()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val TAG = "DiarizationReport"

        /** Datasets wie `_find_gt_rttm` (msdwild, voxconverse). */
        private val DATASETS = listOf("msdwild", "voxconverse")
    }
}
