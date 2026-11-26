package com.wynh.appapp.dictionary

import android.content.ActivityNotFoundException
import android.content.Intent
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.wynh.appapp.channel.ChannelException
import com.wynh.appapp.channel.ChannelProcessor
import com.wynh.appapp.share.ShareIntake
import com.wynh.appapp.share.ShareSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Hands a selection to a supported Android dictionary through its read-only text action. */
internal class DictionarySession(
    private val activity: AppCompatActivity,
    private val web: WebView,
    subappId: String
) {
    private val origin = "https://$subappId.app.local"
    private val state = ShareSessionState(origin)
    private val processor = ChannelProcessor(
        CoroutineScope(SupervisorJob() + Dispatchers.Main), Dispatchers.Default, state,
        ShareIntake(maxPending = 2, maxChars = MAX_MESSAGE.toLong()), MAX_MESSAGE,
        refusal = { state.refusal(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED), false) }
    ) { call ->
        when (call.action) {
            "availability" -> mapOf("available" to (dictionaryIntent().resolveActivity(activity.packageManager) != null))
            "open" -> {
                val text = ChannelProcessor.string(call.options, "text")
                if (text.isNullOrBlank() || text.length > MAX_TEXT) {
                    throw ChannelException("invalid_request", "Supply nonempty dictionary text of at most $MAX_TEXT characters.")
                }
                val intent = dictionaryIntent()
                val component = intent.resolveActivity(activity.packageManager)
                    ?: throw ChannelException("unavailable", "No supported dictionary is available.")
                try {
                    activity.startActivity(intent.setComponent(component).putExtra(Intent.EXTRA_PROCESS_TEXT, text))
                } catch (_: ActivityNotFoundException) {
                    throw ChannelException("unavailable", "No supported dictionary is available.")
                } catch (_: SecurityException) {
                    throw ChannelException("unavailable", "The dictionary could not be opened.")
                }
                mapOf("opened" to true)
            }
            else -> throw ChannelException("invalid_request", "Unknown dictionary command.")
        }
    }

    // PROCESS_TEXT also includes unrelated actions. Resolve only the verified dictionary package.
    private fun dictionaryIntent() = Intent(Intent.ACTION_PROCESS_TEXT)
        .setPackage("de.reimardoeffinger.quickdic")
        .setType("text/plain")
        .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)

    fun install() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        WebViewCompat.addWebMessageListener(web, "AppDictionary", setOf(origin)) { _, message, source, mainFrame, reply ->
            if (!state.accepts(source.toString(), mainFrame)) return@addWebMessageListener
            processor.receive(message.data ?: return@addWebMessageListener) { reply.postMessage(it) }
        }
    }

    fun pause() { state.foreground = false }
    fun resume() { state.foreground = true }
    fun pageChanged() { state.pageChanged() }
    fun close() { processor.close() }

    private companion object {
        const val MAX_TEXT = 1_000
        const val MAX_MESSAGE = 7_000
    }
}
