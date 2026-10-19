package com.wynh.appapp.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Keeps the process alive while any AI request is generating and owns one ongoing notification per
 * unfinished request. Every notification, the foreground state and the decision to stop come from
 * [reconcile], which recomputes them from the request ledger on the main thread, so overlapping
 * starts, completions and cancellations cannot leave a stale or missing notification.
 */
class AiRequestService : Service() {
    private val reconciler = AiNotificationReconciler()
    private var lastStartId = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "AI requests", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val cancelId = intent?.takeIf { it.action == ACTION_CANCEL_REQUEST }?.getStringExtra(EXTRA_REQUEST_ID)
        if (cancelId != null) (application as? AppAiApplication)?.ai?.cancelFromNotification(cancelId)
        // A command from startForegroundService must reach startForeground; a Cancel action need not.
        reconcile(mustEnterForeground = cancelId == null)
        return START_NOT_STICKY
    }

    internal fun reconcile(mustEnterForeground: Boolean = false) {
        val manager = getSystemService(NotificationManager::class.java)
        val active = (application as? AppAiApplication)?.ai?.unfinishedRequests().orEmpty()
        val byNotification = active.associate { it.second.presentation.notificationId to it.second }
        val plan = reconciler.plan(active.map { AiNotificationEntry(it.first, it.second.presentation.notificationId) })
        val foreground = plan.foreground
        if (foreground == null) {
            if (mustEnterForeground) startForegroundCompat(PLACEHOLDER_ID, placeholder())
            stopForeground(STOP_FOREGROUND_REMOVE)
            plan.cancel.forEach(manager::cancel)
            if (lastStartId > 0) stopSelf(lastStartId)
            return
        }
        startForegroundCompat(foreground.notificationId, build(foreground, byNotification.getValue(foreground.notificationId)))
        plan.others.forEach { manager.notify(it.notificationId, build(it, byNotification.getValue(it.notificationId))) }
        plan.cancel.forEach(manager::cancel)
    }

    private fun build(entry: AiNotificationEntry, owner: AiRequestOwner): Notification {
        val presentation = owner.presentation
        val cancel = PendingIntent.getService(this, entry.notificationId,
            Intent(this, AiRequestService::class.java).setAction(ACTION_CANCEL_REQUEST)
                .setData(Uri.parse("appapp-ai://request/${entry.requestId}"))
                .putExtra(EXTRA_REQUEST_ID, entry.requestId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(presentation.title)
            .setContentText(presentation.body)
            .setSubText(presentation.appName.takeIf { it.isNotBlank() })
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        presentation.open?.let { open ->
            val pending = PendingIntent.getActivity(this, entry.notificationId, Intent(open),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.setContentIntent(pending).addAction(android.R.drawable.ic_menu_view, "Open", pending)
        }
        return builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel).build()
    }

    private fun placeholder(): Notification = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle(AiNotificationCopy.DEFAULT_TITLE)
        .build()

    private fun startForegroundCompat(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(this, id, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else startForeground(id, notification)
    }

    override fun onTimeout(startId: Int, foregroundServiceType: Int) {
        (application as? AppAiApplication)?.ai?.cancelAll()
        reconcile()
    }

    override fun onDestroy() {
        reconciler.reset()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val ACTION_CANCEL_REQUEST = "com.wynh.appapp.ai.CANCEL_REQUEST"
        private const val EXTRA_REQUEST_ID = "ai_request_id"
        private const val CHANNEL = "ai_requests"
        private const val PLACEHOLDER_ID = 7399
        internal const val NOTIFICATION_BASE = 7400
        internal const val NOTIFICATION_SPAN = 100_000
        @Volatile private var instance: AiRequestService? = null

        /** Re-evaluates notifications after any ledger change, from any thread. */
        fun reconcile() {
            Handler(Looper.getMainLooper()).post { instance?.reconcile() }
        }
    }
}
