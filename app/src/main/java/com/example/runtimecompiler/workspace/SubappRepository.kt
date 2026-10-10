package com.example.runtimecompiler.workspace

import android.content.Context
import com.example.runtimecompiler.templates.DefaultWebApp
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

    @Synchronized
    fun ensureMigrated(): List<SubappRecord> {
        root.mkdirs()
        if (!migrationMarker.exists()) migrateLegacyWorkspace()
        migrationMarker.takeIf { it.isFile }?.readText()?.let(::cleanupLegacyStorage)
        return list()
    }

    @Synchronized
    fun list(): List<SubappRecord> {
        root.mkdirs()
        val ids = try {
            val array = JSONArray(index.takeIf { it.exists() }?.readText() ?: "[]")
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(::validId) }
        } catch (_: Exception) { emptyList() }
        return ids.distinct().mapNotNull(::resolve)
    }

    fun resolve(id: String): SubappRecord? {
        if (!validId(id)) return null
        val dir = File(root, id)
        return if (File(dir, "workspace/index.html").isFile) SubappRecord(id, dir) else null
    }

    @Synchronized
    fun createStarter(): SubappRecord {
        val id = UUID.randomUUID().toString()
        val dir = File(root, id)
        val workspace = WorkspaceManager(context, workspaceDir = File(dir, "workspace"))
        check(workspace.resetWorkspace()) { "Could not initialize starter app" }
        register(id)
        WorkspaceHistoryManager(context, appDirectory = dir)
            .captureInitialVersion(workspace, "Starter Template")
        return SubappRecord(id, dir)
    }

    @Synchronized
    fun createImportedFrom(sourceWorkspace: File): SubappRecord {
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
        return SubappRecord(id, dir)
    }

    @Synchronized
    fun delete(id: String): Boolean {
        if (!validId(id)) return false
        val record = resolve(id) ?: return false
        val deleted = record.directory.deleteRecursively()
        if (deleted) {
            context.getSharedPreferences("runtime_app_state_$id", Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences("runtime_scheduled_alarms_$id", Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences("runtime_notifications_$id", Context.MODE_PRIVATE).edit().clear().commit()
            writeIndex(list().map { it.id }.filterNot { it == id })
        }
        return deleted
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
