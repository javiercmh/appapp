package com.wynh.appapp.speech

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import com.google.gson.JsonObject
import com.google.android.material.snackbar.Snackbar
import java.util.UUID

/** Foreground speech owned by one WebView session. All state belongs to the main thread. */
class SpeechController(private val activity: Activity) {
    private val handler = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var ready = false
    private var closed = false
    private var initialization = 0
    private val waiting = mutableListOf<Pair<() -> Unit, (String, String) -> Unit>>()
    private var current: String? = null
    private var complete: ((String) -> Unit)? = null
    private var failure: ((String, String) -> Unit)? = null
    private var control: Snackbar? = null
    private val initTimeout = Runnable { initializationFailed() }

    private fun initializationFailed() {
        if (closed || ready) return
        initialization++
        engine?.shutdown(); engine = null
        val pending = waiting.toList(); waiting.clear()
        pending.forEach { it.second("unavailable", "The speech engine could not initialize.") }
    }

    fun withEngine(action: () -> Unit, error: (String, String) -> Unit) {
        if (closed) { error("unavailable", "Speech session closed."); return }
        if (ready) { action(); return }
        waiting.add(action to error)
        if (engine != null) return
        handler.postDelayed(initTimeout, 10000)
        val attempt = ++initialization
        engine = TextToSpeech(activity.applicationContext) { status -> handler.post {
            if (closed || attempt != initialization) return@post
            handler.removeCallbacks(initTimeout)
            if (status != TextToSpeech.SUCCESS) { initializationFailed(); return@post }
            ready = true
            engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { }
                override fun onStop(id: String?, interrupted: Boolean) { handler.post { if (id == current) finish("cancelled") } }
                override fun onDone(id: String?) { handler.post { if (id == current) finish("completed") } }
                @Deprecated("Platform callback")
                override fun onError(id: String?) { handler.post { if (id == current) fail("synthesis", "Speech playback failed.") } }
                override fun onError(id: String?, code: Int) { handler.post {
                    if (id == current) fail(if (code == TextToSpeech.ERROR_NETWORK || code == TextToSpeech.ERROR_NETWORK_TIMEOUT) "network" else "synthesis", "Speech playback failed (engine error $code).")
                } }
            })
            val pending = waiting.toList(); waiting.clear(); pending.forEach { it.first() }
        } }
    }

    fun voices(): List<Map<String, Any>> = engine?.voices.orEmpty().map {
        mapOf("id" to it.name, "name" to it.name, "language" to it.locale.toLanguageTag(),
            "requiresNetwork" to it.isNetworkConnectionRequired)
    }

    fun speak(options: JsonObject, done: (String) -> Unit, error: (String, String) -> Unit) {
        val tts = engine ?: return error("unavailable", "Speech engine unavailable.")
        try {
            val text = options.get("text")?.asString.orEmpty()
            require(text.isNotBlank()) { "Supply text to speak." }
            require(text.length <= TextToSpeech.getMaxSpeechInputLength()) { "Text exceeds the speech engine input limit." }
            val locale = SpeechPolicy.language(options.get("language")?.asString.orEmpty())
            val rate = SpeechPolicy.scalar(options.get("rate")?.asDouble ?: 1.0)
            val pitch = SpeechPolicy.scalar(options.get("pitch")?.asDouble ?: 1.0)
            val showControl = SpeechPolicy.showControl(options)
            val id = options.get("voiceId")?.takeUnless { it.isJsonNull }?.asString
            stop()
            val voice = if (id != null) {
                tts.voices.orEmpty().firstOrNull { it.name == id && SpeechPolicy.matches(locale, it.locale) }
                    ?: return error("voice", "The requested voice is unavailable or uses another language.")
            } else {
                when (tts.setLanguage(locale)) {
                    TextToSpeech.LANG_MISSING_DATA -> return error("missing_data", "Install voice data for ${locale.toLanguageTag()} in Android text-to-speech settings.")
                    TextToSpeech.LANG_NOT_SUPPORTED -> return error("language", "This speech engine does not support ${locale.toLanguageTag()}.")
                }
                tts.voice?.takeIf { SpeechPolicy.matches(locale, it.locale) }
                    ?: return error("language", "No compatible voice is available for ${locale.toLanguageTag()}.")
            }
            if (tts.setVoice(voice) != TextToSpeech.SUCCESS || tts.setSpeechRate(rate) != TextToSpeech.SUCCESS ||
                tts.setPitch(pitch) != TextToSpeech.SUCCESS) return error("voice", "The speech engine rejected the voice settings.")
            current = UUID.randomUUID().toString(); complete = done; failure = error
            control = if (!showControl) null else
                Snackbar.make(activity.findViewById(android.R.id.content), "Speaking", Snackbar.LENGTH_INDEFINITE)
                    .setAction("Stop") { stop() }.also { it.show() }
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, current) != TextToSpeech.SUCCESS)
                fail("synthesis", "The speech engine could not start playback.")
        } catch (e: Exception) {
            if (failure === error) { engine?.stop(); fail("synthesis", "The speech engine could not start playback.") }
            else error("invalid_request", e.message ?: "Invalid speech request.")
        }
    }

    private fun finish(status: String) {
        val callback = complete
        current = null; complete = null; failure = null
        control?.dismiss(); control = null
        callback?.invoke(status)
    }
    private fun fail(code: String, message: String) {
        val callback = failure
        complete = null; finish("cancelled")
        callback?.invoke(code, message)
    }
    fun stop() { engine?.stop(); finish("cancelled") }
    fun reset() {
        val pending = waiting.toList(); waiting.clear()
        pending.forEach { it.second("cancelled", "Speech interrupted.") }
        stop()
    }
    fun close() {
        closed = true; reset(); handler.removeCallbacks(initTimeout)
        engine?.shutdown(); engine = null; ready = false
    }
    fun showError(@Suppress("UNUSED_PARAMETER") code: String, message: String) { Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }
}
