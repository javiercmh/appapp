package com.wynh.appapp.channel

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wynh.appapp.share.ShareIntake
import com.wynh.appapp.share.ShareMessages
import com.wynh.appapp.share.ShareSessionState
import com.wynh.appapp.updates.WorkspaceLocks
import com.wynh.appapp.workspace.WorkspaceOperations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChannelException(val code: String, message: String) : Exception(message)

/** One parsed request. [options] is always an object; [page] and [version] are the sender's page and workspace generation. */
class ChannelCall(val id: String, val action: String, val options: JsonObject, val page: Int, val version: Long)

/**
 * Message flow of the origin-restricted request/reply channel of Save as: the raw
 * message is bounded before parsing, parsing is off the UI thread, every command needs a foreground
 * and idle workspace, and a reply is dropped once the document changed or the session closed.
 * [handler] runs on the scope's (UI) dispatcher and moves its own blocking work elsewhere. Reuses
 * the AppShare state and intake classes; AppShare's own processor is not involved.
 */
class ChannelProcessor(
    private val scope: CoroutineScope,
    private val work: CoroutineDispatcher,
    private val state: ShareSessionState,
    private val intake: ShareIntake,
    private val maxMessage: Int,
    private val refusal: () -> String?,
    private val versionOf: () -> Long = { 0L },
    private val handler: suspend (ChannelCall) -> Any?
) {
    private val gson = Gson()

    fun receive(raw: String, reply: (String) -> Unit) {
        val page = state.generation
        val version = versionOf()
        fun respond(value: Any) { if (state.current(page)) runCatching { reply(gson.toJson(value)) } }
        fun error(id: String, code: String, detail: String) = respond(mapOf("id" to id, "error" to mapOf("code" to code, "message" to detail)))
        fun refuse(id: String): Boolean { val code = refusal() ?: return false; error(id, code, REFUSED); return true }
        if (!scope.isActive) return
        if (raw.length > maxMessage) {
            ShareMessages.peekId(raw)?.let { id -> if (!refuse(id)) error(id, "too_large", "Message exceeds the host limit.") }
            return
        }
        if (!intake.admit(raw.length)) {
            ShareMessages.peekId(raw)?.let { id -> if (!refuse(id)) error(id, "busy", "Too many requests are being processed.") }
            return
        }
        // ATOMIC: a body cancelled before dispatch still starts, so its finally releases the admission.
        scope.launch(start = CoroutineStart.ATOMIC) {
            val json = try {
                withContext(work) { runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() }
            } finally { intake.done(raw.length) }
            val id = json?.get("id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeIf { it.length in 1..100 }
                ?: return@launch
            if (!state.current(page) || refuse(id)) return@launch
            val action = string(json, "action").orEmpty()
            val options = json.get("options")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
            try {
                val value = handler(ChannelCall(id, action, options, page, version))
                if (value != null) respond(mapOf("id" to id, "value" to value))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChannelException) {
                error(id, e.code, e.message.orEmpty())
            } catch (_: Exception) {
                error(id, "failed", "The request failed.")
            }
        }
    }

    /** Disposal: no later reply; handlers waiting on a result are cancelled. */
    fun close() { state.close(); scope.cancel() }

    companion object {
        const val REFUSED = "This requires a foreground, idle workspace."
        fun string(json: JsonObject, key: String): String? =
            json.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        fun requireString(json: JsonObject, key: String): String =
            string(json, key) ?: throw ChannelException("invalid_arguments", "Supply a string for $key.")
    }
}

/**
 * Binds requests to the workspace generation of the page that sent them. The page's version is read
 * when it starts ([version]) and copied into every [ChannelCall] on receipt; a queued request keeps
 * that immutable copy, so a later page load can never make an old request look current.
 */
class DocumentGuard(private val root: java.io.File) {
    @Volatile private var bound = WorkspaceOperations.version(root)
    fun pageStarted() { bound = WorkspaceOperations.version(root) }
    fun version(): Long = bound

    /** True while an operation holds the workspace or a replacement advanced the generation past [version]. */
    fun stale(version: Long): Boolean = WorkspaceOperations.isBusy(root) || WorkspaceOperations.version(root) != version

    /**
     * Reads under the workspace lock. The request's page ([current]) and generation are checked
     * inside the lock, immediately before [block].
     */
    fun <T> underLock(version: Long, current: () -> Boolean = { true }, block: () -> T): T =
        synchronized(WorkspaceLocks.forWorkspace(root)) {
            if (!current() || stale(version)) throw ChannelException("busy", "The workspace changed or is busy.")
            block()
        }
}
