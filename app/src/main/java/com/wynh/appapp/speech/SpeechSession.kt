package com.wynh.appapp.speech

import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.gson.Gson
import com.google.gson.JsonParser

class SpeechSession(private val activity: AppCompatActivity, private val web: WebView, subappId: String) {
    private val origin = "https://$subappId.app.local"
    private val controller = SpeechController(activity)
    private var generation = 0
    private var foreground = true
    private var closed = false
    private val gson = Gson()

    fun install() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        WebViewCompat.addWebMessageListener(web, "AppSpeech", setOf(origin)) { _, message, source, mainFrame, reply ->
            if (closed || !mainFrame || source.toString().trimEnd('/') != origin) return@addWebMessageListener
            val request = runCatching { JsonParser.parseString(message.data).asJsonObject }.getOrNull() ?: return@addWebMessageListener
            val id = runCatching { request.get("id").asString }.getOrNull() ?: return@addWebMessageListener
            if (id.length > 100) return@addWebMessageListener
            val action = runCatching { request.get("action").asString }.getOrNull()
            val page = generation
            fun respond(value: Any) { if (!closed && page == generation) reply.postMessage(gson.toJson(value)) }
            val error: (String, String) -> Unit = { code, detail ->
                if (code == "cancelled" && action == "speak") respond(mapOf("id" to id, "value" to mapOf("status" to "cancelled")))
                else respond(mapOf("id" to id, "error" to mapOf("code" to code, "message" to detail)))
            }
            if (!foreground) { error("unavailable", "Speech requires a foreground app."); return@addWebMessageListener }
            when (action) {
                "capabilities" -> respond(mapOf("id" to id, "value" to mapOf("features" to SpeechPolicy.features)))
                "stop" -> { controller.reset(); respond(mapOf("id" to id, "value" to true)) }
                "voices", "speak" -> controller.withEngine({
                    if (page == generation && foreground && !closed) {
                        if (action == "voices") respond(mapOf("id" to id, "value" to mapOf("voices" to controller.voices())))
                        else {
                            val options = request.get("options")
                            if (options == null || !options.isJsonObject) error("invalid_request", "Supply speech options.")
                            else controller.speak(options.asJsonObject, { status -> respond(mapOf("id" to id, "value" to mapOf("status" to status))) }, error)
                        }
                    }
                }, error)
                else -> error("invalid_request", "Unknown speech command.")
            }
        }
    }
    fun pageChanged() { generation++; controller.reset() }
    fun pause() { foreground = false; controller.reset() }
    fun resume() { foreground = true }
    fun close() { closed = true; generation++; controller.close() }
}
