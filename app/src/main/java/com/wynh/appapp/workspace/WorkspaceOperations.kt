package com.wynh.appapp.workspace

import com.wynh.appapp.updates.WorkspaceLocks
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Repository lock precedes workspace locks. No UI callbacks belong inside these transactions. */
object WorkspaceOperations {
    private val owners = ConcurrentHashMap<String, Lease>()
    private val versions = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    fun version(root: File): Long = versions[root.canonicalPath]?.get() ?: 0L
    fun isBusy(root: File): Boolean = owners.containsKey(root.canonicalPath)
    fun checkWritable(root: File) {
        val owner = owners[root.canonicalPath]
        check(owner == null || owner.thread === Thread.currentThread()) { "A workspace operation is in progress" }
    }
    class Lease internal constructor(private val root: File, private val key: String) : AutoCloseable {
        @Volatile internal var thread: Thread? = null
        fun <T> run(operation: () -> T): T {
            check(owners[key] === this && thread == null) { "Workspace operation is no longer available" }
            thread = Thread.currentThread()
            try { return synchronized(WorkspaceLocks.forWorkspace(root)) { operation() } }
            finally { thread = null }
        }
        override fun close() { owners.remove(key, this) }
    }

    /**
     * Reserve on the UI thread before suspension, then run the disk work on I/O. While any
     * reservation is held, bridge calls fail fast and the asset server answers 503. A [replacement]
     * swaps or removes code or data wholesale and also advances the document generation, so pages
     * loaded earlier can no longer write and must reload. Other operations (a single-file save, a
     * snapshot, a read-only backup) only exclude competing operations: running pages keep their
     * generation and need no reload.
     */
    fun reserve(root: File, replacement: Boolean = true): Lease {
        val key = root.canonicalPath
        val lease = Lease(root, key)
        check(owners.putIfAbsent(key, lease) == null) { "A workspace operation is in progress" }
        if (replacement) versions.computeIfAbsent(key) { java.util.concurrent.atomic.AtomicLong() }.incrementAndGet()
        return lease
    }
    fun <T> run(root: File, replacement: Boolean = true, operation: () -> T): T = reserve(root, replacement).use { it.run(operation) }

    /**
     * Finishes interrupted work, never waiting for an operation that owns the workspace: that
     * operation recovers at its own start. Safe to call from any thread.
     */
    fun recoverIfIdle(root: File, also: () -> Unit = {}) {
        val lease = try { reserve(root, replacement = false) } catch (_: IllegalStateException) { return }
        lease.use { it.run { recover(root); also() } }
    }

    private fun staging(root: File) = File(root.parentFile, "workspace_import_staging")
    private fun previous(root: File) = File(root.parentFile, "workspace_import_old")

    /** Fully prepare and sync new bytes before the two-rename activation; the old tree is recoverable. */
    fun replace(root: File, rename: (File, File) -> Boolean = File::renameTo, prepare: (File) -> Unit) {
        recover(root)
        val staged = staging(root)
        val old = previous(root)
        try {
            check(staged.mkdirs()) { "Could not create import staging" }
            prepare(staged)
            require(File(staged, "index.html").isFile) { "Workspace must contain index.html at its root" }
            WorkspaceFiles.syncTree(staged)
            if (root.exists()) check(rename(root, old)) { "Could not move old workspace aside" }
            try { check(rename(staged, root)) { "Could not activate imported workspace" } }
            catch (e: Exception) {
                if (old.exists() && !root.exists()) check(old.renameTo(root)) { "Workspace recovery is required" }
                throw e
            }
            old.deleteRecursively()
        } finally { staged.deleteRecursively() }
    }

    /** What a text restore deliberately did not touch. */
    data class TextRestore(val keptUnreadable: List<String>, val ignoredEntries: List<String>)

    /**
     * Replaces known text files with [snapshot]. A text-extension file that is not valid UTF-8
     * (or whose name the snapshot format cannot carry) was never captured, so restore neither
     * deletes nor overwrites it; the same holds for binary and unknown formats.
     */
    fun restoreText(root: File, snapshot: Map<String, String>, rename: (File, File) -> Boolean = File::renameTo): TextRestore {
        val kept = mutableListOf<String>()
        val ignored = mutableListOf<String>()
        replace(root, rename) { staged ->
            WorkspaceFiles.copyTree(root, staged)
            val files = WorkspaceFiles(staged)
            WorkspaceFiles.regularFiles(staged).toList().forEach { file ->
                val name = file.relativeTo(staged).invariantSeparatorsPath
                if (!WorkspaceFiles.isHistoryText(name)) return@forEach
                if (WorkspaceFiles.normalize(name) != name || WorkspaceFiles.decodeUtf8(file.readBytes()) == null) {
                    kept += name; return@forEach
                }
                if (name !in snapshot) check(file.delete())
            }
            snapshot.forEach { (name, text) ->
                require(WorkspaceFiles.normalize(name) == name) { "Invalid snapshot path" }
                when {
                    !WorkspaceFiles.isHistoryText(name) -> ignored.add(name)
                    name in kept -> Unit
                    else -> files.writeText(name, text)
                }
            }
        }
        return TextRestore(kept, ignored)
    }

    fun recover(root: File) {
        val old = previous(root)
        if (old.exists() && !root.exists()) check(old.renameTo(root)) { "Could not restore interrupted workspace operation" }
        check(!old.exists() || old.deleteRecursively()) { "Could not clean previous workspace" }
        check(!staging(root).exists() || staging(root).deleteRecursively()) { "Could not clean workspace staging" }
        // Interrupted individual writes are never visible as user files.
        if (root.isDirectory) root.walkTopDown().onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .filter { it.isFile && it.name.startsWith(".app2-write-") }.forEach { it.delete() }
    }
}
