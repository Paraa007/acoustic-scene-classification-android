package com.fzi.acousticscene.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.fzi.acousticscene.R
import com.fzi.acousticscene.data.LastConfigStore
import com.fzi.acousticscene.model.ModelConfig
import com.google.android.material.button.MaterialButton

/**
 * Home page. Lists the four entry points into the app:
 * - Start new session — opens the wizard fresh
 * - Quick Start — shown only when a saved last config exists and is still executable
 *   1:1 against the models currently available. Skips the wizard entirely and lands
 *   on the read-only Summary; one tap on Start kicks off the session
 * - History
 * - Settings
 */
class WelcomeFragment : Fragment(R.layout.fragment_welcome) {

    private val viewModel: MainViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val btnNewSession = view.findViewById<MaterialButton>(R.id.btnNewSession)
        val btnQuickStart = view.findViewById<MaterialButton>(R.id.btnQuickStart)
        val btnHistory = view.findViewById<MaterialButton>(R.id.btnHistory)
        val btnSettings = view.findViewById<MaterialButton>(R.id.btnSettings)

        btnNewSession.setOnClickListener {
            val available = listAvailableModels()
            if (available.isEmpty()) {
                Toast.makeText(requireContext(), R.string.welcome_no_models, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            viewModel.resetWizard(availableModels = available, prefill = null)
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
                    quickStartMode = true
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
