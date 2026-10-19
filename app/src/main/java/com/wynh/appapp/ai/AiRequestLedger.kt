package com.wynh.appapp.ai

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

data class AiRequestResult(val id: String, val text: String? = null, val error: String? = null, val errorCode: String? = null, val done: Boolean = false)
internal data class AiRequestOwner(
    val appId: String, val taskId: Int, val providerId: String, val job: Job,
    val presentation: AiPresentation = AiPresentation()
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
    /** Requests still generating; a finished result awaiting acknowledgement is not included. */
    @Synchronized fun unfinished(): List<Pair<String, AiRequestOwner>> =
        owners.filter { (id, owner) -> results[id]?.done == false && owner.job.isActive }.map { it.key to it.value }
}
