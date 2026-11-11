package com.wynh.appapp.saveas

import com.google.gson.JsonObject
import com.wynh.appapp.channel.ChannelException
import com.wynh.appapp.channel.ChannelProcessor
import com.wynh.appapp.share.SharePolicy
import com.wynh.appapp.workspace.WorkspaceManager

/** Pure validation of AppSave requests. */
object SaveAsPolicy {
    const val MAX_TEXT_BYTES = SharePolicy.MAX_FILE_BYTES
    const val MAX_FILE_BYTES = 64L * 1024 * 1024
    const val OCTET = "application/octet-stream"
    /** The text types `save.text` accepts, with the extension forced onto the name. */
    val textTypes = mapOf("text/plain" to "txt", "application/json" to "json", "text/markdown" to "md", "text/csv" to "csv", "text/html" to "html")
    private val unsafe = Regex("[^A-Za-z0-9._-]")
    private val mimeSyntax = Regex("[a-z0-9][a-z0-9.+-]{0,60}/[a-z0-9][a-z0-9.+-]{0,60}")

    sealed interface Request {
        data class Text(val name: String, val mimeType: String, val content: String) : Request
        data class File(val path: String, val name: String, val mimeType: String) : Request
    }

    fun parse(action: String, options: JsonObject): Request = when (action) {
        "text" -> {
            val mime = ChannelProcessor.requireString(options, "mimeType")
            val extension = textTypes[mime] ?: throw ChannelException("invalid_arguments", "Unsupported text MIME type.")
            val content = ChannelProcessor.requireString(options, "content")
            if (content.length > MAX_TEXT_BYTES) throw ChannelException("too_large", "Text exceeds 10 MiB.")
            Request.Text(safeName(ChannelProcessor.requireString(options, "name"), extension), mime, content)
        }
        "file" -> {
            val path = ChannelProcessor.requireString(options, "path")
            val given = ChannelProcessor.string(options, "name")
            val mime = ChannelProcessor.string(options, "mimeType")?.lowercase()?.also {
                if (!mimeSyntax.matches(it)) throw ChannelException("invalid_arguments", "Invalid MIME type.")
            } ?: WorkspaceManager.getMimeType(path)
            Request.File(path, safeName(given ?: path, null), mime)
        }
        else -> throw ChannelException("invalid_arguments", "Unknown save command.")
    }

    fun bytes(content: String): ByteArray {
        if (content.length > MAX_TEXT_BYTES) throw ChannelException("too_large", "Text exceeds 10 MiB.")
        return content.toByteArray(Charsets.UTF_8).also { if (it.size > MAX_TEXT_BYTES) throw ChannelException("too_large", "Text exceeds 10 MiB.") }
    }

    /**
     * A safe basename: no directories, ASCII letters, digits, dots, underscores and hyphens, no leading
     * dot, at most 120 characters. A benign extension is preserved; [forcedExtension] replaces it.
     */
    fun safeName(raw: String, forcedExtension: String?): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val safe = base.replace(unsafe, "_").trimStart('.')
        val dot = safe.lastIndexOf('.')
        val extension = if (dot > 0 && dot < safe.length - 1 && safe.length - dot <= 11) safe.substring(dot + 1) else null
        val stem = (if (extension != null) safe.substring(0, dot) else safe).trimEnd('.').ifEmpty { "file" }
        val finalExtension = forcedExtension ?: extension
        return if (finalExtension == null) stem.take(120) else stem.take(120 - finalExtension.length - 1) + "." + finalExtension
    }
}
