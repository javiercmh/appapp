package com.wynh.appapp.bridge

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import com.wynh.appapp.workspace.BridgeWorkspaceFiles
import com.wynh.appapp.workspace.SubappRepository
import com.wynh.appapp.grants.SubappCapability
import com.wynh.appapp.grants.SubappGrants

/**
 * Native side of `window.AndroidStorage`, `window.AndroidMemory` and `window.AndroidNotification`.
 * It is never registered with `addJavascriptInterface` directly: only [jsMethods] are reachable,
 * through the token-gated dispatcher that `SubappWebViews` exposes to the subapp origin.
 * Provides persistent state storage, unified workspace file system access (read/write/delete/list),
 * storage telemetry, and native system notifications.
 */
class NativeStorageBridge(
    private val context: Context,
    private val subappId: String,
    private val appWorkspaceDir: File,
    private val onLogListener: ((String) -> Unit)? = null,
    private val onRequestNotificationPermission: (() -> Unit)? = null,
    private val onSetSelectionMenuSuppressed: ((Boolean) -> Unit)? = null
) {
    @Volatile private var workspaceVersion = com.wynh.appapp.workspace.WorkspaceOperations.version(appWorkspaceDir)
    private fun workspaceUnavailable(): Boolean = com.wynh.appapp.workspace.WorkspaceOperations.isBusy(appWorkspaceDir) ||
        workspaceVersion != com.wynh.appapp.workspace.WorkspaceOperations.version(appWorkspaceDir)

    private val sharedPrefs: SharedPreferences =
        context.getSharedPreferences("runtime_app_state_$subappId", Context.MODE_PRIVATE)

    private val workspaceDir: File
        get() {
            val dir = appWorkspaceDir
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    private fun log(message: String) {
        onLogListener?.invoke(message)
    }

    /** The complete JavaScript surface, with the parameter types the dispatcher validates. */
    val jsMethods: Map<String, BridgeMethod> by lazy {
        val s = ArgType.STRING
        val i = ArgType.INT
        mapOf(
            "saveState" to BridgeMethod(s, s) { saveState(it[0] as String, it[1] as String) },
            "loadState" to BridgeMethod(s, s) { loadState(it[0] as String, it[1] as String) },
            "removeState" to BridgeMethod(s) { removeState(it[0] as String) },
            "clearAllState" to BridgeMethod { clearAllState() },
            "writeFile" to BridgeMethod(s, s) { writeFile(it[0] as String, it[1] as String) },
            "readFile" to BridgeMethod(s) { readFile(it[0] as String) },
            "writeFileBase64" to BridgeMethod(s, s) { writeFileBase64(it[0] as String, it[1] as String) },
            "readFileBase64" to BridgeMethod(s) { readFileBase64(it[0] as String) },
            "fileExists" to BridgeMethod(s) { fileExists(it[0] as String) },
            "deleteFile" to BridgeMethod(s) { deleteFile(it[0] as String) },
            "listFiles" to BridgeMethod { listFiles() },
            "getStorageStats" to BridgeMethod { getStorageStats() },
            "getWorkspaceFiles" to BridgeMethod { getWorkspaceFiles() },
            "readWorkspaceFile" to BridgeMethod(s) { readWorkspaceFile(it[0] as String) },
            "writeWorkspaceFile" to BridgeMethod(s, s) { writeWorkspaceFile(it[0] as String, it[1] as String) },
            "workspaceFileExists" to BridgeMethod(s) { workspaceFileExists(it[0] as String) },
            "hasNotificationPermission" to BridgeMethod { hasNotificationPermission() },
            "requestNotificationPermission" to BridgeMethod { requestNotificationPermission() },
            "setSelectionMenuSuppressed" to BridgeMethod(ArgType.BOOLEAN) { setSelectionMenuSuppressed(it[0] as Boolean) },
            "openNotificationSettings" to BridgeMethod { openNotificationSettings() },
            "showNotification" to BridgeMethod(s, s) { showNotification(it[0] as String, it[1] as String) },
            "showNotificationWithId" to BridgeMethod(i, s, s) { showNotificationWithId(it[0] as Int, it[1] as String, it[2] as String) },
            "scheduleNotification" to BridgeMethod(i, s, s, s) {
                scheduleNotification(it[0] as Int, it[1] as String, it[2] as String, it[3] as String)
            },
            "cancelScheduledNotification" to BridgeMethod(i) { cancelScheduledNotification(it[0] as Int) },
            "canScheduleExactNotifications" to BridgeMethod { canScheduleExactNotifications() },
            "requestExactNotificationPermission" to BridgeMethod { requestExactNotificationPermission() },
            "openExactAlarmSettings" to BridgeMethod { openExactAlarmSettings() },
            "cancelNotification" to BridgeMethod(i) { cancelNotification(it[0] as Int) }
        )
    }

    // --- Persistent Key-Value State Access ---

    fun saveState(key: String, value: String): Boolean {
        return try {
            sharedPrefs.edit().putString(key, value).apply()
            log("[Storage] Saved state key '$key' (${value.length} chars)")
            true
        } catch (e: Exception) {
            log("[Storage Error] Failed to save key '$key': ${e.message}")
            false
        }
    }

    fun loadState(key: String, defaultValue: String = ""): String {
        return try {
            val result = sharedPrefs.getString(key, defaultValue) ?: defaultValue
            log("[Storage] Loaded state key '$key' (${result.length} chars)")
            result
        } catch (e: Exception) {
            log("[Storage Error] Failed to load key '$key': ${e.message}")
            defaultValue
        }
    }

    fun removeState(key: String): Boolean {
        sharedPrefs.edit().remove(key).apply()
        log("[Storage] Removed state key '$key'")
        return true
    }

    fun clearAllState(): Boolean {
        sharedPrefs.edit().clear().apply()
        log("[Storage] Cleared all key-value state")
        return true
    }

    // --- Unified File System Access (Stored directly in workspace/) ---

    private fun bridgeFiles() = BridgeWorkspaceFiles(workspaceDir, ::log)

    fun writeFile(fileName: String, content: String): Boolean {
        if (workspaceUnavailable()) return false
        return synchronized(com.wynh.appapp.updates.WorkspaceLocks.forWorkspace(appWorkspaceDir)) {
            if (workspaceUnavailable()) return@synchronized false
            try {
                bridgeFiles().writeText(fileName, content)
            } catch (e: Exception) {
                log("[Storage Error] Failed to write file: ${e.message}")
                false
            }
        }
    }

    fun readFile(fileName: String): String {
        if (workspaceUnavailable()) return ""
        return synchronized(com.wynh.appapp.updates.WorkspaceLocks.forWorkspace(appWorkspaceDir)) {
            if (workspaceUnavailable()) return@synchronized ""
            try {
                bridgeFiles().readText(fileName)
            } catch (e: Exception) {
                log("[Storage Error] Failed to read file: ${e.message}")
                ""
            }
        }
    }

    /**
     * Writes binary content (image, font, any non-text asset) to a workspace file.
     * Accepts either a bare base64 payload or a full `data:<mime>;base64,...` URL as produced by
     * `FileReader.readAsDataURL()` / `canvas.toDataURL()`.
     *
     * Once written, the file is served by this subapp's local origin, so a web app can
     * simply reference it: `<img src="assets/photo_123.jpg">`.
     */
    fun writeFileBase64(fileName: String, base64: String): Boolean {
        if (workspaceUnavailable()) return false
        return synchronized(com.wynh.appapp.updates.WorkspaceLocks.forWorkspace(appWorkspaceDir)) {
            if (workspaceUnavailable()) return@synchronized false
            try {
                bridgeFiles().writeBase64(fileName, base64)
            } catch (e: Exception) {
                log("[Storage Error] Failed to write binary file: ${e.message}")
                false
            }
        }
    }

    /**
     * Reads a workspace file back as a base64 string. Returns "" if missing or over the size cap.
     * Encoded without line wrapping so the result is safe to hand to `atob()` or embed in a `data:` URL.
     */
    fun readFileBase64(fileName: String): String {
        if (workspaceUnavailable()) return ""
        return synchronized(com.wynh.appapp.updates.WorkspaceLocks.forWorkspace(appWorkspaceDir)) {
            if (workspaceUnavailable()) return@synchronized ""
            try {
                bridgeFiles().readBase64(fileName)
            } catch (e: Exception) {
                log("[Storage Error] Failed to read binary file: ${e.message}")
                ""
            }
        }
    }

    fun fileExists(fileName: String): Boolean {
        if (workspaceUnavailable()) return false
        return synchronized(com.wynh.appapp.updates.WorkspaceLocks.forWorkspace(appWorkspaceDir)) {
            if (workspaceUnavailable()) return@synchronized false
            try {
                bridgeFiles().exists(fileName)
            } catch (_: Exception) {
                false
            }
        }
    }

    fun deleteFile(fileName: String): Boolean {
        if (workspaceUnavailable()) return false
        return synchronized(com.wynh.appapp.updates.WorkspaceLocks.forWorkspace(appWorkspaceDir)) {
            if (workspaceUnavailable()) return@synchronized false
            try {
                bridgeFiles().delete(fileName)
            } catch (e: Exception) {
                log("[Storage Error] Failed to delete file: ${e.message}")
                false
            }
        }
    }

    fun listFiles(): String {
        if (workspaceUnavailable()) return "[]"
        return synchronized(com.wynh.appapp.updates.WorkspaceLocks.forWorkspace(appWorkspaceDir)) {
            if (workspaceUnavailable()) return@synchronized "[]"
            try {
                val jsonArray = JSONArray()
                for (entry in bridgeFiles().list()) {
                    jsonArray.put(JSONObject().apply {
                        put("name", entry.name)
                        put("size", entry.size)
                        put("lastModified", entry.lastModified)
                    })
                }
                jsonArray.toString()
            } catch (e: Exception) {
                log("[Storage Error] Failed to list files: ${e.message}")
                "[]"
            }
        }
    }

    // --- Storage & System Telemetry ---

    fun getStorageStats(): String {
        if (workspaceUnavailable()) return "{}"
        return synchronized(com.wynh.appapp.updates.WorkspaceLocks.forWorkspace(appWorkspaceDir)) {
            if (workspaceUnavailable()) return@synchronized "{}"
            val filesDir = context.filesDir
            val freeSpace = filesDir.freeSpace
            val totalSpace = filesDir.totalSpace
            val usableSpace = filesDir.usableSpace

            var appFilesBytes = 0L
            var appFilesCount = 0
            if (workspaceDir.exists()) {
                for (f in com.wynh.appapp.workspace.WorkspaceFiles.regularFiles(workspaceDir)) {
                    appFilesBytes += f.length()
                    appFilesCount++
                }
            }

            val json = JSONObject().apply {
                put("freeSpaceBytes", freeSpace)
                put("totalSpaceBytes", totalSpace)
                put("usableSpaceBytes", usableSpace)
                put("appFilesCount", appFilesCount)
                put("appFilesBytes", appFilesBytes)
                put("filesDir", workspaceDir.absolutePath)
            }
            return json.toString()
        }
    }

    // --- Workspace Project Access Aliases ---

    fun getWorkspaceFiles(): String = listFiles()

    fun readWorkspaceFile(fileName: String): String = readFile(fileName)

    fun writeWorkspaceFile(fileName: String, content: String): Boolean = writeFile(fileName, content)

    fun workspaceFileExists(fileName: String): Boolean = fileExists(fileName)

    // --- Native Notifications Bridge ---

    /** True only when Android allows App² notifications and the user allowed this subapp. */
    fun hasNotificationPermission(): Boolean = systemNotificationsEnabled() && notificationsGranted()

    /**
     * Asks for this subapp's notification grant first (once per page load), then for Android's
     * runtime permission as before. Returns true only when both are already in place.
     */
    fun requestNotificationPermission(): Boolean {
        if (!notificationsGranted()) {
            askNotificationGrant()
            return false
        }
        return if (systemNotificationsEnabled()) {
            true
        } else {
            onRequestNotificationPermission?.invoke()
            false
        }
    }

    private fun systemNotificationsEnabled() = NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun notificationsGranted() =
        SubappGrants.isGranted(context, subappId, SubappCapability.NOTIFICATIONS)

    @Volatile private var notificationGrantAsked = false

    /** Call from the WebView's onPageStarted: each page load may ask for the grant once. */
    fun pageStarted() {
        workspaceVersion = com.wynh.appapp.workspace.WorkspaceOperations.version(appWorkspaceDir)
        notificationGrantAsked = false
    }

    /**
     * Shows the native grant dialog on the UI thread, at most once per page load, so a script
     * calling a notification method in a loop cannot stack prompts. Allow stores the grant and then
     * continues with Android's runtime permission; the web app retries the call itself.
     */
    private fun askNotificationGrant() {
        if (notificationGrantAsked) return
        val activity = context as? android.app.Activity ?: run {
            log("[Notification Warning] Notifications are not allowed for this app; allow them in the library: app menu → Permissions.")
            return
        }
        notificationGrantAsked = true
        activity.runOnUiThread {
            val name = SubappGrants.displayName(activity, subappId)
            SubappGrants.ask(
                activity,
                activity.getString(com.wynh.appapp.R.string.permission_notifications_title, name),
                activity.getString(com.wynh.appapp.R.string.permission_notifications_message, name)
            ) { allowed ->
                if (!allowed) { log("[Notification] Notifications were not allowed for this app."); return@ask }
                SubappGrants.setGranted(activity, subappId, SubappCapability.NOTIFICATIONS, true)
                log("[Notification] Notifications allowed for this app.")
                if (!systemNotificationsEnabled()) onRequestNotificationPermission?.invoke()
            }
        }
    }

    /**
     * Hides or restores Android's floating text-selection menu for the current page load.
     * Selection handles and selection events keep working; the subapp supplies its own actions.
     */
    fun setSelectionMenuSuppressed(suppressed: Boolean): Boolean {
        val callback = onSetSelectionMenuSuppressed ?: return false
        return try {
            callback(suppressed)
            true
        } catch (e: Exception) {
            onLogListener?.invoke("[Selection Menu Error] ${e.message}")
            false
        }
    }

    /**
     * Opens this app's notification settings screen. The only way back for a user who denied the
     * runtime prompt twice (Android then refuses to show it again) or who is on API 26-32, where
     * there is no runtime prompt at all but notifications can still be switched off.
     */
    fun openNotificationSettings(): Boolean {
        val screens = listOf(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            )
        )
        screens.forEach { intent ->
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: Exception) {
                // Try the next screen.
            }
        }
        log("[Permissions Error] Could not open notification settings.")
        return false
    }

    fun showNotification(title: String, message: String): Boolean {
        val notificationId = (System.currentTimeMillis() % 100000).toInt()
        return showNotificationWithId(notificationId, title, message)
    }

    fun showNotificationWithId(id: Int, title: String, message: String): Boolean {
        return try {
            if (!notificationsGranted()) {
                log("[Notification Warning] Cannot post notification: not allowed for this app yet.")
                askNotificationGrant()
                return false
            }
            if (!hasNotificationPermission()) {
                log("[Notification Warning] Cannot post notification: permission not granted.")
                onRequestNotificationPermission?.invoke()
                return false
            }

            val intent = Intent(context, com.wynh.appapp.SubappRuntimeActivity::class.java).apply {
                data = Uri.parse("appapp://subapp/$subappId")
                action = Intent.ACTION_VIEW
            }
            val pendingIntent = PendingIntent.getActivity(
                    context,
                    scopedId(subappId, id),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .apply {
                    if (pendingIntent != null) {
                        setContentIntent(pendingIntent)
                    }
                }
                .build()

            NotificationManagerCompat.from(context).notify(subappId, id, notification)
            rememberNotification(context, subappId, id)
            log("[Notification] Posted notification #$id: '$title' - '$message'")
            true
        } catch (e: SecurityException) {
            log("[Notification Error] Permission denied: ${e.message}")
            false
        } catch (e: Exception) {
            log("[Notification Error] Failed to show notification: ${e.message}")
            false
        }
    }

    /**
     * Schedules a notification to be posted at [triggerAtMillis] (epoch milliseconds, passed as a
     * string because JS numbers cross the bridge as doubles). Re-using an [id] replaces the
     * previously scheduled alarm, which is what makes rescheduling idempotent for the caller.
     * Fires even when the app is closed; survives reboot via the persisted alarm list.
     */
    fun scheduleNotification(id: Int, triggerAtMillis: String, title: String, message: String): Boolean {
        val at = triggerAtMillis.toDoubleOrNull()?.toLong()
        if (at == null || at <= System.currentTimeMillis()) {
            log("[Notification Warning] Ignored schedule #$id: trigger time is missing or in the past.")
            return false
        }
        if (!notificationsGranted()) {
            log("[Notification Warning] Cannot schedule #$id: notifications are not allowed for this app yet.")
            askNotificationGrant()
            return false
        }
        val scheduled = scheduleAlarm(context, subappId, id, at, title, message)
        log(
            if (scheduled) "[Notification] Scheduled #$id for $at: '$title'"
            else "[Notification Error] Could not schedule #$id"
        )
        return scheduled
    }

    fun cancelScheduledNotification(id: Int): Boolean {
        cancelAlarm(context, subappId, id)
        log("[Notification] Cancelled scheduled notification #$id")
        return true
    }

    /** Whether the app can schedule precise reminders right now. */
    fun canScheduleExactNotifications(): Boolean = canScheduleExact(context)

    /** Opens Android's user-controlled Alarms & reminders access screen. */
    fun requestExactNotificationPermission(): Boolean = openExactAlarmSettings()

    fun openExactAlarmSettings(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || canScheduleExact(context)) return true
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            .setData(Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            log("[Permissions Error] Could not open Alarms & reminders settings: ${e.message}")
            false
        }
    }

    fun cancelNotification(id: Int): Boolean {
        return try {
            NotificationManagerCompat.from(context).cancel(subappId, id)
            forgetNotification(context, subappId, id)
            log("[Notification] Cancelled notification #$id")
            true
        } catch (e: Exception) {
            log("[Notification Error] Failed to cancel notification #$id: ${e.message}")
            false
        }
    }

    companion object {
        const val CHANNEL_ID = "appapp_notifications"
        const val CHANNEL_NAME = "App² Notifications"
        private const val ALARM_PREFS = "runtime_scheduled_alarms"
        private val alarmLock = Any()

        /**
         * Creates the notification channel. Safe to call repeatedly, and callable from a
         * broadcast receiver running in a process where MainActivity never started.
         */
        fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications posted from App² runtime web applications"
                enableVibration(true)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        fun canScheduleExact(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            return alarmManager.canScheduleExactAlarms()
        }

        /** Arms the alarm and records it so it can be restored after a reboot. */
        fun scheduleAlarm(
            context: Context,
            subappId: String,
            id: Int,
            triggerAtMillis: Long,
            title: String,
            message: String
        ): Boolean {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = alarmPendingIntent(
                context, subappId, id, title, message,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ) ?: return false

            return synchronized(alarmLock) {
                try {
                    if (!canScheduleExact(context)) {
                        return@synchronized false
                    }
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                    persistAlarm(context, subappId, id, triggerAtMillis, title, message)
                    true
                } catch (e: Exception) {
                    false
                }
            }
        }

        fun cancelAlarm(context: Context, subappId: String, id: Int) {
            synchronized(alarmLock) {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                alarmPendingIntent(context, subappId, id, "", "", PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
                    ?.let { alarmManager.cancel(it) }
                forgetPersistedAlarm(context, subappId, id)
            }
        }

        /** Re-arms every still-future alarm; called after boot and after app replacement. */
        fun reschedulePersistedAlarms(context: Context) {
            SubappRepository(context).ensureMigrated().forEach { reschedulePersistedAlarms(context, it.id) }
        }

        /** Ungranted subapps are skipped; revocation already removed their persisted alarms. */
        fun reschedulePersistedAlarms(context: Context, subappId: String) {
            if (!SubappGrants.isGranted(context, subappId, SubappCapability.NOTIFICATIONS)) return
            synchronized(alarmLock) {
                val prefs = alarmPreferences(context, subappId)
                val stored = JSONObject(prefs.getString(ALARM_PREFS, "{}") ?: "{}")
                val now = System.currentTimeMillis()
                stored.keys().asSequence().toList().forEach { key ->
                    val entry = stored.optJSONObject(key) ?: return@forEach
                    val at = entry.optLong("at")
                    if (at <= now) stored.remove(key)
                }
                prefs.edit().putString(ALARM_PREFS, stored.toString()).commit()
                stored.keys().asSequence().toList().forEach { key -> key.toIntOrNull()?.let { cancelLegacyAlarm(context, it) } }
                if (canScheduleExact(context)) {
                    stored.keys().asSequence().toList().forEach { key ->
                        val entry = stored.optJSONObject(key) ?: return@forEach
                        val id = key.toIntOrNull() ?: return@forEach
                        scheduleAlarm(context, subappId, id, entry.optLong("at"), entry.optString("title"), entry.optString("message"))
                    }
                }
            }
        }

        /** Whether [subappId] has any persisted scheduled notification (used by the grant migration). */
        fun hasPersistedAlarms(context: Context, subappId: String): Boolean = runCatching {
            JSONObject(alarmPreferences(context, subappId).getString(ALARM_PREFS, "{}") ?: "{}").length() > 0
        }.getOrDefault(false)

        fun forgetPersistedAlarm(context: Context, subappId: String, id: Int) {
            synchronized(alarmLock) {
                val prefs = alarmPreferences(context, subappId)
                val stored = JSONObject(prefs.getString(ALARM_PREFS, "{}") ?: "{}")
                stored.remove(id.toString())
                prefs.edit().putString(ALARM_PREFS, stored.toString()).commit()
            }
        }

        private fun persistAlarm(context: Context, subappId: String, id: Int, at: Long, title: String, message: String) {
            val prefs = alarmPreferences(context, subappId)
            val stored = JSONObject(prefs.getString(ALARM_PREFS, "{}") ?: "{}")
            stored.put(id.toString(), JSONObject().put("at", at).put("title", title).put("message", message))
            prefs.edit().putString(ALARM_PREFS, stored.toString()).commit()
        }

        private fun alarmPendingIntent(
            context: Context,
            subappId: String,
            id: Int,
            title: String,
            message: String,
            flags: Int
        ): PendingIntent? {
            val intent = Intent(context, ScheduledNotificationReceiver::class.java).apply {
                data = Uri.parse("appapp-alarm://subapp/$subappId/notification/$id")
                putExtra(ScheduledNotificationReceiver.EXTRA_ID, id)
                putExtra(ScheduledNotificationReceiver.EXTRA_SUBAPP_ID, subappId)
                putExtra(ScheduledNotificationReceiver.EXTRA_TITLE, title)
                putExtra(ScheduledNotificationReceiver.EXTRA_MESSAGE, message)
            }
            return PendingIntent.getBroadcast(context, scopedId(subappId, id), intent, flags)
        }

        private fun cancelLegacyAlarm(context: Context, id: Int) {
            val legacyIntent = Intent(context, ScheduledNotificationReceiver::class.java)
            PendingIntent.getBroadcast(context, id, legacyIntent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
                ?.let { pending ->
                    (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending)
                    pending.cancel()
                }
        }

        private fun alarmPreferences(context: Context, subappId: String) =
            context.getSharedPreferences("${ALARM_PREFS}_$subappId", Context.MODE_PRIVATE)

        fun scopedId(subappId: String, id: Int): Int = 31 * subappId.hashCode() + id

        private fun rememberNotification(context: Context, subappId: String, id: Int) {
            val prefs = context.getSharedPreferences("runtime_notifications_$subappId", Context.MODE_PRIVATE)
            prefs.edit().putStringSet("ids", prefs.getStringSet("ids", emptySet()).orEmpty() + id.toString()).apply()
        }

        private fun forgetNotification(context: Context, subappId: String, id: Int) {
            val prefs = context.getSharedPreferences("runtime_notifications_$subappId", Context.MODE_PRIVATE)
            prefs.edit().putStringSet("ids", prefs.getStringSet("ids", emptySet()).orEmpty() - id.toString()).apply()
        }

        fun cancelAllForSubapp(context: Context, subappId: String) {
            val notificationPrefs = context.getSharedPreferences("runtime_notifications_$subappId", Context.MODE_PRIVATE)
            notificationPrefs.getStringSet("ids", emptySet()).orEmpty().forEach { raw ->
                raw.toIntOrNull()?.let { NotificationManagerCompat.from(context).cancel(subappId, it) }
            }
            notificationPrefs.edit().clear().commit()
            val alarms = alarmPreferences(context, subappId)
            val ids = runCatching {
                val keys = JSONObject(alarms.getString(ALARM_PREFS, "{}") ?: "{}").keys()
                buildList { while (keys.hasNext()) keys.next().toIntOrNull()?.let(::add) }
            }.getOrDefault(emptyList())
            ids.forEach { cancelAlarm(context, subappId, it) }
            alarms.edit().clear().commit()
        }
    }
}
