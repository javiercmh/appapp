package com.wynh.appapp.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class AiRequestService : Service() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "AI requests", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskId = intent?.getIntExtra(EXTRA_TASK_ID, -1) ?: -1
        if (intent?.action == ACTION_CANCEL_TASK && taskId >= 0) {
            (application as? AppAiApplication)?.ai?.cancelTask(taskId)
            requestFinished(this)
            return START_NOT_STICKY
        }
        val cancel = Intent(this, AiRequestService::class.java).setAction(ACTION_CANCEL_TASK).putExtra(EXTRA_TASK_ID, taskId)
        val pending = PendingIntent.getService(this, taskId, cancel, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("AI request in progress")
            .setContentText("AppApp will continue while you use another app.")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", pending)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID + taskId.coerceAtLeast(0), notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else startForeground(NOTIFICATION_ID + taskId.coerceAtLeast(0), notification)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val task = rootIntent?.getIntExtra(EXTRA_TASK_ID, -1) ?: -1
        if (task >= 0) {
            (application as? AppAiApplication)?.ai?.cancelTask(task)
        } else {
            val appId = rootIntent?.getStringExtra("EXTRA_SUBAPP_ID")
                ?: rootIntent?.data?.lastPathSegment
            appId?.let { (application as? AppAiApplication)?.ai?.cancelApp(it) }
        }
        if ((application as? AppAiApplication)?.ai?.activeRequestCount() == 0) stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onTimeout(startId: Int, foregroundServiceType: Int) {
        (application as? AppAiApplication)?.ai?.cancelAll()
        stopSelf(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_TASK_ID = "ai_task_id"
        private const val ACTION_CANCEL_TASK = "com.wynh.appapp.ai.CANCEL_TASK"
        private const val CHANNEL = "ai_requests"
        private const val NOTIFICATION_ID = 7400
        fun requestFinished(context: Context) {
            Handler(Looper.getMainLooper()).postDelayed({
                val aiApp = context.applicationContext as? AppAiApplication ?: return@postDelayed
                if (aiApp.ai.activeRequestCount() == 0) context.stopService(Intent(context, AiRequestService::class.java))
            }, 1500)
        }
    }
}
