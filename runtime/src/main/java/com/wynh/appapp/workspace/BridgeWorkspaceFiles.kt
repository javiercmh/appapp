package com.wynh.appapp.workspace

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Base64

/**
 * Limits for file operations that subapp JavaScript requests through the native bridge.
 * They apply only to bridge calls: the editor, history restore, template install, ZIP import and
 * native updates write through [WorkspaceManager] and their own validators instead.
 */
object BridgeFileLimits {
    /** Longest accepted path, in UTF-16 code units, before normalization. */
    const val MAX_PATH_CHARS = 512

    /** Longest accepted single path segment (a common file system limit). */
    const val MAX_SEGMENT_CHARS = 255

    /** Deepest accepted nesting, counted in segments including the file name. */
    const val MAX_SEGMENTS = 16

    /** Largest UTF-8 file accepted by `writeFile` or returned by `readFile`. */
    const val MAX_TEXT_BYTES = 16L * 1024 * 1024

    /**
     * Largest decoded file accepted by `writeFileBase64` or returned by `readFileBase64`.
     * A JavascriptInterface call blocks the JS thread and marshals the payload across JNI as a
     * Java String (about 2 bytes per char), so binary payloads stay smaller than text ones.
     */
    const val MAX_BINARY_BYTES = 6L * 1024 * 1024

    /** Largest raw base64 argument accepted before decoding (about 6 MiB decoded, plus a data-URL prefix). */
    const val MAX_BASE64_CHARS = 8_400_000

    /** Total size of all regular files in one workspace after a bridge write completes. */
    const val WORKSPACE_QUOTA_BYTES = 512L * 1024 * 1024
}

/**
 * Validated file operations for one workspace, as exposed to JavaScript by the native bridge.
 *
 * Unsafe paths are rejected rather than rewritten:
 * - Accepted forms: `data/entries.json`, `/data/entries.json`, `./data/entries.json`, and
 *   duplicate slashes (`data//entries.json`). These normalize to the same relative path.
 *   A `.` segment is dropped.
 * - Rejected: empty paths, a trailing slash, `..` segments, backslashes, NUL and other control
 *   characters, and paths or segments over [BridgeFileLimits].
 * - Compatibility mapping: other characters outside `A-Z a-z 0-9 . _ -` (spaces, `%`, `:`,
 *   non-ASCII) become `_`, and segments are trimmed, exactly as earlier releases stored them.
 *   The mapping cannot produce a separator or a `..` segment.
 * - A symbolic link anywhere below the workspace root is refused; the resolved canonical path
 *   must lie inside the canonical root on an exact path-segment boundary.
 *
 * Every method returns the bridge's failure value (`""`, `false` or `null`) and logs the reason
 * instead of throwing. Callers hold the workspace lock.
 */
class BridgeWorkspaceFiles(
    private val root: File,
    private val log: (String) -> Unit = {}
) {

    fun writeText(rawPath: String, content: String): Boolean {
        if (content.length > BridgeFileLimits.MAX_TEXT_BYTES) {
            return refuse("write", rawPath, "content exceeds ${BridgeFileLimits.MAX_TEXT_BYTES} bytes")
        }
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > BridgeFileLimits.MAX_TEXT_BYTES) {
            return refuse("write", rawPath, "content exceeds ${BridgeFileLimits.MAX_TEXT_BYTES} bytes")
        }
        val path = resolveOrLog("write", rawPath) ?: return false
        return if (store(path, bytes)) {
            log("[Storage] Wrote ${content.length} chars to workspace file '${path.relative}' (${bytes.size} bytes)")
            true
        } else false
    }

    fun readText(rawPath: String): String {
        val path = resolveOrLog("read", rawPath) ?: return ""
        val bytes = load(path, BridgeFileLimits.MAX_TEXT_BYTES) ?: return ""
        val content = String(bytes, StandardCharsets.UTF_8)
        log("[Storage] Read ${content.length} chars from workspace file '${path.relative}'")
        return content
    }

    /** Accepts a bare base64 payload or a `data:<mime>;base64,...` URL. */
    fun writeBase64(rawPath: String, base64: String): Boolean {
        if (base64.length > BridgeFileLimits.MAX_BASE64_CHARS) {
            return refuse("write", rawPath, "${base64.length} base64 chars exceeds cap of ${BridgeFileLimits.MAX_BASE64_CHARS}")
        }
        val payload = if (base64.startsWith("data:")) base64.substringAfter("base64,", "") else base64
        if (payload.isEmpty()) return refuse("write", rawPath, "empty base64 payload")
        val bytes = try {
            // The MIME decoder tolerates the line breaks and missing padding found in data URLs.
            Base64.getMimeDecoder().decode(payload)
        } catch (e: IllegalArgumentException) {
            return refuse("write", rawPath, "invalid base64 (${e.message})")
        }
        if (bytes.size > BridgeFileLimits.MAX_BINARY_BYTES) {
            return refuse("write", rawPath, "decoded ${bytes.size} bytes exceeds ${BridgeFileLimits.MAX_BINARY_BYTES} bytes")
        }
        val path = resolveOrLog("write", rawPath) ?: return false
        return if (store(path, bytes)) {
            log("[Storage] Wrote binary '${path.relative}' (${bytes.size} bytes)")
            true
        } else false
    }

    /** Returns the file as unwrapped base64, or `""` when missing, invalid or over the cap. */
    fun readBase64(rawPath: String): String {
        val path = resolveOrLog("read", rawPath) ?: return ""
        val bytes = load(path, BridgeFileLimits.MAX_BINARY_BYTES) ?: return ""
        log("[Storage] Read binary '${path.relative}' (${bytes.size} bytes)")
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun exists(rawPath: String): Boolean {
        val path = WorkspaceFiles.resolve(root, rawPath) as? WorkspacePath.Valid ?: return false
        return Files.isRegularFile(path.file.toPath(), LinkOption.NOFOLLOW_LINKS)
    }

    /** Deletes one regular file and then removes parent directories left empty, up to the root. */
    fun delete(rawPath: String): Boolean {
        val path = resolveOrLog("delete", rawPath) ?: return false
        val target = path.file.toPath()
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return false
        return try {
            Files.delete(target)
            log("[Storage] Deleted file '${path.relative}': true")
            val rootFile = root.canonicalFile
            var parent = path.file.parentFile
            while (parent != null && parent != rootFile && WorkspaceFiles.isStrictlyInside(rootFile.path, parent.path)) {
                val children = parent.list()
                if (children == null || children.isNotEmpty() || !parent.delete()) break
                parent = parent.parentFile
            }
            true
        } catch (e: IOException) {
            log("[Storage Error] Failed to delete file '${path.relative}': ${e.message}")
            false
        }
    }

    /** Regular files in the workspace, without following symbolic links. */
    fun list(): List<WorkspaceEntry> = WorkspaceFiles.regularFiles(root).map { file ->
        WorkspaceEntry(file.relativeTo(root).invariantSeparatorsPath, file.length(), file.lastModified())
    }.toList()

    data class WorkspaceEntry(val name: String, val size: Long, val lastModified: Long)

    private fun store(path: WorkspacePath.Valid, bytes: ByteArray): Boolean {
        val target = path.file.toPath()
        return try {
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                return refuse("write", path.relative, "a directory exists at this path")
            }
            val existing = if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) Files.size(target) else 0L
            val usage = WorkspaceFiles.usedBytes(root)
            if (usage - existing + bytes.size > BridgeFileLimits.WORKSPACE_QUOTA_BYTES) {
                return refuse(
                    "write", path.relative,
                    "workspace quota of ${BridgeFileLimits.WORKSPACE_QUOTA_BYTES} bytes would be exceeded ($usage bytes used)"
                )
            }
            path.file.parentFile?.let { parent ->
                if (!parent.isDirectory && !parent.mkdirs()) {
                    return refuse("write", path.relative, "could not create parent directory")
                }
            }
            WorkspaceFiles(root).writeBytes(path.relative, bytes)
            true
        } catch (e: Exception) {
            log("[Storage Error] Failed to write file '${path.relative}': ${e.message}")
            false
        }
    }

    private fun load(path: WorkspacePath.Valid, maxBytes: Long): ByteArray? {
        val target = path.file.toPath()
        return try {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                log("[Storage] File '${path.relative}' does not exist yet.")
                return null
            }
            val size = Files.size(target)
            if (size > maxBytes) {
                log("[Storage Error] '${path.relative}' is $size bytes; exceeds read cap of $maxBytes bytes")
                return null
            }
            WorkspaceFiles(root).readBytes(path.relative, maxBytes)
        } catch (e: Exception) {
            log("[Storage Error] Failed to read file '${path.relative}': ${e.message}")
            null
        }
    }

    private fun resolveOrLog(operation: String, rawPath: String): WorkspacePath.Valid? =
        when (val result = WorkspaceFiles.resolve(root, rawPath)) {
            is WorkspacePath.Valid -> result
            is WorkspacePath.Invalid -> {
                refuse(operation, rawPath, result.reason)
                null
            }
        }

    private fun refuse(operation: String, rawPath: String, reason: String): Boolean {
        log("[Storage Error] Refused $operation of '${WorkspaceFiles.printable(rawPath)}': $reason")
        return false
    }

}
