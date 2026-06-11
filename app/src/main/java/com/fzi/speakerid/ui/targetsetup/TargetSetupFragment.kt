package com.fzi.speakerid.ui.targetsetup

import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.fzi.acousticscene.R
import com.fzi.acousticscene.databinding.FragmentSpeakeridTargetSetupBinding
import com.fzi.speakerid.ui.SpeakerIdDataManager
import com.fzi.speakerid.ui.explorer.SpeakerExplorerFragment
import kotlin.math.min

/**
 * Port von siqas `gui/screens/target_setup/target_setup.py` (+ .kv):
 * Schritt 1 des Normalbetriebs — den Zielsprecher definieren.
 *
 * Verhalten 1:1 wie das Original:
 *  - `on_pre_enter`/`on_enter` -> `_check_target_status` prueft, ob Cluster "1"
 *    bereits einen Centroid hat, und setzt `is_target_ready` + Status-Text
 *    (gruen "Zielsprecher erfolgreich erfasst!" bzw. rot "Bitte nehmen Sie
 *    Ihre Stimme auf...") -> hier [onResume].
 *  - "Live Aufnehmen (Mikrofon)" -> target_recorder.
 *  - "Audiodatei auswaehlen" -> `go_to_explorer`: Explorer-Screen im Modus
 *    "target" (Welle 2); dessen `confirm_selection` erzeugt den Cluster-"1"-
 *    Centroid (`generate_target_centroid_from_files` + `save_target_cache` +
 *    `target_audio_path`) und springt selbst hierher zurueck — `on_enter`
 *    (onResume) zeigt dann den gruenen Ready-Status.
 *  - "Weiter" (nur aktiv bei is_target_ready) -> physics_arena.
 *  - ModernBackButton unten links -> zurueck (Hauptmenue).
 *  - Responsive Schriftklammern `min(sp(x), root.width * f)` und die Breite
 *    des "Weiter"-Buttons (`min(dp(280), root.width * 0.7)`) werden bei
 *    Layoutaenderung nachgezogen.
 */
class TargetSetupFragment : Fragment() {

    private var _binding: FragmentSpeakeridTargetSetupBinding? = null
    private val binding get() = _binding!!

    private lateinit var dm: SpeakerIdDataManager

    private var lastLayoutWidth = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSpeakeridTargetSetupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dm = SpeakerIdDataManager.getInstance(requireContext())

        binding.recordButton.setOnClickListener { goToRecorder() }
        binding.fileButton.setOnClickListener { goToExplorer() }
        binding.nextButton.setOnClickListener { goNext() }
        binding.backButton.setOnClickListener { findNavController().popBackStack() }

        // Responsive Klammern aus dem .kv: min(sp(x), root.width * f)
        binding.root.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val width = right - left
            if (width > 0 && width != lastLayoutWidth) {
                lastLayoutWidth = width
                applyResponsiveSizes(width)
            }
        }
    }

    /** `on_pre_enter`/`on_enter` -> `_check_target_status`. */
    override fun onResume() {
        super.onResume()
        checkTargetStatus()
    }

    // ── _check_target_status ─────────────────────────────────────────────────

    /** Prueft, ob der Target-Cluster bereits einen Centroid (Vektor) hat. */
    private fun checkTargetStatus() {
        val targetCluster = dm.clusters["1"]
        if (targetCluster?.centroid != null) {
            binding.nextButton.isEnabled = true
            // "[color=00AA00]Zielsprecher erfolgreich erfasst![/color]"
            setStatus(
                getString(R.string.speakerid_target_setup_status_ready),
                Color.parseColor("#00AA00"),
            )
        } else {
            binding.nextButton.isEnabled = false
            // "[color=AA0000]Bitte nehmen Sie Ihre Stimme auf oder laden Sie eine Datei.[/color]"
            setStatus(
                getString(R.string.speakerid_target_setup_status_not_ready),
                Color.parseColor("#AA0000"),
            )
        }
    }

    // ── Navigation (go_to_recorder / go_next) ────────────────────────────────

    /** Port von `go_to_recorder`. */
    private fun goToRecorder() {
        try {
            findNavController().navigate(R.id.targetRecorderFragment)
        } catch (e: Exception) {
            setStatus(
                getString(R.string.speakerid_target_setup_error_recorder),
                Color.parseColor("#AA0000"),
            )
        }
    }

    /**
     * Port von `go_to_explorer`: `explorer.selection_mode = "target"` +
     * Screenwechsel. Der Explorer navigiert nach erfolgreicher Analyse
     * selbst zurueck (popBackStack auf dieses Fragment).
     */
    private fun goToExplorer() {
        try {
            findNavController().navigate(
                R.id.speakeridExplorerFragment,
                bundleOf(
                    SpeakerExplorerFragment.ARG_SELECTION_MODE to
                        SpeakerExplorerFragment.MODE_TARGET,
                ),
            )
        } catch (e: Exception) {
            setStatus(
                getString(R.string.speakerid_target_setup_error_explorer),
                Color.parseColor("#AA0000"),
            )
        }
    }

    /** Port von `go_next`. */
    private fun goNext() {
        if (binding.nextButton.isEnabled) {
            try {
                findNavController().navigate(R.id.physicsArenaFragment)
            } catch (e: Exception) {
                setStatus(
                    getString(R.string.speakerid_target_setup_error_arena),
                    Color.parseColor("#AA0000"),
                )
            }
        } else {
            // (Der Button ist disabled — Paritaets-Port des else-Zweigs.)
            setStatus(
                getString(R.string.speakerid_target_setup_define_first),
                Color.parseColor("#AA0000"),
            )
        }
    }

    // ── UI-Helfer ────────────────────────────────────────────────────────────

    private fun setStatus(text: String, color: Int) {
        val b = _binding ?: return
        b.statusLabel.text = text
        b.statusLabel.setTextColor(color)
    }

    /**
     * Responsive Groessen aus dem .kv:
     *  - Titel        min(sp(22), w*0.06)
     *  - CubeButtons  min(sp(15), w*0.04)
     *  - Status       min(sp(13), w*0.035)
     *  - Weiter       min(sp(15), w*0.042), Breite min(dp(280), w*0.7)
     */
    private fun applyResponsiveSizes(widthPx: Int) {
        val b = _binding ?: return
        val w = widthPx.toFloat()

        b.titleLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, min(spPx(22f), w * 0.06f))
        b.recordButton.setTextSize(TypedValue.COMPLEX_UNIT_PX, min(spPx(15f), w * 0.04f))
        b.fileButton.setTextSize(TypedValue.COMPLEX_UNIT_PX, min(spPx(15f), w * 0.04f))
        b.statusLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, min(spPx(13f), w * 0.035f))
        b.nextButton.setTextSize(TypedValue.COMPLEX_UNIT_PX, min(spPx(15f), w * 0.042f))

        val buttonWidth = min(dpPx(280f), w * 0.7f).toInt()
        if (b.nextButton.layoutParams.width != buttonWidth) {
            b.nextButton.layoutParams = b.nextButton.layoutParams.also { it.width = buttonWidth }
        }
    }

    private fun spPx(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private fun dpPx(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    override fun onDestroyView() {
        lastLayoutWidth = 0
        _binding = null
        super.onDestroyView()
    }
}
