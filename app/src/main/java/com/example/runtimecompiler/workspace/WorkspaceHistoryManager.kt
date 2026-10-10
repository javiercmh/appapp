package com.example.runtimecompiler.workspace

import android.content.Context
import com.example.runtimecompiler.settings.AppSettingsManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
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

    companion object {
        const val INITIAL_SNAPSHOT_ID = "initial_subapp_snapshot"
    }

    private fun log(message: String) {
        onLogListener?.invoke(message)
    }

    /**
     * Loads dynamic user snapshots from disk.
     */
    @Synchronized
    fun loadUserSnapshots(): List<WorkspaceSnapshot> {
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
        } catch (e: Exception) {
            log("[History Error] Failed to read snapshots: ${e.message}")
            emptyList()
        }
    }

    /** Captures the immutable first version for this subapp. */
    @Synchronized
    fun captureInitialVersion(
        workspaceManager: WorkspaceManager,
        label: String,
        replaceExisting: Boolean = false
    ): Boolean {
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
    @Synchronized
    fun ensureInitialVersion(workspaceManager: WorkspaceManager) {
        if (!initialSnapshotFile.exists()) {
            captureInitialVersion(workspaceManager, "Baseline captured for existing app")
        }
    }

    /** Returns user snapshots plus this subapp's permanent initial version, when available. */
    @Synchronized
    fun getSnapshots(): List<WorkspaceSnapshot> {
        val initial = readSnapshotFile(initialSnapshotFile)
        return loadUserSnapshots() + listOfNotNull(initial)
    }

    /**
     * Captures current workspace files and stores a new snapshot (respecting configured max retention limit).
     */
    @Synchronized
    fun saveSnapshot(workspaceManager: WorkspaceManager, label: String = "Run Snapshot"): WorkspaceSnapshot? {
        return try {
            val filesMap = readWorkspaceFiles(workspaceManager)
            if (filesMap.isEmpty()) return null

            val timestamp = System.currentTimeMillis()
            val formattedTime = timeFormat.format(Date(timestamp))
            val snapshot = WorkspaceSnapshot(
                id = "snap_$timestamp",
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
            val maxSnapshots = settingsManager.maxHistorySnapshots
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
    @Synchronized
    fun pruneToMax(maxCount: Int) {
        val existing = loadUserSnapshots().toMutableList()
        if (existing.size > maxCount) {
            while (existing.size > maxCount) {
                existing.removeAt(existing.lastIndex)
            }
            writeSnapshotsToDisk(existing)
            log("[History] Pruned snapshots to max limit $maxCount")
        }
    }

    /**
     * Wipes all user snapshots from disk.
     */
    @Synchronized
    fun clearAllHistory(): Boolean {
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

        FileOutputStream(historyFile).use { out ->
            out.write(jsonArray.toString().toByteArray(StandardCharsets.UTF_8))
        }
    }

    /**
     * Reverts all workspace files to the contents in the specified snapshot.
     */
    @Synchronized
    fun restoreSnapshot(snapshotId: String, workspaceManager: WorkspaceManager): Boolean {
        return try {
            val snapshots = getSnapshots()
            val target = snapshots.find { it.id == snapshotId } ?: run {
                log("[History Error] Snapshot '$snapshotId' not found.")
                return false
            }

            // 1. Clean up workspace files that were created after the snapshot. Runtime logs and
            // binary assets are kept — snapshots never captured them, so deleting would lose data.
            val currentFiles = workspaceManager.listFiles()
            for (f in currentFiles) {
                if (f.name != "app.log" &&
                    !WorkspaceManager.isBinaryAsset(f.name) &&
                    !target.files.containsKey(f.name)
                ) {
                    workspaceManager.deleteFile(f.name)
                }
            }

            // 2. Write all files from snapshot
            for ((fileName, content) in target.files) {
                workspaceManager.writeFile(fileName, content)
            }

            log("[History] Successfully restored snapshot from ${target.formattedTime}")
            true
        } catch (e: Exception) {
            log("[History Error] Failed to restore snapshot: ${e.message}")
            false
        }
    }

    private fun readWorkspaceFiles(workspaceManager: WorkspaceManager): Map<String, String> {
        val files = linkedMapOf<String, String>()
        for (file in workspaceManager.listFiles()) {
            // History stores text; binary assets remain untouched on disk and outside snapshots.
            if (!WorkspaceManager.isBinaryAsset(file.name)) {
                files[file.name] = workspaceManager.readFile(file.name)
            }
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
        FileOutputStream(file).use { it.write(json.toString().toByteArray(StandardCharsets.UTF_8)) }
    }
}
