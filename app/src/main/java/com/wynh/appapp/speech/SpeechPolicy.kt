package com.wynh.appapp.speech

import com.google.gson.JsonObject
import java.util.Locale

object SpeechPolicy {
    fun language(tag: String): Locale {
        require(tag.isNotBlank() && tag.length <= 64) { "Supply a BCP 47 language tag." }
        val locale = Locale.Builder().setLanguageTag(tag).build()
        require(locale.language.isNotBlank() && locale.language != "und") { "Supply a valid speech language." }
        return locale
    }

    fun matches(requested: Locale, actual: Locale): Boolean =
        requested.language == actual.language &&
            (requested.script.isEmpty() || requested.script == actual.script)

    fun scalar(value: Double): Float {
        require(value.isFinite() && value in 0.5..2.0) { "Rate and pitch must be between 0.5 and 2." }
        return value.toFloat()
    }

    /** Subapps that draw their own playback controls pass `showControl: false`; the default keeps the native bar. */
    fun showControl(options: JsonObject): Boolean {
        val value = options.get("showControl")?.takeUnless { it.isJsonNull } ?: return true
        require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) { "showControl must be true or false." }
        return value.asBoolean
    }

    /** Optional request options this host honours, so subapps can detect them without guessing from a version. */
    val features = listOf("rate", "pitch", "showControl")
}
