package com.fzi.acousticscene.ui.entry

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.fzi.acousticscene.R

/**
 * Start destination of the nav graph (v2). Asks the user which mode they want
 * to operate the app in — Test mode (open, runs a saved quickstart) or
 * Configuration mode (gated by a developer password).
 *
 * Picking is part of the entry flow on every app start: we deliberately don't
 * remember the last mode. The two destinations are exclusive — back-button
 * from either of them returns here.
 */
class ModeSelectFragment : Fragment(R.layout.fragment_mode_select) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<LinearLayout>(R.id.modeTestTile).setOnClickListener {
            findNavController().navigate(R.id.action_mode_to_test_welcome)
        }
        view.findViewById<LinearLayout>(R.id.modeConfigTile).setOnClickListener {
            findNavController().navigate(R.id.action_mode_to_password)
        }
    }
}
