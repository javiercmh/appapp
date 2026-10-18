package com.wynh.appapp.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
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
    private val requestLock = Mutex()
    /** At most six generation starts per subapp per minute, across all of its tasks. */
    private val startLimiter = AiStartRateLimiter()
    private val ledger = AiRequestLedger()
    private val requests = ledger.results
    private val jobs = ledger.owners
    private val webViews = ConcurrentHashMap<String, MutableList<Pair<WeakReference<WebView>, String>>>()

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

    fun start(appId: String, taskId: Int, providerId: String, model: String, instructions: String, input: String, effort: String?): String {
        if (com.wynh.appapp.workspace.WorkspaceOperations.isBusy(java.io.File(app.filesDir, "subapps/$appId/workspace")))
            throw com.wynh.appapp.ai.network.ApiFailure(com.wynh.appapp.ai.network.ApiFailure.Kind.INVALID_REQUEST, "The app workspace is being changed. Try again after it finishes.")
        val route = registry.route(providerId, model)
        // Defense in depth: the web bridge asks first, but nothing generates without the grant.
        if (!com.wynh.appapp.grants.SubappGrants.isGranted(app, appId, com.wynh.appapp.grants.SubappCapability.AI))
            throw com.wynh.appapp.ai.network.ApiFailure(com.wynh.appapp.ai.network.ApiFailure.Kind.PERMISSION, "AI is not allowed for this app. Allow it when asked, or in the library: app menu → Permissions.")
        if (!startLimiter.tryAcquire(appId))
            throw com.wynh.appapp.ai.network.ApiFailure(com.wynh.appapp.ai.network.ApiFailure.Kind.USAGE_LIMIT, "This app started too many AI requests. Wait a minute, then generate again.")
        val id = UUID.randomUUID().toString()
        try { ledger.begin(id, AiRequestOwner(appId, taskId, route.provider.id, Job())) }
        catch (_: IllegalStateException) { throw com.wynh.appapp.ai.network.ApiFailure(com.wynh.appapp.ai.network.ApiFailure.Kind.INVALID_REQUEST, "A request is already running or awaiting acknowledgement.") }
        try {
            ContextCompat.startForegroundService(app, Intent(app, AiRequestService::class.java)
                .putExtra(AiRequestService.EXTRA_TASK_ID, taskId))
        } catch (e: Exception) {
            ledger.finish(AiRequestResult(id, error = "Background request service could not start (${e.javaClass.simpleName}).", errorCode = "background_service", done = true))
            (jobs[id]?.job as? kotlinx.coroutines.CompletableJob)?.complete()
            return id
        }
        val job = scope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try { requestLock.withLock {
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
            } } finally { AiRequestService.requestFinished(app) }
        }
        ledger.bind(id, job)
        job.start()
        return id
    }

    fun result(id: String): AiRequestResult? = requests[id]
    fun owner(id: String): Int? = jobs[id]?.taskId
    fun ownedByApp(id: String, appId: String): Boolean = jobs[id]?.appId == appId
    fun hasRequest(appId: String): Boolean = ledger.hasRequest(appId)
    fun activeRequestCount(): Int = ledger.activeRequestCount()
    fun cancel(id: String) {
        ledger.cancel(id)
        AiRequestService.requestFinished(app)
    }
    fun acknowledge(id: String) { ledger.acknowledge(id) }
    fun cancelTask(taskId: Int) {
        jobs.filterValues { it.taskId == taskId }.keys.toList().forEach(::cancel)
    }
    fun cancelAll() { jobs.keys.toList().forEach(::cancel) }
    /** Cancels running requests of [appId] but keeps completed results available for saving. */
    fun cancelUnfinished(appId: String) {
        jobs.filter { (id, owner) -> owner.appId == appId && requests[id]?.done == false }.keys.toList().forEach(::cancel)
    }
    fun cancelApp(appId: String) { jobs.filterValues { it.appId == appId }.keys.toList().forEach(::cancel) }
    fun cancelProvider(providerId: String) {
        ledger.cancelProvider(providerId)
        AiRequestService.requestFinished(app)
    }
    suspend fun signOut(): String {
        cancelProvider("chatgpt")
        inference.clearCatalog()
        return auth.signOut()
    }

    suspend fun exposedModels(providerId: String) = registry.models(providerId)
    suspend fun models() = inference.models()
    suspend fun check(model: String) = requestLock.withLock { inference.check(model) }

    fun close() { scope.cancel() }
}
