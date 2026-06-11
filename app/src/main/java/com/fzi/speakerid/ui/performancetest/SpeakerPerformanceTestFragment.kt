package com.fzi.speakerid.ui.performancetest

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.fzi.acousticscene.R
import com.fzi.acousticscene.databinding.FragmentSpeakeridPerformanceTestBinding
import com.fzi.speakerid.library.data.Cluster
import com.fzi.speakerid.library.data.ClusterEmbedding
import com.fzi.speakerid.library.pipeline.OnnxSessionProvider
import com.fzi.speakerid.library.pipeline.steps.EmbeddingExtractor
import com.fzi.speakerid.library.pipeline.steps.Resampler
import com.fzi.speakerid.library.pipeline.steps.SileroVad
import com.fzi.speakerid.library.pipeline.steps.SpeakerExtraction
import com.fzi.speakerid.library.pipeline.steps.SpeakerMatcher
import com.fzi.speakerid.library.pipeline.steps.SpeakerOverlapCleaner
import com.fzi.speakerid.library.pipeline.steps.WavReader
import com.fzi.speakerid.ui.AssetModelInstaller
import com.fzi.speakerid.ui.SpeakerIdDataManager
import com.fzi.speakerid.ui.SpeakerLiveSession
import com.fzi.speakerid.ui.explorer.SpeakerExplorerFragment
import com.fzi.speakerid.ui.widgets.SpeakerIdExpertTabBar
import java.io.File
import java.util.Locale
import java.util.Random
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Port von siqas `gui/screens/performance_test/performance_test.py` + `.kv`
 * (<PerformanceTestScreen>): laedt eine WAV-Datei, schickt sie chunk-weise
 * durch alle Pipeline-Schritte und misst pro Schritt die Wall-Time
 * ([SpeakerPerformanceChunkProfiler] = Port des `ChunkProfiler`).
 *
 * Messlaeufe nutzen die echten library-Klassen ([SileroVad],
 * [EmbeddingExtractor], [SpeakerOverlapCleaner], [SpeakerMatcher],
 * [SpeakerExtraction], [Cluster]) — exakt die Aufrufe aus `_run`.
 *
 * Bindings 1:1 wie im .kv (Kivy-Property = Feld + `render*`-Aufruf):
 * Konfigurations-/Pipeline-Parameter-Slider und -Choices schreiben in die
 * Felder, der Worker snapshottet sie beim Start. `on_enter_screen` (Defaults
 * aus dem DataManager spiegeln + Default-Audio setzen) -> [onResume];
 * `on_leave` hat im Original keine Logik -> der Benchmark laeuft wie in Kivy
 * beim Verlassen weiter (UI-Posts sind binding-null-sicher), erst
 * [onDestroy] stoppt ihn.
 *
 * Bewusste Abweichungen (Wave-1-Vorgaben):
 *  - `LiveNoiseReducer` (denoise.py) wurde in der Kotlin-library nicht
 *    portiert — der "noise"-Messschritt entfaellt, Toggle/Strategie-Wahl und
 *    die Quittungszeile (inkl. `is_inactive: not use_noise`) bleiben 1:1.
 *  - `knn_temporal` faellt im Kotlin-[SpeakerMatcher] dokumentiert auf
 *    `target_priority` zurueck (Option bleibt waehlbar wie im .kv).
 *  - `DEFAULT_AUDIO_PATH` (Projekt-Asset) existiert in der App nicht; als
 *    Default dient die Simulations-WAV `dm.current_audio_path`, falls
 *    vorhanden.
 *  - `cleaner_min_island_duration` wird wie im Python-Worker NICHT an
 *    `process_streaming` durchgereicht (dort ungenutzter Snapshot).
 */
class SpeakerPerformanceTestFragment : Fragment() {

    private var _binding: FragmentSpeakeridPerformanceTestBinding? = null
    private val binding get() = _binding!!

    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Kivy-Properties (Konfiguration) ──────────────────────────────────────
    private var chunkDuration = 1.0
    private var useVad = true
    private var useNoise = true
    private var usePyannote = true
    private var noiseStrategy = "spectral_subtract"

    // ── Pipeline-Parameter (spiegeln DataManager-Defaults; beim Enter geladen)
    private var centroidStrategy = "ema"
    private var assignmentStrategy = "best_overall"
    private var clusteringStrategy = "clique"
    private var thresholdTarget = 0.7
    private var thresholdNormal = 0.7
    private var emaAlpha = 0.1
    private var movingAverageWindow = 10.0
    private var minSamplesNewSpeaker = 3.0
    private var clusteringInterval = 1.0
    private var cleanerMinDuration = 0.2
    private var cleanerMinIslandDuration = 0.02
    private var simSpeakers = 0.0
    private var simSpeakerPoints = 5.0
    private var poolPrefill = 0.0

    // ── Status ───────────────────────────────────────────────────────────────
    @Volatile
    private var isRunning = false
    private var progress = 0.0
    private var chunksDone = 0
    private var chunksTotal = 0
    private var statusText = ""

    /** avg_/max_<base>_ms — Basen wie `reset_props` in `start_benchmark`. */
    private val avgMs = HashMap<String, Double>()
    private val maxMs = HashMap<String, Double>()
    private var avgTotalMs = 0.0
    private var avgWallMs = 0.0
    private var maxWallMs = 0.0
    private var realtimeRatio = 0.0

    private var selectedFile = ""
    private var wallHistory: List<Double> = emptyList()
    private var chartData: List<Double> = emptyList()
    private var chartMetric = "wall"
    private var profilerHist: List<Map<String, Double>> = emptyList()

    private val profiler = SpeakerPerformanceChunkProfiler()
    private var provider: OnnxSessionProvider? = null

    @Volatile
    private var shutdownRequested = false
    private var workerThread: Thread? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSpeakeridPerformanceTestBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = binding

        // ScreenHeader: on_back -> root.go_back()
        b.speakeridPerfHeader.onBack = { findNavController().popBackStack() }

        // ── Experten-Tab-Leiste (main.kv tab_bar_container) ─────────────────
        val dmTab = SpeakerIdDataManager.getInstance(requireContext().applicationContext)
        b.speakeridPerfTabBar.apply {
            // kv: state 'down' wenn screen_manager.current == 'performance_test'
            setActiveTab(SpeakerIdExpertTabBar.Tab.PERFORMANCE)
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
                    SpeakerIdExpertTabBar.Tab.EMBEDDINGS ->
                        nav.navigate(R.id.speakeridEmbeddingsFragment)
                    SpeakerIdExpertTabBar.Tab.SETTINGS ->
                        nav.navigate(R.id.speakeridExpertSettingsFragment)
                    SpeakerIdExpertTabBar.Tab.PERFORMANCE -> Unit
                }
            }
            onClose = {
                // kv X-Button: app.expert_lab_active = False;
                //              screen_manager.current = 'main_menu'
                dmTab.expertModeActive.value = false
                findNavController().popBackStack(R.id.speakeridMenuFragment, false)
            }
            // Leiste nur bei expert_lab_active (main.kv)
            visibility = if (dmTab.expertModeActive.value) View.VISIBLE else View.GONE
        }

        // ── Konfiguration ────────────────────────────────────────────────────
        b.speakeridPerfBtnPickWav.setOnClickListener { openExplorerPicker() }

        b.speakeridPerfSliderChunk.onValueChanged = {
            chunkDuration = it
            // threshold_ms: root.chunk_duration * 1000 (Chart-Bindung)
            _binding?.speakeridPerfChart?.thresholdMs = it * 1000.0
        }
        b.speakeridPerfToggleVad.onActiveChanged = {
            useVad = it
            renderInactiveRows()
        }
        b.speakeridPerfToggleNoise.onActiveChanged = {
            useNoise = it
            renderDisabledStates()
            renderInactiveRows()
        }
        b.speakeridPerfChoiceNoise.options = listOf(
            getString(R.string.speakerid_perf_noise_opt_spectral) to "spectral_subtract",
            getString(R.string.speakerid_perf_noise_opt_noisereduce) to "noisereduce",
        )
        b.speakeridPerfChoiceNoise.onValueChanged = { noiseStrategy = it }
        b.speakeridPerfTogglePyannote.onActiveChanged = {
            usePyannote = it
            renderDisabledStates()
            renderInactiveRows()
        }

        // ── Pipeline-Parameter ───────────────────────────────────────────────
        b.speakeridPerfBtnDefaults.setOnClickListener { resetToAppDefaults() }

        b.speakeridPerfChoiceCentroid.options = listOf(
            getString(R.string.speakerid_perf_centroid_static) to "static",
            getString(R.string.speakerid_perf_centroid_mean) to "mean",
            getString(R.string.speakerid_perf_centroid_moving_average) to "moving_average",
            getString(R.string.speakerid_perf_centroid_ema) to "ema",
            getString(R.string.speakerid_perf_centroid_median) to "median",
            getString(R.string.speakerid_perf_centroid_medoid) to "medoid",
        )
        b.speakeridPerfChoiceCentroid.onValueChanged = { centroidStrategy = it }

        b.speakeridPerfChoiceAssignment.options = listOf(
            getString(R.string.speakerid_perf_assignment_first) to "first_match",
            getString(R.string.speakerid_perf_assignment_last) to "last_match",
            getString(R.string.speakerid_perf_assignment_best) to "best_overall",
            getString(R.string.speakerid_perf_assignment_target) to "target_priority",
            getString(R.string.speakerid_perf_assignment_knn) to "knn_temporal",
        )
        b.speakeridPerfChoiceAssignment.onValueChanged = { assignmentStrategy = it }

        b.speakeridPerfChoiceClustering.options = listOf(
            getString(R.string.speakerid_perf_clustering_clique) to "clique",
            getString(R.string.speakerid_perf_clustering_star) to "star",
            getString(R.string.speakerid_perf_clustering_temporal) to "temporal",
        )
        b.speakeridPerfChoiceClustering.onValueChanged = { clusteringStrategy = it }

        b.speakeridPerfSliderThresholdTarget.onValueChanged = { thresholdTarget = it }
        b.speakeridPerfSliderThresholdNormal.onValueChanged = { thresholdNormal = it }
        b.speakeridPerfSliderEmaAlpha.onValueChanged = { emaAlpha = it }
        b.speakeridPerfSliderMaWindow.onValueChanged = { movingAverageWindow = it }
        b.speakeridPerfSliderMinSamples.onValueChanged = { minSamplesNewSpeaker = it }
        b.speakeridPerfSliderClusteringInterval.onValueChanged = { clusteringInterval = it }
        b.speakeridPerfSliderCleanerMinDuration.onValueChanged = { cleanerMinDuration = it }
        b.speakeridPerfSliderCleanerMinIsland.onValueChanged = { cleanerMinIslandDuration = it }
        b.speakeridPerfSliderSimSpeakers.onValueChanged = { simSpeakers = it }
        b.speakeridPerfSliderSimPoints.onValueChanged = { simSpeakerPoints = it }
        b.speakeridPerfSliderPoolPrefill.onValueChanged = { poolPrefill = it }

        // ── Steuerung ────────────────────────────────────────────────────────
        b.speakeridPerfBtnStart.setOnClickListener {
            if (isRunning) stopBenchmark() else startBenchmark()
        }

        // ── Quittungs-Zeilen (label_text aus dem .kv) ────────────────────────
        b.speakeridPerfRowMono.labelText = getString(R.string.speakerid_perf_row_mono)
        b.speakeridPerfRowNormalize.labelText = getString(R.string.speakerid_perf_row_normalize)
        b.speakeridPerfRowResample.labelText = getString(R.string.speakerid_perf_row_resample)
        b.speakeridPerfRowVad.labelText = getString(R.string.speakerid_perf_row_vad)
        b.speakeridPerfRowEmbedding.labelText = getString(R.string.speakerid_perf_row_redimnet)
        b.speakeridPerfRowAssignment.labelText = getString(R.string.speakerid_perf_row_assignment)
        b.speakeridPerfRowCentroid.labelText = getString(R.string.speakerid_perf_row_centroid)
        b.speakeridPerfRowClustering.labelText = getString(R.string.speakerid_perf_row_clustering)
        b.speakeridPerfRowNoise.labelText = getString(R.string.speakerid_perf_row_noise)
        b.speakeridPerfRowPyannote.labelText = getString(R.string.speakerid_perf_row_pyannote)

        // ── Chart (Farben wie das .kv: primary, primary@0.7, surface_dark) ──
        val primary = ContextCompat.getColor(requireContext(), R.color.speakerid_primary)
        b.speakeridPerfChart.lineColor = primary
        b.speakeridPerfChart.avgColor = Color.argb(
            (0.7f * 255 + 0.5f).toInt(), Color.red(primary), Color.green(primary), Color.blue(primary),
        )
        b.speakeridPerfChart.bgColor =
            ContextCompat.getColor(requireContext(), R.color.speakerid_surface_dark)

        b.speakeridPerfChoiceChart.options = listOf(
            getString(R.string.speakerid_perf_chart_total) to "wall",
            getString(R.string.speakerid_perf_chart_mono) to "mono_conversion",
            getString(R.string.speakerid_perf_chart_normalize) to "normalization",
            getString(R.string.speakerid_perf_chart_resample) to "resample",
            getString(R.string.speakerid_perf_chart_vad) to "vad",
            getString(R.string.speakerid_perf_chart_embedding) to "embedding",
            getString(R.string.speakerid_perf_chart_assignment) to "assignment",
            getString(R.string.speakerid_perf_chart_centroid) to "centroid_update",
            getString(R.string.speakerid_perf_chart_clustering) to "clustering",
            getString(R.string.speakerid_perf_chart_noise) to "noise",
            getString(R.string.speakerid_perf_chart_pyannote) to "pyannote",
        )
        b.speakeridPerfChoiceChart.onValueChanged = {
            chartMetric = it
            rebuildChartData() // on_chart_metric
            renderChart()
        }

        // Explorer-Rueckgabe ("performance"-Mode): gewaehlter WAV-Pfad
        val backStackEntry = findNavController().currentBackStackEntry
        backStackEntry?.savedStateHandle
            ?.getLiveData<String>(RESULT_KEY_SELECTED_FILE)
            ?.observe(viewLifecycleOwner) { path ->
                if (!path.isNullOrEmpty()) {
                    selectedFile = path
                    backStackEntry.savedStateHandle.remove<String>(RESULT_KEY_SELECTED_FILE)
                    renderFileLabel()
                }
            }

        if (statusText.isEmpty()) statusText = getString(R.string.speakerid_perf_status_ready)
        renderAll()
    }

    /** Port von `on_enter_screen` (Kivy `on_enter` feuert bei jedem Betreten). */
    override fun onResume() {
        super.onResume()
        onEnterScreen()
    }

    private fun onEnterScreen() {
        // DEFAULT_AUDIO_PATH-Pendant: Simulations-WAV aus dem DataManager
        val dm = SpeakerIdDataManager.getInstance(requireContext().applicationContext)
        if (selectedFile.isEmpty()) {
            val candidate = dm.currentAudioPath.value
            if (candidate.isNotEmpty() && File(candidate).exists()) {
                selectedFile = candidate
            }
        }

        // Defaults aus dem DataManager spiegeln (User kann sie hier ueberschreiben)
        try {
            centroidStrategy = dm.centroidUpdateStrategy.value.ifEmpty { "ema" }
            assignmentStrategy = dm.clusterAssignmentStrategy.value.ifEmpty { "best_overall" }
            thresholdTarget = dm.thresholdTarget.value
            thresholdNormal = dm.thresholdNormal.value
            emaAlpha = dm.emaAlpha.value
            movingAverageWindow = dm.movingAverageWindowSize.value.toDouble()
            useVad = dm.useSileroVad.value
            useNoise = dm.useNoiseReduction.value
            noiseStrategy = dm.noiseStrategy.value
            cleanerMinDuration = dm.cleanerMinDuration.value
            cleanerMinIslandDuration = dm.cleanerMinIslandDuration.value
        } catch (_: Exception) {
            // wie Python: still ignorieren
        }
        renderAll()
    }

    /** Port von `reset_to_app_defaults`. */
    private fun resetToAppDefaults() {
        onEnterScreen()
    }

    // ── Aktionen ─────────────────────────────────────────────────────────────

    /** Port von `start_benchmark`. */
    private fun startBenchmark() {
        if (isRunning) return
        if (selectedFile.isEmpty() || !File(selectedFile).exists()) {
            statusText = getString(R.string.speakerid_perf_status_no_file)
            Log.w(TAG, "[Analyse] Abbruch: Audiodatei fehlt oder Pfad ungueltig: '$selectedFile'")
            renderStatus()
            return
        }

        // WICHTIG: Live-Modus stoppen, falls er im Hintergrund laeuft!
        // Verhindert, dass 2 Threads gleichzeitig das ONNX KI-Modell ueberlasten.
        try {
            SpeakerLiveSession.stopIfRunning()
        } catch (e: Exception) {
            Log.e(TAG, "[Analyse] Fehler beim Stoppen des Live-Controllers: $e")
        }

        isRunning = true
        progress = 0.0
        chunksDone = 0
        chunksTotal = 0

        for (base in STAT_BASES) {
            avgMs[base] = 0.0
            maxMs[base] = 0.0
        }
        avgTotalMs = 0.0
        avgWallMs = 0.0
        maxWallMs = 0.0
        realtimeRatio = 0.0
        wallHistory = emptyList()
        chartData = emptyList()
        profilerHist = emptyList()
        statusText = getString(R.string.speakerid_perf_status_loading)
        renderAll()

        val appContext = requireContext().applicationContext
        workerThread = thread(name = "SpeakerPerfBenchmark", isDaemon = true) {
            runBenchmark(appContext)
        }
    }

    /** Port von `stop_benchmark`. */
    private fun stopBenchmark() {
        isRunning = false
    }

    // ── Worker (Port von `_run`) ─────────────────────────────────────────────

    @Suppress("LocalVariableName") // UPPER_CASE-Snapshots wie im Python-Worker
    private fun runBenchmark(appContext: Context) {
        try {
            // Snapshot der UI-Parameter (damit Live-Aenderungen den Run nicht stoeren)
            // (USE_NOISE/NOISE_STRATEGY entfallen: Denoise-Schritt nicht portiert)
            val USE_VAD = useVad
            val USE_PYANNOTE = usePyannote
            val CENTROID_STRATEGY = centroidStrategy
            val ASSIGNMENT_STRATEGY = assignmentStrategy
            val CLUSTERING_STRATEGY = clusteringStrategy
            val TH_TARGET = thresholdTarget
            val TH_NORMAL = thresholdNormal
            val EMA_ALPHA = emaAlpha
            val MA_WINDOW = movingAverageWindow.toInt()
            val MIN_SAMPLES = max(2, minSamplesNewSpeaker.toInt())
            val CLUSTERING_INTERVAL = max(1, clusteringInterval.toInt())
            val SIM_SPEAKERS = max(0, simSpeakers.toInt())
            val SIM_POINTS_PER = max(1, simSpeakerPoints.toInt())
            val POOL_PREFILL = max(0, poolPrefill.toInt())
            val CLEANER_MIN_DURATION = cleanerMinDuration
            // CLEANER_MIN_ISLAND (cleanerMinIslandDuration) wird wie im
            // Python-Worker snapshottet, aber nie verwendet (process_streaming
            // laeuft mit seinem Default) — bewusst nicht durchgereicht.
            val CHUNK_DURATION = chunkDuration

            profiler.clear()
            profiler.enable()

            // ONNX-Sessions (ersetzt die Python-Modul-Singletons)
            AssetModelInstaller.install(appContext)
            val prov = provider
                ?: OnnxSessionProvider(AssetModelInstaller.modelsDir(appContext)).also { provider = it }
            val vad = SileroVad(prov)
            val embedder = EmbeddingExtractor(prov)

            // ── Hilfsfunktion: zufaelliger normalisierter Embedding-Vektor ──
            val random = Random()
            fun randomEmbedding(dim: Int = 192): DoubleArray {
                val v = DoubleArray(dim) { random.nextGaussian() }
                var sq = 0.0
                for (x in v) sq += x * x
                val norm = sqrt(sq) + 1e-9
                for (i in v.indices) v[i] /= norm
                return v
            }

            // ── Cleaner-Instanz (falls aktiviert) ───────────────────────────
            val overlapCleaner = if (USE_PYANNOTE) {
                postStatus(appContext.getString(R.string.speakerid_perf_status_pyannote_init))
                SpeakerOverlapCleaner(prov).also { it.resetStream() }
            } else {
                null
            }

            // Noise Reducer: denoise.py wurde in der Kotlin-library nicht
            // portiert — der "noise"-Schritt entfaellt (Zeile bleibt 0.00/inaktiv).

            // ── Cluster-State: echte Cluster-Objekte wie im DataManager ─────
            val clusters = LinkedHashMap<String, Cluster>()
            clusters["0"] = Cluster("0")
            clusters["1"] = Cluster("1")

            // Simulierte Zusatz-Sprecher mit zufaelligem Centroid
            for (k in 2 until SIM_SPEAKERS + 2) {
                val c = Cluster(k.toString())
                repeat(SIM_POINTS_PER) {
                    c.addEmbedding(randomEmbedding(), updateStrategy = "mean")
                }
                clusters[k.toString()] = c
            }

            val pendingPool = ArrayList<ClusterEmbedding>()

            // ── Audio laden: roh, ohne Preprocessing (Mikrofon-Simulation) ──
            val raw = WavReader.read(File(selectedFile))
            val sr = raw.sampleRate
            val nSamples = raw.numFrames
            val samplesPerChunk = max(1, (CHUNK_DURATION * sr).toInt())
            val total = nSamples / samplesPerChunk

            if (total == 0) {
                postFinish(appContext.getString(R.string.speakerid_perf_status_too_short))
                return
            }

            /** Roh-Slice [start, start+len) pro Kanal (Pendant zu `raw[:, a:b]`). */
            fun sliceChannels(startSample: Int): List<FloatArray> {
                val end = minOf(startSample + samplesPerChunk, nSamples)
                return raw.channels.map { it.copyOfRange(startSample, end) }
            }

            fun monoOf(slices: List<FloatArray>): FloatArray {
                if (slices.size == 1) return slices[0]
                val n = slices[0].size
                val out = FloatArray(n)
                val ch = slices.size.toFloat()
                for (i in 0 until n) {
                    var acc = 0f
                    for (c in slices) acc += c[i]
                    out[i] = acc / ch
                }
                return out
            }

            fun normalizeInPlace(seg: FloatArray) {
                var maxVal = 0f
                for (s in seg) {
                    val a = kotlin.math.abs(s)
                    if (a > maxVal) maxVal = a
                }
                if (maxVal > 0f) {
                    for (i in seg.indices) seg[i] /= maxVal
                }
            }

            /** `_make_chunk_tensor`: roher Chunk -> normalisiertes mono @16kHz. */
            fun makeChunkTensor(idx: Int): FloatArray {
                var seg = monoOf(sliceChannels(idx * samplesPerChunk))
                normalizeInPlace(seg)
                if (sr != 16000) seg = Resampler.resample(seg, sr, 16000)
                return seg
            }

            // ── WARM-UP: ONNX-Sessions vollstaendig stabilisieren ───────────
            var warmupVec = DoubleArray(0)
            for (wu in 0 until WARMUP_RUNS) {
                postStatus(
                    appContext.getString(
                        R.string.speakerid_perf_status_warmup, wu + 1, WARMUP_RUNS,
                    ),
                )
                val warmup = makeChunkTensor(minOf(wu, total - 1))
                if (USE_VAD) vad.getSpeechDuration(warmup)
                if (USE_PYANNOTE && overlapCleaner != null) {
                    overlapCleaner.processStreaming(warmup, minDuration = CLEANER_MIN_DURATION)
                }
                warmupVec = embedder.extractEmbedding(warmup)
                SpeakerMatcher.findClusterForEmbedding(
                    newEmbedding = warmupVec,
                    clustersDict = clusters,
                    strategyName = ASSIGNMENT_STRATEGY,
                    targetThreshold = TH_TARGET,
                    normalThreshold = TH_NORMAL,
                )
            }
            clusters.getValue("1").addEmbedding(warmupVec, updateStrategy = "mean")

            repeat(POOL_PREFILL) {
                pendingPool.add(ClusterEmbedding(randomEmbedding(), time = 1.0))
            }
            profiler.clear()

            postTotal(total, appContext)

            // ── Per-Chunk-Loop (Mikrofon-Simulation) ────────────────────────
            val wallHist = ArrayList<Double>()

            for (i in 0 until total) {
                if (!isRunning) break

                val tIter = System.nanoTime()
                profiler.startChunk()

                // Schritt 1: Mono-Konvertierung + Normalisierung (pro Chunk)
                val slices = sliceChannels(i * samplesPerChunk)
                var seg: FloatArray = slices[0]
                profiler.measure("mono_conversion") {
                    seg = monoOf(slices)
                }
                profiler.measure("normalization") {
                    normalizeInPlace(seg)
                }

                // Schritt 2: Resampling (pro Chunk, falls Mikrofon != 16kHz)
                if (sr != 16000) {
                    profiler.measure("resample") {
                        seg = Resampler.resample(seg, sr, 16000)
                    }
                }

                val audioArr = seg

                // --- Noise Reducer: nicht portiert (siehe Klassen-KDoc) ---

                // --- Overlap-Filter (Pyannote) ---
                val cleanSegments = ArrayList<Pair<FloatArray, Double>>() // (audio, duration)
                if (USE_PYANNOTE && overlapCleaner != null) {
                    profiler.measure("pyannote") {
                        val result = overlapCleaner.processStreaming(
                            audioArr, minDuration = CLEANER_MIN_DURATION,
                        )
                        for (s in result.segments) cleanSegments.add(s.audio to s.duration)
                    }
                } else {
                    // Cleaner aus: ganzer Chunk als ein sauberes Segment
                    cleanSegments.add(audioArr to CHUNK_DURATION)
                }

                // --- Verarbeitung der sauberen Segmente ---
                for ((segAudio, _) in cleanSegments) {
                    if (segAudio.isEmpty()) continue

                    if (USE_VAD) {
                        profiler.measure("vad") {
                            vad.getSpeechDuration(segAudio)
                        }
                    }

                    var vec = DoubleArray(0)
                    profiler.measure("embedding") {
                        vec = embedder.extractEmbedding(segAudio)
                    }

                    // ── Sprecher-Zuordnung (Assignment) ─────────────────────
                    var matchedId = "0"
                    profiler.measure("assignment") {
                        matchedId = SpeakerMatcher.findClusterForEmbedding(
                            newEmbedding = vec,
                            clustersDict = clusters,
                            strategyName = ASSIGNMENT_STRATEGY,
                            targetThreshold = TH_TARGET,
                            normalThreshold = TH_NORMAL,
                        )
                    }

                    // ── Centroid-Update bzw. Pending-Pool fuellen ───────────
                    if (matchedId != "0") {
                        profiler.measure("centroid_update") {
                            clusters.getValue(matchedId).addEmbedding(
                                vec,
                                updateStrategy = CENTROID_STRATEGY,
                                alpha = EMA_ALPHA,
                                windowSize = MA_WINDOW,
                            )
                        }
                    } else {
                        pendingPool.add(ClusterEmbedding(vec, time = 1.0))
                    }
                }

                // ── Clustering (periodisch, ausserhalb der Segment-Schleife) ─
                if ((i + 1) % CLUSTERING_INTERVAL == 0 && pendingPool.size >= MIN_SAMPLES) {
                    var newPoints: List<ClusterEmbedding> = emptyList()
                    profiler.measure("clustering") {
                        newPoints = SpeakerExtraction.extractNewClusterFromPool(
                            poolEmbeddings = pendingPool,
                            threshold = TH_NORMAL,
                            minSamples = MIN_SAMPLES,
                            strategy = CLUSTERING_STRATEGY,
                        )
                    }
                    if (newPoints.isNotEmpty()) {
                        val maxExisting = clusters.keys.mapNotNull { it.toIntOrNull() }
                            .filter { it > 0 }.maxOrNull()
                        val newId = if (maxExisting != null) (maxExisting + 1).toString() else "2"
                        val newCluster = Cluster(newId)
                        for (p in newPoints) {
                            pendingPool.remove(p)
                            newCluster.addEmbedding(p.embedding, updateStrategy = "mean")
                        }
                        clusters[newId] = newCluster
                    }
                }

                profiler.endChunk()
                wallHist.add((System.nanoTime() - tIter) / 1e6)

                if ((i + 1) % 5 == 0 || (i + 1) == total) {
                    val done = i + 1
                    val avgW = wallHist.sum() / wallHist.size
                    val maxW = wallHist.maxOrNull() ?: 0.0
                    val histSnap = ArrayList(wallHist)
                    mainHandler.post { updateStats(done, avgW, maxW, histSnap) }
                }
            }

            postFinish(appContext.getString(R.string.speakerid_perf_status_done))
        } catch (e: Exception) {
            Log.e(TAG, "[BENCHMARK ERROR] ${e.javaClass.simpleName}: ${e.message}", e)
            postFinish(
                appContext.getString(
                    R.string.speakerid_perf_status_error, e.message ?: e.toString(),
                ),
            )
        } finally {
            profiler.disable()
            if (shutdownRequested) {
                provider?.close()
                provider = null
            }
        }
    }

    // ── UI-Updates (Main Thread, Pendant zu Clock.schedule_once) ─────────────

    private fun postStatus(text: String) {
        mainHandler.post {
            statusText = text
            renderStatus()
        }
    }

    /** Port von `_set_total` (nutzt wie Python das LIVE-`chunk_duration`). */
    private fun postTotal(total: Int, appContext: Context) {
        mainHandler.post {
            chunksTotal = total
            statusText = appContext.getString(
                R.string.speakerid_perf_status_processing,
                total,
                String.format(Locale.US, "%.2f", chunkDuration),
            )
            renderStatus()
            renderProgress()
        }
    }

    /** Port von `_finish`. */
    private fun postFinish(msg: String) {
        mainHandler.post {
            isRunning = false
            statusText = msg
            if (chunksDone > 0) {
                updateStats(chunksDone, avgWallMs, maxWallMs, null)
            }
            renderStatus()
            renderStartButton()
        }
    }

    /** Port von `_update_stats` (laeuft auf dem Main-Thread). */
    private fun updateStats(done: Int, avgWall: Double, maxWall: Double, hist: List<Double>?) {
        chunksDone = done
        progress = done.toDouble() / max(1, chunksTotal)

        // Nur Chunks mit vollem Pipeline-Durchlauf (= Speech-Chunks mit
        // Embedding) auswerten, damit die Summen-Mathematik stimmt.
        val histFull = profiler.history().filter { it.containsKey("embedding") }
        val nFull = histFull.size

        fun avgOf(key: String): Double =
            if (nFull == 0) 0.0 else histFull.sumOf { it[key] ?: 0.0 } / nFull

        fun maxValOf(key: String): Double =
            if (nFull == 0) 0.0 else histFull.maxOf { it[key] ?: 0.0 }

        for ((uiBase, key) in STAT_MAPPING) {
            avgMs[uiBase] = avgOf(key)
            maxMs[uiBase] = maxValOf(key)
        }

        avgTotalMs = avgOf(SpeakerPerformanceChunkProfiler.TOTAL_KEY)
        if (avgWall > 0) {
            avgWallMs = avgWall
            maxWallMs = maxWall
        }
        if (!hist.isNullOrEmpty()) {
            wallHistory = hist
        }
        profilerHist = profiler.history()
        rebuildChartData()
        val chunkMs = chunkDuration * 1000.0
        realtimeRatio = if (chunkMs > 0 && avgWallMs > 0) avgWallMs / chunkMs else 0.0

        renderStats()
        renderProgress()
        renderStartButton()
    }

    /** Port von `_rebuild_chart_data`. */
    private fun rebuildChartData() {
        chartData = if (chartMetric == "wall") {
            wallHistory
        } else {
            profilerHist.filter { it.containsKey(chartMetric) }.map { it.getValue(chartMetric) }
        }
    }

    // ── Datei-Wahl (Port von `open_explorer_picker`) ─────────────────────────

    /** Oeffnet den projektweiten Explorer im "performance"-Mode. */
    private fun openExplorerPicker() {
        findNavController().navigate(
            R.id.speakeridExplorerFragment,
            bundleOf(
                ARG_KEY_SELECTION_MODE to SpeakerExplorerFragment.MODE_PERFORMANCE,
                ARG_KEY_PREVIOUS_SCREEN to "performance_test",
            ),
        )
    }

    // ── Rendering (Kivy-Property-Bindings -> Views) ──────────────────────────

    private fun renderAll() {
        val b = _binding ?: return
        b.speakeridPerfSliderChunk.value = chunkDuration
        b.speakeridPerfToggleVad.isActive = useVad
        b.speakeridPerfToggleNoise.isActive = useNoise
        b.speakeridPerfChoiceNoise.value = noiseStrategy
        b.speakeridPerfTogglePyannote.isActive = usePyannote
        b.speakeridPerfChoiceCentroid.value = centroidStrategy
        b.speakeridPerfChoiceAssignment.value = assignmentStrategy
        b.speakeridPerfChoiceClustering.value = clusteringStrategy
        b.speakeridPerfSliderThresholdTarget.value = thresholdTarget
        b.speakeridPerfSliderThresholdNormal.value = thresholdNormal
        b.speakeridPerfSliderEmaAlpha.value = emaAlpha
        b.speakeridPerfSliderMaWindow.value = movingAverageWindow
        b.speakeridPerfSliderMinSamples.value = minSamplesNewSpeaker
        b.speakeridPerfSliderClusteringInterval.value = clusteringInterval
        b.speakeridPerfSliderCleanerMinDuration.value = cleanerMinDuration
        b.speakeridPerfSliderCleanerMinIsland.value = cleanerMinIslandDuration
        b.speakeridPerfSliderSimSpeakers.value = simSpeakers
        b.speakeridPerfSliderSimPoints.value = simSpeakerPoints
        b.speakeridPerfSliderPoolPrefill.value = poolPrefill
        b.speakeridPerfChoiceChart.value = chartMetric

        renderFileLabel()
        renderDisabledStates()
        renderStatus()
        renderProgress()
        renderStartButton()
        renderStats()
    }

    /** `os.path.basename(root.selected_file) if root.selected_file else "(keine Datei)"` */
    private fun renderFileLabel() {
        val b = _binding ?: return
        b.speakeridPerfLabelFile.text =
            if (selectedFile.isEmpty()) {
                getString(R.string.speakerid_perf_no_file)
            } else {
                File(selectedFile).name
            }
    }

    /** `disabled: not root.use_noise` / `disabled: not root.use_pyannote` */
    private fun renderDisabledStates() {
        val b = _binding ?: return
        b.speakeridPerfFrameNoiseChoice.blocked = !useNoise
        b.speakeridPerfFrameCleanerMinDuration.blocked = !usePyannote
        b.speakeridPerfFrameCleanerMinIsland.blocked = !usePyannote
    }

    private fun renderStatus() {
        val b = _binding ?: return
        b.speakeridPerfLabelStatus.text = statusText
    }

    private fun renderProgress() {
        val b = _binding ?: return
        b.speakeridPerfProgress.progress = (progress * 1000).toInt().coerceIn(0, 1000)
        b.speakeridPerfLabelChunks.text =
            String.format(Locale.US, "%d/%d", chunksDone, chunksTotal)
    }

    /** `text: "Stoppen" if root.is_running else "Benchmark starten"` + Farbe. */
    private fun renderStartButton() {
        val b = _binding ?: return
        if (isRunning) {
            b.speakeridPerfBtnStart.setText(R.string.speakerid_perf_btn_stop)
            b.speakeridPerfBtnStart.setBackgroundResource(R.drawable.speakerid_performance_btn_stop)
        } else {
            b.speakeridPerfBtnStart.setText(R.string.speakerid_perf_btn_start)
            b.speakeridPerfBtnStart.setBackgroundResource(R.drawable.speakerid_performance_btn_start)
        }
    }

    private fun renderInactiveRows() {
        val b = _binding ?: return
        b.speakeridPerfRowResample.isInactive = (avgMs["resample"] ?: 0.0) == 0.0
        b.speakeridPerfRowVad.isInactive = !useVad
        b.speakeridPerfRowNoise.isInactive = !useNoise
        b.speakeridPerfRowPyannote.isInactive = !usePyannote
    }

    private fun renderStats() {
        val b = _binding ?: return

        fun setRow(row: SpeakerPerformanceReceiptRowView, base: String) {
            row.valueMs = avgMs[base] ?: 0.0
            row.maxMs = maxMs[base] ?: 0.0
        }
        setRow(b.speakeridPerfRowMono, "mono")
        setRow(b.speakeridPerfRowNormalize, "normalize")
        setRow(b.speakeridPerfRowResample, "resample")
        setRow(b.speakeridPerfRowVad, "vad")
        setRow(b.speakeridPerfRowEmbedding, "embedding")
        setRow(b.speakeridPerfRowAssignment, "assignment")
        setRow(b.speakeridPerfRowCentroid, "centroid")
        setRow(b.speakeridPerfRowClustering, "clustering")
        setRow(b.speakeridPerfRowNoise, "noise")
        setRow(b.speakeridPerfRowPyannote, "pyannote")
        renderInactiveRows()

        b.speakeridPerfSumPipeline.text = String.format(Locale.US, "%.3f ms", avgTotalMs)
        b.speakeridPerfSumWallAvg.text = String.format(Locale.US, "%.3f ms", avgWallMs)
        b.speakeridPerfSumWallMax.text = String.format(Locale.US, "%.3f ms", maxWallMs)
        b.speakeridPerfSumRatio.text = String.format(Locale.US, "%.2f×", realtimeRatio)
        // [0.2,0.7,0.3] gruen | [0.85,0.3,0.3] rot | text_secondary
        b.speakeridPerfSumRatio.setTextColor(
            when {
                realtimeRatio > 0.0 && realtimeRatio < 1.0 -> Color.rgb(51, 179, 77)
                realtimeRatio >= 1.0 -> Color.rgb(217, 77, 77)
                else -> ContextCompat.getColor(
                    requireContext(), R.color.speakerid_text_secondary,
                )
            },
        )

        renderChart()
    }

    private fun renderChart() {
        val b = _binding ?: return
        b.speakeridPerfChart.thresholdMs = chunkDuration * 1000.0
        b.speakeridPerfChart.showThreshold = chartMetric == "wall"
        b.speakeridPerfChart.data = chartData
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        // Kivy-Screens persistieren — das Fragment nicht: beim endgueltigen
        // Zerstoeren Benchmark abbrechen und die ONNX-Sessions freigeben.
        isRunning = false
        shutdownRequested = true
        if (workerThread?.isAlive != true) {
            provider?.close()
            provider = null
        }
    }

    companion object {
        private const val TAG = "SpeakerPerfTest"

        /** `WARMUP_RUNS = 3` aus `_run`. */
        private const val WARMUP_RUNS = 3

        /** Basen der avg_/max_-Properties (`reset_props` in `start_benchmark`). */
        private val STAT_BASES = listOf(
            "mono", "normalize", "resample", "vad",
            "embedding", "assignment", "centroid", "clustering",
            "noise", "pyannote",
        )

        /** (UI-Property-Name, Profiler-Key) — `mapping` aus `_update_stats`. */
        private val STAT_MAPPING = listOf(
            "mono" to "mono_conversion",
            "normalize" to "normalization",
            "resample" to "resample",
            "vad" to "vad",
            "embedding" to "embedding",
            "assignment" to "assignment",
            "centroid" to "centroid_update",
            "clustering" to "clustering",
            "noise" to "noise",
            "pyannote" to "pyannote",
        )

        /**
         * Nav-Argumente fuer den Explorer ("performance"-Mode) und der
         * SavedStateHandle-Key, unter dem der Explorer den gewaehlten
         * WAV-Pfad an den vorherigen Back-Stack-Eintrag zurueckgibt —
         * Schluessel kommen vom Explorer selbst (ein Vertrag, eine Quelle).
         */
        const val ARG_KEY_SELECTION_MODE = SpeakerExplorerFragment.ARG_SELECTION_MODE
        const val ARG_KEY_PREVIOUS_SCREEN = "speakerid_explorer_previous_screen"
        const val RESULT_KEY_SELECTED_FILE = SpeakerExplorerFragment.RESULT_SELECTED_FILE
    }
}
