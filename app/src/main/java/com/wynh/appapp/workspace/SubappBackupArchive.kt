package com.wynh.appapp.workspace

import com.google.gson.Gson
import com.wynh.appapp.updates.digest
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Portable workspace-only backup; file hashes also verify the document-provider copy. */
object SubappBackupArchive {
    private const val FORMAT = "app2-subapps"
    private const val MANIFEST = "backup.json"
    private const val MAX_BYTES = 2L * 1024 * 1024 * 1024
    private const val MAX_FILE_BYTES = 512L * 1024 * 1024
    private const val MAX_ENTRIES = 100_000
    private const val MAX_MANIFEST_BYTES = 16L * 1024 * 1024
    private val gson = Gson()
    data class Entry(val path: String, val size: Long, val sha256: String)
    data class App(val directory: String, val files: List<Entry>)
    data class Manifest(val format: String, val version: Int, val apps: List<App>)

    fun create(workspaces: List<File>, archive: File) {
        var total = 0L
        var count = 0
        val apps = workspaces.mapIndexed { index, workspace ->
            require(File(workspace, "index.html").isFile && File(workspace, "manifest.json").isFile) { "A subapp is missing index.html or manifest.json" }
            val files = workspace.walkTopDown().filter { it.isFile }.map { file ->
                val path = file.relativeTo(workspace).invariantSeparatorsPath
                checkedZipDestination(workspace, path)
                require(file.length() <= MAX_FILE_BYTES) { "Backup file exceeds 512 MB: $path" }
                total += file.length(); count++
                require(total <= MAX_BYTES && count < MAX_ENTRIES) { "Backup exceeds 2 GB or 100,000 files" }
                Entry(path, file.length(), digest(file))
            }.sortedBy { it.path }.toList()
            App("apps/$index", files)
        }
        val manifest = gson.toJson(Manifest(FORMAT, 1, apps)).toByteArray(Charsets.UTF_8)
        require(manifest.size <= MAX_MANIFEST_BYTES) { "Backup manifest is too large" }
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST)); zip.write(manifest); zip.closeEntry()
            apps.forEachIndexed { index, app ->
                app.files.forEach { entry ->
                    zip.putNextEntry(ZipEntry("${app.directory}/${entry.path}"))
                    File(workspaces[index], entry.path).inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    /** Fully validates into a private empty directory before any app is published. */
    fun validate(input: InputStream, staging: File): List<File> {
        require(staging.isDirectory && staging.listFiles()?.isEmpty() == true) { "Validation needs an empty staging directory" }
        val archive = File(staging, "source.zip")
        try {
            input.use { source -> archive.outputStream().use { copyLimited(source, it, MAX_BYTES) } }
            ZipFile(archive).use { zip ->
                val entries = zip.entries().toList()
                require(entries.size <= MAX_ENTRIES) { "Too many backup entries" }
                val seen = mutableSetOf<String>()
                entries.forEach {
                    checkedZipDestination(staging, it.name)
                    require(!it.isDirectory && seen.add(it.name)) { "Duplicate or unexpected backup entry: ${it.name}" }
                }
                val metadata = zip.getEntry(MANIFEST) ?: error("Not an App² subapp backup")
                val json = zip.getInputStream(metadata).use { source ->
                    val out = java.io.ByteArrayOutputStream()
                    copyLimited(source, out, MAX_MANIFEST_BYTES)
                    out.toString(Charsets.UTF_8.name())
                }
                val manifest = gson.fromJson(json, Manifest::class.java)
                require(manifest.format == FORMAT && manifest.version == 1) { "Unsupported backup format" }
                val expected = mutableSetOf(MANIFEST)
                val directories = mutableSetOf<String>()
                var total = 0L
                val workspaces = manifest.apps.mapIndexed { index, app ->
                    require(app.directory == "apps/$index" && directories.add(app.directory)) { "Invalid backup app directory" }
                    val workspace = File(staging, app.directory)
                    check(workspace.mkdirs()) { "Could not create restore staging" }
                    val paths = mutableSetOf<String>()
                    app.files.forEach { file ->
                        val destination = checkedZipDestination(workspace, file.path)
                        require(paths.add(file.path) && file.size in 0..MAX_FILE_BYTES && file.sha256.matches(Regex("[a-f0-9]{64}"))) { "Invalid backup file metadata" }
                        total += file.size
                        require(total <= MAX_BYTES) { "Backup exceeds 2 GB" }
                        val name = "${app.directory}/${file.path}"
                        require(expected.add(name)) { "Duplicate backup file" }
                        val entry = zip.getEntry(name) ?: error("Missing backup file: $name")
                        require(entry.size == file.size) { "Backup size mismatch: $name" }
                        require(staging.usableSpace > file.size + 20L * 1024 * 1024) { "Insufficient storage to restore backup" }
                        check(destination.parentFile!!.mkdirs() || destination.parentFile!!.isDirectory)
                        zip.getInputStream(entry).use { source -> destination.outputStream().use { copyLimited(source, it, file.size) } }
                        require(destination.length() == file.size && digest(destination) == file.sha256) { "Backup checksum mismatch: $name" }
                    }
                    require(paths.containsAll(listOf("index.html", "manifest.json"))) { "Backup app is missing required files" }
                    workspace
                }
                require(expected == seen) { "Unexpected files in backup" }
                return workspaces
            }
        } finally { archive.delete() }
    }

    private fun copyLimited(input: InputStream, output: java.io.OutputStream, limit: Long) {
        val buffer = ByteArray(32768)
        var size = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            size += count
            require(size <= limit) { "Backup exceeds size limit" }
            output.write(buffer, 0, count)
        }
    }
}

/** Reject ambiguous ZIP paths rather than normalizing them into a different file. */
internal fun checkedZipDestination(root: File, path: String): File {
    require(path.isNotBlank() && !path.startsWith('/') && !path.contains('\\') && !path.contains('\u0000') &&
        path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Unsafe ZIP path: $path" }
    val target = File(root, path)
    require(target.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "Unsafe ZIP path: $path" }
    return target
}
