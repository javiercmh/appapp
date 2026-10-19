package com.wynh.appapp.ai

import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.lifecycle.lifecycleScope
import androidx.activity.ComponentActivity
import kotlinx.coroutines.launch
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import androidx.lifecycle.Lifecycle
import com.wynh.appapp.MainActivity
import com.wynh.appapp.R
import com.wynh.appapp.SubappRuntimeActivity
import com.wynh.appapp.grants.SubappCapability
import com.wynh.appapp.grants.SubappGrants

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
    private var prompting = false

    fun install(): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return false
        manager.observe(appId, webView, origin)
        WebViewCompat.addWebMessageListener(webView, listenerName, setOf(origin)) { _, message, source, isMainFrame, reply ->
            if (!AiOriginPolicy.allows(source.toString(), isMainFrame, origin)) return@addWebMessageListener
            try { handle(message, reply) }
            catch (_: Exception) {
                val id = runCatching { JsonParser.parseString(message.data).asJsonObject.get("id") }
                    .getOrNull()?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                if (id != null) fail(reply, id, "invalid_request", "Invalid AI command.")
            }
        }
        return true
    }

    private fun handle(message: WebMessageCompat, reply: JavaScriptReplyProxy) {
        val request = try { JsonParser.parseString(message.data ?: "{}").asJsonObject } catch (_: Exception) { return }
        val id = request.get("id")?.asString ?: return
        val action = request.get("action")?.asString ?: return
        when (action) {
            "providers" -> respond(reply, id, "result", com.google.gson.Gson().toJson(mapOf("providers" to listOf(manager.registry.exposed()))))
            "models" -> activity.runOnUiThread { activity.lifecycleScope.launch { 
                try { val models = manager.exposedModels(request.get("providerId")?.asString ?: "chatgpt"); respond(reply, id, "result", """{"models":${models.joinToString(prefix="[", postfix="]") { m -> """{"id":${quote(m.slug)},"name":${quote(m.displayName)},"reasoningLevels":${m.reasoningLevels.joinToString(prefix="[",postfix="]",transform=::quote)}}""" }}}""") }
                catch (e: Exception) { fail(reply, id, (e as? com.wynh.appapp.ai.network.ApiFailure)?.kind?.name?.lowercase() ?: "models", e.message ?: "Could not load models.") }
            } }
            "start" -> {
                if (manager.hasRequest(appId)) { fail(reply, id, "request_in_progress", "A request is already running or awaiting acknowledgement."); return }
                val data = request.getAsJsonObject("data") ?: JsonObject()
                val providerId = data.get("providerId")?.asString ?: "chatgpt"
                val model = data.get("model")?.asString ?: ""
                val instructions = data.get("instructions")?.asString ?: ""
                val input = data.get("input")?.asString ?: ""
                val effort = data.get("reasoningEffort")?.takeUnless { it.isJsonNull }?.asString
                val title = optionalText(data, "title")
                val body = optionalText(data, "body")
                if (title == null || body == null) { fail(reply, id, "invalid_request", "Invalid request."); return }
                if (model.isBlank() || model.length > 8192 || providerId.length > 32 || input.length > 100_000 || instructions.length > 100_000) { fail(reply, id, "invalid_request", "Invalid request."); return }
                val begin = { start(reply, id, providerId, model, instructions, input, effort, title.value, body.value) }
                if (SubappGrants.isGranted(activity, appId, SubappCapability.AI)) begin()
                else askForAi(providerId, model) { allowed ->
                    if (!allowed) fail(reply, id, "permission", "AI was not allowed for this app. Allow it in the library: app menu → Permissions.")
                    else if (manager.hasRequest(appId)) fail(reply, id, "request_in_progress", "A request is already running or awaiting acknowledgement.")
                    else begin()
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
                    if (action == "acknowledge" && !result.done) { fail(reply, id, "invalid_request", "Only completed requests can be acknowledged."); return }
                    if (action == "cancel") manager.cancel(requestId) else manager.acknowledge(requestId)
                    respond(reply, id, "result", "true")
                } else fail(reply, id, "not_found", "Request not found.")
            }
            "openSettings" -> respond(reply, id, "error", quote("Manage the AI connection in AppApp Settings."))
            else -> fail(reply, id, "invalid_request", "Unsupported AI command.")
        }
    }

    private class OptionalText(val value: String?)

    /** Absent or null is allowed; any other non-string value is invalid. Length is bounded natively. */
    private fun optionalText(data: JsonObject, key: String): OptionalText? {
        val element = data.get(key)
        if (element == null || element.isJsonNull) return OptionalText(null)
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
        return OptionalText(element.asString)
    }

    /** Brings this activity's task forward from the notification; derived from the host, never from the page. */
    private fun openIntent(): Intent =
        if (activity is MainActivity) Intent(activity, MainActivity::class.java).putExtra(MainActivity.EXTRA_SUBAPP_ID, appId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        else Intent(activity, SubappRuntimeActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("appapp://subapp/$appId"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS)

    private fun start(reply: JavaScriptReplyProxy, id: String, providerId: String, model: String, instructions: String, input: String, effort: String?, title: String?, body: String?) {
        val reqId = try { manager.start(appId, taskId, providerId, model, instructions, input, effort, title, body, openIntent()) }
        catch (e: com.wynh.appapp.ai.network.ApiFailure) { fail(reply, id, e.kind.name.lowercase(), e.userMessage); return }
        respond(reply, id, "result", quote(reqId))
        manager.event(webView, origin, """{"event":"status","id":${quote(reqId)},"data":{"status":"running"}}""")
        activity.lifecycleScope.launch {
            while (true) {
                val result = manager.result(reqId)
                if (result == null) break
                if (result.done) {
                    val event = if (result.error != null) "error" else "complete"
                    val payload = if (result.error != null) errorJson(result.errorCode ?: "service", result.error) else quote(result.text ?: "")
                    manager.event(webView, origin, """{"event":"$event","id":${quote(reqId)},"data":$payload}""")
                    break
                }
                kotlinx.coroutines.delay(250)
            }
        }
    }

    /** One native disclosure dialog at a time; nothing is sent to a provider before Allow. */
    private fun askForAi(providerId: String, model: String, onResult: (Boolean) -> Unit) {
        if (prompting || activity.isFinishing || activity.isDestroyed ||
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { onResult(false); return }
        prompting = true
        val name = SubappGrants.displayName(activity, appId)
        SubappGrants.ask(activity, activity.getString(R.string.permission_ai_title, name), aiDisclosure(name, providerId, model)) { allowed ->
            prompting = false
            if (allowed) SubappGrants.setGranted(activity, appId, SubappCapability.AI, true)
            onResult(allowed)
        }
    }

    private fun aiDisclosure(name: String, providerId: String, model: String): String {
        val paid = manager.allowPaidModels
        val provider = when (manager.exposure) {
            "chatgpt" -> "ChatGPT"
            "openrouter" -> "OpenRouter"
            else -> "App² AI (Combined: ChatGPT and OpenRouter)"
        }
        val destination = runCatching { manager.registry.route(providerId, model).provider.name }.getOrNull()
        val openRouterCost = if (paid) "OpenRouter models use your OpenRouter quota and can spend OpenRouter credits, because paid models are enabled."
            else "OpenRouter models use your OpenRouter quota; only free models are enabled."
        val cost = when (manager.exposure) {
            "chatgpt" -> "Requests consume your ChatGPT plan usage."
            "openrouter" -> openRouterCost
            else -> "ChatGPT models consume your ChatGPT plan usage. $openRouterCost"
        }
        return buildString {
            append("$name will send the prompt text and instructions it supplies to $provider, using the account connected in App² Settings → AI.")
            if (destination != null && manager.exposure == "combined") append(" This request goes to $destination.")
            append("\n\n$cost")
            append("\n\nThis permission covers future AI requests from this app. Running requests can be cancelled from their notification. You can revoke it in the library: app menu → Permissions.")
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
