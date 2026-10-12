package com.wynh.appapp.translation

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.gson.Gson
import com.google.gson.JsonParser

/** Opens the device translation handler for text supplied by the owning subapp. */
class TranslationSession(private val activity: AppCompatActivity, private val web: WebView, subappId: String) {
    private val origin = "https://$subappId.app.local"
    private val gson = Gson()
    private var foreground = true
    private var closed = false

    private fun intent() = Intent(Intent.ACTION_TRANSLATE)
    private fun available() = Build.VERSION.SDK_INT >= 29 &&
        intent().resolveActivity(activity.packageManager) != null

    fun install() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        WebViewCompat.addWebMessageListener(web, "AppTranslation", setOf(origin)) { _, message, source, mainFrame, reply ->
            if (closed || !mainFrame || source.toString().trimEnd('/') != origin) return@addWebMessageListener
            val request = runCatching { JsonParser.parseString(message.data).asJsonObject }.getOrNull()
                ?: return@addWebMessageListener
            val id = runCatching { request.get("id").asString }.getOrNull() ?: return@addWebMessageListener
            if (id.length > 100) return@addWebMessageListener
            fun respond(value: Any) { reply.postMessage(gson.toJson(value)) }
            fun error(code: String, detail: String) {
                respond(mapOf("id" to id, "error" to mapOf("code" to code, "message" to detail)))
            }
            if (!foreground) {
                error("unavailable", "Translation requires a foreground app.")
                return@addWebMessageListener
            }
            when (runCatching { request.get("action").asString }.getOrNull()) {
                "availability" -> respond(mapOf("id" to id, "value" to mapOf("available" to available())))
                "open" -> {
                    val text = runCatching {
                        val value = request.getAsJsonObject("options").get("text")
                        require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
                        value.asString
                    }.getOrNull()
                    if (text.isNullOrBlank() || text.length > 100_000) {
                        error("invalid_request", "Supply nonempty translation text of at most 100000 characters.")
                        return@addWebMessageListener
                    }
                    if (!available()) {
                        error("unavailable", "No device translation handler is available.")
                        return@addWebMessageListener
                    }
                    try {
                        activity.startActivity(intent().putExtra(Intent.EXTRA_TEXT, text))
                        respond(mapOf("id" to id, "value" to mapOf("opened" to true)))
                    } catch (_: ActivityNotFoundException) {
                        error("unavailable", "No device translation handler is available.")
                    } catch (_: SecurityException) {
                        error("unavailable", "The device translation handler could not be opened.")
                    }
                }
                else -> error("invalid_request", "Unknown translation command.")
            }
        }
    }
    fun pause() { foreground = false }
    fun resume() { foreground = true }
    fun close() { closed = true }
}
