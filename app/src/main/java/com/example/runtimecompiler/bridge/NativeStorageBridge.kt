package com.example.runtimecompiler.bridge

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import com.example.runtimecompiler.workspace.WorkspaceManager

/**
 * JavaScript Interface exposed inside WebView as `window.AndroidStorage`, `window.AndroidMemory`,
 * and `window.AndroidNotification`.
 * Provides persistent state storage, unified workspace file system access (read/write/delete/list),
 * storage telemetry, and native system notifications.
 */
class NativeStorageBridge(
    private val context: Context,
    private val onLogListener: ((String) -> Unit)? = null,
    private val onRequestNotificationPermission: (() -> Unit)? = null
) {
    private val sharedPrefs: SharedPreferences =
        context.getSharedPreferences("runtime_app_state", Context.MODE_PRIVATE)

    private val workspaceDir: File
        get() {
            val dir = File(context.filesDir, "workspace")
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
        return try {
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

    @JavascriptInterface
    fun readFile(fileName: String): String {
        return try {
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

    /**
     * Writes binary content (image, font, any non-text asset) to a workspace file.
     * Accepts either a bare base64 payload or a full `data:<mime>;base64,...` URL as produced by
     * `FileReader.readAsDataURL()` / `canvas.toDataURL()`.
     *
     * Once written, the file is served by the `https://app.local` interceptor, so a web app can
     * simply reference it: `<img src="assets/photo_123.jpg">`.
     */
    @JavascriptInterface
    fun writeFileBase64(fileName: String, base64: String): Boolean {
        return try {
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

    /**
     * Reads a workspace file back as a base64 string. Returns "" if missing or over the size cap.
     * Encoded with NO_WRAP so the result is safe to hand to `atob()` or embed in a `data:` URL.
     */
    @JavascriptInterface
    fun readFileBase64(fileName: String): String {
        return try {
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

    @JavascriptInterface
    fun fileExists(fileName: String): Boolean {
        return try {
            val file = getAppFile(fileName)
            file.exists() && file.isFile
        } catch (_: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun deleteFile(fileName: String): Boolean {
        return try {
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

    @JavascriptInterface
    fun listFiles(): String {
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
        return jsonArray.toString()
    }

    // --- Storage & System Telemetry ---

    @JavascriptInterface
    fun getStorageStats(): String {
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

            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val pendingIntent = if (intent != null) {
                PendingIntent.getActivity(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else null

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

            NotificationManagerCompat.from(context).notify(id, notification)
            log("[Notification] Posted notification #$id: '$title' - '$message'")
            true
        } catch (e: Exception) {
            log("[Notification Error] Failed to show notification: ${e.message}")
            false
        }
    }

    @JavascriptInterface
    fun cancelNotification(id: Int): Boolean {
        return try {
            NotificationManagerCompat.from(context).cancel(id)
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
