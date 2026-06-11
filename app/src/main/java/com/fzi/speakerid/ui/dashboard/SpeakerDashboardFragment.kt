package com.fzi.speakerid.ui.dashboard

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.fzi.acousticscene.R
import com.fzi.acousticscene.databinding.FragmentSpeakeridDashboardBinding
import com.fzi.speakerid.ui.SpeakerIdDataManager
import com.fzi.speakerid.ui.settings.SpeakerSettingsState
import com.fzi.speakerid.ui.widgets.SpeakerIdExpertTabBar
import java.util.Locale
import kotlin.math.min
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Port von siqas `gui/screens/dashboard/dashboard.py` (+ .kv):
 * Dashboard — Uebersicht ueber den aktuellen App-Zustand (Experten-Labor).
 *
 * Verhalten 1:1 wie das Original:
 *  - `on_enter_screen`: `_refresh_all` + Bindings auf `current_audio_path`,
 *    `target_audio_path`, `threshold_target/normal`, `use_silero_vad`,
 *    `clusters`, `ui_theme`, `chunk_history` -> [repeatOnLifecycle] (STARTED).
 *  - Kivy-Markup ([color=...]/[b]) -> Spannables mit identischen Hex-Farben.
 *  - `vad_status` ist wie in Kivy eine tote Property (kein Widget im .kv)
 *    -> nicht gerendert; ebenso `speaker_durations`/`speaker_colors`.
 *  - Buttons: `print_all_data()` (State-Dump nach Logcat statt stdout),
 *    3 Dummy-Embeddings, `reevaluate_unlabeled_pool()`.
 *  - Responsive Zeilen (`orientation: vertical if root.width < dp(X) else
 *    horizontal`, Breakpoints 700/850/700/700/750dp) + Titelgroesse
 *    min(sp(24), root.width*0.05) via Layout-Listener.
 */
class SpeakerDashboardFragment : Fragment() {

    private var _binding: FragmentSpeakeridDashboardBinding? = null
    private val binding get() = _binding!!

    private lateinit var dm: SpeakerIdDataManager

    private var lastLayoutWidth = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSpeakeridDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dm = SpeakerIdDataManager.getInstance(requireContext())

        // ── Experten-Tab-Leiste (main.kv tab_bar_container) ─────────────────
        binding.speakeridDashboardTabBar.apply {
            // kv: state: 'down' if screen_manager.current == 'dashboard'
            setActiveTab(SpeakerIdExpertTabBar.Tab.STATUS)
            onTabSelected = { tab ->
                // kv on_release: screen_manager.current = 'diarization_report'
                if (tab == SpeakerIdExpertTabBar.Tab.DIARIZATION) {
                    findNavController().navigate(R.id.diarizationReportFragment)
                }
            }
            onClose = {
                // kv X-Button: app.expert_lab_active = False;
                //              screen_manager.current = 'main_menu'
                dm.expertModeActive.value = false
                findNavController().popBackStack(R.id.speakeridMenuFragment, false)
            }
        }

        // ── Action-Buttons (on_release aus dem .kv) ─────────────────────────
        binding.speakeridDashboardBtnDump.setOnClickListener { printAllData() }
        binding.speakeridDashboardBtnDummy.setOnClickListener {
            // root.dm.add_new_embedding([0.1]*192, "0") (x3 mit 0.1/0.2/0.3)
            dm.addNewEmbedding(DoubleArray(192) { 0.1 }, "0")
            dm.addNewEmbedding(DoubleArray(192) { 0.2 }, "0")
            dm.addNewEmbedding(DoubleArray(192) { 0.3 }, "0")
        }
        binding.speakeridDashboardBtnReassign.setOnClickListener {
            dm.reevaluateUnlabeledPool()
        }

        // Responsive Breakpoints + Titelgroesse
        binding.root.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val width = right - left
            if (width > 0 && width != lastLayoutWidth) {
                lastLayoutWidth = width
                applyResponsiveLayout(width)
            }
        }

        // ── on_enter_screen: _refresh_all + Bindings ────────────────────────
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                refreshAll()

                // bind_to('current_audio_path', _on_audio_changed) — Kivy-Binds
                // feuern erst bei Aenderung -> drop(1) der Initial-Emission.
                launch {
                    dm.currentAudioPath.drop(1).collect { updateAudioStatus(it) }
                }
                // bind_to('target_audio_path', _on_target_audio_changed)
                launch {
                    dm.targetAudioPath.drop(1).collect { updateTargetStatus(it) }
                }
                // bind_to('threshold_target'/'threshold_normal', _on_threshold_changed)
                launch {
                    dm.thresholdTarget.drop(1).collect { updateThresholdInfo() }
                }
                launch {
                    dm.thresholdNormal.drop(1).collect { updateThresholdInfo() }
                }
                // bind_to('clusters', _on_clusters_changed) + UNLABELED/Waveform
                launch {
                    dm.clustersVersion.drop(1).collect {
                        updateClusterInfo()
                        updateChunkHistory()
                    }
                }
                // bind_to('ui_theme', _on_theme_changed)
                launch {
                    SpeakerSettingsState.uiTheme.drop(1).collect { updateThemeInfo(it) }
                }
                // kv-Binding der LIVE-AUFNAHME-Karte an is_recording_running
                launch {
                    dm.isRecordingRunning.collect { updateRecordingCard(it) }
                }
            }
        }
    }

    // ── _refresh_all ─────────────────────────────────────────────────────────

    private fun refreshAll() {
        updateAudioStatus(dm.currentAudioPath.value)
        updateTargetStatus(dm.targetAudioPath.value)
        updateThresholdInfo()
        updateAssignmentInfo()
        updateUpdateInfo()
        updateThemeInfo(SpeakerSettingsState.uiTheme.value)
        updateClusterInfo()
        // _on_chunk_history_changed(None, self.dm.chunk_history) — Initialwert
        updateChunkHistory()
    }

    // ── Observer-Callbacks ───────────────────────────────────────────────────

    /** `[color=008800]{path}[/color]` bzw. `[color=CC0000]Keine Datei geladen[/color]`. */
    private fun updateAudioStatus(path: String) {
        val b = _binding ?: return
        b.speakeridDashboardAudioValue.text = if (path.isNotEmpty()) {
            colored(path, Color.parseColor("#008800"))
        } else {
            colored(
                getString(R.string.speakerid_dashboard_value_no_file),
                Color.parseColor("#CC0000"),
            )
        }
    }

    /** `[color=007777]{t_path}[/color]` bzw. `[color=CC0000]Kein Target geladen[/color]`. */
    private fun updateTargetStatus(path: String) {
        val b = _binding ?: return
        b.speakeridDashboardTargetValue.text = if (path.isNotEmpty()) {
            colored(path, Color.parseColor("#007777"))
        } else {
            colored(
                getString(R.string.speakerid_dashboard_value_no_target),
                Color.parseColor("#CC0000"),
            )
        }
    }

    /** f"Target: [b]{:.2f}[/b]  |  Cluster: [b]{:.2f}[/b]" */
    private fun updateThresholdInfo() {
        val b = _binding ?: return
        b.speakeridDashboardThresholdValue.text = SpannableStringBuilder()
            .append(getString(R.string.speakerid_dashboard_value_threshold_target_prefix))
            .append(bold(String.format(Locale.US, "%.2f", dm.thresholdTarget.value)))
            .append(getString(R.string.speakerid_dashboard_value_threshold_separator))
            .append(getString(R.string.speakerid_dashboard_value_threshold_cluster_prefix))
            .append(bold(String.format(Locale.US, "%.2f", dm.thresholdNormal.value)))
    }

    /** f"[b]{dm.cluster_assignment_strategy}[/b]" */
    private fun updateAssignmentInfo() {
        val b = _binding ?: return
        b.speakeridDashboardAssignmentValue.text = bold(dm.clusterAssignmentStrategy.value)
    }

    /** f"[b]{dm.centroid_update_strategy}[/b]" */
    private fun updateUpdateInfo() {
        val b = _binding ?: return
        b.speakeridDashboardUpdateValue.text = bold(dm.centroidUpdateStrategy.value)
    }

    /** f"Aktuelles Theme: [b]{value}[/b]" */
    private fun updateThemeInfo(theme: String) {
        val b = _binding ?: return
        b.speakeridDashboardThemeValue.text = SpannableStringBuilder()
            .append(getString(R.string.speakerid_dashboard_value_theme_prefix))
            .append(bold(theme))
    }

    /** Port von `_on_clusters_changed` + UNLABELED-Karte aus dem .kv. */
    private fun updateClusterInfo() {
        val b = _binding ?: return
        // f"[b]{num_emb}[/b] Embeddings" / f"[b]{num_spk}[/b] Sprecher erkannt"
        b.speakeridDashboardEmbeddingsValue.text = SpannableStringBuilder()
            .append(bold(dm.numEmbeddings.toString()))
            .append(getString(R.string.speakerid_dashboard_value_embeddings_suffix))
        b.speakeridDashboardSpeakersValue.text = SpannableStringBuilder()
            .append(bold(dm.numSpeakers.toString()))
            .append(getString(R.string.speakerid_dashboard_value_speakers_suffix))
        // str(app.data_manager.unlabeled_count) + " Chunks" (ohne Markup)
        b.speakeridDashboardUnlabeledValue.text =
            dm.unlabeledCount.toString() +
                getString(R.string.speakerid_dashboard_value_chunks_suffix)
        // speaker_durations/speaker_colors sind im .kv ungenutzt (tote
        // DictProperties) -> bewusst nicht portiert.
    }

    /** kv: "[color=CC0000][b]AKTIV[/b][/color]" / "[color=008800]Standby[/color]" */
    private fun updateRecordingCard(isRunning: Boolean) {
        val b = _binding ?: return
        b.speakeridDashboardRecordingValue.text = if (isRunning) {
            val s = SpannableString(getString(R.string.speakerid_dashboard_value_recording_active))
            s.setSpan(
                ForegroundColorSpan(Color.parseColor("#CC0000")),
                0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            s.setSpan(StyleSpan(Typeface.BOLD), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            s
        } else {
            colored(
                getString(R.string.speakerid_dashboard_value_recording_standby),
                Color.parseColor("#008800"),
            )
        }
    }

    /** Port von `_on_chunk_history_changed`: `wf.segment_data = value`. */
    private fun updateChunkHistory() {
        val b = _binding ?: return
        b.speakeridDashboardWaveform.segmentData = dm.chunkHistorySnapshot()
    }

    // ── print_all_data (dashboard-Button "DataManager: Alle Daten ausgeben") ─

    /**
     * Port von `SiqasDataManager.print_all_data` — identische Zeilen, nur
     * nach Logcat statt stdout (Android hat keine Konsole).
     */
    private fun printAllData() {
        val w = 72
        val sb = StringBuilder("\n").append("═".repeat(w)).append('\n')
        sb.append(center("  SIQAS DataManager – State Dump", w)).append('\n')
        sb.append("═".repeat(w)).append('\n')

        sb.append("  Pipeline          : ")
            .append(if (dm.isPipelineRunning.value) "▶ AKTIV" else "■ INAKTIV").append('\n')
        sb.append("  Aufnahme          : ")
            .append(if (dm.isRecordingRunning.value) "● LÄUFT" else "○ GESTOPPT").append('\n')
        sb.append("  Mikrofon          : ")
            .append(if (dm.useVirtualMic.value) "VIRTUAL" else "HARDWARE").append('\n')
        sb.append("  VAD (Silero)      : ")
            .append(if (dm.useSileroVad.value) "EIN" else "AUS").append('\n')
        sb.append("  Expert-Modus      : ")
            .append(if (dm.expertModeActive.value) "EIN" else "AUS").append('\n')
        sb.append("─".repeat(w)).append('\n')

        sb.append(String.format(Locale.US, "  Threshold Target  : %.4f%n", dm.thresholdTarget.value))
        sb.append(String.format(Locale.US, "  Threshold Normal  : %.4f%n", dm.thresholdNormal.value))
        val strat = dm.centroidUpdateStrategy.value
        val stratDetail = when (strat) {
            "ema" -> "  (α=${dm.emaAlpha.value})"
            "moving_average" -> "  (Win=${dm.movingAverageWindowSize.value})"
            else -> ""
        }
        sb.append("  Centroid-Strategie: ").append(strat).append(stratDetail).append('\n')
        sb.append("  Zuweisung         : ").append(dm.clusterAssignmentStrategy.value).append('\n')
        sb.append("  Projektion        : ").append(dm.projectionMethod.value).append('\n')
        sb.append("  Audio-Pfad        : ").append(dm.currentAudioPath.value).append('\n')
        sb.append("  Target-Ref-Pfad   : ").append(dm.targetAudioPath.value).append('\n')
        sb.append("─".repeat(w)).append('\n')

        val clusters = dm.clustersSnapshot()
        val totalChunks = clusters.values.sumOf { it.occurrences }
        val totalTime = clusters.values.sumOf { it.totalTime }
        sb.append("  Cluster gesamt    : ${clusters.size}  (aktive Sprecher: ${dm.numSpeakers})\n")
        sb.append(
            String.format(
                Locale.US, "  Chunks gesamt     : %d  |  Zeit gesamt: %.1fs%n",
                totalChunks, totalTime,
            )
        )
        sb.append(
            String.format(
                Locale.US, "  Unlabeled         : %d Chunks (%.1f%%)%n",
                dm.unlabeledCount, dm.unlabeledPercentage,
            )
        )
        sb.append(String.format(Locale.US, "  Stille-Anteil     : %.1f%%%n", dm.silencePercentage))
        sb.append("  chunk_history Len : ${dm.chunkHistorySnapshot().size}\n")
        sb.append("─".repeat(w)).append('\n')

        sb.append(
            String.format(
                Locale.US, "  %4s  %-10s  %6s  %7s  %8s  %8s  %10s%n",
                "ID", "ROLLE", "Chunks", "Zeit", "Centroid", "STD", "Embeddings",
            )
        )
        sb.append("  ").append("·".repeat(w - 2)).append('\n')

        val sortedIds = clusters.keys.sortedWith(
            compareBy({ it != "1" }, { it != "-1" }, { it != "0" }, { it })
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
            sb.append(
                String.format(
                    Locale.US, "  %4s  %-10s  %6d  %6.1fs  %8s  %8.4f  %9dd%n",
                    cid, role, c.occurrences, c.totalTime, centroidFlag, c.std, embDim,
                )
            )
        }
        sb.append("═".repeat(w)).append('\n')
        Log.i(TAG, sb.toString())
    }

    private fun center(text: String, width: Int): String {
        if (text.length >= width) return text
        val total = width - text.length
        val left = total / 2
        return " ".repeat(left) + text + " ".repeat(total - left)
    }

    // ── Spannable-Helfer (Kivy-Markup-Ersatz) ────────────────────────────────

    private fun colored(text: String, color: Int): CharSequence =
        SpannableString(text).apply {
            setSpan(ForegroundColorSpan(color), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

    private fun bold(text: String): CharSequence =
        SpannableString(text).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

    // ── Responsive Layout ────────────────────────────────────────────────────

    /**
     * Breakpoints aus dem .kv (`root.width < dp(X)`): Audio 700 / ML 850 /
     * Strategien 700 / System 700 / Buttons 750. Titel min(sp(24), w*0.05).
     */
    private fun applyResponsiveLayout(widthPx: Int) {
        val b = _binding ?: return
        val w = widthPx.toFloat()

        b.speakeridDashboardTitle.setTextSize(
            TypedValue.COMPLEX_UNIT_PX, min(spPx(24f), w * 0.05f)
        )

        configureCardRow(b.speakeridDashboardRowAudio, horizontal = w >= dpPx(700f))
        configureCardRow(b.speakeridDashboardRowMl, horizontal = w >= dpPx(850f))
        configureCardRow(b.speakeridDashboardRowStrategies, horizontal = w >= dpPx(700f))
        configureCardRow(b.speakeridDashboardRowSystem, horizontal = w >= dpPx(700f))
        configureButtonRow(b.speakeridDashboardRowButtons, horizontal = w >= dpPx(750f))
    }

    /**
     * Karten-Zeile: vertikal = Karten untereinander (75dp, spacing 10dp oben);
     * horizontal = Zeile dp(100) hoch, Karten 75dp unten ausgerichtet (Kivy
     * positioniert Kinder mit festem size_hint_y=None am unteren Rand),
     * gleiche Breiten, spacing 10dp.
     */
    private fun configureCardRow(row: LinearLayout, horizontal: Boolean) {
        val wantOrientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        if (row.orientation == wantOrientation) return
        row.orientation = wantOrientation
        row.layoutParams = row.layoutParams.also {
            it.height = if (horizontal) dpInt(100f) else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        for (i in 0 until row.childCount) {
            val child = row.getChildAt(i)
            val lp = child.layoutParams as LinearLayout.LayoutParams
            lp.height = dpInt(75f)
            if (horizontal) {
                lp.width = 0
                lp.weight = 1f
                lp.topMargin = 0
                lp.marginStart = if (i == 0) 0 else dpInt(10f)
                lp.gravity = Gravity.BOTTOM
            } else {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                lp.weight = 0f
                lp.topMargin = if (i == 0) 0 else dpInt(10f)
                lp.marginStart = 0
                lp.gravity = Gravity.NO_GRAVITY
            }
            child.layoutParams = lp
        }
    }

    /** Button-Zeile (Hoehe bleibt minimum_height, Buttons behalten wrap_content). */
    private fun configureButtonRow(row: LinearLayout, horizontal: Boolean) {
        val wantOrientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        if (row.orientation == wantOrientation) return
        row.orientation = wantOrientation
        for (i in 0 until row.childCount) {
            val child = row.getChildAt(i)
            val lp = child.layoutParams as LinearLayout.LayoutParams
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            if (horizontal) {
                lp.width = 0
                lp.weight = 1f
                lp.topMargin = 0
                lp.marginStart = if (i == 0) 0 else dpInt(10f)
                lp.gravity = Gravity.BOTTOM
            } else {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                lp.weight = 0f
                lp.topMargin = if (i == 0) 0 else dpInt(10f)
                lp.marginStart = 0
                lp.gravity = Gravity.NO_GRAVITY
            }
            child.layoutParams = lp
        }
    }

    private fun spPx(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private fun dpPx(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun dpInt(v: Float): Int = dpPx(v).toInt()

    override fun onDestroyView() {
        lastLayoutWidth = 0
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val TAG = "SpeakerIdDashboard"
    }
}
