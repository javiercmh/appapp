package com.wynh.appapp.workspace

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File

/** Bounded metadata/icon loading; caller runs this on an I/O dispatcher. */
data class SubappMetadata(val name: String, val description: String, val icon: Bitmap?, val stamp: String)
object SubappMetadataLoader {
    fun load(root: File, previous: SubappMetadata? = null): SubappMetadata {
        val files = WorkspaceFiles(root)
        val manifest = runCatching { JSONObject(files.readText("manifest.json", 1024 * 1024)) }.getOrDefault(JSONObject())
        val icon = runCatching { files.file(manifest.optJSONArray("icons")?.optJSONObject(0)?.optString("src")?.takeIf { it.isNotBlank() } ?: "icon.png") }.getOrNull()
        val stamp = listOf(File(root, "manifest.json"), icon).filterNotNull().joinToString { "${it.path}:${it.length()}:${it.lastModified()}" }
        if (previous?.stamp == stamp) return previous
        val bitmap = icon?.takeIf { it.isFile && it.length() <= 8L * 1024 * 1024 }?.let { file ->
            runCatching {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, options)
                options.inSampleSize = 1
                while (options.outWidth / options.inSampleSize > 192 || options.outHeight / options.inSampleSize > 192) options.inSampleSize *= 2
                options.inJustDecodeBounds = false
                BitmapFactory.decodeFile(file.path, options)
            }.getOrNull()
        }
        return SubappMetadata(manifest.optString("name").take(200), manifest.optString("description").take(2000), bitmap, stamp)
    }
}
