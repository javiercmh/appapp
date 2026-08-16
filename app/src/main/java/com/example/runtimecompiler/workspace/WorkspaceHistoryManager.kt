package com.example.runtimecompiler.workspace

import android.content.Context
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
 * Manages version snapshots of the workspace (up to 5 versions FIFO).
 * Allows users to inspect and revert to previous states of their app.
 */
class WorkspaceHistoryManager(
    private val context: Context,
    private val onLogListener: ((String) -> Unit)? = null
) {
    private val historyFile = File(context.filesDir, "workspace_history.json")
    private val timeFormat = SimpleDateFormat("HH:mm:ss (MMM d)", Locale.getDefault())

    private fun log(message: String) {
        onLogListener?.invoke(message)
    }

    /**
     * Loads existing snapshots from disk.
     */
    @Synchronized
    fun getSnapshots(): List<WorkspaceSnapshot> {
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

    /**
     * Captures current workspace files and stores a new snapshot (max 5 snapshots).
     */
    @Synchronized
    fun saveSnapshot(workspaceManager: WorkspaceManager, label: String = "Run Snapshot"): WorkspaceSnapshot? {
        return try {
            val currentFiles = workspaceManager.listFiles()
            if (currentFiles.isEmpty()) return null

            val filesMap = mutableMapOf<String, String>()
            for (f in currentFiles) {
                filesMap[f.name] = workspaceManager.readFile(f.name)
            }

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

            val existing = getSnapshots().toMutableList()

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

            // Keep maximum 5 snapshots
            while (existing.size > 5) {
                existing.removeAt(existing.lastIndex)
            }

            // Save to disk
            val jsonArray = JSONArray()
            for (s in existing) {
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

            log("[History] Saved version snapshot '$formattedTime' (${existing.size}/5 stored)")
            snapshot
        } catch (e: Exception) {
            log("[History Error] Failed to save snapshot: ${e.message}")
            null
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

            // Write all files from snapshot
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
}
