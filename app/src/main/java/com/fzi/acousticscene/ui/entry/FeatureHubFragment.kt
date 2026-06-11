package com.fzi.acousticscene.ui.entry

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.fzi.acousticscene.R

/**
 * Root of the nav graph and first screen of the umbrella app
 * ("FZI Audio Suite"). Offers one tile per feature:
 *
 *  - Acoustic Scene Classification → the existing flow, starting at
 *    [ModeSelectFragment] (unchanged from here on).
 *  - Speaker ID (SIQAS) → placeholder until the port from the standalone
 *    SIQAS app lands ([com.fzi.speakerid.SpeakerIdPlaceholderFragment]).
 *
 * The hub is session-agnostic: it shows no mode badge and reads no session
 * state. System back from here exits the app (hub = back-stack root).
 */
class FeatureHubFragment : Fragment(R.layout.fragment_feature_hub) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<LinearLayout>(R.id.featureAscTile).setOnClickListener {
            findNavController().navigate(R.id.action_hub_to_mode_select)
        }
        view.findViewById<LinearLayout>(R.id.featureSpeakerIdTile).setOnClickListener {
            findNavController().navigate(R.id.action_hub_to_speakerid)
        }
    }
}
