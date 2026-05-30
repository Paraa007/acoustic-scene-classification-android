package com.fzi.acousticscene.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.fzi.acousticscene.R
import com.fzi.acousticscene.data.LastConfigStore
import com.fzi.acousticscene.data.RecordingEngineHolder
import com.fzi.acousticscene.model.ModelConfig
import com.fzi.acousticscene.model.WizardIntent
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Configuration mode home — the developer's hub. Hosts five buttons:
 *
 * 1. Start new session — wizard with [WizardIntent.StartRecording] intent
 * 2. Quick Start (visible only when a saved last config is still executable)
 * 3. History
 * 4. Settings
 * 5. Configure test mode — wizard with [WizardIntent.SaveAsSlot] intent
 *
 * Buttons 1, 2 and 5 all run the same wizard; the intent decides the Summary
 * CTA and exit behaviour.
 */
class WelcomeFragment : Fragment(R.layout.fragment_welcome) {

    private val viewModel: MainViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Back row (chevron + "Back" label) returns to Mode Select. System back
        // is mirrored via OnBackPressedCallback so the hardware key matches the
        // visible affordance.
        view.findViewById<LinearLayout>(R.id.welcomeBackRow).setOnClickListener {
            findNavController().popBackStack()
        }
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    findNavController().popBackStack()
                }
            }
        )

        val btnNewSession = view.findViewById<MaterialButton>(R.id.btnNewSession)
        val btnQuickStart = view.findViewById<MaterialButton>(R.id.btnQuickStart)
        val btnHistory = view.findViewById<MaterialButton>(R.id.btnHistory)
        val btnSettings = view.findViewById<MaterialButton>(R.id.btnSettings)
        val btnConfigureTestMode = view.findViewById<LinearLayout>(R.id.btnConfigureTestMode)

        btnNewSession.setOnClickListener {
            val available = listAvailableModels()
            if (available.isEmpty()) {
                Toast.makeText(requireContext(), R.string.welcome_no_models, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            viewModel.resetWizard(
                availableModels = available,
                prefill = null,
                intent = WizardIntent.StartRecording
            )
            findNavController().navigate(R.id.action_welcome_to_wizard)
        }

        val lastConfig = LastConfigStore.load(requireContext())
        val available = listAvailableModels()
        if (lastConfig != null && lastConfig.isExecutable(available)) {
            btnQuickStart.visibility = View.VISIBLE
            btnQuickStart.setOnClickListener {
                viewModel.resetWizard(
                    availableModels = available,
                    prefill = lastConfig,
                    intent = WizardIntent.QuickStart
                )
                findNavController().navigate(R.id.action_welcome_to_wizard)
            }
        } else {
            btnQuickStart.visibility = View.GONE
        }

        btnHistory.setOnClickListener {
            startActivity(Intent(requireContext(), HistoryActivity::class.java))
        }

        btnSettings.setOnClickListener {
            findNavController().navigate(R.id.action_welcome_to_settings)
        }

        btnConfigureTestMode.setOnClickListener {
            val avail = listAvailableModels()
            if (avail.isEmpty()) {
                Toast.makeText(requireContext(), R.string.welcome_no_models, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            viewModel.resetWizard(
                availableModels = avail,
                prefill = null,
                intent = WizardIntent.SaveAsSlot
            )
            findNavController().navigate(R.id.action_welcome_to_wizard)
        }

        setupActiveSessionBanner(view)
    }

    /**
     * Shows a "session is running" card between the title and the CTA whenever a
     * recording is live in the background, and re-attaches to the live screen on
     * tap. Visibility is driven by [ActiveSessionRegistry], the copy (mode, model,
     * elapsed) by [RecordingEngineHolder.uiState] — combined so a single collector
     * keeps both in sync.
     */
    private fun setupActiveSessionBanner(view: View) {
        val banner = view.findViewById<View>(R.id.welcomeActiveBanner)
        banner.setOnClickListener {
            findNavController().navigate(
                R.id.action_welcome_to_live,
                bundleOf(LiveRecordingFragment.ARG_REENTRY to true)
            )
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                com.fzi.acousticscene.data.ActiveSessionRegistry.active
                    .combine(RecordingEngineHolder.uiState) { entry, ui -> entry to ui }
                    .collect { (entry, ui) ->
                        ActiveSessionBanner.bind(banner, entry != null, ui)
                    }
            }
        }
    }

    private fun listAvailableModels(): List<String> {
        return try {
            requireContext().assets.list(ModelConfig.DEV_MODELS_DIR)
                ?.filter { it.endsWith(".pt") }
                ?.sorted()
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
