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
        val sent = intent?.getLongExtra(EXTRA_EPOCH, -1L) ?: -1L
        // A start command from an earlier attempt can arrive after a replacement: it must still reach
        // startForeground, but it neither confirms anything nor changes the epoch this service reports.
        val stale = sent >= 0 && sent < epoch
        if (sent > epoch) epoch = sent
        val ai = (application as? AppAiApplication)?.ai
        if (cancelId != null) ai?.cancelFromNotification(cancelId)
        // A command from startForegroundService must reach startForeground; a Cancel action need not.
        val outcome = reconcile(mustEnterForeground = cancelId == null)
        if (sent >= 0 && !stale) when (outcome) {
            Outcome.HELD -> ai?.onForegroundConfirmed(sent)
            // Nothing is left to run (everything waiting was cancelled or ended): the service is stopping, so
            // the attempt must not be reported as a foreground service.
            Outcome.IDLE -> ai?.onForegroundFailed(sent, "service stopped")
            Outcome.REFUSED -> Unit
        }
        return START_NOT_STICKY
    }

    internal enum class Outcome { HELD, IDLE, REFUSED }

    /**
     * Applies the notifications and foreground state for the current sessions and requests.
     * [Outcome.REFUSED]: Android refused startForeground; the failure is reported and the service stops.
     */
    internal fun reconcile(mustEnterForeground: Boolean = false): Outcome {
        val manager = getSystemService(NotificationManager::class.java)
        val ai = (application as? AppAiApplication)?.ai
        val active = ai?.notificationSources().orEmpty()
        val byNotification = active.associate { it.first.notificationId to it }
        val plan = reconciler.plan(active.map { it.first })
        val foreground = plan.foreground
        if (foreground == null) {
            if (mustEnterForeground && !startForegroundCompat(PLACEHOLDER_ID, placeholder())) return refused()
            stopForeground(STOP_FOREGROUND_REMOVE)
            plan.cancel.forEach(manager::cancel)
            shown.clear(); foregroundId = 0; handler.removeCallbacks(verify)
            ai?.onServiceIdle()
            if (lastStartId > 0) stopSelf(lastStartId)
            return Outcome.IDLE
        }
        // Android rate-limits notification updates per app and drops the excess, which could drop the
        // promotion of a new foreground notification, so unchanged notifications are not posted again.
        val (entry, presentation) = byNotification.getValue(foreground.notificationId)
        val foregroundShape = shape(entry, presentation)
        if (mustEnterForeground || foreground.notificationId != foregroundId || shown[foreground.notificationId] != foregroundShape) {
            if (!startForegroundCompat(foreground.notificationId, build(entry, presentation))) return refused()
            foregroundId = foreground.notificationId; shown[foregroundId] = foregroundShape
        }
        plan.others.forEach {
            val other = byNotification.getValue(it.notificationId)
            val otherShape = shape(other.first, other.second)
            if (shown[it.notificationId] != otherShape) {
                manager.notify(it.notificationId, build(it, other.second)); shown[it.notificationId] = otherShape
            }
        }
        plan.cancel.forEach { manager.cancel(it); shown.remove(it) }
        handler.removeCallbacks(verify); handler.postDelayed(verify, VERIFY_MS)
        return Outcome.HELD
    }

    /**
     * Android sheds notification updates above a few per second per app, silently. Since unchanged
     * notifications are not re-posted, one delayed check compares what is shown with what should be and
     * posts again only what is missing or stale (a few attempts, then it stops).
     */
    private fun verifyShown() {
        val live = getSystemService(NotificationManager::class.java).activeNotifications.associateBy { it.id }
        var repaired = false
        for ((id, shape) in shown.toMap()) {
            val posted = live[id]?.notification
            // The foreground notification must also carry the foreground-service flag, which a shed promotion lacks.
            val flagged = id != foregroundId || (posted != null && posted.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
            // The fingerprint covers title, body, app name and the Cancel target, so a dropped update that only
            // changed the Cancel action or the app name is repaired too.
            if (!flagged || posted?.extras?.getString(EXTRA_FINGERPRINT) != fingerprint(shape)) { shown.remove(id); repaired = true }
        }
        if (!repaired) { verifyAttempts = 0; return }
        if (++verifyAttempts <= MAX_VERIFY_ATTEMPTS) reconcile()
    }
    private val handler = Handler(Looper.getMainLooper())
    private val verify = Runnable { verifyShown() }
    private var verifyAttempts = 0

    /** What a notification displays and which target its Cancel action names. */
    private fun shape(entry: AiNotificationEntry, p: AiPresentation) = "${entry.requestId}\u0000${p.title}\u0000${p.body}\u0000${p.appName}"
    private fun fingerprint(shape: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(shape.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    private val shown = HashMap<Int, String>()
    private var foregroundId = 0

    private var epoch = -1L
    private var failure = "refused"
    private fun refused(): Outcome {
        (application as? AppAiApplication)?.ai?.onForegroundFailed(epoch, failure)
        // Only up to the latest start this instance has seen, so a newer start command is not cut off.
        if (lastStartId > 0) stopSelf(lastStartId) else stopSelf()
        return Outcome.REFUSED
    }

    private fun build(entry: AiNotificationEntry, presentation: AiPresentation): Notification {
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
            // A short hash, not the ids themselves, so verifyShown can tell the intended notification from a stale one.
            .addExtras(android.os.Bundle().apply { putString(EXTRA_FINGERPRINT, fingerprint(shape(entry, presentation))) })
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

    /** False when Android refuses (for example ForegroundServiceStartNotAllowedException or a SecurityException). */
    private fun startForegroundCompat(id: Int, notification: Notification): Boolean = try {
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(this, id, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else startForeground(id, notification)
        true
    } catch (e: Exception) {
        failure = e.javaClass.simpleName
        false
    }

    override fun onTimeout(startId: Int, foregroundServiceType: Int) {
        (application as? AppAiApplication)?.ai?.onServiceTimeout()
        reconcile()
    }

    override fun onDestroy() {
        handler.removeCallbacks(verify)
        (application as? AppAiApplication)?.ai?.onServiceDestroyed(epoch)
        reconciler.reset()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val ACTION_CANCEL_REQUEST = "com.wynh.appapp.ai.CANCEL_REQUEST"
        private const val EXTRA_REQUEST_ID = "ai_request_id"
        internal const val EXTRA_EPOCH = "ai_service_epoch"
        internal const val EXTRA_FINGERPRINT = "ai_notification_fingerprint"
        private const val CHANNEL = "ai_requests"
        private const val PLACEHOLDER_ID = 7399
        private const val VERIFY_MS = 1200L
        private const val MAX_VERIFY_ATTEMPTS = 3
        internal const val NOTIFICATION_BASE = 7400
        internal const val NOTIFICATION_SPAN = 100_000
        @Volatile private var instance: AiRequestService? = null

        internal fun isRunning(): Boolean = instance != null

        private val pending = java.util.concurrent.atomic.AtomicBoolean(false)

        /** Re-evaluates notifications after any ledger change, from any thread; bursts coalesce into one pass. */
        fun reconcile() {
            if (pending.compareAndSet(false, true)) Handler(Looper.getMainLooper()).post { pending.set(false); instance?.reconcile() }
        }
    }
}
