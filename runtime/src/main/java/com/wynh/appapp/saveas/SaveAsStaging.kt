package com.wynh.appapp.saveas

import com.wynh.appapp.channel.BoundedIo
import com.wynh.appapp.workspace.WorkspaceFiles
import com.wynh.appapp.workspace.WorkspacePath
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Thrown when a staging admission is refused; [code] is `busy` or `gone`. */
class StagingRefused(val code: String) : Exception(code)

/**
 * Snapshots outbound bytes before the system picker opens, outside every workspace. One instance
 * per directory ([shared]) owns the admission count, the active-owner set and app deletion, all
 * under one lock; callbacks into other components (is the app still installed?) never run while it
 * is held, so no lock order against the repository exists.
 *
 * There is no retry journal: a directory carrying the `writing` marker at process start was
 * interrupted mid-write, so its outcome is unknown and it is discarded without being written again.
 * A sweep never touches a request being written. A request this process owns but never wrote (its
 * picker result never arrived, for example because the task was removed while the picker was open)
 * is abandoned after [RETENTION_MS] of ownership, so it cannot hold an admission slot forever.
 */
class SaveAsStaging private constructor(private val root: File) {
    companion object {
        const val RETENTION_MS = 60 * 60 * 1000L
        const val MAX_ACTIVE = 4
        private const val DATA = "data"
        private const val WRITING = "writing"
        private val instances = ConcurrentHashMap<String, SaveAsStaging>()
        private val idPattern = Regex("[A-Za-z0-9_-]{1,100}")
        fun valid(value: String) = idPattern.matches(value)
        /** The one instance for [root], however many sessions or callers ask. */
        fun shared(root: File): SaveAsStaging = instances.getOrPut(root.absoluteFile.path) { SaveAsStaging(root.absoluteFile) }
    }

    private val lock = Any()
    /** Owned requests and when this process took ownership of each. */
    private val active = HashMap<String, Long>()
    private val generations = HashMap<String, Long>()
    private fun key(subappId: String, requestId: String) = "$subappId/$requestId"

    fun newId(): String = UUID.randomUUID().toString()
    private fun dir(subappId: String, requestId: String): File {
        require(valid(subappId) && valid(requestId))
        return File(root, "$subappId/$requestId")
    }
    fun data(subappId: String, requestId: String) = File(dir(subappId, requestId), DATA)
    fun exists(subappId: String, requestId: String) = data(subappId, requestId).isFile
    fun activeCount(): Int = synchronized(lock) { active.size }

    /**
     * Admits one request atomically (cap, ownership, directory creation), then fills its data file
     * outside the lock. [exists] is evaluated outside the lock; an app deleted at any point after it
     * was read leaves no staging behind, because deletion bumps the generation and removes the tree.
     */
    fun stage(subappId: String, requestId: String, exists: () -> Boolean = { true }, fill: (File) -> Unit) {
        val directory = dir(subappId, requestId)
        val generation = synchronized(lock) { generations[subappId] ?: 0L }
        if (!exists()) throw StagingRefused("gone")
        sweep()
        synchronized(lock) {
            if ((generations[subappId] ?: 0L) != generation) throw StagingRefused("gone")
            if (active.size >= MAX_ACTIVE) throw StagingRefused("busy")
            check(directory.mkdirs())
            active[key(subappId, requestId)] = System.currentTimeMillis()
        }
        try { fill(File(directory, DATA)) } catch (error: Throwable) { discard(subappId, requestId); throw error }
    }

    /** A restored picker result adopts a request left by an earlier process, so sweeps leave it alone. */
    fun adopt(subappId: String, requestId: String): Boolean = synchronized(lock) {
        if (!dir(subappId, requestId).isDirectory) return false
        active[key(subappId, requestId)] = System.currentTimeMillis(); true
    }

    fun markWriting(subappId: String, requestId: String) { File(dir(subappId, requestId), WRITING).writeText("1") }

    fun discard(subappId: String, requestId: String) {
        val directory = dir(subappId, requestId)
        synchronized(lock) { active -= key(subappId, requestId) }
        runCatching { directory.deleteRecursively() }
        runCatching { directory.parentFile?.takeIf { it.isDirectory && it.list().isNullOrEmpty() }?.delete() }
    }

    /**
     * Age-based sweep; at process start ([startup]) it also discards interrupted writes. Owned requests
     * are skipped while being written or until their ownership is older than [RETENTION_MS].
     */
    fun sweep(now: Long = System.currentTimeMillis(), startup: Boolean = false) = synchronized(lock) {
        root.listFiles()?.filter { it.isDirectory }?.forEach { app ->
            app.listFiles()?.filter { it.isDirectory }?.forEach { request ->
                val owned = key(app.name, request.name)
                val writing = File(request, WRITING).exists()
                val ownedSince = active[owned]
                if (ownedSince != null) {
                    if (!writing && now - ownedSince > RETENTION_MS) { active -= owned; request.deleteRecursively() }
                    return@forEach
                }
                if ((startup && writing) || now - request.lastModified() > RETENTION_MS) request.deleteRecursively()
            }
            if (app.list().isNullOrEmpty()) app.delete()
        }
        // Android may clear the cache under an owned request; its expired ownership must not keep a slot.
        active.entries.removeAll { now - it.value > RETENTION_MS && !File(root, it.key).isDirectory }
        Unit
    }

    fun deleteApp(subappId: String) {
        if (!valid(subappId)) return
        synchronized(lock) {
            generations[subappId] = (generations[subappId] ?: 0L) + 1
            active.keys.removeAll { it.startsWith("$subappId/") }
            File(root, subappId).deleteRecursively()
        }
    }
}

/** Reads one workspace file for a save: the caller holds the workspace lock and has checked the request's generation. */
object SaveAsSource {
    class Refusal(val code: String) : Exception(code)

    fun snapshot(files: WorkspaceFiles, path: String, target: File) {
        val source = when (val resolved = WorkspaceFiles.resolve(files.root, path)) {
            is WorkspacePath.Valid -> resolved.file
            is WorkspacePath.Invalid -> throw Refusal("invalid_arguments")
        }
        if (!Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS)) throw Refusal("not_found")
        try {
            Files.newInputStream(source.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
                FileOutputStream(target).use { out -> BoundedIo.copy(input, out, SaveAsPolicy.MAX_FILE_BYTES); out.fd.sync() }
            }
        } catch (_: BoundedIo.LimitExceeded) { throw Refusal("too_large") }
    }
}
