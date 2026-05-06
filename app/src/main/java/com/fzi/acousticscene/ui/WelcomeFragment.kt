package com.fzi.acousticscene.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.fzi.acousticscene.R
import com.fzi.acousticscene.data.LastConfigStore
import com.fzi.acousticscene.model.ModelConfig
import com.fzi.acousticscene.util.ThemeHelper
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Home page. Lists the four entry points into the app:
 * - Neue Session starten — opens the wizard fresh
 * - Letzte Config nutzen — only shown if a previous session was confirmed; opens the wizard
 *   prefilled with that config so the user can tweak before starting (or hit Start on the
 *   summary right away)
 * - History
 * - Settings
 *
 * Replaces the old WelcomeActivity (mode picker) entirely.
 */
class WelcomeFragment : Fragment(R.layout.fragment_welcome) {

    private val viewModel: MainViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupThemeToggle(view)

        val btnNewSession = view.findViewById<MaterialButton>(R.id.btnNewSession)
        val btnLastConfig = view.findViewById<MaterialButton>(R.id.btnLastConfig)
        val lastConfigLabel = view.findViewById<TextView>(R.id.lastConfigLabel)
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
        if (lastConfig != null && lastConfig.modelNames.isNotEmpty()) {
            btnLastConfig.visibility = View.VISIBLE
            lastConfigLabel.visibility = View.VISIBLE
            lastConfigLabel.text = lastConfig.shortLabel()
            btnLastConfig.setOnClickListener {
                val available = listAvailableModels()
                viewModel.resetWizard(availableModels = available, prefill = lastConfig)
                findNavController().navigate(R.id.action_welcome_to_wizard)
            }
        } else {
            btnLastConfig.visibility = View.GONE
            lastConfigLabel.visibility = View.GONE
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

    private fun setupThemeToggle(root: View) {
        val themeSwitch: MaterialSwitch = root.findViewById(R.id.themeSwitch)
        val iconLight: ImageView = root.findViewById(R.id.iconLightMode)
        val iconDark: ImageView = root.findViewById(R.id.iconDarkMode)
        val isDark = ThemeHelper.isDarkMode(requireContext())
        themeSwitch.isChecked = isDark
        iconLight.alpha = if (isDark) 0.4f else 1.0f
        iconDark.alpha = if (isDark) 1.0f else 0.4f
        themeSwitch.setOnCheckedChangeListener { _, checked ->
            iconLight.alpha = if (checked) 0.4f else 1.0f
            iconDark.alpha = if (checked) 1.0f else 0.4f
            ThemeHelper.setDarkMode(requireContext(), checked)
        }
    }
}
