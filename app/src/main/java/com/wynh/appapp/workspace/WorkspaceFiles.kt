package com.wynh.appapp.workspace

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

sealed interface WorkspacePath {
    data class Valid(val relative: String, val file: File) : WorkspacePath
    data class Invalid(val reason: String) : WorkspacePath
}

/** One path and byte-I/O implementation. Callers hold the workspace lock for mutations. */
class WorkspaceFiles(val root: File) {
    fun file(path: String): File = when (val checked = resolve(root, path)) {
        is WorkspacePath.Valid -> checked.file
        is WorkspacePath.Invalid -> throw IllegalArgumentException(checked.reason)
    }

    fun readBytes(path: String, maximum: Long = Long.MAX_VALUE): ByteArray {
        val target = file(path)
        require(target.isFile) { "File does not exist: $path" }
        require(target.length() <= maximum) { "File exceeds read limit" }
        return Files.newInputStream(target.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= maximum) { "File exceeds read limit" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    fun readText(path: String, maximum: Long = Long.MAX_VALUE): String = strictUtf8().decode(ByteBuffer.wrap(readBytes(path, maximum))).toString()

    fun writeBytes(path: String, bytes: ByteArray) = atomicWrite(file(path), bytes)
    fun writeText(path: String, text: String) = writeBytes(path, text.toByteArray(StandardCharsets.UTF_8))

    /**
     * Appends to a bounded log. The workspace root is never created here: a log line queued for an
     * app that was deleted meanwhile must not resurrect it. Callers hold the workspace lock.
     */
    fun appendBounded(path: String, content: String, maxBytes: Long = 200_000) {
        check(root.isDirectory) { "Workspace no longer exists" }
        val target = file(path)
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        if (target.length() > maxBytes) writeBytes(path, truncatedLog(readBytes(path), (maxBytes / 2).toInt()))
        FileOutputStream(target, true).use { it.write(content.toByteArray(StandardCharsets.UTF_8)) }
    }

    companion object {
        private fun strictUtf8() = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)

        /** The text, or null when [bytes] are not valid UTF-8 (history leaves such files untouched). */
        fun decodeUtf8(bytes: ByteArray): String? =
            try { strictUtf8().decode(ByteBuffer.wrap(bytes)).toString() } catch (_: CharacterCodingException) { null }

        /** Keeps the last [keep] bytes of a log. Decoding is lenient so a stray byte cannot stop logging for good. */
        fun truncatedLog(bytes: ByteArray, keep: Int): ByteArray {
            val tail = String(bytes, maxOf(0, bytes.size - keep), minOf(keep, bytes.size), StandardCharsets.UTF_8)
            return ("[System] Log truncated\n" + tail).toByteArray(StandardCharsets.UTF_8)
        }

        /** Flushes every regular file below [directory] to storage before it is published. */
        fun syncTree(directory: File) = regularFiles(directory).forEach { file -> FileOutputStream(file, true).use { it.fd.sync() } }

        fun copyTree(source: File, destination: File) {
            require(source.isDirectory && !Files.isSymbolicLink(source.toPath()))
            check(destination.isDirectory || destination.mkdirs())
            source.walkTopDown().forEach { file ->
                require(!Files.isSymbolicLink(file.toPath())) { "Symbolic link in workspace" }
                val target = File(destination, file.relativeTo(source).invariantSeparatorsPath)
                if (file.isDirectory) check(target.isDirectory || target.mkdirs())
                else if (!file.name.startsWith(".app2-write-")) file.copyTo(target, overwrite = true)
            }
        }

        /** Failed writes leave the old file intact; publication is a same-directory atomic rename. */
        fun atomicWrite(target: File, bytes: ByteArray, beforePublish: () -> Unit = {}) {
            require(!Files.isSymbolicLink(target.toPath())) { "Symbolic link at destination" }
            require(!target.isDirectory) { "Directory at destination" }
            val parent = target.absoluteFile.parentFile!!
            check(parent.isDirectory || parent.mkdirs()) { "Could not create destination directory" }
            val temporary = Files.createTempFile(parent.toPath(), ".app2-write-", ".tmp").toFile()
            try {
                FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
                beforePublish()
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { temporary.delete() }
        }

        fun isHistoryText(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in setOf(
            "html", "htm", "css", "js", "mjs", "json", "txt", "md", "svg", "csv", "tsv", "yaml", "yml", "xml", "py", "ts"
        )

        private val UNSAFE_CHARACTER = Regex("[^A-Za-z0-9._-]")

        /**
         * Validates a bridge path and returns its normalized form, without touching the disk.
         * Returns null and reports the reason through [onInvalid] when the path is rejected.
         */
        fun normalize(rawPath: String, onInvalid: (String) -> Unit = {}): String? {
            fun invalid(reason: String): String? { onInvalid(reason); return null }
            if (rawPath.isEmpty()) return invalid("empty path")
            if (rawPath.length > BridgeFileLimits.MAX_PATH_CHARS) {
                return invalid("path exceeds ${BridgeFileLimits.MAX_PATH_CHARS} characters")
            }
            rawPath.forEach { c ->
                if (c == '\u0000' || Character.isISOControl(c)) return invalid("control character in path")
                if (c == '\\') return invalid("backslash in path")
            }
            if (rawPath.endsWith('/')) return invalid("path ends with a slash")
            // Characters outside the safe set keep their historical mapping to `_`, so files that
            // existing subapps saved under names with spaces or non-ASCII letters stay reachable.
            val segments = rawPath.split('/')
                .map { it.trim().replace(UNSAFE_CHARACTER, "_") }
                .filter { it.isNotEmpty() && it != "." }
            if (segments.isEmpty()) return invalid("empty path")
            if (segments.size > BridgeFileLimits.MAX_SEGMENTS) {
                return invalid("path nests deeper than ${BridgeFileLimits.MAX_SEGMENTS} segments")
            }
            for (segment in segments) {
                if (segment.startsWith(".app2-write-")) return invalid("reserved temporary path")
                if (segment == "..") return invalid("parent-directory segment")
                if (segment.length > BridgeFileLimits.MAX_SEGMENT_CHARS) {
                    return invalid("segment exceeds ${BridgeFileLimits.MAX_SEGMENT_CHARS} characters")
                }
            }
            return segments.joinToString("/")
        }

        /**
         * Validates [rawPath] against [root]: syntax via [normalize], no symbolic link at any
         * existing component below the root, and canonical containment on a path boundary.
         */
        fun resolve(root: File, rawPath: String): WorkspacePath {
            var reason = ""
            val relative = normalize(rawPath) { reason = it } ?: return WorkspacePath.Invalid(reason)
            return try {
                val rootFile = root.canonicalFile
                var current = rootFile
                for (segment in relative.split('/')) {
                    current = File(current, segment)
                    if (Files.isSymbolicLink(current.toPath())) {
                        return WorkspacePath.Invalid("symbolic link in path")
                    }
                }
                if (!isStrictlyInside(rootFile.path, current.canonicalPath)) {
                    return WorkspacePath.Invalid("path resolves outside the workspace")
                }
                WorkspacePath.Valid(relative, current)
            } catch (e: Exception) {
                WorkspacePath.Invalid("path could not be resolved (${e.message})")
            }
        }

        /** True when [candidate] is a descendant of [root]; both must be canonical paths. */
        fun isStrictlyInside(root: String, candidate: String): Boolean {
            val prefix = if (root.endsWith(File.separator)) root else root + File.separator
            return candidate.length > prefix.length && candidate.startsWith(prefix)
        }

        /** True when [candidate] is [root] itself or a descendant; both must be canonical paths. */
        fun isWithin(root: String, candidate: String): Boolean =
            candidate == root || isStrictlyInside(root, candidate)

        /** Regular files below [root], skipping symbolic links and never descending through one. */
        fun regularFiles(root: File): Sequence<File> {
            if (!root.isDirectory) return emptySequence()
            return root.walkTopDown()
                .onEnter { it == root || !Files.isSymbolicLink(it.toPath()) }
                .filter { !it.name.startsWith(".app2-write-") && Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
        }

        /** Bytes held by regular files in the workspace, as counted against the quota. */
        fun usedBytes(root: File): Long = regularFiles(root).sumOf { it.length() }

        fun printable(rawPath: String): String {
            val clipped = if (rawPath.length > 120) rawPath.take(120) + "..." else rawPath
            return clipped.map { if (Character.isISOControl(it)) '?' else it }.joinToString("")
        }
    }
}
