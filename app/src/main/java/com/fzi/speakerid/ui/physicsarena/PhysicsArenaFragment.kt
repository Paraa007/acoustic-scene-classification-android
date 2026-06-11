package com.fzi.speakerid.ui.physicsarena

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.fzi.acousticscene.R
import com.fzi.acousticscene.databinding.FragmentSpeakeridPhysicsArenaBinding
import com.fzi.speakerid.ui.SpeakerIdDataManager
import com.fzi.speakerid.ui.SpeakerIdTheme
import com.fzi.speakerid.ui.SpeakerLiveSession
import com.fzi.speakerid.ui.SpeakerSessionController
import java.util.Locale
import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Port von siqas `gui/screens/physics_arena/physics_arena.py` (+ .kv):
 * Schritt 2 — Live-Visualisierung der einkommenden Audiostroeme.
 *
 * Verhalten 1:1 wie das Original:
 *  - `on_enter_screen`: Bindings auf `clusters`, `is_recording_running`,
 *    `current_active_voices` und `live_controller.current_speaker_id`
 *    plus 0,5-s-Ticker fuer die Laufzeit -> [repeatOnLifecycle] (STARTED);
 *    `on_leave_screen` beendet nur den Ticker/die Bindings — die Aufnahme
 *    laeuft im Hintergrund weiter (kein stop()).
 *  - `_on_clusters_updated`: Legenden-Tabelle (Target zuerst, dann nach
 *    Anteil absteigend) + `bubble_arena.sync_with_clusters`.
 *  - `toggle_live` -> [SpeakerLiveSession.toggleLive].
 *  - `finish_analysis`: Live-Session stoppen und zur Statistik.
 *  - `go_back` (Button + Hardware-Back wie BaseScreen `key == 27`):
 *    Session abbrechen und zurueck zum Hauptmenue.
 */
class PhysicsArenaFragment : Fragment() {

    private var _binding: FragmentSpeakeridPhysicsArenaBinding? = null
    private val binding get() = _binding!!

    private lateinit var dm: SpeakerIdDataManager

    private val adapter = ArenaTableAdapter()

    private var lastLayoutWidth = 0

    /**
     * RECORD_AUDIO-Laufzeit-Permission (Android-Pflicht vor AudioRecord).
     * Nicht dem gelieferten Boolean vertrauen, sondern den echten
     * Permission-Stand pruefen — der Controller verweigert den Start ohne
     * Berechtigung ohnehin (Defense in depth).
     */
    private val recordPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        if (hasRecordPermission()) {
            toggleLive()
        } else {
            showStartHint(getString(R.string.speakerid_physics_mic_permission_denied))
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSpeakeridPhysicsArenaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dm = SpeakerIdDataManager.getInstance(requireContext())

        binding.arenaTable.layoutManager = LinearLayoutManager(requireContext())
        binding.arenaTable.adapter = adapter

        // StringProperty-Defaults: "Zeit: 00:00" / "Standby".
        totalTimeStr = getString(R.string.speakerid_physics_time_initial)
        statusText = getString(R.string.speakerid_physics_status_standby)
        binding.labelTimeStatus.text = "$totalTimeStr\n$statusText"

        binding.btnBack.setOnClickListener { goBack() }
        binding.btnToggle.setOnClickListener { onToggleClicked() }
        binding.btnEval.setOnClickListener { finishAnalysis() }

        // BaseScreen `_on_keyboard` (key 27) -> go_back()
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = goBack()
            },
        )

        // Responsive Klammern aus dem .kv: min(sp(x), root.width * f)
        binding.root.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val width = right - left
            if (width > 0 && width != lastLayoutWidth) {
                lastLayoutWidth = width
                applyResponsiveSizes(width)
            }
        }

        val controller = SpeakerLiveSession.get(requireContext())

        // on_enter_screen-Bindings; sterben mit on_leave (STARTED-Scope).
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // bind_to('clusters', _on_clusters_updated) + Initial-Refresh
                launch {
                    dm.clustersVersion.collect { updateClusters() }
                }
                // bind_to('is_recording_running', _on_recording_status) + Initialwert
                launch {
                    dm.isRecordingRunning.collect { isRunning ->
                        onRecordingStatus(isRunning)
                    }
                }
                // bind_to('current_active_voices', _on_active_voices_changed)
                launch {
                    dm.currentActiveVoices.collect { voices ->
                        onActiveVoicesChanged(voices)
                    }
                }
                // live_controller.bind(current_speaker_id=_on_active_speaker_changed)
                launch {
                    controller.currentSpeakerId.collect { updateClusters() }
                }
                // Clock.schedule_interval(self._sync_ui, 0.5)
                launch {
                    while (true) {
                        syncUi()
                        delay(500L)
                    }
                }
            }
        }
    }

    // ── Observer-Callbacks ───────────────────────────────────────────────────

    /** Port von `_on_recording_status` (+ kv-Binding des START/PAUSE-Buttons). */
    private fun onRecordingStatus(isRunning: Boolean) {
        val b = _binding ?: return
        statusText = getString(
            if (isRunning) R.string.speakerid_physics_status_live
            else R.string.speakerid_physics_status_standby
        )
        b.labelTimeStatus.text = "$totalTimeStr\n$statusText"

        // "PAUSE" + [0.85,0.2,0.2,1] wenn Aufnahme laeuft, sonst "START" + primary
        b.btnToggle.setText(
            if (isRunning) R.string.speakerid_physics_btn_pause
            else R.string.speakerid_physics_btn_start
        )
        b.btnToggle.setBackgroundResource(
            if (isRunning) R.drawable.speakerid_btn_physics_record
            else R.drawable.speakerid_btn_physics_primary
        )
    }

    /** kv-Binding: f"Stimmen: {root.active_voices}" inkl. Ampelfarbe. */
    private fun onActiveVoicesChanged(voices: String) {
        val b = _binding ?: return
        b.labelVoices.text = getString(R.string.speakerid_physics_voices_fmt, voices)
        b.labelVoices.setTextColor(
            when (voices) {
                "1" -> Color.rgb(51, 204, 51)      // [0.2, 0.8, 0.2, 1]
                "2" -> Color.rgb(255, 153, 0)      // [1, 0.6, 0, 1]
                "3+" -> Color.rgb(255, 51, 51)     // [1, 0.2, 0.2, 1]
                else -> ContextCompat.getColor(
                    requireContext(), R.color.speakerid_text_secondary
                )
            }
        )
    }

    /** Port von `_on_clusters_updated`: Tabelle + Bubble-Arena neu aufbauen. */
    private fun updateClusters() {
        val b = _binding ?: return
        val ctx = requireContext()
        val clusters = dm.clustersSnapshot()

        // ClusterManager.get_valid_speakers() / total_valid_time
        val validClusters = clusters.values.filter {
            !it.isUnlabeled && !it.isSilence && it.totalTime > 0.0
        }
        val totalValidSec = validClusters.sumOf { it.totalTime }

        val activeId = SpeakerLiveSession.get(ctx).currentSpeakerId.value

        val rows = validClusters.map { cluster ->
            val perc =
                if (totalValidSec > 0.0) cluster.totalTime / totalValidSec * 100.0 else 0.0
            ArenaRow(
                sidStr = if (cluster.isTarget) {
                    getString(R.string.speakerid_physics_row_target)
                } else {
                    getString(R.string.speakerid_physics_row_speaker_fmt, cluster.id)
                },
                timeStr = String.format(Locale.US, "%.1fs", cluster.totalTime),
                color = SpeakerIdTheme.speakerColor(ctx, cluster.id),
                isTarget = cluster.isTarget,
                percentage = perc.toFloat(),
                isActive = cluster.id == activeId,
            )
        }.sortedWith(compareBy({ !it.isTarget }, { -it.percentage }))

        // f"{int(root.num_speakers)} Personen identifiziert"
        b.labelPersons.text = getString(R.string.speakerid_physics_persons_fmt, rows.size)
        adapter.submit(rows)

        // arena.sync_with_clusters(clusters)
        b.bubbleArena.syncWithClusters(clusters, activeId)
    }

    /** Kivy-StringProperties `total_time_str` / `status_text` (Init in onViewCreated). */
    private var totalTimeStr = ""
    private var statusText = ""

    /** Port von `_sync_ui`: Laufzeit = Summe aller Cluster-Zeiten. */
    private fun syncUi() {
        val b = _binding ?: return
        val totalSec = dm.clustersSnapshot().values.sumOf { it.totalTime }
        totalTimeStr = getString(R.string.speakerid_physics_runtime_fmt, formatTime(totalSec))
        b.labelTimeStatus.text = "$totalTimeStr\n$statusText"
    }

    // ── Aktionen ─────────────────────────────────────────────────────────────

    /**
     * START/PAUSE: vor einem Start mit ECHTEM Mikrofon erst die
     * RECORD_AUDIO-Permission sicherstellen (Stopp und Datei-Simulation
     * brauchen keine); Original-Pendant ist `root.toggle_live()`.
     */
    private fun onToggleClicked() {
        val ctx = requireContext()
        val controller = SpeakerLiveSession.get(ctx)
        val wantsMicStart =
            !controller.isLiveProcessing.value && !dm.useVirtualMic.value
        if (wantsMicStart && !hasRecordPermission()) {
            recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            toggleLive()
        }
    }

    /** `app.live_controller.toggle_live()` + Hinweis, falls der Start scheitert. */
    private fun toggleLive() {
        SpeakerLiveSession.toggleLive(requireContext()) { failure ->
            if (_binding == null) return@toggleLive
            val msg = when (failure) {
                SpeakerSessionController.StartFailure.MIC_PERMISSION_MISSING ->
                    getString(R.string.speakerid_physics_mic_permission_denied)
                SpeakerSessionController.StartFailure.SIM_FILE_MISSING ->
                    getString(R.string.speakerid_physics_sim_file_missing)
                else -> getString(R.string.speakerid_physics_start_failed)
            }
            showStartHint(msg)
        }
    }

    private fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    /** Sichtbarer deutscher Hinweis statt des stillen Python-Konsolen-Logs. */
    private fun showStartHint(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    /** Port von `finish_analysis`: Live-Session beenden, zur Statistik. */
    private fun finishAnalysis() {
        SpeakerLiveSession.stopIfRunning()
        findNavController().navigate(R.id.speakerStatisticsFragment)
    }

    /** Port von `go_back`: Session abbrechen und zum Hauptmenue. */
    private fun goBack() {
        SpeakerLiveSession.stopIfRunning()
        val nav = findNavController()
        if (!nav.popBackStack(R.id.speakeridMenuFragment, false)) {
            nav.popBackStack()
        }
    }

    // ── UI-Helfer ────────────────────────────────────────────────────────────

    /**
     * Responsive Schriftklammern aus dem .kv:
     *  - START/PAUSE      min(sp(16), w*0.045)
     *  - "AUSWERTUNG →"   min(sp(13), w*0.036)
     */
    private fun applyResponsiveSizes(widthPx: Int) {
        val b = _binding ?: return
        val w = widthPx.toFloat()
        b.btnToggle.setTextSize(TypedValue.COMPLEX_UNIT_PX, min(spPx(16f), w * 0.045f))
        b.btnEval.setTextSize(TypedValue.COMPLEX_UNIT_PX, min(spPx(13f), w * 0.036f))
    }

    private fun spPx(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    /** Port von `library/calculations/time_helpers.py::format_time` (MM:SS). */
    private fun formatTime(seconds: Double): String {
        val m = (seconds / 60).toInt()
        val s = (seconds % 60).toInt()
        return String.format(Locale.US, "%02d:%02d", m, s)
    }

    override fun onDestroyView() {
        binding.arenaTable.adapter = null
        lastLayoutWidth = 0
        _binding = null
        super.onDestroyView()
    }
}
