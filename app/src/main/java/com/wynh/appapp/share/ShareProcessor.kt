package com.wynh.appapp.share

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.StringReader
import java.util.concurrent.atomic.AtomicReference

/** Raw-message limits decided before any JSON tree exists. */
object ShareMessages {
    // A one-byte UTF-8 character can serialize as a six-unit \uXXXX escape; the slack covers the envelope.
    const val MAX_MESSAGE = 6 * SharePolicy.MAX_FILE_BYTES + 65_536
    // Enough for a leading, fully escaped 100-unit ID; bridge.js always serializes the ID first.
    private const val PEEK = 1024

    class Envelope(val id: String, val request: Result<SharePolicy.Request>)

    /** Reads a leading ID from a bounded prefix, so an oversized or refused message can still be answered. */
    fun peekId(raw: String): String? = runCatching {
        JsonReader(StringReader(raw.substring(0, minOf(raw.length, PEEK)))).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() == "id") {
                    return@use if (reader.peek() == JsonToken.STRING) reader.nextString().takeIf { it.length in 1..100 } else null
                }
                reader.skipValue()
            }
            null
        }
    }.getOrNull()

    /** Null for messages that cannot be answered: malformed JSON or a missing or invalid ID. */
    fun parse(raw: String): Envelope? {
        val json = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return null
        val id = SharePolicy.string(json.get("id"))?.takeIf { it.length in 1..100 } ?: return null
        return Envelope(id, runCatching { SharePolicy.parse(json) })
    }
}

/** Process-wide budget for messages being parsed; used only on the UI thread. */
class ShareIntake(private val maxPending: Int = 4, private val maxChars: Long = ShareMessages.MAX_MESSAGE.toLong()) {
    private var pending = 0
    private var chars = 0L
    fun admit(length: Int): Boolean {
        if (pending >= maxPending || chars + length > maxChars) return false
        pending++; chars += length
        return true
    }
    fun done(length: Int) { pending--; chars -= length }
    companion object { val shared = ShareIntake() }
}

/**
 * Android-free request flow. [scope] is confined to the UI thread and owned by the session; the
 * session supplies lifecycle, workspace and chooser effects. Success means the chooser launched.
 */
class ShareProcessor(
    private val scope: CoroutineScope,
    private val work: CoroutineDispatcher,
    private val io: CoroutineDispatcher,
    private val state: ShareSessionState,
    private val effects: Effects,
    private val intake: ShareIntake = ShareIntake.shared,
    private val maxMessage: Int = ShareMessages.MAX_MESSAGE
) {
    interface Effects {
        /** A refusal code while the activity is not resumed or the workspace is reserved. */
        fun refusal(): String?
        fun workspaceVersion(): Long
        fun acquire(owner: Any): Boolean
        fun release(owner: Any)
        fun sweep()
        fun write(request: SharePolicy.Request.Document): File
        fun discard(file: File)
        /** Throws [ShareException] or another exception when Android refuses the chooser. */
        fun launch(owner: Any, request: SharePolicy.Request, file: File?)
    }

    private val gson = Gson()

    /** Called on the UI thread for a message already accepted from the owning main frame. */
    fun receive(raw: String, reply: (String) -> Unit) {
        val page = state.generation
        val workspaceVersion = effects.workspaceVersion()
        fun respond(value: Any) {
            if (state.current(page)) runCatching { reply(gson.toJson(value)) }
        }
        fun error(id: String, code: String, detail: String) = respond(mapOf("id" to id, "error" to mapOf("code" to code, "message" to detail)))
        fun refuse(id: String): Boolean {
            val code = effects.refusal() ?: return false
            error(id, code, REFUSED); return true
        }
        // A disposed processor answers nothing and must not take intake budget it could never release.
        if (!scope.isActive) return
        if (raw.length > maxMessage) {
            ShareMessages.peekId(raw)?.let { id -> if (!refuse(id)) error(id, "too_large", "Share message exceeds the host limit.") }
            return
        }
        if (!intake.admit(raw.length)) {
            ShareMessages.peekId(raw)?.let { id -> if (!refuse(id)) error(id, "busy", "Another share request is being processed.") }
            return
        }
        // ATOMIC: a body cancelled before dispatch (close in this same UI turn) still starts, so its
        // finally releases the admission; withContext then throws at once, or waits for a running parse.
        scope.launch(start = CoroutineStart.ATOMIC) {
            val envelope = try { withContext(work) { ShareMessages.parse(raw) } } finally { intake.done(raw.length) } ?: return@launch
            val id = envelope.id
            if (!state.current(page) || refuse(id)) return@launch
            val request = envelope.request.getOrElse { failure ->
                if (failure is ShareException) error(id, failure.code, failure.message.orEmpty()) else error(id, "failed", "The share request could not be read.")
                return@launch
            }
            if (request == SharePolicy.Request.Availability) {
                respond(mapOf("id" to id, "value" to mapOf("available" to true, "file" to true)))
                return@launch
            }
            val owner = Any()
            if (!effects.acquire(owner)) { error(id, "busy", "Another share chooser is open."); return@launch }
            // Set inside the I/O block: withContext waits for the write but drops its result once cancelled.
            val prepared = AtomicReference<File?>()
            var launched = false
            try {
                when (request) {
                    is SharePolicy.Request.Text -> withContext(io) { effects.sweep() }
                    is SharePolicy.Request.Document -> withContext(io) { prepared.set(effects.write(request)) }
                    SharePolicy.Request.Availability -> Unit
                }
                coroutineContext.ensureActive()
                val file = prepared.get()
                if (!state.current(page)) return@launch
                if (effects.workspaceVersion() != workspaceVersion) {
                    error(id, "busy", "The workspace changed during share preparation."); return@launch
                }
                if (refuse(id)) return@launch
                effects.launch(owner, request, file)
                launched = true
                respond(mapOf("id" to id, "value" to mapOf("launched" to true)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ShareException) {
                error(id, e.code, e.message.orEmpty())
            } catch (_: Exception) {
                error(id, "failed", "The share chooser could not be launched.")
            } finally {
                effects.release(owner)
                val file = prepared.get()
                // NonCancellable lets the discard run even after disposal cancelled this job.
                if (!launched && file != null) withContext(NonCancellable + io) { runCatching { effects.discard(file) } }
            }
        }
    }

    /** Disposal: no later reply, and preparation still discards any file it completed. */
    fun close() { state.close(); scope.cancel() }

    private companion object { const val REFUSED = "Sharing requires a foreground, idle workspace." }
}
