package com.wynh.appapp.workspace

import android.content.Context
import com.wynh.appapp.templates.DefaultWebApp
import com.wynh.appapp.updates.WorkspaceLocks
import java.io.File

data class WorkspaceFile(val name: String, val size: Long, val lastModified: Long, val isCore: Boolean)

/** Read-only construction. Only explicit creation/reset operations seed starter files. */
class WorkspaceManager(
    private val context: Context,
    private val onLogListener: ((String) -> Unit)? = null,
    val workspaceDir: File
) {
    val files = WorkspaceFiles(workspaceDir)
    private fun log(message: String) { onLogListener?.invoke(message) }
    fun getFile(fileName: String): File = files.file(fileName)

    fun applyTemplate(template: Map<String, String>): Boolean = mutate("Apply template") {
        template.forEach { (name, content) -> files.writeText(name, content) }
    }
    fun resetWorkspace(): Boolean = applyTemplate(DefaultWebApp.getStarterFiles(context))
    fun factoryResetWorkspace(): Boolean = mutate("Reset workspace") {
        WorkspaceOperations.replace(workspaceDir) { staged ->
            val target = WorkspaceFiles(staged)
            DefaultWebApp.getStarterFiles(context).forEach { (name, content) -> target.writeText(name, content) }
        }
    }

    fun listFiles(): List<WorkspaceFile> {
        val core = listOf("index.html", "css/style.css", "js/app.js", "manifest.json", "app.log", "js/bridge.js", "AGENTS.md", "PACKAGING.md")
        return WorkspaceFiles.regularFiles(workspaceDir).map { file ->
            val name = file.relativeTo(workspaceDir).invariantSeparatorsPath
            WorkspaceFile(name, file.length(), file.lastModified(), name in core)
        }.sortedWith(compareBy<WorkspaceFile> { if (it.isCore) core.indexOf(it.name) else Int.MAX_VALUE }.thenBy { it.name }).toList()
    }

    fun readFile(fileName: String): String = try {
        val file = getFile(fileName)
        if (!file.exists()) "" else files.readText(fileName, BridgeFileLimits.MAX_TEXT_BYTES)
    } catch (e: Exception) { throw IllegalStateException("Could not read $fileName", e) }

    fun writeFile(fileName: String, content: String): Boolean = mutate("Save $fileName") { files.writeText(fileName, content) }

    fun appendToFile(fileName: String, content: String, maxBytes: Long = 200_000): Boolean =
        runCatching {
            synchronized(WorkspaceLocks.forWorkspace(workspaceDir)) { files.appendBounded(fileName, content, maxBytes) }
        }.isSuccess

    fun createFile(fileName: String, initialContent: String = ""): Boolean = mutate("Create $fileName") {
        check(!getFile(fileName).exists()) { "File already exists" }
        files.writeText(fileName, initialContent)
    }

    fun deleteFile(fileName: String): Boolean = mutate("Delete $fileName") {
        val relative = WorkspaceFiles.normalize(fileName) ?: error("Invalid path")
        require(relative !in setOf("index.html", "manifest.json")) { "Protected core file" }
        check(getFile(relative).isFile && getFile(relative).delete()) { "Could not delete file" }
    }

    private fun mutate(label: String, action: () -> Unit): Boolean {
        val result = runCatching { WorkspaceOperations.checkWritable(workspaceDir); synchronized(WorkspaceLocks.forWorkspace(workspaceDir)) { action() } }
        log(if (result.isSuccess) "[Workspace] $label" else "[Workspace Error] $label: ${result.exceptionOrNull()?.message}")
        return result.isSuccess
    }

    companion object {
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
                lower.endsWith(".md") -> "text/markdown"
                else -> "application/octet-stream"
            }
        }
    }
}
