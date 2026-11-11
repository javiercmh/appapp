package com.wynh.appapp.share

import java.io.File
import java.util.UUID

/** Temporary exports are independent of workspace files, backups, and history. */
object ShareCache {
    const val RETENTION_MS = 24 * 60 * 60 * 1000L
    fun sweep(cacheDir: File, now: Long = System.currentTimeMillis()) {
        File(cacheDir, "shares").listFiles()?.filter { it.isDirectory }?.forEach { app ->
            app.listFiles()?.filter { it.isDirectory && now - it.lastModified() > RETENTION_MS }?.forEach { it.deleteRecursively() }
        }
    }
    fun write(cacheDir: File, subappId: String, request: SharePolicy.Request.Document): File {
        require(subappId.matches(Regex("[A-Za-z0-9_-]+")))
        val bytes = SharePolicy.bytes(request.content)
        sweep(cacheDir)
        val directory = File(cacheDir, "shares/$subappId/${UUID.randomUUID()}")
        try {
            check(directory.mkdirs())
            return File(directory, request.name).also { it.writeBytes(bytes) }
        } catch (error: Exception) {
            directory.deleteRecursively()
            throw error
        }
    }
    fun discard(file: File?) { file?.parentFile?.deleteRecursively() }
}
