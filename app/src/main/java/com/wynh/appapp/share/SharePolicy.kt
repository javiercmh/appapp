package com.wynh.appapp.share

import com.google.gson.JsonElement
import com.google.gson.JsonObject

class ShareException(val code: String, message: String) : Exception(message)

/** Pure validation shared by the message channel and its tests. */
object SharePolicy {
    const val MAX_TEXT = 100_000
    const val MAX_FILE_BYTES = 10 * 1024 * 1024
    private val extensions = mapOf("text/plain" to "txt", "application/json" to "json", "text/markdown" to "md", "text/csv" to "csv")
    sealed interface Request {
        data object Availability : Request
        data class Text(val text: String, val title: String?) : Request
        data class Document(val name: String, val mimeType: String, val content: String) : Request
    }
    fun string(value: JsonElement?): String? = value?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun required(options: JsonObject, key: String): String = string(options.get(key))
        ?: throw ShareException("invalid_arguments", "Supply a string for $key.")
    fun parse(request: JsonObject): Request {
        val action = string(request.get("action"))
        if (action == "availability") return Request.Availability
        val options = request.get("options")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw ShareException("invalid_arguments", "Supply share options.")
        return when (action) {
            "text" -> {
                val text = required(options, "text")
                if (text.isEmpty()) throw ShareException("invalid_arguments", "Supply nonempty text.")
                if (text.length > MAX_TEXT) throw ShareException("too_large", "Share text exceeds 100000 UTF-16 units.")
                val title = if (options.has("title")) required(options, "title") else null
                if (title != null && title.length > 200) throw ShareException("invalid_arguments", "Share title exceeds 200 UTF-16 units.")
                Request.Text(text, title)
            }
            "file" -> {
                val mime = required(options, "mimeType")
                val name = filename(required(options, "name"), mime)
                val content = required(options, "content")
                // UTF-8 never uses fewer bytes than UTF-16 units for a well-formed string.
                if (content.length > MAX_FILE_BYTES) throw ShareException("too_large", "Share file exceeds 10 MiB.")
                Request.Document(name, mime, content)
            }
            else -> throw ShareException("invalid_arguments", "Unknown share command.")
        }
    }
    fun bytes(content: String): ByteArray {
        if (content.length > MAX_FILE_BYTES) throw ShareException("too_large", "Share file exceeds 10 MiB.")
        return content.toByteArray(Charsets.UTF_8).also {
            if (it.size > MAX_FILE_BYTES) throw ShareException("too_large", "Share file exceeds 10 MiB.")
        }
    }
    fun filename(name: String, mimeType: String): String {
        val extension = extensions[mimeType] ?: throw ShareException("invalid_arguments", "Unsupported share MIME type.")
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").trimStart('.')
        val stem = safe.substringBeforeLast('.', safe).ifEmpty { "share" }
        return stem.take(120 - extension.length - 1) + "." + extension
    }
}

/** No activity or WebView references: late I/O completions can only use their original document. */
class ShareSessionState(private val origin: String) {
    var closed = false
        private set
    var foreground = false
    var generation = 0
        private set
    fun accepts(source: String, mainFrame: Boolean) = !closed && mainFrame && source.trimEnd('/') == origin
    fun current(page: Int) = !closed && page == generation
    fun pageChanged() { generation++ }
    fun close() { closed = true; generation++; foreground = false }
    fun refusal(resumed: Boolean, reserved: Boolean): String? = when {
        closed || !foreground || !resumed -> "not_foreground"
        reserved -> "busy"
        else -> null
    }
}

/** Activity-owned exclusion: stale preparations cannot release a different request. */
class ShareLaunchState {
    var chooserOpen = false
    private var preparing: Any? = null
    fun acquire(owner: Any): Boolean {
        if (chooserOpen || preparing != null) return false
        preparing = owner
        return true
    }
    fun release(owner: Any) { if (preparing === owner) preparing = null }
    fun launched(owner: Any) {
        check(preparing === owner && !chooserOpen)
        chooserOpen = true
        release(owner)
    }
}
