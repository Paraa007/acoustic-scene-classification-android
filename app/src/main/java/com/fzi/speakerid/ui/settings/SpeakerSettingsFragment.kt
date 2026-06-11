package com.fzi.speakerid.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.fzi.acousticscene.R
import com.fzi.acousticscene.databinding.FragmentSpeakeridSettingsBinding
import com.fzi.speakerid.ui.SpeakerIdDataManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Port von siqas `gui/screens/settings/settings.py` + `settings.kv`
 * (<SettingsScreen> mit <SharedSettingsPanel>).
 *
 * Bindings 1:1 wie im .kv (Kivy-Property-Bind = StateFlow-Collect, `on_value`/
 * `on_active` = Rueckschreiben in den DataManager):
 *  - Target-Matching   <-> [SpeakerIdDataManager.thresholdTarget]  (0..1, 0.01)
 *  - Cluster-Matching  <-> [SpeakerIdDataManager.thresholdNormal]  (0..1, 0.01)
 *  - Chunk-Laenge (s)  <-> [SpeakerIdDataManager.chunkDuration]    (0.4..1.5, 0.1)
 *  - Design-Theme      <-> [SpeakerSettingsState.uiTheme] (ThemeManager.ui_theme)
 *  - Simuliertes Mikro <-> [SpeakerIdDataManager.useVirtualMic]
 *  - Stimmentrennung   <-> [SpeakerIdDataManager.usePyannote]
 *  - "Daten Reset"      -> [SpeakerIdDataManager.reset]
 *
 * Persistenz wie siqas: Die Werte leben ausschliesslich im Prozess-Singleton
 * (DataManager/ThemeManager halten Kivy-Properties nur im Speicher, kein
 * JsonStore o. AE.) — daher hier bewusst keine SharedPreferences.
 *
 * Lifecycle: `on_enter`/`on_leave` (BaseScreen bindet/unbindet Observer)
 * entspricht `repeatOnLifecycle(STARTED)`. Hardware-Back (BaseScreen key 27 ->
 * main_menu) = Standard-Back der Navigation. `settings.py` selbst hat keine
 * weitere Logik (set_theme deckt die SegmentedChoice ab).
 */
class SpeakerSettingsFragment : Fragment() {

    private var _binding: FragmentSpeakeridSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSpeakeridSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val dm = SpeakerIdDataManager.getInstance(requireContext().applicationContext)
        val panel = binding.speakeridSettingsSharedPanel

        // ScreenHeader: on_back -> root.manager.current = 'main_menu'
        binding.speakeridSettingsHeader.onBack = { findNavController().popBackStack() }

        // ── Schwellenwert: Target-Matching ──────────────────────────────────
        panel.speakeridSettingsSliderTarget.apply {
            labelText = getString(R.string.speakerid_settings_target_matching)
            accentColor = ContextCompat.getColor(requireContext(), R.color.speakerid_primary)
            minValue = 0.0
            maxValue = 1.0
            step = 0.01
            fmt = "%.2f"
            value = dm.thresholdTarget.value
            onValueChanged = { dm.thresholdTarget.value = it }
        }

        // ── Schwellenwert: Cluster-Matching ─────────────────────────────────
        panel.speakeridSettingsSliderCluster.apply {
            labelText = getString(R.string.speakerid_settings_cluster_matching)
            accentColor = ContextCompat.getColor(requireContext(), R.color.speakerid_secondary)
            minValue = 0.0
            maxValue = 1.0
            step = 0.01
            fmt = "%.2f"
            value = dm.thresholdNormal.value
            onValueChanged = { dm.thresholdNormal.value = it }
        }

        // ── Chunk-Laenge ─────────────────────────────────────────────────────
        panel.speakeridSettingsSliderChunk.apply {
            labelText = getString(R.string.speakerid_settings_chunk_duration)
            accentColor = ContextCompat.getColor(requireContext(), R.color.speakerid_primary)
            minValue = 0.4
            maxValue = 1.5
            step = 0.1
            fmt = "%.1f"
            value = dm.chunkDuration.value
            onValueChanged = { dm.chunkDuration.value = it }
        }

        // ── Design-Theme (options: [("BASIC","Basic"),("FZI","FZI"),("DUNKEL","FZI-Dark")]) ──
        panel.speakeridSettingsChoiceTheme.apply {
            options = listOf(
                getString(R.string.speakerid_settings_theme_basic) to "Basic",
                getString(R.string.speakerid_settings_theme_fzi) to "FZI",
                getString(R.string.speakerid_settings_theme_dark) to "FZI-Dark",
            )
            value = SpeakerSettingsState.uiTheme.value
            onValueChanged = { SpeakerSettingsState.uiTheme.value = it }
        }

        // ── Toggles ──────────────────────────────────────────────────────────
        panel.speakeridSettingsToggleVirtualMic.apply {
            isActive = dm.useVirtualMic.value
            onActiveChanged = { dm.useVirtualMic.value = it }
        }
        panel.speakeridSettingsTogglePyannote.apply {
            isActive = dm.usePyannote.value
            onActiveChanged = { dm.usePyannote.value = it }
        }

        // ── "Daten Reset" (on_release: app.data_manager.reset()) ────────────
        // reset() laedt u. a. den Target-Cache von Platte -> nicht auf den
        // Main-Thread legen (Verhalten identisch, nur ANR-sicher).
        binding.speakeridSettingsBtnReset.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
                dm.reset()
            }
        }

        // ── DataManager -> UI (Kivy-Property-Bindings) ───────────────────────
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    dm.thresholdTarget.collect { panel.speakeridSettingsSliderTarget.value = it }
                }
                launch {
                    dm.thresholdNormal.collect { panel.speakeridSettingsSliderCluster.value = it }
                }
                launch {
                    dm.chunkDuration.collect { panel.speakeridSettingsSliderChunk.value = it }
                }
                launch {
                    dm.useVirtualMic.collect { panel.speakeridSettingsToggleVirtualMic.isActive = it }
                }
                launch {
                    dm.usePyannote.collect { panel.speakeridSettingsTogglePyannote.isActive = it }
                }
                launch {
                    SpeakerSettingsState.uiTheme.collect { panel.speakeridSettingsChoiceTheme.value = it }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
