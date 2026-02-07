package com.fzi.acousticscene

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.fzi.acousticscene.util.ThemeHelper
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * SettingsFragment - App settings including dark mode toggle
 */
class SettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupThemeToggle(view)
        setupVersionInfo(view)
    }

    private fun setupThemeToggle(view: View) {
        val themeSwitch: MaterialSwitch = view.findViewById(R.id.themeSwitch)
        val iconLight: ImageView = view.findViewById(R.id.iconLightMode)
        val iconDark: ImageView = view.findViewById(R.id.iconDarkMode)
        val themeLabel: TextView = view.findViewById(R.id.themeLabel)

        val isDark = ThemeHelper.isDarkMode(requireContext())
        themeSwitch.isChecked = isDark
        updateThemeIcons(iconLight, iconDark, isDark)
        themeLabel.text = if (isDark) getString(R.string.dark_mode) else getString(R.string.light_mode)

        themeSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateThemeIcons(iconLight, iconDark, isChecked)
            themeLabel.text = if (isChecked) getString(R.string.dark_mode) else getString(R.string.light_mode)
            ThemeHelper.setDarkMode(requireContext(), isChecked)
        }
    }

    private fun updateThemeIcons(iconLight: ImageView, iconDark: ImageView, isDarkMode: Boolean) {
        iconLight.alpha = if (isDarkMode) 0.4f else 1.0f
        iconDark.alpha = if (isDarkMode) 1.0f else 0.4f
    }

    private fun setupVersionInfo(view: View) {
        val versionText: TextView = view.findViewById(R.id.versionText)
        try {
            val pInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            versionText.text = pInfo.versionName
        } catch (e: Exception) {
            versionText.text = "1.0"
        }
    }
}
