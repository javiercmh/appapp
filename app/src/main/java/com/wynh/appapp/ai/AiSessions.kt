package com.wynh.appapp.ai

import com.wynh.appapp.ai.network.ApiFailure

/**
 * Why an operation session ended. [terminalizes] reasons give an unfinished request of the session
 * a terminal error (completed results are never touched); the others leave a running request alone.
 */
internal enum class SessionEnd(val kind: ApiFailure.Kind, val code: String, val message: String, val terminalizes: Boolean) {
    /** The page released the session, or its page was replaced. A running request keeps running. */
    RELEASED(ApiFailure.Kind.SESSION_ENDED, "session_ended", "This background session has ended.", false),
    CANCELLED(ApiFailure.Kind.SESSION_CANCELLED, "session_cancelled", "Background work was cancelled.", true),
    IDLE(ApiFailure.Kind.SESSION_IDLE, "session_idle", "The background session was released after a period without a running request.", false),
    /** The session cap, or Android's own foreground-service time limit, which can arrive earlier. */
    TIMEOUT(ApiFailure.Kind.SESSION_TIMEOUT, "session_timeout", "Background work stopped at a time limit.", true),
    DENIED(ApiFailure.Kind.BACKGROUND_SERVICE, "background_service", "Android could not keep background work running.", true)
}

internal class AiLease(
    var id: String, val appId: String, val taskId: Int, val notificationId: Int,
    val presentation: AiPresentation, val hardDeadline: Long
) {
    /** Null while a request runs under the session. */
    var idleDeadline: Long? = null
    var confirmed = false
}

internal sealed interface LeaseLookup {
    class Live(val lease: AiLease) : LeaseLookup
    class Ended(val reason: SessionEnd) : LeaseLookup
    object Unknown : LeaseLookup
}

/**
 * Pure bookkeeping for operation sessions: one live lease per app and at most one remembered ended
 * session per app, so memory is bounded by the number of apps. Ownership is (appId, taskId); a
 * foreign or unknown id is [LeaseLookup.Unknown]. Call from one thread (the main thread).
 */
internal class AiLeaseTable(private val clock: () -> Long, var idleMs: Long = IDLE_MS, var maxMs: Long = MAX_MS) {
    private val live = HashMap<String, AiLease>()
    private class Ended(val id: String, val taskId: Int, val reason: SessionEnd)
    private val ended = HashMap<String, Ended>()

    fun leaseOf(appId: String): AiLease? = live[appId]
    fun all(): List<AiLease> = live.values.toList()
    fun byId(id: String): AiLease? = live.values.firstOrNull { it.id == id }

    /**
     * Opens a session. A live lease of the same task is adopted under its new id when [adopt] (a
     * request is running under it), keeping its deadlines; otherwise it is replaced. A live lease
     * of another task of the app is never taken over.
     */
    fun open(appId: String, taskId: Int, id: String, notificationId: Int, presentation: AiPresentation, adopt: Boolean): AiLease {
        val old = live[appId]
        if (old != null && old.taskId != taskId) throw IllegalStateException("Another task of this app holds the session.")
        if (old != null && adopt) { old.id = id; ended.remove(appId); return old }
        val now = clock()
        val lease = AiLease(id, appId, taskId, old?.notificationId ?: notificationId, presentation, now + maxMs)
        lease.idleDeadline = now + idleMs
        live[appId] = lease; ended.remove(appId)
        return lease
    }

    fun lookup(appId: String, taskId: Int, id: String): LeaseLookup {
        live[appId]?.takeIf { it.id == id }?.let { return if (it.taskId == taskId) LeaseLookup.Live(it) else LeaseLookup.Unknown }
        ended[appId]?.takeIf { it.id == id }?.let { return if (it.taskId == taskId) LeaseLookup.Ended(it.reason) else LeaseLookup.Unknown }
        return LeaseLookup.Unknown
    }

    fun setBusy(lease: AiLease, busy: Boolean) {
        if (busy) lease.idleDeadline = null else if (lease.idleDeadline == null) lease.idleDeadline = clock() + idleMs
    }

    /** Removes the live lease and remembers why, replacing any earlier memory for the app. */
    fun end(appId: String, reason: SessionEnd): AiLease? {
        val lease = live.remove(appId) ?: return null
        ended[appId] = Ended(lease.id, lease.taskId, reason)
        return lease
    }

    fun forget(appId: String) { live.remove(appId); ended.remove(appId) }

    /** Leases whose cap or idle deadline has passed; the cap wins when both have. */
    fun due(): List<Pair<AiLease, SessionEnd>> {
        val now = clock()
        return live.values.mapNotNull { lease ->
            when {
                now >= lease.hardDeadline -> lease to SessionEnd.TIMEOUT
                lease.idleDeadline?.let { now >= it } == true -> lease to SessionEnd.IDLE
                else -> null
            }
        }
    }

    fun nextDeadline(): Long? = live.values.flatMap { listOfNotNull(it.hardDeadline, it.idleDeadline) }.minOrNull()
    fun now(): Long = clock()
    fun rememberedCount(): Int = ended.size

    companion object {
        const val IDLE_MS = 90_000L
        const val MAX_MS = 30 * 60_000L
    }
}
