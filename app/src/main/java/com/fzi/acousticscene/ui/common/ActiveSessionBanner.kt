package com.fzi.acousticscene.ui.common

import android.view.View
import android.widget.TextView
import com.fzi.acousticscene.R
import com.fzi.acousticscene.model.RecordingCategory
import com.fzi.acousticscene.util.stripModelSuffix
import java.util.Locale

/**
 * Renders the "session is running" banner shown on the hub screens (Welcome and
 * Test Welcome) while a recording runs in the background. Both hubs share this so
 * the card reads identically wherever it shows up.
 *
 * Visibility is decided by the caller from [com.fzi.acousticscene.data.ActiveSessionRegistry];
 * the copy comes from the live [UiState]. When a session is active but the UI
 * state hasn't filled its [UiState.sessionConfig] yet (brief window right after
 * launch), we still show the card with a neutral fallback so it never flickers.
 */
object ActiveSessionBanner {

    /**
     * @param bannerRoot the included banner view (root of view_active_session_banner)
     * @param isActive   whether a session is registered as running
     * @param state      the live recording UI state (for mode / model / elapsed)
     */
    fun bind(bannerRoot: View, isActive: Boolean, state: UiState) {
        if (!isActive) {
            bannerRoot.visibility = View.GONE
            return
        }
        bannerRoot.visibility = View.VISIBLE

        val primary = bannerRoot.findViewById<TextView>(R.id.activeBannerPrimary)
        val secondary = bannerRoot.findViewById<TextView>(R.id.activeBannerSecondary)
        val config = state.sessionConfig

        primary.text = bannerRoot.context.getString(
            when (config?.category) {
                RecordingCategory.INTERVAL -> R.string.active_banner_interval
                else -> R.string.active_banner_continuous
            }
        )

        val modelShort = modelShortLabel(bannerRoot, config?.modelNames.orEmpty())
        val elapsed = formatElapsed(state.sessionElapsedMs)
        secondary.text = if (modelShort.isEmpty()) {
            elapsed
        } else {
            bannerRoot.context.getString(R.string.active_banner_secondary, modelShort, elapsed)
        }
    }

    /**
     * "model" for a single model, "model +2" for three. Strips the .pt suffix so
     * it matches the filenames shown everywhere else. Empty when no models are
     * known yet.
     */
    private fun modelShortLabel(bannerRoot: View, modelNames: List<String>): String {
        val first = modelNames.firstOrNull()?.stripModelSuffix() ?: return ""
        val extra = modelNames.size - 1
        return if (extra > 0) {
            bannerRoot.context.getString(R.string.active_banner_model_extra, first, extra)
        } else {
            first
        }
    }

    /** mm:ss, rolling over to h:mm:ss past an hour. */
    private fun formatElapsed(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
        val s = (totalSeconds % 60).toInt()
        val m = ((totalSeconds / 60) % 60).toInt()
        val h = (totalSeconds / 3600).toInt()
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }
}
