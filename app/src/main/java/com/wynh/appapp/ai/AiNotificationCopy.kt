package com.wynh.appapp.ai

import android.content.Intent

/**
 * What the running-request notification shows. Title and body are optional presentation text from
 * the subapp; [appName] is derived by the host and shown separately, so subapp text cannot pose as
 * another app. It never reaches a provider or a workspace.
 */
internal data class AiPresentation(
    val notificationId: Int = 0,
    val title: String = AiNotificationCopy.DEFAULT_TITLE,
    val body: String = AiNotificationCopy.DEFAULT_BODY,
    val appName: String = "",
    val open: Intent? = null
)

internal object AiNotificationCopy {
    const val DEFAULT_TITLE = "AI request in progress"
    const val DEFAULT_BODY = "AppApp will continue while you use another app."
    const val MAX_TITLE = 60
    const val MAX_BODY = 140

    fun title(value: String?): String = clean(value, MAX_TITLE, DEFAULT_TITLE)
    fun body(value: String?): String = clean(value, MAX_BODY, DEFAULT_BODY)

    /** Single line, control characters become spaces, bounded by code points; blank falls back. */
    private fun clean(value: String?, max: Int, fallback: String): String {
        val text = value.orEmpty().map { if (Character.isISOControl(it)) ' ' else it }
            .joinToString("").trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty()) return fallback
        if (text.codePointCount(0, text.length) <= max) return text
        return text.substring(0, text.offsetByCodePoints(0, max - 1)).trimEnd() + "…"
    }
}
