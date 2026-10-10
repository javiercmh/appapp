package com.wynh.appapp.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import androidx.core.content.ContextCompat
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import com.google.gson.JsonObject
import com.wynh.appapp.ai.auth.AuthRepository
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

data class AiRequestResult(val id: String, val text: String? = null, val error: String? = null, val errorCode: String? = null, val done: Boolean = false)
private data class AiRequestOwner(val appId: String, val taskId: Int, val job: Job)

/** Process-wide ChatGPT identity, catalog and native request owner. */
class AppAiManager(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val http = HttpGateway()
    val auth = AuthRepository(EncryptedCredentialStore(app), OpenAiOAuthApi(http), IdTokenValidator(), scope)
    val inference = OpenAiInferenceRepository(http, auth)
    private val requestLock = Mutex()
    private val requests = ConcurrentHashMap<String, AiRequestResult>()
    private val jobs = ConcurrentHashMap<String, AiRequestOwner>()
    private val webViews = ConcurrentHashMap<String, MutableList<Pair<WeakReference<WebView>, String>>>()

    init {
        scope.launch {
            auth.state.collect { state ->
                val connected = state.accounts.any { it.canInfer }
                val message = """{"event":"connection","data":{"providerId":"chatgpt","connected":$connected}}"""
                webViews.forEach { (_, views) ->
                    synchronized(views) {
                        views.removeAll { ref ->
                            val view = ref.first.get() ?: return@removeAll true
                            runCatching { WebViewCompat.postWebMessage(view, WebMessageCompat(message), Uri.parse(ref.second)) }
                            false
                        }
                    }
                }
            }
        }
    }

    fun observe(appId: String, webView: WebView, origin: String) {
        val views = webViews.getOrPut(appId) { mutableListOf() }
        synchronized(views) { views.add(WeakReference(webView) to origin) }
        val connected = auth.state.value.accounts.any { it.canInfer }
        event(webView, origin, """{"event":"connection","data":{"providerId":"chatgpt","connected":$connected}}""")
    }

    fun event(webView: WebView, origin: String, message: String) {
        runCatching { WebViewCompat.postWebMessage(webView, WebMessageCompat(message), Uri.parse(origin)) }
    }

    fun beginSignIn(clientId: String? = null) = auth.beginSignIn(clientId)

    fun browserRequests(block: suspend (String) -> Unit) = scope.launch(Dispatchers.IO) {
        for (url in auth.browserRequests) block(url)
    }

    suspend fun nextAuthorizationUrl(): String = withTimeout(600_000) { auth.browserRequests.receive() }

    fun start(appId: String, taskId: Int, model: String, instructions: String, input: String, effort: String?): String {
        val id = UUID.randomUUID().toString()
        synchronized(requests) {
            if (hasRequest(appId)) {
                requests[id] = AiRequestResult(id, error = "A request is already running or awaiting acknowledgement.", done = true)
                jobs[id] = AiRequestOwner(appId, taskId, Job())
                return id
            }
            requests[id] = AiRequestResult(id)
            jobs[id] = AiRequestOwner(appId, taskId, Job())
        }
        try {
            ContextCompat.startForegroundService(app, Intent(app, AiRequestService::class.java)
                .putExtra(AiRequestService.EXTRA_TASK_ID, taskId))
        } catch (e: Exception) {
            requests[id] = AiRequestResult(id, error = "Background request service could not start (${e.javaClass.simpleName}).", errorCode = "background_service", done = true)
            jobs[id] = AiRequestOwner(appId, taskId, Job())
            return id
        }
        val job = scope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            requestLock.withLock {
                try {
                    val result = inference.generate(model, instructions, input, effort)
                    requests[id] = AiRequestResult(id, text = result, done = true)
                } catch (e: Exception) {
                    val code = when (e) {
                        is com.wynh.appapp.ai.network.ApiFailure -> e.kind.name.lowercase()
                        is com.wynh.appapp.ai.auth.AuthFailure -> "authentication"
                        else -> "service"
                    }
                    requests[id] = AiRequestResult(id, error = e.message ?: "The request failed.", errorCode = code, done = true)
                }
                AiRequestService.requestFinished(app)
            }
        }
        jobs[id] = AiRequestOwner(appId, taskId, job)
        job.start()
        return id
    }

    fun result(id: String): AiRequestResult? = requests[id]
    fun owner(id: String): Int? = jobs[id]?.taskId
    fun ownedByApp(id: String, appId: String): Boolean = jobs[id]?.appId == appId
    fun hasRequest(appId: String): Boolean = requests.keys.any { id -> ownedByApp(id, appId) }
    fun activeRequestCount(): Int = jobs.values.count { it.job.isActive }
    fun cancel(id: String) {
        jobs.remove(id)?.job?.cancel()
        requests.remove(id)
    }
    fun acknowledge(id: String) { requests.remove(id); jobs.remove(id) }
    fun cancelTask(taskId: Int) {
        jobs.filterValues { it.taskId == taskId }.keys.toList().forEach(::cancel)
    }
    fun cancelAll() { jobs.keys.toList().forEach(::cancel) }
    fun cancelApp(appId: String) { jobs.filterValues { it.appId == appId }.keys.toList().forEach(::cancel) }
    suspend fun signOut(): String {
        cancelAll()
        inference.clearCatalog()
        return auth.signOut()
    }

    suspend fun models() = inference.models()
    suspend fun check(model: String) = inference.check(model)

    fun close() { scope.cancel() }
}
