package com.wynh.appapp.bridge

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.wynh.appapp.workspace.SubappRepository

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
        val subappId = intent.getStringExtra(EXTRA_SUBAPP_ID)
            ?: SubappRepository(context).ensureMigrated().firstOrNull()?.id
            ?: return
        val title = intent.getStringExtra(EXTRA_TITLE) ?: return
        val message = intent.getStringExtra(EXTRA_MESSAGE).orEmpty()

        // The alarm has fired: it is no longer pending, whether or not we can post it.
        NativeStorageBridge.forgetPersistedAlarm(context, subappId, id)

        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        NativeStorageBridge.ensureNotificationChannel(context)

        val launchIntent = Intent(context, com.wynh.appapp.SubappRuntimeActivity::class.java).apply {
            data = android.net.Uri.parse("appapp://subapp/$subappId")
            action = Intent.ACTION_VIEW
        }
        val contentIntent = PendingIntent.getActivity(
                context,
                NativeStorageBridge.scopedId(subappId, id),
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

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
            NotificationManagerCompat.from(context).notify(subappId, id, notification)
            val prefs = context.getSharedPreferences("runtime_notifications_$subappId", Context.MODE_PRIVATE)
            prefs.edit().putStringSet("ids", prefs.getStringSet("ids", emptySet()).orEmpty() + id.toString()).apply()
        } catch (_: SecurityException) {
            // Permission revoked between scheduling and firing — nothing to do.
        }
    }

    companion object {
        const val EXTRA_ID = "scheduled_notification_id"
        const val EXTRA_SUBAPP_ID = "scheduled_notification_subapp_id"
        const val EXTRA_TITLE = "scheduled_notification_title"
        const val EXTRA_MESSAGE = "scheduled_notification_message"
    }
}
