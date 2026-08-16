package com.example.runtimecompiler.bridge

import android.content.Context
import android.content.SharedPreferences
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/**
 * JavaScript Interface exposed inside WebView as `window.AndroidStorage` and `window.AndroidMemory`.
 * Provides persistent state storage, native file system access (read/write/delete/list),
 * and storage telemetry.
 */
class NativeStorageBridge(
    private val context: Context,
    private val onLogListener: ((String) -> Unit)? = null
) {
    private val sharedPrefs: SharedPreferences =
        context.getSharedPreferences("runtime_app_state", Context.MODE_PRIVATE)

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

    // --- Native File System Access ---

    private fun getAppFile(fileName: String): File {
        // Sanitize file name to stay inside app's internal files directory
        val sanitized = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(context.filesDir, sanitized)
    }

    @JavascriptInterface
    fun writeFile(fileName: String, content: String): Boolean {
        return try {
            val file = getAppFile(fileName)
            FileOutputStream(file).use { output ->
                output.write(content.toByteArray(StandardCharsets.UTF_8))
            }
            log("[Storage] Wrote ${content.length} chars to file '${file.name}' (${file.length()} bytes)")
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
            if (!file.exists()) {
                log("[Storage] File '$fileName' does not exist yet.")
                return ""
            }
            val content = FileInputStream(file).use { input ->
                input.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            }
            log("[Storage] Read ${content.length} chars from file '${file.name}'")
            content
        } catch (e: Exception) {
            log("[Storage Error] Failed to read file '$fileName': ${e.message}")
            ""
        }
    }

    @JavascriptInterface
    fun deleteFile(fileName: String): Boolean {
        return try {
            val file = getAppFile(fileName)
            if (file.exists()) {
                val deleted = file.delete()
                log("[Storage] Deleted file '$fileName': $deleted")
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
        val files = context.filesDir.listFiles() ?: emptyArray()
        val jsonArray = JSONArray()
        for (file in files) {
            val obj = JSONObject().apply {
                put("name", file.name)
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
        val files = filesDir.listFiles() ?: emptyArray()
        for (f in files) {
            appFilesBytes += f.length()
        }

        val json = JSONObject().apply {
            put("freeSpaceBytes", freeSpace)
            put("totalSpaceBytes", totalSpace)
            put("usableSpaceBytes", usableSpace)
            put("appFilesCount", files.size)
            put("appFilesBytes", appFilesBytes)
            put("filesDir", filesDir.absolutePath)
        }
        return json.toString()
    }

    // --- Workspace Project Access ---

    @JavascriptInterface
    fun getWorkspaceFiles(): String {
        val workspaceDir = File(context.filesDir, "workspace")
        val files = workspaceDir.listFiles() ?: emptyArray()
        val jsonArray = JSONArray()
        for (file in files) {
            if (file.isFile) {
                val obj = JSONObject().apply {
                    put("name", file.name)
                    put("size", file.length())
                    put("lastModified", file.lastModified())
                }
                jsonArray.put(obj)
            }
        }
        return jsonArray.toString()
    }

    @JavascriptInterface
    fun readWorkspaceFile(fileName: String): String {
        val sanitized = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val workspaceDir = File(context.filesDir, "workspace")
        val file = File(workspaceDir, sanitized)
        if (!file.exists() || !file.isFile) {
            log("[Storage] Workspace file '$sanitized' not found.")
            return ""
        }
        return try {
            FileInputStream(file).use { input ->
                input.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            }
        } catch (e: Exception) {
            log("[Storage Error] Failed to read workspace file '$sanitized': ${e.message}")
            ""
        }
    }

    @JavascriptInterface
    fun writeWorkspaceFile(fileName: String, content: String): Boolean {
        val sanitized = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val workspaceDir = File(context.filesDir, "workspace")
        if (!workspaceDir.exists()) workspaceDir.mkdirs()
        val file = File(workspaceDir, sanitized)
        return try {
            FileOutputStream(file).use { output ->
                output.write(content.toByteArray(StandardCharsets.UTF_8))
            }
            log("[Storage] Wrote ${content.length} chars to workspace file '$sanitized'")
            true
        } catch (e: Exception) {
            log("[Storage Error] Failed to write workspace file '$sanitized': ${e.message}")
            false
        }
    }
}
