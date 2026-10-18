package com.wynh.appapp.workspace

import android.content.Context
import com.wynh.appapp.settings.AppSettingsManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class WorkspaceSnapshot(
    val id: String,
    val timestamp: Long,
    val formattedTime: String,
    val fileCount: Int,
    val files: Map<String, String>,
    val label: String
)

/**
 * Manages version snapshots of the workspace (FIFO user versions + permanent initial version).
 * Allows users to inspect and revert to previous states of their app.
 */
class WorkspaceHistoryManager(
    private val context: Context,
    private val onLogListener: ((String) -> Unit)? = null,
    private val appDirectory: File
) {
    private val historyFile = File(appDirectory, "workspace_history.json")
    private val initialSnapshotFile = File(appDirectory, "workspace_initial_snapshot.json")
    private val timeFormat = SimpleDateFormat("HH:mm:ss (MMM d)", Locale.getDefault())
    private val settingsManager = AppSettingsManager(context)
    private val lock = com.wynh.appapp.updates.WorkspaceLocks.forWorkspace(File(appDirectory, "workspace"))

    companion object {
        const val INITIAL_SNAPSHOT_ID = "initial_subapp_snapshot"
    }

    private fun log(message: String) {
        onLogListener?.invoke(message)
    }

    /**
     * Loads dynamic user snapshots from disk.
     */
    fun loadUserSnapshots(): List<WorkspaceSnapshot> = synchronized(lock) {
        if (!historyFile.exists()) return emptyList()

        return try {
            val jsonStr = FileInputStream(historyFile).use { input ->
                input.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            }
            if (jsonStr.isBlank()) return emptyList()

            val jsonArray = JSONArray(jsonStr)
            val result = mutableListOf<WorkspaceSnapshot>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.getString("id")
                val timestamp = obj.getLong("timestamp")
                val formattedTime = obj.optString("formattedTime", timeFormat.format(Date(timestamp)))
                val label = obj.optString("label", "Snapshot")
                val filesObj = obj.getJSONObject("files")

                val filesMap = mutableMapOf<String, String>()
                val keys = filesObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    filesMap[k] = filesObj.getString(k)
                }

                result.add(
                    WorkspaceSnapshot(
                        id = id,
                        timestamp = timestamp,
                        formattedTime = formattedTime,
                        fileCount = filesMap.size,
                        files = filesMap,
                        label = label
                    )
                )
            }
            // Sorted newest first
            result.sortedByDescending { it.timestamp }
        } catch (e: org.json.JSONException) {
            setAsideDamagedHistory(e)
            emptyList()
        } catch (e: Exception) {
            log("[History Error] Failed to read snapshots: ${e.message}")
            throw IllegalStateException("Version history could not be read; existing history was kept", e)
        }
    }

    /**
     * Unparseable history must not block saving, but its bytes are never discarded: the file is
     * renamed beside the app and a new list starts. Read errors still propagate untouched.
     */
    private fun setAsideDamagedHistory(cause: Exception) {
        val aside = File(appDirectory, "workspace_history.damaged-${System.currentTimeMillis()}.json")
        check(historyFile.renameTo(aside)) { "Version history is damaged and could not be set aside" }
        log("[History Error] Version history was unreadable (${cause.message}); kept as ${aside.name} and started a new list")
    }

    /** Captures the immutable first version for this subapp. */
    fun captureInitialVersion(
        workspaceManager: WorkspaceManager,
        label: String,
        replaceExisting: Boolean = false
    ): Boolean = synchronized(lock) {
        if (initialSnapshotFile.exists() && !replaceExisting) return true
        return try {
            val files = readWorkspaceFiles(workspaceManager)
            if (files.isEmpty()) return false
            val snapshot = WorkspaceSnapshot(
                id = INITIAL_SNAPSHOT_ID,
                timestamp = 0L,
                formattedTime = "Initial Version",
                fileCount = files.size,
                files = files,
                label = label
            )
            writeSnapshotFile(initialSnapshotFile, snapshot)
            true
        } catch (e: Exception) {
            log("[History Error] Failed to save initial version: ${e.message}")
            false
        }
    }

    /** Adds a baseline for subapps created before initial versions were persisted. */
    fun ensureInitialVersion(workspaceManager: WorkspaceManager) = synchronized(lock) {
        if (!initialSnapshotFile.exists()) {
            captureInitialVersion(workspaceManager, "Baseline captured for existing app")
        }
    }

    /** Returns user snapshots plus this subapp's permanent initial version, when available. */
    fun getSnapshots(): List<WorkspaceSnapshot> = synchronized(lock) {
        val initial = readSnapshotFile(initialSnapshotFile)
        return loadUserSnapshots() + listOfNotNull(initial)
    }

    /**
     * Captures current workspace files and stores a new snapshot (respecting configured max retention limit).
     */
    fun saveSnapshot(workspaceManager: WorkspaceManager, label: String = "Run Snapshot"): WorkspaceSnapshot? = synchronized(lock) {
        return try {
            val filesMap = readWorkspaceFiles(workspaceManager)
            if (filesMap.isEmpty()) return null

            val timestamp = System.currentTimeMillis()
            val formattedTime = timeFormat.format(Date(timestamp))
            val snapshot = WorkspaceSnapshot(
                id = "snap_${java.util.UUID.randomUUID()}",
                timestamp = timestamp,
                formattedTime = formattedTime,
                fileCount = filesMap.size,
                files = filesMap,
                label = label
            )

            val existing = loadUserSnapshots().toMutableList()

            // Check if identical to the latest snapshot to avoid duplicate identical entries
            if (existing.isNotEmpty()) {
                val latest = existing.first()
                if (latest.files == filesMap) {
                    log("[History] Current workspace identical to latest snapshot; skipping duplicate.")
                    return latest
                }
            }

            // Insert at the beginning (newest first)
            existing.add(0, snapshot)

            // Keep up to max configured snapshots
            val maxSnapshots = settingsManager.maxHistorySnapshots.coerceIn(1, 20)
            while (existing.size > maxSnapshots) {
                existing.removeAt(existing.lastIndex)
            }

            // Save to disk
            writeSnapshotsToDisk(existing)

            log("[History] Saved version snapshot '$formattedTime' (${existing.size}/$maxSnapshots stored)")
            snapshot
        } catch (e: Exception) {
            log("[History Error] Failed to save snapshot: ${e.message}")
            null
        }
    }

    /**
     * Prunes stored snapshots when the user lowers the retention setting.
     */
    fun pruneToMax(maxCount: Int) = synchronized(lock) {
        val existing = loadUserSnapshots().toMutableList()
        if (existing.size > maxCount) {
            while (existing.size > maxCount.coerceIn(1, 20)) {
                existing.removeAt(existing.lastIndex)
            }
            writeSnapshotsToDisk(existing)
            log("[History] Pruned snapshots to max limit $maxCount")
        }
    }

    /**
     * Wipes all user snapshots from disk.
     */
    fun clearAllHistory(): Boolean = synchronized(lock) {
        return try {
            if (historyFile.exists()) {
                val deleted = historyFile.delete()
                log("[History] Cleared all history snapshots: $deleted")
                deleted
            } else {
                true
            }
        } catch (e: Exception) {
            log("[History Error] Failed to clear history: ${e.message}")
            false
        }
    }

    private fun writeSnapshotsToDisk(snapshots: List<WorkspaceSnapshot>) {
        val jsonArray = JSONArray()
        for (s in snapshots) {
            val obj = JSONObject().apply {
                put("id", s.id)
                put("timestamp", s.timestamp)
                put("formattedTime", s.formattedTime)
                put("label", s.label)
                val fObj = JSONObject()
                for ((name, content) in s.files) {
                    fObj.put(name, content)
                }
                put("files", fObj)
            }
            jsonArray.put(obj)
        }

        writeAtomically(historyFile, jsonArray.toString())
    }

    /**
     * Reverts all workspace files to the contents in the specified snapshot.
     */
    fun restoreSnapshot(snapshotId: String, workspaceManager: WorkspaceManager): Boolean = synchronized(com.wynh.appapp.updates.WorkspaceLocks.forWorkspace(workspaceManager.workspaceDir)) {
        return@synchronized try {
            val snapshots = getSnapshots()
            val target = snapshots.find { it.id == snapshotId } ?: run {
                log("[History Error] Snapshot '$snapshotId' not found.")
                return@synchronized false
            }

            check(saveSnapshot(workspaceManager, "Before restoring ${target.formattedTime}") != null) {
                "Could not save current version before restore"
            }

            val result = WorkspaceOperations.restoreText(workspaceManager.workspaceDir, target.files)
            if (result.keptUnreadable.isNotEmpty()) log("[History] Left untouched (not valid UTF-8 text): ${result.keptUnreadable.joinToString()}")
            if (result.ignoredEntries.isNotEmpty()) log("[History] Skipped snapshot entries of unsupported types: ${result.ignoredEntries.joinToString()}")

            log("[History] Successfully restored snapshot from ${target.formattedTime}")
            true
        } catch (e: Exception) {
            log("[History Error] Failed to restore snapshot: ${e.message}")
            false
        }
    }

    private fun readWorkspaceFiles(workspaceManager: WorkspaceManager): Map<String, String> {
        val files = linkedMapOf<String, String>()
        val root = workspaceManager.workspaceDir
        for (file in WorkspaceFiles.regularFiles(root)) {
            val name = file.relativeTo(root).invariantSeparatorsPath
            // History stores text; binary assets remain untouched on disk and outside snapshots.
            if (!WorkspaceFiles.isHistoryText(name)) continue
            // A text-named file that is not valid UTF-8 (a Windows-1252 CSV, say) cannot round-trip
            // through JSON. It stays on disk, and restore leaves it alone, so it must not block saving.
            val text = if (WorkspaceFiles.normalize(name) == name) WorkspaceFiles.decodeUtf8(file.readBytes()) else null
            if (text == null) log("[History] Not versioned (not valid UTF-8 text or unsupported name): $name") else files[name] = text
        }
        return files
    }

    private fun readSnapshotFile(file: File): WorkspaceSnapshot? {
        if (!file.isFile) return null
        return try {
            val obj = JSONObject(file.readText(StandardCharsets.UTF_8))
            val filesObj = obj.getJSONObject("files")
            val files = linkedMapOf<String, String>()
            val keys = filesObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                files[key] = filesObj.getString(key)
            }
            WorkspaceSnapshot(
                id = obj.getString("id"),
                timestamp = obj.optLong("timestamp", 0L),
                formattedTime = obj.optString("formattedTime", "Initial Version"),
                fileCount = files.size,
                files = files,
                label = obj.optString("label", "Initial Version")
            )
        } catch (e: Exception) {
            log("[History Error] Failed to read initial version: ${e.message}")
            null
        }
    }

    private fun writeSnapshotFile(file: File, snapshot: WorkspaceSnapshot) {
        val filesJson = JSONObject()
        snapshot.files.forEach { (name, content) -> filesJson.put(name, content) }
        val json = JSONObject().apply {
            put("id", snapshot.id)
            put("timestamp", snapshot.timestamp)
            put("formattedTime", snapshot.formattedTime)
            put("label", snapshot.label)
            put("files", filesJson)
        }
        file.parentFile?.mkdirs()
        writeAtomically(file, json.toString())
    }

    private fun writeAtomically(file: File, text: String) = WorkspaceFiles.atomicWrite(file, text.toByteArray(StandardCharsets.UTF_8))
}
