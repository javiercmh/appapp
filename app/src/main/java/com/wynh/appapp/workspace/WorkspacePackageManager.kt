package com.wynh.appapp.workspace

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Manages ZIP export and import operations for the unified project workspace.
 * Supports selective file packaging, direct stream writing for Storage Access Framework,
 * secure extraction with Zip Slip defense, and automated pre-import version snapshots.
 */
object WorkspacePackageManager {

    /**
     * Sanitizes a user-provided package name.
     */
    fun sanitizePackageName(rawName: String, defaultName: String = "my-app"): String {
        val clean = rawName.trim().removeSuffix(".zip").replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return if (clean.isBlank()) defaultName else clean
    }

    /**
     * Packages selected workspace files into a named ZIP file inside cacheDir/exports/.
     */
    fun exportToZipFile(
        context: Context,
        workspaceManager: WorkspaceManager,
        selectedFiles: Set<String>,
        baseName: String = "my-app"
    ): File {
        val exportsDir = File(context.cacheDir, "exports")
        if (!exportsDir.exists()) {
            exportsDir.mkdirs()
        }

        val cleanName = sanitizePackageName(baseName, "my-app")
        val zipFile = File(exportsDir, "$cleanName.zip")

        FileOutputStream(zipFile).use { fos ->
            exportToOutputStream(workspaceManager, selectedFiles, fos)
        }

        return zipFile
    }

    /**
     * Writes selected workspace files as a ZIP archive to any given OutputStream.
     */
    fun exportToOutputStream(
        workspaceManager: WorkspaceManager,
        selectedFiles: Set<String>,
        outputStream: OutputStream
    ) {
        ZipOutputStream(BufferedOutputStream(outputStream)).use { zos ->
            for (fileName in selectedFiles) {
                val file = workspaceManager.getFile(fileName)
                if (file.exists() && file.isFile) {
                    val entry = ZipEntry(fileName).apply {
                        time = file.lastModified()
                        size = file.length()
                    }
                    zos.putNextEntry(entry)
                    FileInputStream(file).use { fis ->
                        fis.copyTo(zos, bufferSize = 8192)
                    }
                    zos.closeEntry()
                }
            }
            zos.finish()
        }
    }

    /**
     * Imports a ZIP archive into the workspace directory.
     * 1. Takes an automatic pre-import history snapshot to ensure effortless rollback.
     * 2. Extracts all entries safely with Zip-Slip path traversal protection.
     * 3. Cleanly replaces workspace contents to avoid stale lingering files.
     */
    fun importFromZip(
        context: Context,
        zipUri: Uri,
        workspaceManager: WorkspaceManager,
        historyManager: WorkspaceHistoryManager,
        savePreImportSnapshot: Boolean = true
    ): Result<List<String>> {
        return try {
            val inputStream = context.contentResolver.openInputStream(zipUri)
                ?: return Result.failure(Exception("Cannot open selected file stream."))

            importFromInputStream(inputStream, workspaceManager, historyManager, savePreImportSnapshot)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Validates ZIP entries into a staging directory beside the workspace, then swaps it in.
     */
    fun importFromInputStream(
        inputStream: InputStream,
        workspaceManager: WorkspaceManager,
        historyManager: WorkspaceHistoryManager,
        savePreImportSnapshot: Boolean = true
    ): Result<List<String>> = synchronized(com.wynh.appapp.updates.WorkspaceLocks.forWorkspace(workspaceManager.workspaceDir)) {
        return@synchronized try {
            // 1. Capture pre-import snapshot
            if (savePreImportSnapshot) check(historyManager.saveSnapshot(workspaceManager, "Pre-Import Backup") != null) { "Could not save pre-import history" }
            // 2. Extract, validate and atomically replace the workspace
            val importedFileNames = replaceFromZip(inputStream, workspaceManager.workspaceDir)
            Result.success(importedFileNames)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Every entry, including skipped metadata and directories, counts toward these limits. */
    internal data class ZipLimits(
        val entries: Int = 1024,
        val entryBytes: Long = 8L * 1024 * 1024,
        val totalBytes: Long = 32L * 1024 * 1024,
        val timeoutMillis: Long = 60_000
    )

    internal fun replaceFromZip(
        inputStream: InputStream,
        workspaceDir: File,
        limits: ZipLimits = ZipLimits(),
        rename: (File, File) -> Boolean = File::renameTo
    ): List<String> {
        var imported = emptyList<String>()
        WorkspaceOperations.replace(workspaceDir, rename) { staged ->
            imported = extractZip(inputStream, staged, limits)
            require(imported.isNotEmpty()) { "The selected ZIP file contains no readable project files." }
            require(File(staged, "index.html").isFile) { "ZIP must contain index.html at its root" }
        }
        return imported
    }

    /** Extracts into an empty [destination], bounding inflated bytes of every entry and elapsed time. */
    internal fun extractZip(inputStream: InputStream, destination: File, limits: ZipLimits = ZipLimits()): List<String> {
        val deadline = System.nanoTime() + limits.timeoutMillis * 1_000_000
        val importedFileNames = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        var entries = 0
        var totalBytes = 0L
        val buffer = ByteArray(8192)
        ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                require(++entries <= limits.entries) { "Too many ZIP entries" }
                val entryName = entry.name
                // Skip hidden metadata files created by macOS/OS archivers (e.g. __MACOSX, .DS_Store)
                val skipped = entryName.startsWith("__MACOSX") || entryName.contains(".DS_Store")
                var output: OutputStream? = null
                if (!skipped) {
                    val destinationFile = checkedZipDestination(destination, entryName.removeSuffix("/"))
                    require(seen.add(destinationFile.canonicalPath)) { "Duplicate ZIP entry: $entryName" }
                    if (entry.isDirectory) {
                        destinationFile.mkdirs()
                    } else {
                        destinationFile.parentFile?.mkdirs()
                        output = FileOutputStream(destinationFile)
                        importedFileNames.add(destinationFile.name)
                    }
                }
                // Skipped and directory entries are still inflated by the stream, so they are read and counted too.
                output.use { out ->
                    var entryBytes = 0L
                    while (true) {
                        if (Thread.interrupted()) throw InterruptedException("ZIP import cancelled")
                        require(System.nanoTime() < deadline) { "ZIP import timed out" }
                        val count = zis.read(buffer)
                        if (count < 0) break
                        entryBytes += count; totalBytes += count
                        require(entryBytes <= limits.entryBytes && totalBytes <= limits.totalBytes) { "ZIP exceeds size limit" }
                        out?.write(buffer, 0, count)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return importedFileNames
    }
}
