package com.example.runtimecompiler.workspace

import android.content.Context
import com.example.runtimecompiler.templates.DefaultWebApp
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

data class WorkspaceFile(
    val name: String,
    val size: Long,
    val lastModified: Long,
    val isCore: Boolean
)

/**
 * Manages the multi-file project workspace stored on Android internal storage.
 * Handles directory creation, file I/O, templates initialization, and MIME type resolution.
 */
class WorkspaceManager(
    private val context: Context,
    private val onLogListener: ((String) -> Unit)? = null
) {
    val workspaceDir: File = File(context.filesDir, "workspace")

    init {
        ensureWorkspaceInitialized()
    }

    private fun log(message: String) {
        onLogListener?.invoke(message)
    }

    /**
     * Initializes the workspace directory and starter files if not already present,
     * and auto-migrates legacy template files on existing devices.
     */
    fun ensureWorkspaceInitialized() {
        if (!workspaceDir.exists()) {
            workspaceDir.mkdirs()
        }

        val indexFile = File(workspaceDir, "index.html")
        if (!indexFile.exists()) {
            resetWorkspace()
        } else {
            // Auto-migration: check if on-disk app.js contains legacy shadow state fallback
            val appJsFile = File(workspaceDir, "app.js")
            if (appJsFile.exists()) {
                val currentJs = readFile("app.js")
                if (currentJs.contains("saved_items_state")) {
                    writeFile("app.js", DefaultWebApp.DEFAULT_APP_JS)
                    log("[Workspace] Auto-migrated on-device 'app.js' to clean decoupled version.")
                }
            }

            val logFile = File(workspaceDir, "app.log")
            if (!logFile.exists()) {
                writeFile("app.log", "[System] App² workspace initialized.\n")
            }
        }
    }

    /**
     * Applies a template file map directly to the workspace directory.
     */
    fun applyTemplate(files: Map<String, String>): Boolean {
        return try {
            if (!workspaceDir.exists()) {
                workspaceDir.mkdirs()
            }
            for ((fileName, content) in files) {
                writeFile(fileName, content)
            }
            val logFile = File(workspaceDir, "app.log")
            if (!logFile.exists()) {
                writeFile("app.log", "[System] App² workspace template applied.\n")
            }
            log("[Workspace] Successfully applied template with ${files.size} files.")
            true
        } catch (e: Exception) {
            log("[Workspace Error] Failed to apply template: ${e.message}")
            false
        }
    }

    /**
     * Re-initializes all default workspace template files from DefaultWebApp.STARTER_FILES.
     */
    fun resetWorkspace(): Boolean {
        val success = applyTemplate(DefaultWebApp.STARTER_FILES)
        if (success) {
            appendToFile("app.log", "[System] App² workspace reset to starter template.\n")
        }
        return success
    }

    /**
     * Performs a complete factory reset of the workspace directory, removing all files and re-initializing starter files.
     */
    fun factoryResetWorkspace(): Boolean {
        return try {
            val files = workspaceDir.listFiles() ?: emptyArray()
            for (file in files) {
                file.delete()
            }
            val success = applyTemplate(DefaultWebApp.STARTER_FILES)
            writeFile("app.log", "[System] App² factory reset completed.\n")
            log("[Workspace] Factory reset workspace successfully.")
            success
        } catch (e: Exception) {
            log("[Workspace Error] Failed to factory reset workspace: ${e.message}")
            false
        }
    }

    private fun sanitizeFileName(fileName: String): String {
        return fileName.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    fun getFile(fileName: String): File {
        val sanitized = sanitizeFileName(fileName)
        return File(workspaceDir, sanitized)
    }

    /**
     * Lists all files in the workspace directory.
     * Core files (index.html, style.css, app.js, manifest.json, app.log) are prioritized at the top.
     */
    fun listFiles(): List<WorkspaceFile> {
        val files = workspaceDir.listFiles() ?: emptyArray()
        val coreNames = setOf("index.html", "style.css", "app.js", "manifest.json", "app.log")

        return files
            .filter { it.isFile }
            .map { file ->
                WorkspaceFile(
                    name = file.name,
                    size = file.length(),
                    lastModified = file.lastModified(),
                    isCore = coreNames.contains(file.name)
                )
            }
            .sortedWith(compareBy<WorkspaceFile> { !it.isCore }
                .thenBy {
                    when (it.name) {
                        "index.html" -> 0
                        "style.css" -> 1
                        "app.js" -> 2
                        "manifest.json" -> 3
                        "app.log" -> 4
                        else -> 5
                    }
                }
                .thenBy { it.name }
            )
    }

    /**
     * Reads the text content of a workspace file.
     */
    fun readFile(fileName: String): String {
        return try {
            val file = getFile(fileName)
            if (!file.exists()) {
                log("[Workspace] File '$fileName' does not exist.")
                return ""
            }
            FileInputStream(file).use { input ->
                input.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            }
        } catch (e: Exception) {
            log("[Workspace Error] Failed to read '$fileName': ${e.message}")
            ""
        }
    }

    /**
     * Writes text content to a workspace file.
     */
    fun writeFile(fileName: String, content: String): Boolean {
        return try {
            val file = getFile(fileName)
            FileOutputStream(file).use { output ->
                output.write(content.toByteArray(StandardCharsets.UTF_8))
            }
            log("[Workspace] Saved '${file.name}' (${content.length} chars)")
            true
        } catch (e: Exception) {
            log("[Workspace Error] Failed to write '$fileName': ${e.message}")
            false
        }
    }

    /**
     * Appends text content to a workspace file (e.g. app.log) in a thread-safe manner.
     * Automatically truncates/rolls over if the file exceeds maxBytes (default: 200KB).
     */
    @Synchronized
    fun appendToFile(fileName: String, content: String, maxBytes: Long = 200_000): Boolean {
        return try {
            val file = getFile(fileName)
            if (!file.exists()) {
                file.createNewFile()
            } else if (file.length() > maxBytes) {
                // Keep the last ~half of the log content on rollover
                val existing = readFile(fileName)
                val halfLen = (maxBytes / 2).toInt()
                val trimmed = if (existing.length > halfLen) {
                    "[System] Log truncated due to size limit...\n" + existing.substring(existing.length - halfLen)
                } else existing
                writeFile(fileName, trimmed)
            }
            FileOutputStream(file, true).use { output ->
                output.write(content.toByteArray(StandardCharsets.UTF_8))
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Creates a new file in the workspace.
     */
    fun createFile(fileName: String, initialContent: String = ""): Boolean {
        val sanitized = sanitizeFileName(fileName)
        if (sanitized.isBlank()) return false
        val file = File(workspaceDir, sanitized)
        if (file.exists()) {
            log("[Workspace Warning] File '$sanitized' already exists.")
            return false
        }
        return writeFile(sanitized, initialContent)
    }

    /**
     * Deletes a file from the workspace. Protects index.html and manifest.json from accidental deletion.
     */
    fun deleteFile(fileName: String): Boolean {
        val sanitized = sanitizeFileName(fileName)
        if (sanitized == "index.html" || sanitized == "manifest.json") {
            log("[Workspace Warning] Cannot delete protected core file '$sanitized'.")
            return false
        }
        return try {
            val file = File(workspaceDir, sanitized)
            if (file.exists()) {
                val deleted = file.delete()
                log("[Workspace] Deleted '$sanitized': $deleted")
                deleted
            } else {
                false
            }
        } catch (e: Exception) {
            log("[Workspace Error] Failed to delete '$sanitized': ${e.message}")
            false
        }
    }

    companion object {
        /**
         * Resolves MIME type based on file extension.
         */
        fun getMimeType(fileName: String): String {
            val lower = fileName.lowercase()
            return when {
                lower.endsWith(".html") || lower.endsWith(".htm") -> "text/html"
                lower.endsWith(".css") -> "text/css"
                lower.endsWith(".js") || lower.endsWith(".mjs") -> "application/javascript"
                lower.endsWith(".json") -> "application/json"
                lower.endsWith(".svg") -> "image/svg+xml"
                lower.endsWith(".png") -> "image/png"
                lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
                lower.endsWith(".gif") -> "image/gif"
                lower.endsWith(".webp") -> "image/webp"
                lower.endsWith(".woff2") -> "font/woff2"
                lower.endsWith(".woff") -> "font/woff"
                lower.endsWith(".ttf") -> "font/ttf"
                lower.endsWith(".txt") -> "text/plain"
                else -> "application/octet-stream"
            }
        }
    }
}
