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
import android.util.Base64
import android.webkit.JavascriptInterface
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import com.wynh.appapp.workspace.WorkspaceManager
import com.wynh.appapp.workspace.SubappRepository

/**
 * JavaScript Interface exposed inside WebView as `window.AndroidStorage`, `window.AndroidMemory`,
 * and `window.AndroidNotification`.
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

    // --- Persistent Key-Value State Access ---

    @JavascriptInterface
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

    @JavascriptInterface
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

    @JavascriptInterface
    fun removeState(key: String): Boolean {
        sharedPrefs.edit().remove(key).apply()
        log("[Storage] Removed state key '$key'")
        return true
    }

    @JavascriptInterface
    fun clearAllState(): Boolean {
        sharedPrefs.edit().clear().apply()
        log("[Storage] Cleared all key-value state")
        return true
    }

    // --- Unified File System Access (Stored directly in workspace/) ---

    private fun getAppFile(fileName: String): File {
        val sanitized = WorkspaceManager.sanitizeRelativePath(fileName)
        val file = File(workspaceDir, sanitized)
        return if (file.canonicalPath.startsWith(workspaceDir.canonicalPath)) {
            file
        } else {
            File(workspaceDir, sanitized.substringAfterLast('/'))
        }
    }

    @JavascriptInterface
    fun writeFile(fileName: String, content: String): Boolean {
        return synchronized(com.wynh.appapp.updates.WorkspaceLocks.forWorkspace(appWorkspaceDir)) {
            try {
                val file = getAppFile(fileName)
                file.parentFile?.mkdirs()
                FileOutputStream(file).use { output ->
                    output.write(content.toByteArray(StandardCharsets.UTF_8))
                }
                val relPath = file.relativeTo(workspaceDir).path.replace('\\', '/')
                log("[Storage] Wrote ${content.length} chars to workspace file '$relPath' (${file.length()} bytes)")
                true
            } catch (e: Exception) {
                log("[Storage Error] Failed to write file '$fileName': ${e.message}")
                false
            }
        }
    }

    @JavascriptInterface
    fun readFile(fileName: String): String {
        return synchronized(com.wynh.appapp.updates.WorkspaceLocks.forWorkspace(appWorkspaceDir)) {
            try {
                val file = getAppFile(fileName)
                if (!file.exists() || !file.isFile) {
                    log("[Storage] File '$fileName' does not exist yet.")
                    return ""
                }
                val content = FileInputStream(file).use { input ->
                    input.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                }
                val relPath = file.relativeTo(workspaceDir).path.replace('\\', '/')
                log("[Storage] Read ${content.length} chars from workspace file '$relPath'")
                content
            } catch (e: Exception) {
                log("[Storage Error] Failed to read file '$fileName': ${e.message}")
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
    @JavascriptInterface
    fun writeFileBase64(fileName: String, base64: String): Boolean {
        return synchronized(com.wynh.appapp.updates.WorkspaceLocks.forWorkspace(appWorkspaceDir)) {
            try {
                if (base64.length > MAX_BASE64_CHARS) {
                    log("[Storage Error] Refused '$fileName': ${base64.length} base64 chars exceeds cap of $MAX_BASE64_CHARS")
                    return false
                }
                // Strip an optional data-URL prefix; browsers hand these out by default.
                val payload = if (base64.startsWith("data:")) base64.substringAfter("base64,", "") else base64
                if (payload.isEmpty()) {
                    log("[Storage Error] Empty base64 payload for '$fileName'")
                    return false
                }
                // DEFAULT (not NO_WRAP) so line breaks and padding from a data URL are tolerated.
                val bytes = Base64.decode(payload, Base64.DEFAULT)
                val file = getAppFile(fileName)
                file.parentFile?.mkdirs()
                FileOutputStream(file).use { output ->
                    output.write(bytes)
                }
                val relPath = file.relativeTo(workspaceDir).path.replace('\\', '/')
                log("[Storage] Wrote binary '$relPath' (${bytes.size} bytes)")
                true
            } catch (e: IllegalArgumentException) {
                log("[Storage Error] Invalid base64 for '$fileName': ${e.message}")
                false
            } catch (e: Exception) {
                log("[Storage Error] Failed to write binary '$fileName': ${e.message}")
                false
            }
        }
    }

    /**
     * Reads a workspace file back as a base64 string. Returns "" if missing or over the size cap.
     * Encoded with NO_WRAP so the result is safe to hand to `atob()` or embed in a `data:` URL.
     */
    @JavascriptInterface
    fun readFileBase64(fileName: String): String {
        return synchronized(com.wynh.appapp.updates.WorkspaceLocks.forWorkspace(appWorkspaceDir)) {
            try {
                val file = getAppFile(fileName)
                if (!file.exists() || !file.isFile) {
                    log("[Storage] Binary file '$fileName' does not exist yet.")
                    return ""
                }
                if (file.length() > MAX_BINARY_BYTES) {
                    log("[Storage Error] '$fileName' is ${file.length()} bytes; too large to read as base64")
                    return ""
                }
                val encoded = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                val relPath = file.relativeTo(workspaceDir).path.replace('\\', '/')
                log("[Storage] Read binary '$relPath' (${file.length()} bytes)")
                encoded
            } catch (e: Exception) {
                log("[Storage Error] Failed to read binary '$fileName': ${e.message}")
                ""
            }
        }
    }

    @JavascriptInterface
    fun fileExists(fileName: String): Boolean {
        return synchronized(com.wynh.appapp.updates.WorkspaceLocks.forWorkspace(appWorkspaceDir)) {
            try {
                val file = getAppFile(fileName)
                file.exists() && file.isFile
            } catch (_: Exception) {
                false
            }
        }
    }

    @JavascriptInterface
    fun deleteFile(fileName: String): Boolean {
        return synchronized(com.wynh.appapp.updates.WorkspaceLocks.forWorkspace(appWorkspaceDir)) {
            try {
                val file = getAppFile(fileName)
                if (file.exists() && file.isFile) {
                    val deleted = file.delete()
                    log("[Storage] Deleted file '$fileName': $deleted")
                    if (deleted) {
                        var parent = file.parentFile
                        while (parent != null && parent != workspaceDir && parent.canonicalPath.startsWith(workspaceDir.canonicalPath)) {
                            val children = parent.listFiles()
                            if (children == null || children.isEmpty()) {
                                parent.delete()
                                parent = parent.parentFile
                            } else {
                                break
                            }
                        }
                    }
                    deleted
                } else {
                    false
                }
            } catch (e: Exception) {
                log("[Storage Error] Failed to delete file '$fileName': ${e.message}")
                false
            }
        }
    }

    @JavascriptInterface
    fun listFiles(): String {
        return synchronized(com.wynh.appapp.updates.WorkspaceLocks.forWorkspace(appWorkspaceDir)) {
            if (!workspaceDir.exists()) return "[]"
            val jsonArray = JSONArray()
            for (file in workspaceDir.walkTopDown().filter { it.isFile }) {
                val relativePath = file.relativeTo(workspaceDir).path.replace('\\', '/')
                val obj = JSONObject().apply {
                    put("name", relativePath)
                    put("size", file.length())
                    put("lastModified", file.lastModified())
                }
                jsonArray.put(obj)
            }
            jsonArray.toString()
        }
    }

    // --- Storage & System Telemetry ---

    @JavascriptInterface
    fun getStorageStats(): String {
        return synchronized(com.wynh.appapp.updates.WorkspaceLocks.forWorkspace(appWorkspaceDir)) {
            val filesDir = context.filesDir
            val freeSpace = filesDir.freeSpace
            val totalSpace = filesDir.totalSpace
            val usableSpace = filesDir.usableSpace

            var appFilesBytes = 0L
            var appFilesCount = 0
            if (workspaceDir.exists()) {
                for (f in workspaceDir.walkTopDown().filter { it.isFile }) {
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

    @JavascriptInterface
    fun getWorkspaceFiles(): String = listFiles()

    @JavascriptInterface
    fun readWorkspaceFile(fileName: String): String = readFile(fileName)

    @JavascriptInterface
    fun writeWorkspaceFile(fileName: String, content: String): Boolean = writeFile(fileName, content)

    @JavascriptInterface
    fun workspaceFileExists(fileName: String): Boolean = fileExists(fileName)

    // --- Native Notifications Bridge ---

    @JavascriptInterface
    fun hasNotificationPermission(): Boolean {
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    @JavascriptInterface
    fun requestNotificationPermission(): Boolean {
        return if (hasNotificationPermission()) {
            true
        } else {
            onRequestNotificationPermission?.invoke()
            false
        }
    }

    /**
     * Hides or restores Android's floating text-selection menu for the current page load.
     * Selection handles and selection events keep working; the subapp supplies its own actions.
     */
    @JavascriptInterface
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
    @JavascriptInterface
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

    @JavascriptInterface
    fun showNotification(title: String, message: String): Boolean {
        val notificationId = (System.currentTimeMillis() % 100000).toInt()
        return showNotificationWithId(notificationId, title, message)
    }

    @JavascriptInterface
    fun showNotificationWithId(id: Int, title: String, message: String): Boolean {
        return try {
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
    @JavascriptInterface
    fun scheduleNotification(id: Int, triggerAtMillis: String, title: String, message: String): Boolean {
        val at = triggerAtMillis.toDoubleOrNull()?.toLong()
        if (at == null || at <= System.currentTimeMillis()) {
            log("[Notification Warning] Ignored schedule #$id: trigger time is missing or in the past.")
            return false
        }
        val scheduled = scheduleAlarm(context, subappId, id, at, title, message)
        log(
            if (scheduled) "[Notification] Scheduled #$id for $at: '$title'"
            else "[Notification Error] Could not schedule #$id"
        )
        return scheduled
    }

    @JavascriptInterface
    fun cancelScheduledNotification(id: Int): Boolean {
        cancelAlarm(context, subappId, id)
        log("[Notification] Cancelled scheduled notification #$id")
        return true
    }

    /** Whether the app can schedule precise reminders right now. */
    @JavascriptInterface
    fun canScheduleExactNotifications(): Boolean = canScheduleExact(context)

    /** Opens Android's user-controlled Alarms & reminders access screen. */
    @JavascriptInterface
    fun requestExactNotificationPermission(): Boolean = openExactAlarmSettings()

    @JavascriptInterface
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

    @JavascriptInterface
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

        fun reschedulePersistedAlarms(context: Context, subappId: String) {
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

        /**
         * Caps for the binary bridge. A @JavascriptInterface call blocks the calling JS thread and
         * marshals the payload across JNI as a Java String (~2 bytes per char), so an unbounded
         * multi-megapixel photo would spike transient heap. Web apps should downscale first;
         * these are the backstop.
         */
        private const val MAX_BINARY_BYTES = 6L * 1024 * 1024
        private const val MAX_BASE64_CHARS = 8_400_000
    }
}
