package com.wynh.appapp.workspace

import android.content.Context
import com.wynh.appapp.templates.DefaultWebApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

data class SubappRecord(val id: String, val directory: File) {
    val workspaceDir: File get() = File(directory, "workspace")
}

/** Owns saved app identities and performs the one-time move from the former single workspace. */
class SubappRepository(private val context: Context) {
    private val root = File(context.filesDir, "subapps")
    private val index = File(root, "index.json")
    private val migrationMarker = File(root, "migration_complete")

    fun ensureMigrated(): List<SubappRecord> = synchronized(operationLock) {
        root.mkdirs()
        recoverBackupImport()
        if (!migrationMarker.exists()) migrateLegacyWorkspace()
        migrationMarker.takeIf { it.isFile }?.readText()?.let(::cleanupLegacyStorage)
        return@synchronized list()
    }

    fun list(): List<SubappRecord> = synchronized(operationLock) {
        root.mkdirs()
        val ids = try {
            val array = JSONArray(index.takeIf { it.exists() }?.readText() ?: "[]")
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(::validId) }
        } catch (_: Exception) { emptyList() }
        return@synchronized ids.distinct().mapNotNull(::resolve)
    }

    /** Persists a caller-provided permutation of the currently installed apps. */
    fun reorder(ids: List<String>) = synchronized(operationLock) {
        val currentIds = list().map { it.id }
        require(ids.size == currentIds.size && ids.toSet() == currentIds.toSet()) {
            "App order must contain every installed app exactly once"
        }
        writeIndex(ids)
    }

    fun resolve(id: String): SubappRecord? {
        if (!validId(id)) return null
        val dir = File(root, id)
        if (dir.isDirectory) com.wynh.appapp.updates.WorkspaceUpdater(dir).recover()
        return if (File(dir, "workspace/index.html").isFile) SubappRecord(id, dir) else null
    }

    fun createStarter(): SubappRecord = synchronized(operationLock) {
        val id = UUID.randomUUID().toString()
        val dir = File(root, id)
        val workspace = WorkspaceManager(context, workspaceDir = File(dir, "workspace"))
        check(workspace.resetWorkspace()) { "Could not initialize starter app" }
        register(id)
        WorkspaceHistoryManager(context, appDirectory = dir)
            .captureInitialVersion(workspace, "Starter Template")
        return@synchronized SubappRecord(id, dir)
    }

    fun createImportedFrom(sourceWorkspace: File): SubappRecord = synchronized(operationLock) {
        val id = UUID.randomUUID().toString()
        val dir = File(root, id)
        val destination = File(dir, "workspace")
        require(sourceWorkspace.isDirectory)
        sourceWorkspace.copyRecursively(destination, overwrite = false)
        if (!File(destination, "index.html").isFile) {
            destination.deleteRecursively()
            DefaultWebApp.getStarterFiles(context).forEach { (name, content) ->
                val file = File(destination, name)
                file.parentFile?.mkdirs()
                file.writeText(content)
            }
        }
        register(id)
        val importedWorkspace = WorkspaceManager(context, workspaceDir = destination)
        WorkspaceHistoryManager(context, appDirectory = dir)
            .captureInitialVersion(importedWorkspace, "Imported App")
        return@synchronized SubappRecord(id, dir)
    }

    fun delete(id: String): Boolean = synchronized(operationLock) {
        if (!validId(id)) return@synchronized false
        (context.applicationContext as? com.wynh.appapp.ai.AppAiApplication)?.ai?.cancelApp(id)
        val record = resolve(id) ?: return@synchronized false
        val deleted = record.directory.deleteRecursively()
        if (deleted) {
            context.getSharedPreferences("runtime_app_state_$id", Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences("runtime_scheduled_alarms_$id", Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences("runtime_notifications_$id", Context.MODE_PRIVATE).edit().clear().commit()
            writeIndex(list().map { it.id }.filterNot { it == id })
        }
        return@synchronized deleted
    }

    companion object {
        val operationLock = Any()
    }

    /** Captures all workspaces with a fixed library and every workspace write lock held. */
    fun stageBackup(destination: File): List<File> = synchronized(operationLock) {
        val records = list()
        fun capture(index: Int): List<File> {
            if (index < records.size) return synchronized(com.wynh.appapp.updates.WorkspaceLocks.forWorkspace(records[index].workspaceDir)) {
                capture(index + 1)
            }
            val bytes = records.sumOf { record -> record.workspaceDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
            require(destination.usableSpace > bytes + 20L * 1024 * 1024) { "Insufficient storage to stage backup" }
            return records.mapIndexed { number, record ->
                File(destination, "app-$number").also { record.workspaceDir.copyRecursively(it, overwrite = false) }
            }
        }
        capture(0)
    }

    /** Publishes all imported copies with one atomic index change. */
    fun importBackup(workspaces: List<File>): List<SubappRecord> = synchronized(operationLock) {
        recoverBackupImport()
        val current = list().map { it.id }
        val ids = workspaces.map { UUID.randomUUID().toString() }
        val staging = File(root, "backup-import-staging")
        check(!staging.exists() || staging.deleteRecursively()) { "Could not clean import staging" }
        check(staging.mkdirs())
        try {
            workspaces.forEachIndexed { index, workspace ->
                val directory = File(staging, ids[index])
                workspace.copyRecursively(File(directory, "workspace"))
                val manager = WorkspaceManager(context, workspaceDir = File(directory, "workspace"))
                check(WorkspaceHistoryManager(context, appDirectory = directory).captureInitialVersion(manager, "Subapp Backup")) {
                    "Could not save initial version"
                }
            }
            val journal = File(root, "backup-import-journal.json")
            val atomic = android.util.AtomicFile(journal)
            val stream = atomic.startWrite()
            try { stream.write(JSONArray(ids).toString().toByteArray()); atomic.finishWrite(stream) }
            catch (e: Exception) { atomic.failWrite(stream); throw e }
            ids.forEach { id -> check(File(staging, id).renameTo(File(root, id))) { "Could not publish restored app" } }
            writeIndex(current + ids)
            // The index is the commit point; cleanup cannot turn success into a rollback.
            journal.delete()
            ids.map { SubappRecord(it, File(root, it)) }
        } catch (e: Exception) {
            recoverBackupImport()
            throw e
        } finally { staging.deleteRecursively() }
    }

    private fun recoverBackupImport() {
        val journal = File(root, "backup-import-journal.json")
        val atomic = android.util.AtomicFile(journal)
        if (journal.exists() || File(root, "backup-import-journal.json.bak").exists()) {
            val array = JSONArray(atomic.openRead().bufferedReader().use { it.readText() })
            val registered = JSONArray(index.takeIf { it.isFile }?.readText() ?: "[]")
            val retained = (0 until registered.length()).map { registered.getString(it) }.toSet()
            for (number in 0 until array.length()) {
                val id = array.getString(number)
                require(validId(id)) { "Invalid backup recovery identity" }
                if (id !in retained) check(!File(root, id).exists() || File(root, id).deleteRecursively()) { "Could not recover backup import" }
            }
            atomic.delete()
        }
        File(root, "backup-import-staging").let { check(!it.exists() || it.deleteRecursively()) { "Could not clean backup staging" } }
    }

    private fun migrateLegacyWorkspace() {
        val legacy = File(context.filesDir, "workspace")
        val legacyHistory = File(context.filesDir, "workspace_history.json")
        if (!legacy.isDirectory || !File(legacy, "index.html").isFile) {
            val starter = createStarter()
            migrationMarker.writeText(starter.id)
            return
        }
        val id = UUID.randomUUID().toString()
        val pendingIdFile = File(root, "migration_pending_id")
        val stableId = pendingIdFile.takeIf { it.isFile }?.readText()?.takeIf(::validId) ?: id.also { pendingIdFile.writeText(it) }
        val finalDir = File(root, stableId)
        if (!finalDir.isDirectory) {
            val staging = File(root, "$stableId.migrating")
            staging.deleteRecursively()
            val targetWorkspace = File(staging, "workspace")
            legacy.copyRecursively(targetWorkspace, overwrite = true)
            if (legacyHistory.isFile) legacyHistory.copyTo(File(staging, "workspace_history.json"), overwrite = true)
            require(sameFiles(legacy, targetWorkspace)) { "Legacy workspace migration verification failed" }
            if (legacyHistory.isFile) require(sameContent(legacyHistory, File(staging, "workspace_history.json"))) { "Legacy history migration verification failed" }
            if (!staging.renameTo(finalDir)) throw IllegalStateException("Could not publish migrated subapp")
        }
        require(sameFiles(legacy, File(finalDir, "workspace"))) { "Published workspace verification failed" }
        if (legacyHistory.isFile) require(sameContent(legacyHistory, File(finalDir, "workspace_history.json"))) { "Published history verification failed" }

        val oldState = context.getSharedPreferences("runtime_app_state", Context.MODE_PRIVATE)
        val newState = context.getSharedPreferences("runtime_app_state_$stableId", Context.MODE_PRIVATE)
        val copied = newState.edit()
        oldState.all.forEach { (key, value) -> when (value) {
            is String -> copied.putString(key, value)
            is Int -> copied.putInt(key, value)
            is Long -> copied.putLong(key, value)
            is Float -> copied.putFloat(key, value)
            is Boolean -> copied.putBoolean(key, value)
            is Set<*> -> copied.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        check(copied.commit()) { "Could not migrate saved app state" }
        check(newState.all == oldState.all) { "Saved app state migration verification failed" }
        val legacyAlarms = context.getSharedPreferences("runtime_scheduled_alarms", Context.MODE_PRIVATE)
        val alarmJson = legacyAlarms.getString("runtime_scheduled_alarms", null)
        if (alarmJson != null) {
            val migratedAlarms = context.getSharedPreferences("runtime_scheduled_alarms_$stableId", Context.MODE_PRIVATE)
            check(migratedAlarms.edit().putString("runtime_scheduled_alarms", alarmJson).commit()) { "Could not migrate scheduled reminders" }
            check(migratedAlarms.getString("runtime_scheduled_alarms", null) == alarmJson) { "Scheduled reminder migration verification failed" }
        }
        writeIndex((list().map { it.id } + stableId).distinct())
        migrationMarker.writeText(stableId)
        cleanupLegacyStorage(stableId)
    }

    private fun cleanupLegacyStorage(id: String) {
        val legacy = File(context.filesDir, "workspace")
        val legacyHistory = File(context.filesDir, "workspace_history.json")
        val record = resolve(id) ?: return
        if (legacy.isDirectory && !sameFiles(legacy, record.workspaceDir)) return
        legacy.deleteRecursively()
        legacyHistory.delete()
        context.getSharedPreferences("runtime_app_state", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("runtime_scheduled_alarms", Context.MODE_PRIVATE).edit().clear().commit()
        File(root, "migration_pending_id").delete()
    }

    private fun sameFiles(a: File, b: File): Boolean {
        val left = a.walkTopDown().filter { it.isFile }.map { it.relativeTo(a).path to digest(it) }.toList().sortedBy { it.first }
        val right = b.walkTopDown().filter { it.isFile }.map { it.relativeTo(b).path to digest(it) }.toList().sortedBy { it.first }
        return left == right
    }

    private fun sameContent(a: File, b: File): Boolean = a.isFile && b.isFile && digest(a) == digest(b)
    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun register(id: String) = writeIndex((list().map { it.id } + id).distinct())
    private fun writeIndex(ids: List<String>) {
        root.mkdirs()
        val temp = File(root, "index.tmp")
        temp.writeText(JSONArray(ids).toString())
        check(temp.renameTo(index)) { "Could not save app library" }
    }
    private fun validId(id: String): Boolean = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
}
