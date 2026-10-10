package com.wynh.appapp.ai

import android.app.Activity
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.lifecycle.lifecycleScope
import androidx.activity.ComponentActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Origin-limited asynchronous AI interface; the JavaScript side never receives credentials. */
class AiWebMessageBridge(
    private val activity: ComponentActivity,
    private val webView: WebView,
    private val appId: String,
    private val taskId: Int,
    private val manager: AppAiManager
) {
    private val origin = "https://${appId}.app.local"
    private val listenerName = "AppAI"

    fun install(): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return false
        manager.observe(appId, webView, origin)
        WebViewCompat.addWebMessageListener(webView, listenerName, setOf(origin)) { _, message, source, isMainFrame, reply ->
            if (!AiOriginPolicy.allows(source.toString(), isMainFrame, origin)) return@addWebMessageListener
            handle(message, reply)
        }
        return true
    }

    private fun handle(message: WebMessageCompat, reply: JavaScriptReplyProxy) {
        val request = try { JsonParser.parseString(message.data ?: "{}").asJsonObject } catch (_: Exception) { return }
        val id = request.get("id")?.asString ?: return
        val action = request.get("action")?.asString ?: return
        when (action) {
            "providers" -> respond(reply, id, "result", """{"providers":[{"id":"chatgpt","name":"ChatGPT","connected":${manager.auth.state.value.accounts.any { it.canInfer }}}]}""")
            "models" -> activity.runOnUiThread { activity.lifecycleScope.launch { 
                if (request.get("providerId")?.asString?.let { it != "chatgpt" } == true) { respond(reply, id, "error", quote("Unsupported provider.")); return@launch }
                try { val models = manager.models(); respond(reply, id, "result", """{"models":${models.joinToString(prefix="[", postfix="]") { m -> """{"id":${quote(m.slug)},"name":${quote(m.displayName)},"reasoningLevels":${m.reasoningLevels.joinToString(prefix="[",postfix="]",transform=::quote)}}""" }}}""") }
                catch (e: Exception) { fail(reply, id, "models", e.message ?: "Could not load models.") }
            } }
            "start" -> {
                if (manager.hasRequest(appId)) { fail(reply, id, "request_in_progress", "A request is already running or awaiting acknowledgement."); return }
                val data = request.getAsJsonObject("data") ?: JsonObject()
                if (data.get("providerId")?.asString != "chatgpt") { fail(reply, id, "provider", "Unsupported provider."); return }
                val model = data.get("model")?.asString ?: ""
                val instructions = data.get("instructions")?.asString ?: ""
                val input = data.get("input")?.asString ?: ""
                if (model.isBlank() || input.length > 100_000 || instructions.length > 100_000) { fail(reply, id, "invalid_request", "Invalid request."); return }
                val reqId = manager.start(appId, taskId, model, instructions, input, data.get("reasoningEffort")?.asString)
                respond(reply, id, "result", quote(reqId))
                manager.event(webView, origin, """{"event":"status","id":${quote(reqId)},"data":{"status":"running"}}""")
                activity.lifecycleScope.launch {
                    while (true) {
                        val result = manager.result(reqId)
                        if (result?.done == true) {
                            val event = if (result.error != null) "error" else "complete"
                            val payload = if (result.error != null) errorJson(result.errorCode ?: "service", result.error) else quote(result.text ?: "")
                            manager.event(webView, origin, """{"event":"$event","id":${quote(reqId)},"data":$payload}""")
                            break
                        }
                        kotlinx.coroutines.delay(250)
                    }
                }
            }
            "result" -> {
                val requestId = request.get("requestId")?.asString ?: ""
                if (!manager.ownedByApp(requestId, appId)) { fail(reply, id, "not_found", "Request not found."); return }
                val result = manager.result(requestId)
                if (result == null) fail(reply, id, "not_found", "Request not found.")
                else if (!result.done) respond(reply, id, "pending", "null")
                else if (result.error != null) respond(reply, id, "error", errorJson(result.errorCode ?: "service", result.error))
                else respond(reply, id, "result", quote(result.text ?: ""))
            }
            "cancel", "acknowledge" -> {
                val requestId = request.get("requestId")?.asString ?: ""
                val result = manager.result(requestId)
                if (result != null && manager.ownedByApp(requestId, appId)) {
                    if (action == "cancel") manager.cancel(requestId) else manager.acknowledge(requestId)
                    respond(reply, id, "result", "true")
                } else fail(reply, id, "not_found", "Request not found.")
            }
            "openSettings" -> respond(reply, id, "error", quote("Manage the AI connection in AppApp Settings."))
        }
    }

    private fun respond(reply: JavaScriptReplyProxy, id: String, type: String, data: String) {
        reply.postMessage("""{"id":${quote(id)},"type":${quote(type)},"data":$data}""")
    }

    private fun fail(reply: JavaScriptReplyProxy, id: String, code: String, message: String) =
        respond(reply, id, "error", errorJson(code, message))

    private fun errorJson(code: String, message: String): String =
        """{"code":${quote(code)},"message":${quote(message)}}"""

    private fun quote(value: String): String = com.google.gson.Gson().toJson(value)
}

internal object AiOriginPolicy {
    fun allows(sourceOrigin: String, isMainFrame: Boolean, expectedOrigin: String): Boolean =
        isMainFrame && sourceOrigin == expectedOrigin
}
