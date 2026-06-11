package com.fzi.speakerid.ui.explorer

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.fzi.acousticscene.R
import com.fzi.acousticscene.databinding.FragmentSpeakeridExplorerBinding
import com.fzi.speakerid.ui.AssetModelInstaller
import com.fzi.speakerid.ui.SpeakerIdDataManager
import com.fzi.speakerid.ui.targetsetup.TargetCentroidGenerator
import com.fzi.speakerid.ui.widgets.SpeakerIdExpertTabBar
import java.io.File
import java.util.Locale

/**
 * Port von siqas `gui/screens/explorer/explorer.py` (+ .kv) — der projektweite
 * Datei-Browser fuer Analyse-Audio, Zielsprecher-Dateien, Performance-Test-
 * und Diarisations-Auswahl.
 *
 * Kivy -> Android:
 *  - `on_pre_enter` -> [onResume]: Audio-Ordner anlegen, Sandbox/Startpfad je
 *    `selection_mode` setzen, Auswahl/Korb leeren.
 *  - FileChooserListView (rootpath/path/multiselect/filters) ->
 *    RecyclerView + [SpeakerExplorerFileAdapter]; "../"-Eintrag, Ordner
 *    oeffnen per Tap, Dateien selektieren (Target-Modus = Mehrfachauswahl),
 *    Filter `*.wav`/`*.mp3`.
 *  - Pfad-Mapping (Android-Realitaet, app-interne Verzeichnisse direkt
 *    gelistet wie im Original):
 *      PROJECT_ROOT -> `filesDir`,
 *      ASSETS_ROOT  -> `filesDir/speakerid`,
 *      AUDIO_DIR    -> `filesDir/speakerid/audio`,
 *      "Auf-\nnahmen" -> `filesDir/target_recordings` (dort speichert der
 *      TargetRecorderFragment-Port wirklich; im Original
 *      `assets/audio/target_recordings`) — als zusaetzlich erlaubte Wurzel
 *      neben der Sandbox.
 *  - Fuer Dateien ausserhalb der App-Sandbox: SAF-Picker (Muster aus
 *    TargetSetupFragment), Kopie nach `cacheDir/speakerid_explorer_import`.
 *  - `selection_mode`/`previous_screen_name` setzte der aufrufende Screen ->
 *    Fragment-Argument [ARG_SELECTION_MODE]; Rueckgabe der gewaehlten Datei
 *    an performance_test/diarization_report ueber das
 *    savedStateHandle-Ergebnis [RESULT_SELECTED_FILE] + popBackStack
 *    (Pendant zu `perf.selected_file = files[0]`).
 *  - Experten-Tab-Leiste (main.kv, sichtbar bei `app.expert_lab_active` ->
 *    `dm.expertModeActive`) liegt wie bei Dashboard/Diarization-Report oben
 *    im Screen, aktiver Tab "Datei-Auswahl".
 *  - Hintergrund-Analyse (`generate_target_centroid_from_files` + Clock) ->
 *    Thread + Handler wie im TargetSetupFragment.
 */
class SpeakerExplorerFragment : Fragment() {

    private var _binding: FragmentSpeakeridExplorerBinding? = null
    private val binding get() = _binding!!

    private lateinit var dm: SpeakerIdDataManager
    private lateinit var adapter: SpeakerExplorerFileAdapter
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Kivy-Properties ──────────────────────────────────────────────────────
    private var selectionMode = MODE_ANALYSIS
    private var currentPath: File? = null
    private lateinit var sandboxPath: File
    private var hasSelection = false
    private val selection = mutableListOf<File>()   // filechooser.selection
    private val basket = mutableListOf<File>()      // ordneruebergreifender Korb
    private var basketText = ""

    // ── Pfade (PROJECT_ROOT/ASSETS_ROOT/AUDIO_DIR-Pendants) ─────────────────
    private lateinit var projectRoot: File
    private lateinit var assetsRoot: File
    private lateinit var audioDir: File
    private lateinit var recordingsDir: File

    /** SAF-Zugriff auf externe Dateien (Muster aus TargetSetupFragment). */
    private val externPicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (!uris.isNullOrEmpty()) onExternPicked(uris) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSpeakeridExplorerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dm = SpeakerIdDataManager.getInstance(requireContext())

        // `explorer.selection_mode = ...` des aufrufenden Screens
        selectionMode = arguments?.getString(ARG_SELECTION_MODE) ?: MODE_ANALYSIS

        val filesDir = requireContext().filesDir
        projectRoot = filesDir
        assetsRoot = File(filesDir, "speakerid")
        audioDir = File(assetsRoot, "audio")
        recordingsDir = File(filesDir, "target_recordings")
        sandboxPath = assetsRoot

        basketText = getString(R.string.speakerid_explorer_basket_empty_initial)

        // ── Dateiliste ───────────────────────────────────────────────────────
        adapter = SpeakerExplorerFileAdapter { entry -> onEntryTapped(entry) }
        binding.speakeridExplorerFileList.layoutManager = LinearLayoutManager(requireContext())
        binding.speakeridExplorerFileList.adapter = adapter

        // ── Top-Bar ─────────────────────────────────────────────────────────
        binding.speakeridExplorerBackButton.setOnClickListener { goBack() }
        binding.speakeridExplorerConfirmButton.setOnClickListener { confirmSelection() }
        binding.speakeridExplorerClearButton.setOnClickListener { clearSelection() }

        // ── Auswahl-Korb ────────────────────────────────────────────────────
        binding.speakeridExplorerBasketAddButton.setOnClickListener { addToBasket() }
        binding.speakeridExplorerBasketDeleteButton.setOnClickListener { clearBasket() }

        // ── Schnellzugriffe (SidebarNavButton on_release: root.set_path) ────
        binding.speakeridExplorerNavAudio.setOnClickListener {
            setPath(File(sandboxPath, "audio"))
        }
        binding.speakeridExplorerNavExtern.setOnClickListener { openExternPicker() }
        binding.speakeridExplorerNavAssets.setOnClickListener { setPath(sandboxPath) }
        binding.speakeridExplorerNavRecordings.setOnClickListener { setPath(recordingsDir) }
        binding.speakeridExplorerNavData.setOnClickListener {
            setPath(File(sandboxPath, "data"))
        }
        binding.speakeridExplorerNavMsdwild.setOnClickListener {
            setPath(File(projectRoot, "data/msdwild/wav"))
        }
        binding.speakeridExplorerNavVoxconverse.setOnClickListener {
            setPath(File(projectRoot, "data/voxconverse/wav"))
        }
        binding.speakeridExplorerNavTruth.setOnClickListener {
            setPath(File(projectRoot, "data/msdwild/rttm/label_truth"))
        }

        // ── Experten-Tab-Leiste (main.kv tab_bar_container) ─────────────────
        binding.speakeridExplorerTabBar.apply {
            // kv: state 'down' wenn screen_manager.current == 'explorer'
            setActiveTab(SpeakerIdExpertTabBar.Tab.EXPLORER)
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
                    SpeakerIdExpertTabBar.Tab.PIPELINE ->
                        nav.navigate(R.id.speakeridPipelineFragment)
                    SpeakerIdExpertTabBar.Tab.EMBEDDINGS ->
                        nav.navigate(R.id.speakeridEmbeddingsFragment)
                    SpeakerIdExpertTabBar.Tab.PERFORMANCE ->
                        nav.navigate(R.id.speakeridPerformanceTestFragment)
                    SpeakerIdExpertTabBar.Tab.SETTINGS ->
                        nav.navigate(R.id.speakeridExpertSettingsFragment)
                    SpeakerIdExpertTabBar.Tab.EXPLORER -> Unit
                }
            }
            onClose = {
                // kv X-Button: app.expert_lab_active = False;
                //              screen_manager.current = 'main_menu'
                dm.expertModeActive.value = false
                findNavController().popBackStack(R.id.speakeridMenuFragment, false)
            }
        }

        // ── Modus-Wahl (SegmentedChoice "Auswahl für:") ──────────────────────
        binding.speakeridExplorerModeChoice.apply {
            options = listOf(
                getString(R.string.speakerid_explorer_mode_analysis) to MODE_ANALYSIS,
                getString(R.string.speakerid_explorer_mode_target) to MODE_TARGET,
            )
            // kv: on_value: root.selection_mode = self.value
            onValueChanged = { newMode -> onSelectionModeChanged(newMode) }
            value = selectionMode
        }
    }

    // ── Lifecycle: on_pre_enter ──────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        // os.makedirs(AUDIO_DIR, exist_ok=True)
        audioDir.mkdirs()

        val startPath: File
        if (selectionMode == MODE_DIARIZATION) {
            sandboxPath = projectRoot
            // Direkt in den wav/-Ordner springen statt data/ — vermeidet,
            // dass der User versehentlich label_truth/ mit 3000 RTTMs oeffnet.
            val wavDefault = File(projectRoot, "data/msdwild/wav")
            startPath = if (wavDefault.isDirectory) wavDefault else File(projectRoot, "data")
        } else {
            sandboxPath = assetsRoot
            startPath = audioDir
        }

        currentPath = startPath
        setStatus(startPath.absolutePath)
        binding.speakeridExplorerSelectionLabel.text =
            getString(R.string.speakerid_explorer_no_selection)
        selection.clear()
        basket.clear()
        basketText = getString(R.string.speakerid_explorer_basket_empty_initial)

        // Tab-Leiste nur bei expert_lab_active (main.kv)
        binding.speakeridExplorerTabBar.visibility =
            if (dm.expertModeActive.value) View.VISIBLE else View.GONE

        applyExpertVisibility()
        applyModeVisibility()
        applySelectionRow()
        applyBasketRow()
        updateConfirmButton()
        refreshFileList()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    // ── Navigation (go_up / set_path / go_back) ──────────────────────────────

    /** Port von `go_up`: einen Ordner nach oben — bleibt in der Sandbox. */
    private fun goUp() {
        val parent = currentPath?.parentFile ?: return
        if (isAllowed(parent) && parent.exists()) {
            currentPath = parent
            setStatus(parent.absolutePath)
            refreshFileList()
        }
    }

    /** Port von `set_path`: Pfad direkt setzen — bleibt in der Sandbox. */
    private fun setPath(path: File) {
        if (path.exists() && path.isDirectory && isAllowed(path)) {
            currentPath = path
            setStatus(path.absolutePath)
            refreshFileList()
        } else {
            setStatus(
                getString(
                    R.string.speakerid_explorer_status_path_unreachable,
                    path.absolutePath,
                ),
            )
        }
    }

    /**
     * Port von `go_back`: Auswahl leeren, dann modusabhaengig zurueck wie im
     * Original (performance -> performance_test, diarization ->
     * diarization_report, target -> target_setup, sonst Experten-Dashboard;
     * `expert_lab_active` ist im Port fuer MODE_ANALYSIS immer der Fall).
     */
    private fun goBack() {
        selection.clear()
        adapter.setSelected(emptyList())
        binding.speakeridExplorerSelectionLabel.text =
            getString(R.string.speakerid_explorer_no_selection)
        currentPath?.let { setStatus(it.absolutePath) }
        val target = when (selectionMode) {
            MODE_PERFORMANCE -> R.id.speakeridPerformanceTestFragment
            MODE_DIARIZATION -> R.id.diarizationReportFragment
            MODE_TARGET -> R.id.targetSetupFragment
            else -> R.id.speakerDashboardFragment
        }
        val nav = findNavController()
        if (!nav.popBackStack(target, false)) {
            // Ziel nicht im Backstack (Kivy wechselt einfach den Screen).
            nav.navigate(target)
        }
    }

    // ── Dateiliste (FileChooserListView-Ersatz) ──────────────────────────────

    private fun refreshFileList() {
        val dir = currentPath ?: return
        adapter.submit(buildEntries(dir), selection.map { it.absolutePath })
    }

    private fun buildEntries(dir: File): List<SpeakerExplorerEntry> {
        val entries = mutableListOf<SpeakerExplorerEntry>()

        // "../" wie in der FileChooserListView (nicht an der rootpath-Wurzel)
        val parent = dir.parentFile
        if (canon(dir) != canon(sandboxPath) &&
            parent != null && isAllowed(parent) && parent.exists()
        ) {
            entries.add(
                SpeakerExplorerEntry(
                    file = null,
                    isDir = true,
                    displayName = getString(R.string.speakerid_explorer_parent_dir),
                    sizeText = "",
                ),
            )
        }

        val children = dir.listFiles()?.toList().orEmpty()
        val dirs = children.filter { it.isDirectory }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
        // filters: ['*.wav', '*.mp3']
        val files = children.filter {
            it.isFile && (it.name.endsWith(".wav") || it.name.endsWith(".mp3"))
        }.sortedBy { it.name.lowercase(Locale.ROOT) }

        for (d in dirs) {
            entries.add(SpeakerExplorerEntry(d, isDir = true, displayName = d.name, sizeText = ""))
        }
        for (f in files) {
            entries.add(
                SpeakerExplorerEntry(f, isDir = false, displayName = f.name, sizeText = niceSize(f)),
            )
        }
        return entries
    }

    /** Tap-Logik der FileChooserListView (Ordner oeffnen / Datei waehlen). */
    private fun onEntryTapped(entry: SpeakerExplorerEntry) {
        val file = entry.file
        if (file == null) {
            goUp()
            return
        }
        if (entry.isDir) {
            setPath(file)
            return
        }
        if (selectionMode == MODE_TARGET) {
            // multiselect: True — Tap toggelt
            if (!selection.remove(file)) selection.add(file)
        } else {
            selection.clear()
            selection.add(file)
        }
        adapter.setSelected(selection.map { it.absolutePath })
        updateSelectionDisplay()
    }

    // ── Einzel-Auswahl Anzeige (update_selection_display / clear_selection) ──

    private fun updateSelectionDisplay() {
        val b = _binding ?: return
        b.speakeridExplorerSelectionLabel.text = when {
            selection.isEmpty() -> getString(R.string.speakerid_explorer_no_selection)
            selection.size == 1 -> getString(
                R.string.speakerid_explorer_selection_single, selection[0].name,
            )
            else -> getString(
                R.string.speakerid_explorer_selection_multi,
                selection.size,
                selection.joinToString(",  ") { it.name },
            )
        }
        if (selectionMode == MODE_ANALYSIS) {
            hasSelection = selection.isNotEmpty()
        }
        applySelectionRow()
        applyBasketRow()
        updateConfirmButton()
    }

    /** Port von `clear_selection`. */
    private fun clearSelection() {
        selection.clear()
        binding.speakeridExplorerSelectionLabel.text =
            getString(R.string.speakerid_explorer_no_selection)
        hasSelection = false
        adapter.setSelected(emptyList())
        applySelectionRow()
        applyBasketRow()
        updateConfirmButton()
    }

    // ── Auswahl-Korb (add_to_basket / clear_basket / _refresh_basket_text) ──

    private fun addToBasket() {
        for (f in selection) {
            if (f !in basket) basket.add(f)
        }
        refreshBasketText()
    }

    private fun clearBasket() {
        basket.clear()
        refreshBasketText()
    }

    private fun refreshBasketText() {
        if (basket.isEmpty()) {
            basketText = getString(R.string.speakerid_explorer_basket_empty)
            hasSelection = false
        } else {
            basketText = getString(
                R.string.speakerid_explorer_basket_files,
                basket.size,
                basket.joinToString(",   ") { it.name },
            )
            hasSelection = true
        }
        applySelectionRow()
        applyBasketRow()
        updateConfirmButton()
    }

    // ── Auswahl bestaetigen (confirm_selection) ──────────────────────────────

    private fun confirmSelection() {
        when (selectionMode) {
            MODE_TARGET -> {
                // Korb; Fallback auf die direkte FileChooser-Auswahl
                val files = if (basket.isNotEmpty()) basket.toList() else selection.toList()
                if (files.isEmpty()) return
                setStatus(getString(R.string.speakerid_explorer_status_analyzing, files.size))
                val appContext = requireContext().applicationContext
                Thread({
                    try {
                        // generate_target_centroid_from_files(files)
                        val modelsDir = AssetModelInstaller.install(appContext)
                        val vector = TargetCentroidGenerator.generateFromFiles(files, modelsDir)
                        mainHandler.post { onTargetSuccess(vector, files) }
                    } catch (e: Exception) {
                        val msg = e.message ?: e.toString()
                        mainHandler.post { onTargetError(msg) }
                    }
                }, "ExplorerTargetAnalyze").apply {
                    isDaemon = true
                    start()
                }
            }
            MODE_PERFORMANCE, MODE_DIARIZATION -> {
                // Original: perf/report.selected_file = files[0] + Screenwechsel
                // -> savedStateHandle-Ergebnis an den Aufrufer + zurueck.
                val files = selection.toList()
                if (files.isEmpty()) return
                val nav = findNavController()
                nav.previousBackStackEntry?.savedStateHandle
                    ?.set(RESULT_SELECTED_FILE, files[0].absolutePath)
                nav.popBackStack()
            }
            else -> {
                val files = selection.toList()
                if (files.isEmpty()) return
                dm.currentAudioPath.value = files[0].absolutePath
                setStatus(
                    getString(R.string.speakerid_explorer_status_analysis_set, files[0].name),
                )
            }
        }
    }

    /** Port von `_on_target_success`. */
    private fun onTargetSuccess(targetVector: DoubleArray, files: List<File>) {
        if (_binding == null) return
        try {
            // clusters["1"].centroid = v (is_target ist im Port implizit id=="1")
            dm.clusters["1"]?.centroid = targetVector
            dm.dispatchClusters()
            dm.targetAudioPath.value = files[0].absolutePath
            dm.saveTargetCache()
            setStatus(getString(R.string.speakerid_explorer_status_target_set, files[0].name))

            // Automatisch zurueck zum Target-Setup navigieren
            val nav = findNavController()
            if (!nav.popBackStack(R.id.targetSetupFragment, false)) {
                nav.navigate(R.id.targetSetupFragment)
            }
        } catch (e: Exception) {
            setStatus(
                getString(
                    R.string.speakerid_explorer_status_save_error,
                    e.message ?: e.toString(),
                ),
            )
        }
    }

    /** Port von `_on_target_error`. */
    private fun onTargetError(errorMsg: String) {
        if (_binding == null) return
        setStatus(getString(R.string.speakerid_explorer_status_error, errorMsg))
    }

    // ── SAF-Import (Android-Ergaenzung fuer externe Dateien) ─────────────────

    private fun openExternPicker() {
        try {
            externPicker.launch(arrayOf("audio/*"))
        } catch (e: Exception) {
            setStatus(
                getString(R.string.speakerid_explorer_status_error, e.message ?: e.toString()),
            )
        }
    }

    /** Kopiert die SAF-Auswahl in den Cache und uebernimmt sie als Selektion. */
    private fun onExternPicked(uris: List<Uri>) {
        val appContext = requireContext().applicationContext
        Thread({
            val copied = mutableListOf<File>()
            var error: String? = null
            try {
                val importDir = File(appContext.cacheDir, "speakerid_explorer_import")
                importDir.mkdirs()
                for ((index, uri) in uris.withIndex()) {
                    val name = uri.lastPathSegment
                        ?.substringAfterLast('/')
                        ?.substringAfterLast(':')
                        ?.takeIf { it.isNotBlank() }
                        ?: "extern_$index.wav"
                    val dest = File(importDir, name)
                    appContext.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Datei nicht lesbar" }
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    copied.add(dest)
                }
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
            mainHandler.post {
                if (_binding == null) return@post
                if (error != null) {
                    setStatus(getString(R.string.speakerid_explorer_status_error, error))
                    return@post
                }
                if (selectionMode == MODE_TARGET) {
                    for (f in copied) {
                        if (f !in selection) selection.add(f)
                    }
                } else {
                    selection.clear()
                    copied.firstOrNull()?.let { selection.add(it) }
                }
                adapter.setSelected(selection.map { it.absolutePath })
                updateSelectionDisplay()
            }
        }, "ExplorerImport").apply {
            isDaemon = true
            start()
        }
    }

    // ── kv-Bindings (Sichtbarkeiten / Farben / Texte) ────────────────────────

    /** `on_value: root.selection_mode = self.value` + abhaengige kv-Bindings. */
    private fun onSelectionModeChanged(newMode: String) {
        selectionMode = newMode
        if (_binding == null) return
        applyModeVisibility()
        applySelectionRow()
        applyBasketRow()
        updateConfirmButton()
    }

    /** Experten-Schnellzugriffe (`height: ... if app.expert_lab_active else 0`). */
    private fun applyExpertVisibility() {
        val expert = dm.expertModeActive.value
        val vis = if (expert) View.VISIBLE else View.GONE
        binding.speakeridExplorerNavAssets.visibility = vis
        binding.speakeridExplorerNavRecordings.visibility = vis
        binding.speakeridExplorerNavData.visibility = vis
        binding.speakeridExplorerCompareHeader.visibility = vis
        binding.speakeridExplorerNavMsdwild.visibility = vis
        binding.speakeridExplorerNavVoxconverse.visibility = vis
        binding.speakeridExplorerNavTruth.visibility = vis
    }

    private fun applyModeVisibility() {
        val expert = dm.expertModeActive.value
        // SegmentedChoice: collapsed wenn kein Expertenmodus oder "performance"
        binding.speakeridExplorerModeChoice.collapsed =
            !expert || selectionMode == MODE_PERFORMANCE
        // Auswahl-Korb nur im Target-Modus (kv Hoehe 0 + opacity 0 + disabled)
        binding.speakeridExplorerBasketRow.visibility =
            if (selectionMode == MODE_TARGET) View.VISIBLE else View.GONE
        // Zurueck-Button: unsichtbar wenn Experten-Labor + "analysis"
        binding.speakeridExplorerBackButton.visibility =
            if (expert && selectionMode == MODE_ANALYSIS) View.GONE else View.VISIBLE
    }

    /** Auswahl-Anzeige: bg primary@18%, Label primary + bold bei Auswahl. */
    private fun applySelectionRow() {
        val b = _binding ?: return
        val ctx = requireContext()
        val primary = ContextCompat.getColor(ctx, R.color.speakerid_primary)
        if (hasSelection) {
            // app.theme_manager.primary_color[:3] + [0.18]
            b.speakeridExplorerSelectionRow.setBackgroundColor(
                ColorUtils.setAlphaComponent(primary, 46),
            )
            b.speakeridExplorerSelectionLabel.setTextColor(primary)
            b.speakeridExplorerSelectionLabel.typeface =
                ResourcesCompat.getFont(ctx, R.font.speakerid_dejavu_sans_bold)
        } else {
            b.speakeridExplorerSelectionRow.setBackgroundColor(
                ContextCompat.getColor(ctx, R.color.speakerid_surface_dim),
            )
            b.speakeridExplorerSelectionLabel.setTextColor(
                ContextCompat.getColor(ctx, R.color.speakerid_text_secondary),
            )
            b.speakeridExplorerSelectionLabel.typeface =
                ResourcesCompat.getFont(ctx, R.font.speakerid_dejavu_sans)
        }
        // "✕": opacity 0 + disabled ohne Auswahl (Platz bleibt -> INVISIBLE)
        b.speakeridExplorerClearButton.visibility =
            if (hasSelection) View.VISIBLE else View.INVISIBLE
        b.speakeridExplorerClearButton.isEnabled = hasSelection
    }

    /** Auswahl-Korb: bg secondary@12%, Label secondary bei gefuelltem Korb. */
    private fun applyBasketRow() {
        val b = _binding ?: return
        val ctx = requireContext()
        val secondary = ContextCompat.getColor(ctx, R.color.speakerid_secondary)
        if (basket.isNotEmpty()) {
            // app.theme_manager.secondary_color[:3] + [0.12]
            b.speakeridExplorerBasketRow.setBackgroundColor(
                ColorUtils.setAlphaComponent(secondary, 31),
            )
            b.speakeridExplorerBasketLabel.setTextColor(secondary)
        } else {
            b.speakeridExplorerBasketRow.setBackgroundColor(
                ContextCompat.getColor(ctx, R.color.speakerid_surface),
            )
            b.speakeridExplorerBasketLabel.setTextColor(
                ContextCompat.getColor(ctx, R.color.speakerid_text_secondary),
            )
        }
        b.speakeridExplorerBasketLabel.text =
            getString(R.string.speakerid_explorer_basket_label, basketText)
        b.speakeridExplorerBasketAddButton.isEnabled = selection.isNotEmpty()
        b.speakeridExplorerBasketDeleteButton.isEnabled = basket.isNotEmpty()
    }

    /** "✓ OK (n)" — Zaehler/Aktivierung wie die kv-Ternaries. */
    private fun updateConfirmButton() {
        val b = _binding ?: return
        val count = if (selectionMode == MODE_TARGET && basket.isNotEmpty()) {
            basket.size
        } else {
            selection.size
        }
        b.speakeridExplorerConfirmButton.text =
            getString(R.string.speakerid_explorer_confirm, count)
        b.speakeridExplorerConfirmButton.isEnabled = if (selectionMode == MODE_TARGET) {
            basket.isNotEmpty() || selection.isNotEmpty()
        } else {
            selection.isNotEmpty()
        }
    }

    // ── Helfer ───────────────────────────────────────────────────────────────

    private fun setStatus(text: String) {
        _binding?.speakeridExplorerStatusLabel?.text = text
    }

    private fun canon(f: File): String =
        try {
            f.canonicalPath
        } catch (e: Exception) {
            f.absolutePath
        }

    /**
     * Sandbox-Wache aus `set_path`/`go_up` (`startswith(sandbox_abs)`);
     * zusaetzlich erlaubt: das echte Aufnahme-Verzeichnis des Recorders.
     */
    private fun isAllowed(f: File?): Boolean {
        if (f == null) return false
        val p = canon(f) + File.separator
        return p.startsWith(canon(sandboxPath) + File.separator) ||
            p.startsWith(canon(recordingsDir) + File.separator)
    }

    /** Kivy `FileChooser.get_nice_size` ('%1.0f %s', B/KB/MB/GB/TB). */
    private fun niceSize(f: File): String {
        val size = try {
            f.length()
        } catch (e: Exception) {
            return getString(R.string.speakerid_explorer_size_unreadable)
        }
        var value = size.toDouble()
        for (unit in listOf("B", "KB", "MB", "GB")) {
            if (value < 1024.0) return String.format(Locale.US, "%1.0f %s", value, unit)
            value /= 1024.0
        }
        return String.format(Locale.US, "%1.0f %s", value, "TB")
    }

    companion object {
        /** Werte von `selection_mode` (explorer.py). */
        const val MODE_ANALYSIS = "analysis"
        const val MODE_TARGET = "target"
        const val MODE_PERFORMANCE = "performance"
        const val MODE_DIARIZATION = "diarization"

        /**
         * Fragment-Argument: Pendant zu `explorer.selection_mode = ...` des
         * aufrufenden Screens (Default "analysis").
         */
        const val ARG_SELECTION_MODE = "speakerid_explorer_mode"

        /**
         * savedStateHandle-Schluessel (String = absoluter Pfad), den der
         * Aufrufer (performance_test/diarization_report) nach der Rueckkehr
         * liest — Pendant zu `selected_file = files[0]`.
         */
        const val RESULT_SELECTED_FILE = "speakerid_explorer_selected_file"
    }
}
