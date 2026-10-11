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
     * Extracts ZIP entries from an input stream directly into the workspace.
     */
    fun importFromInputStream(
        inputStream: InputStream,
        workspaceManager: WorkspaceManager,
        historyManager: WorkspaceHistoryManager,
        savePreImportSnapshot: Boolean = true
    ): Result<List<String>> = synchronized(com.wynh.appapp.updates.WorkspaceLocks.forWorkspace(workspaceManager.workspaceDir)) {
        var staging: File? = null
        return@synchronized try {
            // 1. Capture pre-import snapshot
            if (savePreImportSnapshot) historyManager.saveSnapshot(workspaceManager, "Pre-Import Backup")

            val workspaceDir = workspaceManager.workspaceDir
            if (!workspaceDir.exists()) {
                workspaceDir.mkdirs()
            }

            val importedFileNames = mutableListOf<String>()

            // 2. Extract ZIP contents to a temporary staging folder first to verify validity
            val tempExtractDir = File(workspaceDir.parentFile, "workspace_temp_${System.currentTimeMillis()}")
            staging = tempExtractDir
            if (tempExtractDir.exists()) {
                tempExtractDir.deleteRecursively()
            }
            tempExtractDir.mkdirs()

            val seen = mutableSetOf<String>()
            var totalBytes = 0L
            ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val entryName = entry.name
                    // Skip hidden metadata files created by macOS/OS archivers (e.g. __MACOSX, .DS_Store)
                    if (!entryName.startsWith("__MACOSX") && !entryName.contains(".DS_Store")) {
                        val destinationFile = checkedZipDestination(tempExtractDir, entryName.removeSuffix("/"))

                        // Zip-Slip Path Traversal Protection
                        if (!destinationFile.canonicalPath.startsWith(tempExtractDir.canonicalPath + File.separator)) {
                            throw SecurityException("ZIP entry contains unsafe path traversal: $entryName")
                        }

                        require(seen.add(destinationFile.canonicalPath)) { "Duplicate ZIP entry: $entryName" }
                        require(seen.size <= 1024) { "Too many ZIP entries" }
                        if (entry.isDirectory) {
                            destinationFile.mkdirs()
                        } else {
                            destinationFile.parentFile?.mkdirs()
                            FileOutputStream(destinationFile).use { fos ->
                                val buffer = ByteArray(8192)
                                var entryBytes = 0L
                                while (true) {
                                    val count = zis.read(buffer)
                                    if (count < 0) break
                                    entryBytes += count; totalBytes += count
                                    require(entryBytes <= 8 * 1024 * 1024 && totalBytes <= 32 * 1024 * 1024) { "ZIP exceeds size limit" }
                                    fos.write(buffer, 0, count)
                                }
                            }
                            importedFileNames.add(destinationFile.name)
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            if (importedFileNames.isEmpty()) {
                tempExtractDir.deleteRecursively()
                return@synchronized Result.failure(Exception("The selected ZIP file contains no readable project files."))
            }

            require(File(tempExtractDir, "index.html").isFile) { "ZIP must contain index.html at its root" }

            // 3. Clean replace: remove existing files in workspace and copy new files over
            val currentFiles = workspaceDir.listFiles() ?: emptyArray()
            for (f in currentFiles) {
                f.deleteRecursively()
            }

            val extractedFiles = tempExtractDir.listFiles() ?: emptyArray()
            for (f in extractedFiles) {
                val dest = File(workspaceDir, f.name)
                f.copyRecursively(dest, overwrite = true)
            }
            tempExtractDir.deleteRecursively()

            // Ensure core entry point index.html exists
            workspaceManager.ensureWorkspaceInitialized()

            Result.success(importedFileNames)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            staging?.deleteRecursively()
        }
    }
}
