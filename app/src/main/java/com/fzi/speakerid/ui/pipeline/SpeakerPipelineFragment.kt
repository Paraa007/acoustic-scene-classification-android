package com.fzi.speakerid.ui.pipeline

import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.fzi.acousticscene.R
import com.fzi.acousticscene.databinding.FragmentSpeakeridPipelineBinding
import com.fzi.speakerid.ui.SpeakerIdDataManager
import com.fzi.speakerid.ui.explorer.SpeakerExplorerFragment
import com.fzi.speakerid.ui.widgets.SpeakerIdExpertTabBar
import java.io.File
import java.util.Locale
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Port von siqas `gui/screens/pipeline/pipeline.py` + `pipeline.kv`
 * (<PipelineScreen>): Offline-Datei-Analyse — steuert den
 * [SpeakerPipelineController] und zeigt den Fortschritt.
 *
 * Bindings 1:1 wie im Original (`on_enter_screen`):
 *  - controller.bind(is_auto_running/...)        -> StateFlow-Collects
 *  - bind_to('chunk_history', ...)               -> [SpeakerIdDataManager.clustersVersion]
 *  - bind_to('current_audio_path', ...)          -> [SpeakerIdDataManager.currentAudioPath]
 *  - on_enter ruft `_refresh_file_info` + initiale History — hier die
 *    Initial-Emissionen der Collects in `repeatOnLifecycle(STARTED)`.
 *
 * `on_leave` (unbind_all) = Collects enden bei STOPPED; der Controller laeuft
 * wie in Kivy im Hintergrund weiter (Prozess-Singleton).
 *
 * Navigation: `jump_to_explorer` -> speakeridExplorerFragment (Explorer-
 * Default-`selection_mode` ist "analysis"); Hardware-Back = Standard-Back.
 */
class SpeakerPipelineFragment : Fragment() {

    private var _binding: FragmentSpeakeridPipelineBinding? = null
    private val binding get() = _binding!!

    private lateinit var dm: SpeakerIdDataManager
    private lateinit var controller: SpeakerPipelineController

    /** `has_file` / `is_running` (Screen-Properties). */
    private var hasFile = false
    private var isRunning = false
    private var lastLayoutWidth = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSpeakeridPipelineBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dm = SpeakerIdDataManager.getInstance(requireContext().applicationContext)
        controller = SpeakerPipelineController.getInstance(requireContext().applicationContext)

        // ── Experten-Tab-Leiste (main.kv tab_bar_container) ─────────────────
        binding.speakeridPipelineTabBar.apply {
            // kv: state 'down' wenn screen_manager.current == 'pipeline'
            setActiveTab(SpeakerIdExpertTabBar.Tab.PIPELINE)
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
                    SpeakerIdExpertTabBar.Tab.EMBEDDINGS ->
                        nav.navigate(R.id.speakeridEmbeddingsFragment)
                    SpeakerIdExpertTabBar.Tab.PERFORMANCE ->
                        nav.navigate(R.id.speakeridPerformanceTestFragment)
                    SpeakerIdExpertTabBar.Tab.SETTINGS ->
                        nav.navigate(R.id.speakeridExpertSettingsFragment)
                    SpeakerIdExpertTabBar.Tab.PIPELINE -> Unit
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

        // ── Steuerung (on_release-Handler aus dem .kv) ───────────────────────

        // "Datei" -> jump_to_explorer() (selection_mode "analysis" = Default)
        binding.speakeridPipelineBtnFile.setOnClickListener {
            findNavController().navigate(R.id.speakeridExplorerFragment)
        }

        // "Reset" -> reset_pipeline(): controller.reset(); dm.reset();
        // segment_data = []; _refresh_file_info() — in dieser Reihenfolge.
        binding.speakeridPipelineBtnReset.setOnClickListener {
            val resetJob = controller.reset()
            viewLifecycleOwner.lifecycleScope.launch {
                resetJob.join()
                // dm.reset() laedt u. a. den Target-Cache -> nicht auf den Main-Thread
                launch(Dispatchers.Default) { dm.reset() }.join()
                binding.speakeridPipelineWaveform.segmentData = emptyList()
                refreshFileInfo()
            }
        }

        // "1 Schritt" -> execute_next_step()
        binding.speakeridPipelineBtnStep.setOnClickListener {
            controller.requestNextStep()
        }

        // "PAUSE"/"Analyse starten" -> toggle_auto()
        binding.speakeridPipelineBtnAuto.setOnClickListener {
            if (controller.isAutoRunning.value) {
                controller.stopAuto()
            } else {
                // Frischen Start erzwingen, wenn Pipeline noch nicht geladen hat
                if (controller.currentChunkIdx.value == 0 && controller.currentState.value == 0) {
                    controller.reset()
                }
                controller.startAuto()
            }
        }

        // ── Responsive Schriften/Logo (kv: min(sp(x), root.width * f)) ───────
        binding.root.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val width = right - left
            if (width > 0 && width != lastLayoutWidth) {
                lastLayoutWidth = width
                applyResponsiveSizes(width.toFloat())
            }
        }

        // ── Bindings (on_enter_screen) ───────────────────────────────────────
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // bind_to('current_audio_path') + initiales _refresh_file_info()
                launch {
                    dm.currentAudioPath.collect { refreshFileInfo() }
                }
                // bind_to('chunk_history') + initiales _on_history_changed()
                launch {
                    dm.clustersVersion.collect {
                        binding.speakeridPipelineWaveform.segmentData = dm.chunkHistorySnapshot()
                    }
                }
                // controller.bind(is_auto_running=...)
                launch {
                    controller.isAutoRunning.collect {
                        isRunning = it
                        renderAutoButton()
                        renderButtonStates()
                    }
                }
                // controller.bind(current_chunk_idx/total_chunks/status_text=_update_progress)
                // Kivy-Binds feuern erst bei Aenderung -> drop(1): beim Betreten
                // bestimmt _refresh_file_info den Fortschritts-Text.
                launch {
                    combine(
                        controller.currentChunkIdx,
                        controller.totalChunks,
                        controller.statusText,
                    ) { c, t, status -> Triple(c, t, status) }
                        .drop(1)
                        .collect { (c, t, status) -> updateProgress(c, t, status) }
                }
                // controller.bind(current_sub_state/last_vad_result/
                //                 last_vector_preview/last_matched_id=_update_step_detail)
                launch {
                    combine(
                        controller.currentSubState,
                        controller.lastVadResult,
                        controller.lastVectorPreview,
                        controller.lastMatchedId,
                    ) { sub, vad, preview, matched ->
                        StepDetail(sub, vad, preview, matched)
                    }.collect { updateStepDetail(it) }
                }
            }
        }
    }

    // ── Port von `_refresh_file_info` ────────────────────────────────────────

    private fun refreshFileInfo() {
        val b = _binding ?: return
        val path = dm.currentAudioPath.value
        if (path.isNotEmpty() && File(path).exists()) {
            b.speakeridPipelineLabelFileName.text = File(path).name
            hasFile = true
            if (controller.totalChunks.value > 0) {
                b.speakeridPipelineLabelProgress.text = getString(
                    R.string.speakerid_pipeline_progress_chunks,
                    controller.currentChunkIdx.value,
                    controller.totalChunks.value,
                )
            } else {
                b.speakeridPipelineLabelProgress.text =
                    getString(R.string.speakerid_pipeline_progress_ready_analysis)
            }
        } else {
            b.speakeridPipelineLabelFileName.text =
                getString(R.string.speakerid_pipeline_pick_file)
            hasFile = false
            b.speakeridPipelineLabelProgress.text =
                getString(R.string.speakerid_pipeline_progress_dash)
        }
        // kv: color primary if root.has_file else error
        b.speakeridPipelineLabelFileName.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (hasFile) R.color.speakerid_primary else R.color.speakerid_error,
            )
        )
        renderButtonStates()
    }

    // ── Port von `_update_progress` ──────────────────────────────────────────

    private fun updateProgress(c: Int, t: Int, status: String) {
        val b = _binding ?: return
        b.speakeridPipelineLabelProgress.text = if (t > 0) {
            getString(R.string.speakerid_pipeline_progress_status, c, t, status)
        } else {
            status
        }
    }

    // ── Port von `_update_step_detail` ───────────────────────────────────────

    private data class StepDetail(
        val sub: Int,
        val vad: Double,
        val preview: String,
        val matched: String,
    )

    private fun updateStepDetail(d: StepDetail) {
        val b = _binding ?: return

        // Kivy-Binds feuern erst bei Aenderung: im unberuehrten Zustand bleibt
        // step_detail_text leer -> "—  Warte auf ersten Schritt" (kv-Fallback).
        val pristine = controller.currentState.value == 0 &&
            controller.currentChunkIdx.value == 0 &&
            d.sub == 0 && d.preview.isEmpty() && d.matched.isEmpty()
        if (pristine) {
            b.speakeridPipelineLabelStepDetail.text =
                getString(R.string.speakerid_pipeline_step_waiting)
            b.speakeridPipelineLabelStepDetail.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.speakerid_text_secondary)
            )
            return
        }

        val idx = controller.currentChunkIdx.value
        val stepName = when (d.sub) {
            0 -> getString(R.string.speakerid_pipeline_step_name_vad)
            1 -> getString(R.string.speakerid_pipeline_step_name_embedding)
            2 -> getString(R.string.speakerid_pipeline_step_name_matching)
            3 -> getString(R.string.speakerid_pipeline_step_name_saving)
            else -> getString(R.string.speakerid_pipeline_step_name_none)
        }
        val detail = when (d.sub) {
            0 -> getString(R.string.speakerid_pipeline_detail_vad_running)
            1 -> getString(
                R.string.speakerid_pipeline_detail_embedding,
                String.format(Locale.US, "%.2f", d.vad),
            )
            2 -> getString(R.string.speakerid_pipeline_detail_matching, d.preview)
            else -> getString(R.string.speakerid_pipeline_detail_saving, d.matched)
        }
        b.speakeridPipelineLabelStepDetail.text =
            getString(R.string.speakerid_pipeline_step_detail, idx, stepName, detail)
        // kv: color primary if root.step_detail_text else text_secondary
        b.speakeridPipelineLabelStepDetail.setTextColor(
            ContextCompat.getColor(requireContext(), R.color.speakerid_primary)
        )
    }

    // ── kv-Bindings an has_file / is_running ─────────────────────────────────

    /** "PAUSE"/error wenn is_running, sonst "Analyse starten"/primary. */
    private fun renderAutoButton() {
        val b = _binding ?: return
        b.speakeridPipelineBtnAuto.setText(
            if (isRunning) R.string.speakerid_pipeline_btn_pause
            else R.string.speakerid_pipeline_btn_start
        )
        b.speakeridPipelineBtnAuto.setBackgroundResource(
            if (isRunning) R.drawable.speakerid_pipeline_btn_pause
            else R.drawable.speakerid_pipeline_btn_start
        )
    }

    /** "1 Schritt": disabled wenn !has_file || is_running; Auto: !has_file. */
    private fun renderButtonStates() {
        val b = _binding ?: return
        b.speakeridPipelineBtnStep.isEnabled = hasFile && !isRunning
        b.speakeridPipelineBtnAuto.isEnabled = hasFile
    }

    // ── Responsive Groessen aus dem .kv ──────────────────────────────────────

    private fun applyResponsiveSizes(w: Float) {
        val b = _binding ?: return
        // Titel: min(sp(20), root.width * 0.055)
        b.speakeridPipelineTitle.setTextSize(
            TypedValue.COMPLEX_UNIT_PX, min(spPx(20f), w * 0.055f)
        )
        // LogoBar: logo_width min(dp(90), root.width * 0.2)
        b.speakeridPipelineLogoBar.logoWidth = min(dpPx(90f), w * 0.2f).toInt()
        // Zeile-1-Buttons: min(sp(12), root.width * 0.033)
        val smallPx = min(spPx(12f), w * 0.033f)
        listOf<Button>(
            b.speakeridPipelineBtnFile,
            b.speakeridPipelineBtnReset,
            b.speakeridPipelineBtnStep,
        ).forEach { it.setTextSize(TypedValue.COMPLEX_UNIT_PX, smallPx) }
        // Auto-Button: min(sp(15), root.width * 0.042)
        b.speakeridPipelineBtnAuto.setTextSize(
            TypedValue.COMPLEX_UNIT_PX, min(spPx(15f), w * 0.042f)
        )
    }

    private fun spPx(v: Float): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics
        )

    private fun dpPx(v: Float): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
        )

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
