package com.fzi.acousticscene.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.fzi.acousticscene.R
import com.fzi.acousticscene.data.ActiveSessionRegistry
import com.fzi.acousticscene.data.ActiveSessionStore
import com.fzi.acousticscene.ui.MainActivity

/**
 * Shared "study session was interrupted" check, used by both entry points:
 * the BOOT_COMPLETED receiver and the app-launch check in MainActivity. Both
 * only *notify* — actually restarting the microphone foreground service needs
 * a user interaction on Android 11+, and tapping the notification is exactly
 * that.
 *
 * The prompt fires only when [ActiveSessionStore] still holds a snapshot
 * (written exclusively for interval sessions with a calendar end date, cleared
 * on every clean stop), the planned end is still ahead, and nothing is
 * currently recording. Anything ambiguous stays silent.
 */
object SessionRecoveryNotifier {
    private const val TAG = "SessionRecovery"
    private const val CHANNEL_ID = "session_recovery_channel"
    const val NOTIFICATION_ID = 3
    const val EXTRA_RESUME_INTERRUPTED = "com.fzi.acousticscene.RESUME_INTERRUPTED_SESSION"

    /**
     * Posts the recovery notification when an interrupted session is found.
     * Expired snapshots (planned end already passed) are cleared on the spot
     * so they can never prompt later.
     */
    fun maybeNotify(context: Context) {
        val snapshot = ActiveSessionStore.load(context) ?: return
        if (ActiveSessionRegistry.get() != null) return
        if (snapshot.plannedEndMillis <= System.currentTimeMillis()) {
            ActiveSessionStore.clear(context)
            return
        }
        postNotification(context)
    }

    fun cancel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
    }

    private fun postNotification(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.recovery_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.recovery_channel_description)
        }
        nm.createNotificationChannel(channel)

        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_RESUME_INTERRUPTED, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, NOTIFICATION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.recovery_notification_title))
            .setContentText(context.getString(R.string.recovery_notification_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        try {
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS revoked — nothing we can do from here.
            Log.w(TAG, "Recovery notification blocked", e)
        }
    }
}
