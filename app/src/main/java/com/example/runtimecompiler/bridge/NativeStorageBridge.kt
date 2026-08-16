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
 * Provides persistent state storage, unified workspace file system access (read/write/delete/list),
 * and storage telemetry.
 */
class NativeStorageBridge(
    private val context: Context,
    private val onLogListener: ((String) -> Unit)? = null
) {
    private val sharedPrefs: SharedPreferences =
        context.getSharedPreferences("runtime_app_state", Context.MODE_PRIVATE)

    init {
        // Purge legacy shadow state from earlier versions if present
        if (sharedPrefs.contains("saved_items_state")) {
            sharedPrefs.edit().remove("saved_items_state").apply()
        }
    }

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
        // Sanitize file name to stay inside app's internal workspace directory
        val sanitized = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(workspaceDir, sanitized)
    }

    @JavascriptInterface
    fun writeFile(fileName: String, content: String): Boolean {
        return try {
            val file = getAppFile(fileName)
            FileOutputStream(file).use { output ->
                output.write(content.toByteArray(StandardCharsets.UTF_8))
            }
            log("[Storage] Wrote ${content.length} chars to workspace file '${file.name}' (${file.length()} bytes)")
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
            log("[Storage] Read ${content.length} chars from workspace file '${file.name}'")
            content
        } catch (e: Exception) {
            log("[Storage Error] Failed to read file '$fileName': ${e.message}")
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

    // --- Storage & System Telemetry ---

    @JavascriptInterface
    fun getStorageStats(): String {
        val filesDir = context.filesDir
        val freeSpace = filesDir.freeSpace
        val totalSpace = filesDir.totalSpace
        val usableSpace = filesDir.usableSpace

        var appFilesBytes = 0L
        val files = workspaceDir.listFiles() ?: emptyArray()
        for (f in files) {
            if (f.isFile) {
                appFilesBytes += f.length()
            }
        }

        val json = JSONObject().apply {
            put("freeSpaceBytes", freeSpace)
            put("totalSpaceBytes", totalSpace)
            put("usableSpaceBytes", usableSpace)
            put("appFilesCount", files.filter { it.isFile }.size)
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
}
