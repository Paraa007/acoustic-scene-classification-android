package com.fzi.acousticscene.ui.common

import android.content.res.ColorStateList
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.fzi.acousticscene.R
import com.fzi.acousticscene.data.RecordingEngineHolder
import com.fzi.acousticscene.model.SessionMode

/**
 * Persistent TEST MODE / CONFIG MODE pill shown top-end on every screen behind
 * the mode picker, so the user can always tell which entry point they are
 * operating in.
 *
 * The mode picker deliberately persists nothing across app starts (see
 * [com.fzi.acousticscene.ui.entry.ModeSelectFragment]), so this object keeps
 * the last hub the user landed on as process-wide in-memory state — the two
 * Welcome hubs call [record] in onViewCreated. [bind] falls back to the running
 * session's [com.fzi.acousticscene.model.SessionConfig.mode] for activities
 * that can be reached straight from a notification after process revival
 * (History, Evaluation). When neither source knows the mode the badge stays
 * hidden rather than guessing.
 */
object ModeBadge {

    @Volatile
    private var lastChosenMode: SessionMode? = null

    /** Called by the two mode hubs (Test Welcome / Config Welcome) on entry. */
    fun record(mode: SessionMode) {
        lastChosenMode = mode
    }

    private fun currentMode(): SessionMode? =
        lastChosenMode ?: RecordingEngineHolder.uiState.value.sessionConfig?.mode

    /**
     * Binds the included `@layout/view_mode_badge` (id `modeBadge`): picks the
     * variant for the current mode, or hides the badge when the mode is
     * unknown. Call from onViewCreated / onCreate; the mode can't change while
     * a screen is alive (the user would have to travel back through a hub),
     * so a one-shot bind is enough.
     */
    fun bind(badge: View?) {
        if (badge == null) return
        val mode = currentMode()
        if (mode == null) {
            badge.visibility = View.GONE
            return
        }
        val label = badge.findViewById<TextView>(R.id.modeBadgeLabel)
        val dot = badge.findViewById<View>(R.id.modeBadgeDot)
        val (backgroundRes, accentRes, textRes) = when (mode) {
            SessionMode.TEST -> Triple(
                R.drawable.bg_mode_badge_test, R.color.status_info, R.string.mode_badge_test
            )
            SessionMode.CONFIG -> Triple(
                R.drawable.bg_mode_badge_config, R.color.accent_green, R.string.mode_badge_config
            )
        }
        val accent = ContextCompat.getColor(badge.context, accentRes)
        badge.setBackgroundResource(backgroundRes)
        label.setText(textRes)
        label.setTextColor(accent)
        dot.backgroundTintList = ColorStateList.valueOf(accent)
        badge.visibility = View.VISIBLE
    }
}
