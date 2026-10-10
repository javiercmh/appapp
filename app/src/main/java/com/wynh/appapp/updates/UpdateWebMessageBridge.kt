package com.wynh.appapp.updates

import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.gson.JsonParser
import com.wynh.appapp.workspace.SubappRecord

class UpdateWebMessageBridge(private val activity: AppCompatActivity, private val web: WebView, private val record: SubappRecord) {
    fun install() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        val origin = "https://${record.id}.app.local"
        var reviewing = false
        WebViewCompat.addWebMessageListener(web, "AppUpdates", setOf(origin)) { _, message, source, mainFrame, reply ->
            if (!com.wynh.appapp.ai.AiOriginPolicy.allows(source.toString(), mainFrame, origin)) return@addWebMessageListener
            val request = runCatching {
                val data = JsonParser.parseString(message.data).asJsonObject
                data.get("action").asString to data.get("id").asString
            }.getOrNull() ?: return@addWebMessageListener
            if (request.first != "open" || reviewing) return@addWebMessageListener
            val id = request.second
            if (id.length > 100) return@addWebMessageListener
            reviewing = true
            reply.postMessage("""{"id":${com.google.gson.Gson().toJson(id)},"opened":true}""")
            UpdateController(activity, record).open()
            web.postDelayed({ reviewing = false }, 2000)
        }
    }
}
