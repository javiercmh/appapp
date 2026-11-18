package com.wynh.appapp.ai.auth

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class OAuthAttempt(
    val redirectUri: String,
    val hostId: String,
    val registration: Registration? = null,
    val state: String = randomValue(),
    val nonce: String = randomValue(),
    val verifier: String = randomValue(),
    val retryClientId: String? = null
) {
    private val requestedClientId = registration?.clientId ?: retryClientId ?: OpenAiAuth.DYNAMIC_CLIENT
    val challenge: String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun authorizationUrl(): String {
        val params = linkedMapOf(
            "client_id" to requestedClientId,
            "ext_agent_host_id" to hostId,
            "response_type" to "code",
            "redirect_uri" to redirectUri,
            "scope" to OpenAiAuth.scopes.joinToString(" "),
            "resource" to OpenAiAuth.RESOURCE,
            "state" to state,
            "nonce" to nonce,
            "code_challenge_method" to "S256",
            "code_challenge" to challenge
        )
        if (requestedClientId == OpenAiAuth.DYNAMIC_CLIENT) params["agent_name_hint"] = "AppApp"
        registration?.credentials?.idToken?.let { params["id_token_hint"] = it }
        registration?.identity?.email?.let { params["login_hint"] = it }
        return OpenAiAuth.AUTHORIZE + "?" + params.entries.joinToString("&") {
            "${encode(it.key)}=${encode(it.value)}"
        }
    }

    fun validateCallback(target: String): CallbackResult {
        val uri = try { URI(target) } catch (_: Exception) { throw invalidCallback() }
        if (uri.isAbsolute || uri.rawPath != "/auth/callback" || uri.fragment != null) throw invalidCallback()
        val values = linkedMapOf<String, String>()
        try {
            for (part in (uri.rawQuery ?: "").split('&')) {
                val pair = part.split('=', limit = 2)
                if (pair.size != 2) throw invalidCallback()
                val key = URLDecoder.decode(pair[0], "UTF-8")
                if (values.containsKey(key)) throw invalidCallback()
                values[key] = URLDecoder.decode(pair[1], "UTF-8")
            }
        } catch (_: IllegalArgumentException) { throw invalidCallback() }
        val returnedState = values["state"] ?: throw invalidCallback()
        if (!MessageDigest.isEqual(state.toByteArray(), returnedState.toByteArray())) throw invalidCallback()
        values["error"]?.let {
            return CallbackResult.Denied(if (it == "access_denied") "Sign-in was declined. Try again when ready." else "ChatGPT could not complete sign-in. Please try again.")
        }
        val code = values["code"]?.takeIf { it.isNotBlank() } ?: throw invalidCallback()
        val clientId = values["client_id"] ?: requestedClientId.takeUnless { it == OpenAiAuth.DYNAMIC_CLIENT } ?: throw invalidCallback()
        if (clientId.isBlank() || clientId == OpenAiAuth.DYNAMIC_CLIENT) throw invalidCallback()
        if (requestedClientId != OpenAiAuth.DYNAMIC_CLIENT && clientId != requestedClientId) throw invalidCallback()
        return CallbackResult.Success(code, clientId)
    }

    override fun toString() = "OAuthAttempt([redacted])"

    companion object {
        fun randomValue(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
        private fun invalidCallback() = AuthFailure("The sign-in callback was invalid. Please restart sign-in.")
    }
}

sealed interface CallbackResult {
    class Success(val code: String, val clientId: String) : CallbackResult {
        override fun toString() = "CallbackResult.Success([redacted])"
    }
    data class Denied(val message: String) : CallbackResult
}
