package com.fzi.speakerid.ui.mainmenu

import android.os.Bundle
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.fzi.acousticscene.R
import com.fzi.acousticscene.databinding.FragmentSpeakeridMainMenuBinding
import com.fzi.speakerid.ui.SpeakerIdDataManager

/**
 * 1:1-Port von siqas `gui/screens/main_menu/main_menu.py::MainMenuScreen`
 * (+ `main_menu.kv`): Einstiegs-Screen des Speaker-ID-Features.
 *
 * Button-Handler wie im .kv:
 *  - "STARTEN"        -> [startNormalFlow] (Reset bei laufender Pipeline/Aufnahme,
 *                        dann Wizard-Start `target_setup`)
 *  - "⚙ Einstellungen" -> `expert_lab_active = False`, Screen `settings`
 *  - "Info"            -> Screen `info`
 *  - "Experten-Labor"  -> `expert_lab_active = True`, Screen `dashboard`
 *
 * Lifecycle: `on_enter_screen` liest im Original nur `dm.clusters["1"]` ohne
 * Wirkung (No-Op) - hier deshalb kein onResume-Code. `go_back` beendet im
 * Original die App; auf Android gilt Standard-Back (zurueck zum Hub).
 */
class SpeakerIdMenuFragment : Fragment(R.layout.fragment_speakerid_main_menu) {

    private val dataManager: SpeakerIdDataManager
        get() = SpeakerIdDataManager.getInstance(requireContext())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentSpeakeridMainMenuBinding.bind(view)

        // Logo-Reihe: .kv `size_hint: (min(0.9, 480 / root.width), 1)` — Kivy
        // rechnet unitless in physischen Pixeln, der Cap ist also 480 px
        // (auf dem Pixel 9 ~183dp), nicht 480dp.
        (binding.speakeridMainMenuLogoRow.layoutParams as ConstraintLayout.LayoutParams)
            .matchConstraintMaxWidth = 480

        // Hero-Button: on_release: root.start_normal_flow()
        binding.speakeridMainMenuStartButton.setOnClickListener { startNormalFlow() }

        // "⚙  Einstellungen": app.expert_lab_active = False; manager.current = 'settings'
        binding.speakeridMainMenuSettingsButton.setOnClickListener {
            dataManager.expertModeActive.value = false
            findNavController().navigate(R.id.speakerSettingsFragment)
        }

        // "Info": manager.current = 'info'
        binding.speakeridMainMenuInfoButton.setOnClickListener {
            findNavController().navigate(R.id.speakerInfoFragment)
        }

        // "Experten-Labor": app.expert_lab_active = True; manager.current = 'dashboard'
        binding.speakeridMainMenuExpertButton.setOnClickListener {
            dataManager.expertModeActive.value = true
            findNavController().navigate(R.id.speakerDashboardFragment)
        }
    }

    /**
     * Port von `MainMenuScreen.start_normal_flow`: startet den gefuehrten
     * Wizard-Ablauf (neue Session). Reset, damit alte Daten nicht stoeren.
     */
    private fun startNormalFlow() {
        val dm = dataManager
        if (dm.isPipelineRunning.value || dm.isRecordingRunning.value) {
            dm.reset()
        }
        // manager.current = 'target_setup'
        findNavController().navigate(R.id.targetSetupFragment)
    }

    /**
     * Port von `MainMenuScreen.resume_session`: springt direkt in die Arena,
     * falls ein Target-Profil (Cluster "1" mit Centroid) existiert, sonst
     * normaler Flow. Im Original-.kv an keinen Button gebunden - nur fuer
     * Verhaltens-Paritaet mitportiert.
     */
    @Suppress("unused")
    private fun resumeSession() {
        val target = dataManager.clusters["1"]
        if (target?.centroid != null) {
            // manager.current = 'physics_arena'
            findNavController().navigate(R.id.physicsArenaFragment)
        } else {
            // Kein Profil -> normaler Flow
            startNormalFlow()
        }
    }
}
