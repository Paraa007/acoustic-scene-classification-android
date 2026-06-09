package com.fzi.acousticscene.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After a reboot, checks whether an interval study session was cut off
 * mid-run and, if so, posts the recovery notification. Deliberately does NOT
 * start the recording service itself: microphone foreground services must be
 * user-initiated on Android 11+, so the notification tap (handled in
 * MainActivity) is the actual restart trigger.
 */
class SessionRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        SessionRecoveryNotifier.maybeNotify(context)
    }
}
