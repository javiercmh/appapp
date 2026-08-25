package com.example.runtimecompiler.bridge

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Posts a notification that was scheduled earlier through
 * `window.AndroidNotification.scheduleNotification(...)`.
 *
 * The alarm may wake a cold process in which `MainActivity` never ran, so this
 * receiver must not assume the notification channel already exists.
 */
class ScheduledNotificationReceiver : BroadcastReceiver() {

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, 0)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: return
        val message = intent.getStringExtra(EXTRA_MESSAGE).orEmpty()

        // The alarm has fired: it is no longer pending, whether or not we can post it.
        NativeStorageBridge.forgetPersistedAlarm(context, id)

        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        NativeStorageBridge.ensureNotificationChannel(context)

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = if (launchIntent != null) {
            PendingIntent.getActivity(
                context,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        val notification = NotificationCompat.Builder(context, NativeStorageBridge.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .apply { if (contentIntent != null) setContentIntent(contentIntent) }
            .build()

        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // Permission revoked between scheduling and firing — nothing to do.
        }
    }

    companion object {
        const val EXTRA_ID = "scheduled_notification_id"
        const val EXTRA_TITLE = "scheduled_notification_title"
        const val EXTRA_MESSAGE = "scheduled_notification_message"
    }
}
