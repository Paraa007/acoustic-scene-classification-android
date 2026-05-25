package com.fzi.acousticscene.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.fzi.acousticscene.R
import com.fzi.acousticscene.data.LastConfigStore
import com.fzi.acousticscene.model.ModelConfig
import com.fzi.acousticscene.model.WizardIntent
import com.google.android.material.button.MaterialButton

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
