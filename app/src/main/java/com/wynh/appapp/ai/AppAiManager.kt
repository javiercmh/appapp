package com.wynh.appapp.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.wynh.appapp.ai.network.ApiFailure
import android.webkit.WebView
import androidx.core.content.ContextCompat
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import com.wynh.appapp.ai.auth.AuthRepository
import com.wynh.appapp.ai.auth.EncryptedApiKeyStore
import com.wynh.appapp.ai.network.OpenRouterInferenceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import com.wynh.appapp.ai.auth.EncryptedCredentialStore
import com.wynh.appapp.ai.auth.IdTokenValidator
import com.wynh.appapp.ai.auth.OpenAiOAuthApi
import com.wynh.appapp.ai.network.HttpGateway
import com.wynh.appapp.ai.network.OpenAiInferenceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/** Process-wide provider routing, connection and native request owner. */
class AppAiManager(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val http = HttpGateway()
    val auth = AuthRepository(EncryptedCredentialStore(app), OpenAiOAuthApi(http), IdTokenValidator(), scope)
    val inference = OpenAiInferenceRepository(http, auth)
    private val preferences = app.getSharedPreferences("appapp_ai", Context.MODE_PRIVATE)
    private val keyStore = EncryptedApiKeyStore(app)
    @Volatile private var openRouterKey: String? = null
    var openRouterMessage: String? = null
        private set
    var openRouterRestoring = true
        private set
    val openRouterConnected: Boolean get() = openRouterKey != null
    val allowPaidModels: Boolean get() = preferences.getBoolean("openrouter_allow_paid", false)
    val openRouter = OpenRouterInferenceRepository(http, { openRouterKey }, { allowPaidModels })
    val registry = AiProviderRegistry(listOf(
        AiProvider("chatgpt", "ChatGPT", inference) { auth.state.value.let { state -> state.accounts.firstOrNull { it.clientId == state.activeClientId }?.canInfer == true } },
        AiProvider("openrouter", "OpenRouter", openRouter) { openRouterConnected }
    ), preferences.getString("exposure", "chatgpt")?.takeIf { it in AiProviderRegistry.modes } ?: "chatgpt")
    val exposure: String get() = registry.exposure
    /** Serializes only the Settings model check; generation requests from different subapps run concurrently. */
    private val checkLock = Mutex()
    private val notificationIds = java.util.concurrent.atomic.AtomicInteger()
    /** At most six generation starts per subapp per minute, across all of its tasks. */
    private val startLimiter = AiStartRateLimiter()
    private val ledger = AiRequestLedger()
    private val requests = ledger.results
    private val jobs = ledger.owners
    private val webViews = ConcurrentHashMap<String, MutableList<Pair<WeakReference<WebView>, String>>>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val leases = AiLeaseTable(android.os.SystemClock::elapsedRealtime)

    init {
        scope.launch { auth.state.collect { broadcastConnection() } }
        scope.launch {
            try { openRouterKey = withContext(Dispatchers.IO) { keyStore.read() } }
            catch (e: Exception) { openRouterMessage = e.message }
            finally { openRouterRestoring = false; broadcastConnection() }
        }
    }

    private fun connectionMessage(provider: ExposedProvider): String =
        com.google.gson.Gson().toJson(mapOf("event" to "connection", "data" to mapOf("providerId" to provider.id, "connected" to provider.connected)))

    private fun broadcastConnection(provider: ExposedProvider = registry.exposed()) {
        val message = connectionMessage(provider)
        webViews.forEach { (_, views) -> synchronized(views) {
            views.removeAll { ref ->
                val view = ref.first.get() ?: return@removeAll true
                event(view, ref.second, message)
                false
            }
        } }
    }

    fun setExposure(value: String) {
        if (value == exposure) return
        val previous = registry.exposed()
        registry.setExposure(value)
        preferences.edit().putString("exposure", value).apply()
        broadcastConnection(previous.copy(connected = false))
        broadcastConnection()
    }

    fun setAllowPaid(value: Boolean) {
        preferences.edit().putBoolean("openrouter_allow_paid", value).apply()
        broadcastConnection()
    }

    suspend fun saveOpenRouterKey(value: String) {
        val candidate = value.trim()
        if (candidate.isBlank() || candidate.length > 8192 || candidate.any { it.code !in 33..126 })
            throw com.wynh.appapp.ai.network.ApiFailure(com.wynh.appapp.ai.network.ApiFailure.Kind.INVALID_REQUEST, "Enter a valid OpenRouter API key.")
        openRouter.verifyKey(candidate)
        withContext(Dispatchers.IO) { keyStore.write(candidate) }
        cancelProvider("openrouter")
        openRouterKey = candidate
        openRouterMessage = "Key verified. Available to subapps according to your exposure setting."
        broadcastConnection()
    }

    suspend fun removeOpenRouterKey() {
        withContext(Dispatchers.IO) { keyStore.reset() }
        cancelProvider("openrouter")
        openRouterKey = null
        openRouterMessage = "Saved key removed. It has not been revoked at OpenRouter."
        broadcastConnection()
    }

    fun observe(appId: String, webView: WebView, origin: String) {
        val views = webViews.getOrPut(appId) { mutableListOf() }
        synchronized(views) { views.add(WeakReference(webView) to origin) }
        event(webView, origin, connectionMessage(registry.exposed()))
    }

    fun event(webView: WebView, origin: String, message: String) {
        runCatching { WebViewCompat.postWebMessage(webView, WebMessageCompat(message), Uri.parse(origin)) }
    }

    fun beginSignIn(clientId: String? = null) = auth.beginSignIn(clientId)

    fun browserRequests(block: suspend (String) -> Unit) = scope.launch(Dispatchers.IO) {
        for (url in auth.browserRequests) block(url)
    }

    suspend fun nextAuthorizationUrl(): String = withTimeout(600_000) { auth.browserRequests.receive() }

    fun start(
        appId: String, taskId: Int, providerId: String, model: String, instructions: String, input: String, effort: String?,
        title: String? = null, body: String? = null, openIntent: Intent? = null, sessionId: String? = null
    ): String {
        if (com.wynh.appapp.workspace.WorkspaceOperations.isBusy(java.io.File(app.filesDir, "subapps/$appId/workspace")))
            throw ApiFailure(ApiFailure.Kind.INVALID_REQUEST, "The app workspace is being changed. Try again after it finishes.")
        val route = registry.route(providerId, model)
        // Defense in depth: the web bridge asks first, but nothing generates without the grant.
        if (!com.wynh.appapp.grants.SubappGrants.isGranted(app, appId, com.wynh.appapp.grants.SubappCapability.AI))
            throw ApiFailure(ApiFailure.Kind.PERMISSION, "AI is not allowed for this app. Allow it when asked, or in the library: app menu → Permissions.")
        val lease = sessionId?.let { liveLease(appId, taskId, it) }
        if (!startLimiter.tryAcquire(appId))
            throw ApiFailure(ApiFailure.Kind.USAGE_LIMIT, "This app started too many AI requests. Wait a minute, then generate again.")
        val id = UUID.randomUUID().toString()
        val presentation = AiPresentation(
            notificationId = lease?.notificationId ?: nextNotificationId(),
            title = AiNotificationCopy.title(title), body = AiNotificationCopy.body(body),
            appName = com.wynh.appapp.grants.SubappGrants.displayName(app, appId), open = openIntent)
        try { ledger.begin(id, AiRequestOwner(appId, taskId, route.provider.id, Job(), presentation, lease?.id)) }
        catch (_: IllegalStateException) { throw ApiFailure(ApiFailure.Kind.INVALID_REQUEST, "A request is already running or awaiting acknowledgement.") }
        val job = scope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                try {
                    val result = route.provider.inference.generate(route.model, instructions, input, effort)
                    ledger.finish(AiRequestResult(id, text = result, done = true))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val code = when (e) {
                        is com.wynh.appapp.ai.network.ApiFailure -> e.kind.name.lowercase()
                        is com.wynh.appapp.ai.auth.AuthFailure -> "authentication"
                        else -> "service"
                    }
                    ledger.finish(AiRequestResult(id, error = e.message ?: "The request failed.", errorCode = code, done = true))
                }
            } finally { main.post { changed() } }
        }
        ledger.bind(id, job)
        // The provider is called only after the service is confirmed in the foreground.
        withForeground(
            ready = { if (ledger.results[id]?.done == false) job.start(); changed() },
            denied = { reason ->
                ledger.fail(id, SessionEnd.DENIED.code, "Android could not start background work ($reason).")
                if (lease != null) endLease(appId, SessionEnd.DENIED)
                changed()
            })
        return id
    }

    fun result(id: String): AiRequestResult? = requests[id]
    fun owner(id: String): Int? = jobs[id]?.taskId
    fun ownedByApp(id: String, appId: String): Boolean = jobs[id]?.appId == appId
    fun hasRequest(appId: String): Boolean = ledger.hasRequest(appId)
    fun activeRequestCount(): Int = ledger.activeRequestCount()
    internal fun unfinishedRequests(): List<Pair<String, AiRequestOwner>> = ledger.unfinished()

    /** One notification per app: its running request, or its session while no request runs. */
    internal fun notificationSources(): List<Pair<AiNotificationEntry, AiPresentation>> {
        val running = ledger.unfinished()
        val busy = running.map { it.second.appId }.toSet()
        // A leased request's Cancel names its session, which stays valid across stage gaps; an action from an
        // ended or replaced session finds no live session and does nothing. Legacy requests stay request-scoped.
        return running.map { (id, o) -> AiNotificationEntry(o.sessionId?.let { LEASE_PREFIX + it } ?: id, o.presentation.notificationId) to o.presentation } +
            leases.all().filter { it.appId !in busy }.map { AiNotificationEntry(LEASE_PREFIX + it.id, it.notificationId) to it.presentation }
    }

    /** Notification Cancel action: acts on exactly this request or session and ignores stale IDs. */
    fun cancelFromNotification(key: String): Boolean {
        if (key.startsWith(LEASE_PREFIX)) {
            val lease = leases.byId(key.removePrefix(LEASE_PREFIX)) ?: return false
            endLease(lease.appId, SessionEnd.CANCELLED); changed(); return true
        }
        if (requests[key]?.done != false) return false
        val session = jobs[key]?.sessionId
        if (session != null) {
            // Cancel aborts the whole operation: later starts under the session fail without a provider call.
            val lease = leases.byId(session)
            if (lease != null) { endLease(lease.appId, SessionEnd.CANCELLED); changed(); return true }
        }
        cancel(key)
        return true
    }
    fun cancel(id: String) {
        ledger.cancel(id)
        changed()
    }
    fun acknowledge(id: String) { ledger.acknowledge(id) }
    fun cancelTask(taskId: Int) {
        releaseTask(taskId)
        jobs.filterValues { it.taskId == taskId }.keys.toList().forEach(::cancel)
    }
    /** Final cleanup: ends every session and cancels every request. */
    fun cancelAll() {
        leases.all().forEach { endLease(it.appId, SessionEnd.RELEASED); leases.forget(it.appId) }
        jobs.keys.toList().forEach(::cancel)
        changed()
    }
    /**
     * Cancels running requests of [appId] and ends its session, even when idle, but keeps completed
     * results available for saving. Used when the AI grant is revoked.
     */
    fun cancelUnfinished(appId: String) {
        endLease(appId, SessionEnd.CANCELLED)
        jobs.filter { (id, owner) -> owner.appId == appId && requests[id]?.done == false }.keys.toList().forEach(::cancel)
        changed()
    }
    fun cancelApp(appId: String) {
        endLease(appId, SessionEnd.RELEASED); leases.forget(appId)
        jobs.filterValues { it.appId == appId }.keys.toList().forEach(::cancel)
        changed()
    }

    // ---- Operation sessions -------------------------------------------------------------------

    /** Hooks for tests; production uses the defaults. */
    internal class Timings(val idleMs: Long = AiLeaseTable.IDLE_MS, val maxMs: Long = AiLeaseTable.MAX_MS, val handshakeMs: Long = HANDSHAKE_MS)
    internal var timings = Timings()
        set(value) { field = value; leases.idleMs = value.idleMs; leases.maxMs = value.maxMs }
    internal fun interface ForegroundStarter { fun start(epoch: Long) }
    internal var foregroundStarter = ForegroundStarter { epoch ->
        ContextCompat.startForegroundService(app, Intent(app, AiRequestService::class.java).putExtra(AiRequestService.EXTRA_EPOCH, epoch))
    }

    internal fun isSessionLive(appId: String, taskId: Int, sessionId: String): Boolean =
        leases.lookup(appId, taskId, sessionId) is LeaseLookup.Live

    /** The epoch of the latest service start request, for tests that simulate later foreground loss. */
    internal val foregroundEpoch: Long get() = handshake.epoch

    private fun nextNotificationId() = AiRequestService.NOTIFICATION_BASE + notificationIds.getAndUpdate { (it + 1) % AiRequestService.NOTIFICATION_SPAN }

    private fun liveLease(appId: String, taskId: Int, sessionId: String): AiLease = when (val found = leases.lookup(appId, taskId, sessionId)) {
        is LeaseLookup.Live -> found.lease
        is LeaseLookup.Ended -> throw ApiFailure(found.reason.kind, found.reason.message)
        LeaseLookup.Unknown -> throw ApiFailure(ApiFailure.Kind.NOT_FOUND, "Session not found.")
    }

    /**
     * Opens an operation session: a bounded hold on the foreground service, begun while the owning
     * activity is [resumed]. It never calls a provider. A running request of the same task is
     * adopted, never discarded or reissued. Main thread only; [done] receives the session id, or the failure.
     */
    internal fun beginSession(
        appId: String, taskId: Int, providerId: String, model: String, title: String?, body: String?,
        resumed: Boolean, openIntent: Intent?, done: (String?, ApiFailure?) -> Unit
    ) {
        try {
            if (com.wynh.appapp.workspace.WorkspaceOperations.isBusy(java.io.File(app.filesDir, "subapps/$appId/workspace")))
                throw ApiFailure(ApiFailure.Kind.INVALID_REQUEST, "The app workspace is being changed. Try again after it finishes.")
            registry.route(providerId, model)
            if (!com.wynh.appapp.grants.SubappGrants.isGranted(app, appId, com.wynh.appapp.grants.SubappCapability.AI))
                throw ApiFailure(ApiFailure.Kind.PERMISSION, "AI is not allowed for this app. Allow it when asked, or in the library: app menu → Permissions.")
            if (!resumed) throw ApiFailure(ApiFailure.Kind.BACKGROUND_SERVICE, "Background work can only be started while the app is on screen.")
        } catch (e: ApiFailure) { done(null, e); return }
        val adopt = ledger.unfinished().any { it.second.appId == appId && it.second.taskId == taskId }
        val lease = try {
            leases.open(appId, taskId, UUID.randomUUID().toString(), nextNotificationId(),
                AiPresentation(title = AiNotificationCopy.title(title), body = AiNotificationCopy.leaseBody(body),
                    appName = com.wynh.appapp.grants.SubappGrants.displayName(app, appId), open = openIntent), adopt)
        } catch (_: IllegalStateException) {
            done(null, ApiFailure(ApiFailure.Kind.REQUEST_IN_PROGRESS, "Another task of this app already holds a background session.")); return
        }
        ledger.setSession(appId, taskId, lease.id)
        val sessionId = lease.id
        // The handshake can outlast the session: Cancel, revocation, final close or a replacing begin may
        // end it first, so the answer is checked against the table, never assumed.
        withForeground(
            ready = {
                val gone = unavailableSession(appId, taskId, sessionId)
                if (gone == null) lease.confirmed = true
                changed()
                done(if (gone == null) sessionId else null, gone)
            },
            denied = { reason ->
                val gone = unavailableSession(appId, taskId, sessionId)
                if (gone == null) endLease(appId, SessionEnd.DENIED)
                changed()
                done(null, gone ?: ApiFailure(ApiFailure.Kind.BACKGROUND_SERVICE, "Android could not start background work ($reason)."))
            })
        changed()
    }

    /** Why [sessionId] can no longer be used, or null while it is live. */
    private fun unavailableSession(appId: String, taskId: Int, sessionId: String): ApiFailure? = when (val found = leases.lookup(appId, taskId, sessionId)) {
        is LeaseLookup.Live -> null
        is LeaseLookup.Ended -> ApiFailure(found.reason.kind, found.reason.message)
        LeaseLookup.Unknown -> ApiFailure(ApiFailure.Kind.NOT_FOUND, "Session not found.")
    }

    /** The live session id of [appId], for tests that act on it the way the notification does. */
    internal fun liveSessionId(appId: String): String? = leases.leaseOf(appId)?.id

    /** Ownership-checked and idempotent: true for an own live or just-ended session, false otherwise. */
    fun endSession(appId: String, taskId: Int, sessionId: String): Boolean = when (val found = leases.lookup(appId, taskId, sessionId)) {
        is LeaseLookup.Live -> { endLease(appId, SessionEnd.RELEASED); changed(); true }
        is LeaseLookup.Ended -> true
        LeaseLookup.Unknown -> false
    }

    /** The page was replaced or reloaded: its sessions are gone, its running requests are not. */
    fun releaseTask(taskId: Int) {
        leases.all().filter { it.taskId == taskId }.forEach { endLease(it.appId, SessionEnd.RELEASED); leases.forget(it.appId) }
        changed()
    }

    private fun endLease(appId: String, reason: SessionEnd) {
        val lease = leases.end(appId, reason) ?: return
        for (id in ledger.unfinishedIn(lease.id)) if (reason.terminalizes) ledger.fail(id, reason.code, reason.message)
        ledger.detachSession(lease.id)
    }

    private val sweep = Runnable { changed() }
    private fun scheduleSweep() {
        main.removeCallbacks(sweep)
        val next = leases.nextDeadline() ?: return
        main.postDelayed(sweep, (next - leases.now()).coerceAtLeast(1))
    }

    /** Recomputes session activity and notifications after any change. Main thread. */
    private fun changed() {
        val busy = ledger.unfinished().map { it.second.appId }.toSet()
        leases.all().forEach { leases.setBusy(it, it.appId in busy) }
        leases.due().forEach { (lease, reason) -> endLease(lease.appId, reason) }
        scheduleSweep()
        AiRequestService.reconcile()
    }

    // ---- Foreground handshake -----------------------------------------------------------------

    private class Waiter(val ready: () -> Unit, val denied: (String) -> Unit)
    private val handshake = ForegroundHandshake()
    private val waiters = HashMap<Long, MutableList<Waiter>>()
    private val timeouts = HashMap<Long, Runnable>()

    /** Runs [ready] once the service is confirmed in the foreground, or [denied] with a reason. */
    private fun withForeground(ready: () -> Unit, denied: (String) -> Unit) {
        if (handshake.state == ForegroundHandshake.State.FOREGROUND && !AiRequestService.isRunning()) handshake.reset()
        if (handshake.state == ForegroundHandshake.State.FOREGROUND) { ready(); return }
        val waiter = Waiter(ready, denied)
        if (handshake.state == ForegroundHandshake.State.STARTING) { waiters.getOrPut(handshake.epoch) { mutableListOf() }.add(waiter); return }
        val epoch = handshake.begin()
        waiters.getOrPut(epoch) { mutableListOf() }.add(waiter)
        val timeout = Runnable { timeouts.remove(epoch); onForegroundFailed(epoch, "timed out", stopService = true) }
        timeouts[epoch] = timeout
        main.postDelayed(timeout, timings.handshakeMs)
        try { foregroundStarter.start(epoch) }
        catch (e: Exception) { onForegroundFailed(epoch, e.javaClass.simpleName, stopService = false) }
    }

    /** The service reached startForeground for [epoch]. */
    internal fun onForegroundConfirmed(epoch: Long) {
        if (!handshake.confirm(epoch)) return
        timeouts.remove(epoch)?.let(main::removeCallbacks)
        waiters.remove(epoch)?.forEach { it.ready() }
    }

    /**
     * Starting the foreground state failed or timed out for [epoch], or it was lost later. A pending
     * start denies its waiters; a lost one ends every session and gives running requests a terminal error.
     */
    internal fun onForegroundFailed(epoch: Long, reason: String, stopService: Boolean = false) {
        if (handshake.fail(epoch)) {
            timeouts.remove(epoch)?.let(main::removeCallbacks)
            waiters.remove(epoch)?.forEach { it.denied(reason) }
            if (stopService) runCatching { app.stopService(Intent(app, AiRequestService::class.java)) }
        } else if (handshake.lost(epoch)) {
            leases.all().forEach { endLease(it.appId, SessionEnd.DENIED) }
            for ((id, _) in ledger.unfinished()) ledger.fail(id, SessionEnd.DENIED.code, SessionEnd.DENIED.message)
            changed()
        }
    }

    /** The service object for [epoch] was destroyed. */
    internal fun onServiceDestroyed(epoch: Long) {
        val left = handshake.destroyed(epoch) ?: return
        if (left == ForegroundHandshake.State.STARTING) {
            timeouts.remove(epoch)?.let(main::removeCallbacks)
            waiters.remove(epoch)?.forEach { it.denied("service stopped") }
        } else {
            // A confirmed service vanished unexpectedly (a normal idle stop resets the state first, and a stale
            // epoch never reaches here). Nothing keeps the process foreground, so end what depended on it;
            // completed results stay available for saving.
            leases.all().forEach { endLease(it.appId, SessionEnd.DENIED) }
            for ((id, _) in ledger.unfinished()) ledger.fail(id, SessionEnd.DENIED.code, SessionEnd.DENIED.message)
            changed()
        }
    }

    /** The service had nothing to run and is stopping. */
    internal fun onServiceIdle() { handshake.idle() }

    /** Android's foreground-service time limit: sessions end, lease requests get a terminal error, others are cancelled. */
    internal fun onServiceTimeout() {
        leases.all().forEach { endLease(it.appId, SessionEnd.TIMEOUT) }
        jobs.filter { requests[it.key]?.done == false }.keys.toList().forEach(::cancel)
        changed()
    }
    fun cancelProvider(providerId: String) {
        ledger.cancelProvider(providerId)
        AiRequestService.reconcile()
    }
    suspend fun signOut(): String {
        cancelProvider("chatgpt")
        inference.clearCatalog()
        return auth.signOut()
    }

    suspend fun exposedModels(providerId: String) = registry.models(providerId)
    suspend fun models() = inference.models()
    suspend fun check(model: String) = checkLock.withLock { inference.check(model) }

    fun close() { scope.cancel() }

    companion object {
        internal const val LEASE_PREFIX = "lease:"
        internal const val HANDSHAKE_MS = 5_000L
    }
}
