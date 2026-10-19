package com.wynh.appapp.updates

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File

/** Native metadata stays beside the workspace, outside packages and web bridge access. */
data class InstalledUpdate(val appId: String, val version: String, val source: GitHubSource, val commit: String, val hashes: Map<String, String>, val seeds: Set<String>)
data class UpdatePreview(val added: List<String>, val changed: List<String>, val removed: List<String>, val edited: List<String>, val adoption: Boolean)

class WorkspaceUpdater(private val directory: File) {
    private val workspace = File(directory, "workspace")
    private val state = File(directory, "update-state.json")
    private val backup = File(directory, "update-backup")
    private val next = File(directory, "update-next")
    private val journal = File(directory, "update-journal")
    private val gson = Gson()

    fun installed(): InstalledUpdate? = if (state.isFile) gson.fromJson(state.readText(), InstalledUpdate::class.java) else null

    fun currentVersion(): String? = runCatching {
        val value = JsonParser.parseString(File(workspace, "manifest.json").readText()).asJsonObject.get("version")
        if (value?.isJsonPrimitive == true && value.asJsonPrimitive.isString) value.asString.takeIf { it.isNotBlank() } else null
    }.getOrNull()

    /** Compares publisher content, independently of local edits and unrelated repository commits. */
    fun publisherUnchanged(pkg: UpdatePackage, files: File): Boolean {
        val old = installed() ?: return false
        return old.appId == pkg.appId && old.source == pkg.source &&
            currentVersion() == pkg.version && old.version == pkg.version && old.seeds == pkg.seeds &&
            old.hashes == pkg.managed.associateWith { digest(File(files, it)) }
    }

    /** Describes how the package's publisher identity or GitHub source differs from the installed one, or null when they agree. */
    fun identityMismatch(pkg: UpdatePackage): String? {
        val old = installed() ?: return null
        return buildList {
            if (old.appId != pkg.appId) add("Publisher ID: installed ${old.appId}, publisher now declares ${pkg.appId}")
            if (old.source != pkg.source) add("Source: installed ${old.source.display}, package declares ${pkg.source.display}")
        }.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /** A mismatch is rejected unless [acceptMismatch] records the user's explicit confirmation of it. */
    fun preview(pkg: UpdatePackage, files: File, acceptMismatch: Boolean = false): UpdatePreview {
        val old = installed()
        require(acceptMismatch || identityMismatch(pkg) == null) { "Publisher identity or GitHub source does not match this installation" }
        val added = pkg.managed.filter { !File(workspace, it).exists() }
        val changed = pkg.managed.filter { File(workspace, it).isFile && digest(File(workspace, it)) != digest(File(files, it)) }
        val removed = old?.hashes?.keys.orEmpty().filter { it !in pkg.managed && it !in pkg.seeds }
        val edited = old?.hashes?.filter { (path, hash) -> !File(workspace, path).isFile || digest(File(workspace, path)) != hash }?.keys?.toList()
            ?: changed
        return UpdatePreview(added, changed, removed, edited, old == null)
    }

    fun reviewedFiles(pkg: UpdatePackage): Map<String, String?> = synchronized(WorkspaceLocks.forWorkspace(workspace)) {
        (pkg.managed + installed()?.hashes?.keys.orEmpty()).associateWith { path ->
            val file = File(workspace, safePath(path))
            if (file.isFile) digest(file) else null
        }
    }

    fun install(pkg: UpdatePackage, files: File, commit: String, hostCompatibilityLevel: Long, reviewed: Map<String, String?>? = null, acceptMismatch: Boolean = false, beforeActivation: () -> Unit = {}) = synchronized(WorkspaceLocks.forWorkspace(workspace)) {
        require(pkg.minimumHostVersion <= hostCompatibilityLevel) { incompatibleHostMessage(pkg.minimumHostVersion, hostCompatibilityLevel) }
        require(commit.matches(Regex("[a-f0-9]{40}"))) { "Invalid commit" }
        val checked = UpdatePackage.parse(File(files, "manifest.json").readText())
        require(checked == pkg) { "Package metadata changed" }
        (pkg.managed + pkg.seeds).forEach { require(File(files, safePath(it)).isFile) { "Missing package file: $it" } }
        recover()
        require(reviewed == null || reviewed == reviewedFiles(pkg)) { "App code changed since review; check the update again" }
        val preview = preview(pkg, files, acceptMismatch)
        val hashes = pkg.managed.associateWith { digest(File(files, it)) }
        prepareBackup()
        com.wynh.appapp.workspace.WorkspaceFiles.copyTree(workspace, next)
        preview.removed.forEach { check(!File(next, it).exists() || File(next, it).delete()) { "Could not remove $it" } }
        pkg.managed.forEach { copyManaged(files, next, it) }
        pkg.seeds.forEach { if (!File(next, it).exists()) copyManaged(files, next, it) }
        verifyPreserved(pkg.managed + preview.removed.toSet())
        beforeActivation()
        commitWorkspace(InstalledUpdate(pkg.appId, pkg.version, pkg.source, commit, hashes, pkg.seeds))
    }

    private fun copyManaged(source: File, target: File, path: String) {
        val file = File(target, safePath(path))
        require(!file.isDirectory) { "A user directory conflicts with package file: $path" }
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        File(source, path).copyTo(file, overwrite = true)
    }

    private fun prepareBackup() {
        require(workspace.isDirectory) { "Workspace is missing" }
        require(directory.usableSpace > workspace.walkTopDown().filter { it.isFile }.sumOf { it.length() } * 3 + 20 * 1024 * 1024) { "Insufficient free space for recovery backup" }
        val fresh = File(directory, "update-backup-new")
        fresh.deleteRecursively()
        com.wynh.appapp.workspace.WorkspaceFiles.copyTree(workspace, File(fresh, "workspace"))
        if (state.isFile) state.copyTo(File(fresh, "state.json"))
        require(treeHashes(workspace) == treeHashes(File(fresh, "workspace"))) { "Backup verification failed" }
        backup.deleteRecursively()
        check(fresh.renameTo(backup)) { "Could not publish recovery backup" }
        next.deleteRecursively()
    }

    private fun verifyPreserved(replaced: Set<String>) {
        val before = treeHashes(workspace).filterKeys { it !in replaced }
        val after = treeHashes(next)
        require(before.all { (path, hash) -> after[path] == hash }) { "User file preservation failed" }
    }

    private fun commitWorkspace(value: InstalledUpdate) {
        val metadata = File(directory, "update-state-next.json")
        durableWrite(metadata, gson.toJson(value))
        durableWrite(journal, "recover")
        try {
            val old = File(directory, "update-old")
            check(!old.exists()) { "Old workspace was not recovered" }
            check(workspace.renameTo(old)) { "Could not move old workspace" }
            check(next.renameTo(workspace)) { "Could not activate update" }
            check(metadata.renameTo(state)) { "Could not save update metadata" }
            check(journal.delete()) { "Could not complete update" }
            old.deleteRecursively()
        } catch (e: Exception) { recover(); throw e }
    }

    /** Any incomplete transaction restores its verified full backup before an app opens. */
    fun recover() = synchronized(WorkspaceLocks.forWorkspace(workspace)) {
        if (journal.exists()) {
            require(File(backup, "workspace/index.html").isFile) { "Update recovery backup is missing" }
            val recovery = File(directory, "update-recover")
            recovery.deleteRecursively()
            com.wynh.appapp.workspace.WorkspaceFiles.copyTree(File(backup, "workspace"), recovery)
            require(treeHashes(recovery) == treeHashes(File(backup, "workspace"))) { "Recovery verification failed" }
            workspace.deleteRecursively()
            check(recovery.renameTo(workspace)) { "Could not restore workspace" }
            val previous = File(backup, "state.json")
            if (previous.isFile) { val temp = File(directory, "update-state-recover"); previous.copyTo(temp, overwrite = true); check(temp.renameTo(state)) }
            else state.delete()
            check(journal.delete())
        }
        File(directory, "update-old").deleteRecursively()
        next.deleteRecursively()
        File(directory, "update-state-next.json").delete()
    }

    private fun durableWrite(file: File, text: String) { file.outputStream().use { it.write(text.toByteArray()); it.fd.sync() } }
    private fun treeHashes(root: File): Map<String, String> = com.wynh.appapp.workspace.WorkspaceFiles.regularFiles(root).associate { it.relativeTo(root).invariantSeparatorsPath to digest(it) }
}
