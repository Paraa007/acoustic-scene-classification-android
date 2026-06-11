package com.fzi.speakerid.ui.info

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.fzi.acousticscene.R
import com.fzi.acousticscene.databinding.FragmentSpeakeridInfoBinding
import org.json.JSONObject
import java.io.IOException

/**
 * 1:1-Port von siqas `gui/screens/info/info.py::InfoScreen` (+ `info.kv`):
 * statischer Info-Screen mit Logos, Versions-/Datums-Zeile, scrollbarem
 * Beschreibungstext und ausklappbarem QR-Code.
 *
 * Logik wie im Original:
 *  - `load_info()`: liest optional `assets/data/app_info.json`
 *    (title/versionsnummer/datum/text); fehlt die Datei - wie im siqas-Repo -
 *    bleiben die StringProperty-Defaults aktiv. Hier: App-Asset
 *    `speakerid/data/app_info.json`.
 *  - `toggle_qr()`: klappt das QR-Panel ein/aus, Button-Pfeil ▶/▼.
 *  - `go_back()`: `manager.current = "main_menu"` -> Standard-Back.
 *
 * Kein on_enter/on_leave im Original, daher kein onResume/onPause-Code.
 * `qr_visible` ueberlebt Rotation via savedInstanceState (in Kivy lebt die
 * Screen-Instanz dauerhaft).
 */
class SpeakerInfoFragment : Fragment(R.layout.fragment_speakerid_info) {

    // StringProperty-Defaults aus info.py
    private var title: String = ""
    private var versionsnummer: String = ""
    private var datum: String = ""
    private var text: String = ""

    // BooleanProperty qr_visible = False
    private var qrVisible: Boolean = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentSpeakeridInfoBinding.bind(view)

        title = getString(R.string.speakerid_info_title)
        versionsnummer = getString(R.string.speakerid_info_version_default)
        datum = getString(R.string.speakerid_info_date_default)
        text = getString(R.string.speakerid_info_text_default)
        loadInfo()

        qrVisible = savedInstanceState?.getBoolean(KEY_QR_VISIBLE, false) ?: false

        // Kivy-Bindings: root.title / "Version: "+... / "Stand: "+... / root.text
        binding.speakeridInfoTitle.text = title
        binding.speakeridInfoVersionLabel.text =
            getString(R.string.speakerid_info_version_fmt, versionsnummer)
        binding.speakeridInfoDateLabel.text =
            getString(R.string.speakerid_info_date_fmt, datum)
        binding.speakeridInfoText.text = text

        // on_release: root.go_back()
        binding.speakeridInfoBackButton.setOnClickListener {
            findNavController().popBackStack()
        }

        // on_release: root.toggle_qr()
        binding.speakeridInfoQrButton.setOnClickListener {
            qrVisible = !qrVisible
            applyQrState(binding)
        }
        applyQrState(binding)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_QR_VISIBLE, qrVisible)
    }

    /**
     * Port von `InfoScreen.load_info()`: app_info.json lesen, fehlende Keys
     * behalten ihre Defaults (`data.get(key, default)` -> `optString`).
     */
    private fun loadInfo() {
        try {
            val raw = requireContext().assets.open(INFO_ASSET_PATH)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            val data = JSONObject(raw)
            title = data.optString("title", title)
            versionsnummer = data.optString("versionsnummer", versionsnummer)
            datum = data.optString("datum", datum)
            text = data.optString("text", text)
        } catch (e: IOException) {
            // Original: print("app_info.json nicht gefunden unter: ...")
            Log.i(TAG, "app_info.json nicht gefunden unter: $INFO_ASSET_PATH")
        } catch (e: Exception) {
            // Original: print("Fehler beim Laden von app_info.json: ...")
            Log.w(TAG, "Fehler beim Laden von app_info.json: $e")
        }
    }

    /** Port von `toggle_qr()` + den qr_visible-Bindings im .kv. */
    private fun applyQrState(binding: FragmentSpeakeridInfoBinding) {
        binding.speakeridInfoQrButton.text = getString(
            if (qrVisible) {
                R.string.speakerid_info_qr_expanded
            } else {
                R.string.speakerid_info_qr_collapsed
            },
        )
        // height: dp(180) if qr_visible else 0 / opacity / disabled
        binding.speakeridInfoQrPanel.visibility =
            if (qrVisible) View.VISIBLE else View.GONE
    }

    private companion object {
        const val TAG = "SpeakerInfoFragment"
        const val INFO_ASSET_PATH = "speakerid/data/app_info.json"
        const val KEY_QR_VISIBLE = "speakerid_info_qr_visible"
    }
}
