package com.wynh.appapp.ai

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

data class AiRequestResult(val id: String, val text: String? = null, val error: String? = null, val errorCode: String? = null, val done: Boolean = false)
internal data class AiRequestOwner(
    val appId: String, val taskId: Int, val providerId: String, val job: Job,
    val presentation: AiPresentation = AiPresentation(),
    /** The operation session this request runs under, if any. */
    val sessionId: String? = null
)

/** Atomic ownership and terminal publication, independent of Android service lifecycle. */
internal class AiRequestLedger {
    val results = ConcurrentHashMap<String, AiRequestResult>()
    val owners = ConcurrentHashMap<String, AiRequestOwner>()
    @Synchronized fun begin(id: String, owner: AiRequestOwner) {
        check(!hasRequest(owner.appId)) { "A request is already running or awaiting acknowledgement." }
        results[id] = AiRequestResult(id)
        owners[id] = owner
    }
    @Synchronized fun bind(id: String, job: Job) {
        val owner = owners[id]
        if (owner == null || results[id]?.done != false) job.cancel()
        else owners[id] = owner.copy(job = job)
    }
    @Synchronized fun finish(result: AiRequestResult) {
        if (results[result.id]?.done == false && owners[result.id]?.job?.isCancelled == false) results[result.id] = result
    }
    @Synchronized fun cancel(id: String) {
        results.remove(id)
        owners.remove(id)?.job?.cancel()
    }
    /** Gives an unfinished request a terminal error and stops its job; a completed result is never replaced. */
    @Synchronized fun fail(id: String, code: String, message: String) {
        if (results[id]?.done != false) return
        owners[id]?.job?.cancel()
        results[id] = AiRequestResult(id, error = message, errorCode = code, done = true)
    }
    /** Sets the session of every unfinished request of [appId] in [taskId]. */
    @Synchronized fun setSession(appId: String, taskId: Int, sessionId: String) {
        owners.filter { (id, o) -> o.appId == appId && o.taskId == taskId && results[id]?.done == false }
            .forEach { (id, o) -> owners[id] = o.copy(sessionId = sessionId) }
    }
    @Synchronized fun detachSession(sessionId: String) {
        owners.filter { it.value.sessionId == sessionId }.forEach { (id, o) -> owners[id] = o.copy(sessionId = null) }
    }
    @Synchronized fun unfinishedIn(sessionId: String): List<String> =
        owners.filter { (id, o) -> o.sessionId == sessionId && results[id]?.done == false }.keys.toList()
    @Synchronized fun acknowledge(id: String) {
        if (results[id]?.done == true) { results.remove(id); owners.remove(id) }
    }
    @Synchronized fun cancelProvider(providerId: String) {
        owners.filter { (id, owner) -> owner.providerId == providerId && results[id]?.done == false }.forEach { (id, owner) ->
            owner.job.cancel()
            results[id] = AiRequestResult(id, error = "Provider connection changed. Start a new request when ready.", errorCode = "authentication", done = true)
        }
    }
    fun hasRequest(appId: String): Boolean = owners.any { (id, owner) -> owner.appId == appId && results.containsKey(id) }
    fun activeRequestCount(): Int = owners.values.count { it.job.isActive }
    /**
     * Requests still generating or waiting for the service to reach the foreground (a LAZY job is
     * not active yet); a finished result awaiting acknowledgement is not included.
     */
    @Synchronized fun unfinished(): List<Pair<String, AiRequestOwner>> =
        owners.filter { (id, owner) -> results[id]?.done == false && !owner.job.isCompleted && !owner.job.isCancelled }.map { it.key to it.value }
}
