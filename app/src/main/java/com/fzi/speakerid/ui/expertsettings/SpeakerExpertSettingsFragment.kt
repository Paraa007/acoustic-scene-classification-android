package com.fzi.speakerid.ui.expertsettings

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.fzi.acousticscene.R
import com.fzi.acousticscene.databinding.FragmentSpeakeridExpertSettingsBinding
import com.fzi.speakerid.ui.SpeakerIdDataManager
import com.fzi.speakerid.ui.explorer.SpeakerExplorerFragment
import com.fzi.speakerid.ui.settings.SpeakerSettingsState
import com.fzi.speakerid.ui.widgets.SpeakerIdExpertTabBar
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Port von siqas `gui/screens/settings/expert_settings.py` + `expert_settings.kv`
 * (<ExpertSettingsScreen>, "Parameter & KI").
 *
 * Der Screen ist SharedSettingsPanel (identische Verdrahtung wie
 * [com.fzi.speakerid.ui.settings.SpeakerSettingsFragment]) plus die
 * Experten-Eintraege:
 *  - Silero VAD          <-> [SpeakerIdDataManager.useSileroVad]        (Default true)
 *  - CENTROID-MODUS      <-> [SpeakerIdDataManager.centroidUpdateStrategy]
 *                            static|mean|moving_average|ema|median|medoid (Default "static")
 *  - PROJEKTIONS-METHODE <-> [SpeakerIdDataManager.projectionMethod]    (Default "PCA");
 *                            im .kv `disabled: app.data_manager.is_mobile` — auf Android
 *                            immer true, daher dauerhaft deaktiviert (siehe
 *                            [applyKivyDisabled]: Kivy-`disabled` kaskadiert auf alle
 *                            Kinder, Label-Text faellt auf disabled_color [1,1,1,0.3],
 *                            Hintergruende bleiben unveraendert)
 *  - "Daten Reset"        -> [SpeakerIdDataManager.reset]
 *  - "Werte ausgeben"     -> Port von `DataManager.print_all_data()` (State-Dump nach
 *                            Logcat statt stdout; lebt hier, weil der DataManager fuer
 *                            Screen-Agenten tabu ist — siehe [printAllData])
 *
 * Persistenz wie siqas: Werte leben ausschliesslich im Prozess-Singleton
 * (keine SharedPreferences) — gleiche Mechanik wie SpeakerSettingsFragment.
 *
 * Lifecycle: `on_enter`/`on_leave` (BaseScreen bindet/unbindet Observer)
 * entspricht `repeatOnLifecycle(STARTED)`. Hardware-Back (BaseScreen key 27 ->
 * 'settings') = Standard-Back der Navigation, ebenso der Header-Pfeil
 * (`on_back: root.manager.current = 'settings'`). `expert_settings.py` selbst
 * hat keine weitere Logik (set_theme deckt die Theme-SegmentedChoice ab).
 */
class SpeakerExpertSettingsFragment : Fragment() {

    private var _binding: FragmentSpeakeridExpertSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSpeakeridExpertSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val dm = SpeakerIdDataManager.getInstance(requireContext().applicationContext)
        val panel = binding.speakeridExpertSettingsSharedPanel

        // ScreenHeader: on_back -> root.manager.current = 'settings'
        binding.speakeridExpertSettingsHeader.onBack = { findNavController().popBackStack() }

        // ── Experten-Tab-Leiste (main.kv tab_bar_container) ─────────────────
        binding.speakeridExpertSettingsTabBar.apply {
            // kv: state 'down' wenn screen_manager.current == 'expert_settings'
            setActiveTab(SpeakerIdExpertTabBar.Tab.SETTINGS)
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
                    SpeakerIdExpertTabBar.Tab.PERFORMANCE ->
                        nav.navigate(R.id.speakeridPerformanceTestFragment)
                    SpeakerIdExpertTabBar.Tab.SETTINGS -> Unit
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

        // ── SharedSettingsPanel (1:1 wie SpeakerSettingsFragment) ────────────
        panel.speakeridSettingsSliderTarget.apply {
            labelText = getString(R.string.speakerid_settings_target_matching)
            accentColor = ContextCompat.getColor(requireContext(), R.color.speakerid_primary)
            minValue = 0.0
            maxValue = 1.0
            step = 0.01
            fmt = "%.2f"
            value = dm.thresholdTarget.value
            onValueChanged = { dm.thresholdTarget.value = it }
        }
        panel.speakeridSettingsSliderCluster.apply {
            labelText = getString(R.string.speakerid_settings_cluster_matching)
            accentColor = ContextCompat.getColor(requireContext(), R.color.speakerid_secondary)
            minValue = 0.0
            maxValue = 1.0
            step = 0.01
            fmt = "%.2f"
            value = dm.thresholdNormal.value
            onValueChanged = { dm.thresholdNormal.value = it }
        }
        panel.speakeridSettingsSliderChunk.apply {
            labelText = getString(R.string.speakerid_settings_chunk_duration)
            accentColor = ContextCompat.getColor(requireContext(), R.color.speakerid_primary)
            minValue = 0.4
            maxValue = 1.5
            step = 0.1
            fmt = "%.1f"
            value = dm.chunkDuration.value
            onValueChanged = { dm.chunkDuration.value = it }
        }
        panel.speakeridSettingsChoiceTheme.apply {
            options = listOf(
                getString(R.string.speakerid_settings_theme_basic) to "Basic",
                getString(R.string.speakerid_settings_theme_fzi) to "FZI",
                getString(R.string.speakerid_settings_theme_dark) to "FZI-Dark",
            )
            value = SpeakerSettingsState.uiTheme.value
            onValueChanged = { SpeakerSettingsState.uiTheme.value = it }
        }
        panel.speakeridSettingsToggleVirtualMic.apply {
            isActive = dm.useVirtualMic.value
            onActiveChanged = { dm.useVirtualMic.value = it }
        }
        panel.speakeridSettingsTogglePyannote.apply {
            isActive = dm.usePyannote.value
            onActiveChanged = { dm.usePyannote.value = it }
        }

        // ── VAD (LabeledToggle "Silero VAD") ─────────────────────────────────
        binding.speakeridExpertSettingsToggleVad.apply {
            isActive = dm.useSileroVad.value
            onActiveChanged = { dm.useSileroVad.value = it }
        }

        // ── Centroid-Modus (options 1:1 aus dem .kv) ─────────────────────────
        binding.speakeridExpertSettingsChoiceCentroid.apply {
            options = listOf(
                getString(R.string.speakerid_expert_settings_centroid_static) to "static",
                getString(R.string.speakerid_expert_settings_centroid_mean) to "mean",
                getString(R.string.speakerid_expert_settings_centroid_moving_avg) to "moving_average",
                getString(R.string.speakerid_expert_settings_centroid_ema) to "ema",
                getString(R.string.speakerid_expert_settings_centroid_median) to "median",
                getString(R.string.speakerid_expert_settings_centroid_medoid) to "medoid",
            )
            value = dm.centroidUpdateStrategy.value
            onValueChanged = { dm.centroidUpdateStrategy.value = it }
        }

        // ── Projektions-Methode (disabled: app.data_manager.is_mobile) ──────
        binding.speakeridExpertSettingsChoiceProjection.apply {
            options = listOf(
                getString(R.string.speakerid_expert_settings_projection_pca) to "PCA",
                getString(R.string.speakerid_expert_settings_projection_tsne) to "t-SNE",
                getString(R.string.speakerid_expert_settings_projection_umap) to "UMAP",
                getString(R.string.speakerid_expert_settings_projection_pca_umap) to "PCA + UMAP",
                getString(R.string.speakerid_expert_settings_projection_pca_tsne) to "PCA + t-SNE",
            )
            value = dm.projectionMethod.value
            onValueChanged = { dm.projectionMethod.value = it }
            if (dm.isMobile.value) applyKivyDisabled(this)
        }

        // ── System-Tools ─────────────────────────────────────────────────────
        // "Daten Reset" (on_release: app.data_manager.reset()) — reset() laedt
        // u. a. den Target-Cache von Platte -> nicht auf den Main-Thread legen.
        binding.speakeridExpertSettingsBtnReset.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
                dm.reset()
            }
        }
        // "Werte ausgeben" (on_release: app.data_manager.print_all_data())
        binding.speakeridExpertSettingsBtnPrint.setOnClickListener {
            printAllData(dm)
        }

        // ── DataManager -> UI (Kivy-Property-Bindings) ───────────────────────
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    dm.thresholdTarget.collect { panel.speakeridSettingsSliderTarget.value = it }
                }
                launch {
                    dm.thresholdNormal.collect { panel.speakeridSettingsSliderCluster.value = it }
                }
                launch {
                    dm.chunkDuration.collect { panel.speakeridSettingsSliderChunk.value = it }
                }
                launch {
                    dm.useVirtualMic.collect { panel.speakeridSettingsToggleVirtualMic.isActive = it }
                }
                launch {
                    dm.usePyannote.collect { panel.speakeridSettingsTogglePyannote.isActive = it }
                }
                launch {
                    SpeakerSettingsState.uiTheme.collect { panel.speakeridSettingsChoiceTheme.value = it }
                }
                launch {
                    dm.useSileroVad.collect { binding.speakeridExpertSettingsToggleVad.isActive = it }
                }
                launch {
                    dm.centroidUpdateStrategy.collect {
                        binding.speakeridExpertSettingsChoiceCentroid.value = it
                    }
                }
                launch {
                    dm.projectionMethod.collect {
                        binding.speakeridExpertSettingsChoiceProjection.value = it
                        // value-Set baut die Pill-Farben neu auf -> Disabled-Optik
                        // wie in Kivy erneut anwenden.
                        if (dm.isMobile.value) {
                            applyKivyDisabled(binding.speakeridExpertSettingsChoiceProjection)
                        }
                    }
                }
            }
        }
    }

    /**
     * Pendant zu Kivys `disabled: True` auf einem Container: `disabled`
     * kaskadiert auf alle Kinder (Touch wird ignoriert), Label/Buttons zeichnen
     * ihren Text mit `disabled_color` (Default [1, 1, 1, 0.3]); die
     * canvas-Hintergruende (Karte + Pills) bleiben unveraendert.
     */
    private fun applyKivyDisabled(view: View) {
        view.isEnabled = false
        if (view is TextView) view.setTextColor(0x4DFFFFFF) // [1,1,1,0.3]
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) applyKivyDisabled(view.getChildAt(i))
        }
    }

    /**
     * Port von `DataManager.print_all_data()` — vollstaendiger State-Dump,
     * Format 1:1 wie die Python-`print`-Ausgabe (Logcat statt stdout).
     * Lebt im Fragment, weil [SpeakerIdDataManager] hier nicht angefasst wird.
     */
    private fun printAllData(dm: SpeakerIdDataManager) {
        val w = 72
        val heavy = "═".repeat(w)
        val light = "─".repeat(w)
        val lines = mutableListOf<String>()

        // print("\n" + "═" * W) / Titel zentriert / print("═" * W)
        lines += ""
        lines += heavy
        val title = "  SIQAS DataManager – State Dump"
        val pad = (w - title.length).coerceAtLeast(0)
        lines += " ".repeat(pad / 2) + title + " ".repeat(pad - pad / 2)
        lines += heavy

        // --- System ---
        lines += "  Pipeline          : " + if (dm.isPipelineRunning.value) "▶ AKTIV" else "■ INAKTIV"
        lines += "  Aufnahme          : " + if (dm.isRecordingRunning.value) "● LÄUFT" else "○ GESTOPPT"
        lines += "  Mikrofon          : " + if (dm.useVirtualMic.value) "VIRTUAL" else "HARDWARE"
        lines += "  VAD (Silero)      : " + if (dm.useSileroVad.value) "EIN" else "AUS"
        lines += "  Expert-Modus      : " + if (dm.expertModeActive.value) "EIN" else "AUS"
        lines += light

        // --- Konfiguration ---
        lines += "  Threshold Target  : " + String.format(Locale.US, "%.4f", dm.thresholdTarget.value)
        lines += "  Threshold Normal  : " + String.format(Locale.US, "%.4f", dm.thresholdNormal.value)
        val strat = dm.centroidUpdateStrategy.value
        val stratDetail = when (strat) {
            "ema" -> "  (α=${dm.emaAlpha.value})"
            "moving_average" -> "  (Win=${dm.movingAverageWindowSize.value})"
            else -> ""
        }
        lines += "  Centroid-Strategie: $strat$stratDetail"
        lines += "  Zuweisung         : ${dm.clusterAssignmentStrategy.value}"
        lines += "  Projektion        : ${dm.projectionMethod.value}"
        lines += "  Audio-Pfad        : ${dm.currentAudioPath.value}"
        lines += "  Target-Ref-Pfad   : ${dm.targetAudioPath.value}"
        lines += light

        // --- Aggregate ---
        val clusters = dm.clustersSnapshot()
        val totalChunks = clusters.values.sumOf { it.occurrences }
        val totalTime = clusters.values.sumOf { it.totalTime }
        lines += "  Cluster gesamt    : ${clusters.size}  (aktive Sprecher: ${dm.numSpeakers})"
        lines += "  Chunks gesamt     : $totalChunks  |  Zeit gesamt: " +
            String.format(Locale.US, "%.1f", totalTime) + "s"
        lines += "  Unlabeled         : ${dm.unlabeledCount} Chunks (" +
            String.format(Locale.US, "%.1f", dm.unlabeledPercentage) + "%)"
        lines += "  Stille-Anteil     : " +
            String.format(Locale.US, "%.1f", dm.silencePercentage) + "%"
        lines += "  chunk_history Len : ${dm.chunkHistorySnapshot().size}"
        lines += light

        // --- Cluster-Detail ---
        lines += String.format(
            Locale.US,
            "  %4s  %-10s  %6s  %7s  %8s  %8s  %10s",
            "ID", "ROLLE", "Chunks", "Zeit", "Centroid", "STD", "Embeddings",
        )
        lines += "  " + "·".repeat(w - 2)

        // sort_key = lambda x: (x != "1", x != "-1", x != "0", x)
        val sortedIds = clusters.keys.sortedWith(
            compareBy({ it != "1" }, { it != "-1" }, { it != "0" }, { it }),
        )
        for (cid in sortedIds) {
            val c = clusters.getValue(cid)
            val role = when {
                c.isUnlabeled -> "UNLABELED"
                c.isTarget -> "TARGET"
                c.isSilence -> "STILLE"
                else -> "SPEAKER"
            }
            val centroidFlag = if (c.centroid != null) "JA" else "NEIN"
            val embDim = c.centroid?.size ?: 0
            lines += String.format(
                Locale.US,
                "  %4s  %-10s  %6d  %6.1fs  %8s  %8.4f  %9dd",
                cid, role, c.occurrences, c.totalTime, centroidFlag, c.std, embDim,
            )
        }

        lines += heavy
        lines += ""

        for (line in lines) Log.i(TAG, line)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "SpeakerExpertSettings"
    }
}
