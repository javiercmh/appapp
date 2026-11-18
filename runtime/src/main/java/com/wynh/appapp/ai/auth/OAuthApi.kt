package com.wynh.appapp.ai.auth

import com.google.gson.JsonObject
import com.nimbusds.jose.jwk.JWKSet
import com.wynh.appapp.ai.network.HttpGateway
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

class TokenGrant(
    val accessToken: String,
    val refreshToken: String?,
    val idToken: String?,
    val expiresInSeconds: Long,
    val scopes: Set<String>?
) { override fun toString() = "TokenGrant([redacted])" }

interface OAuthApi {
    suspend fun exchange(attempt: OAuthAttempt, callback: CallbackResult.Success): TokenGrant
    suspend fun refresh(registration: Registration): TokenGrant
    suspend fun signingKeys(): JWKSet
    suspend fun revoke(registration: Registration)
}

class OpenAiOAuthApi(private val http: HttpGateway) : OAuthApi {
    override suspend fun exchange(attempt: OAuthAttempt, callback: CallbackResult.Success): TokenGrant = token(
        "grant_type" to "authorization_code", "client_id" to callback.clientId,
        "code" to callback.code, "code_verifier" to attempt.verifier,
        "redirect_uri" to attempt.redirectUri, "resource" to OpenAiAuth.RESOURCE
    )

    override suspend fun refresh(registration: Registration): TokenGrant = token(
        "grant_type" to "refresh_token", "client_id" to registration.clientId,
        "refresh_token" to (registration.credentials?.refreshToken ?: throw AuthFailure("Sign in again to renew your ChatGPT session.")),
        "resource" to OpenAiAuth.RESOURCE
    )

    override suspend fun signingKeys(): JWKSet {
        val discovery = discovery()
        val url = trustedEndpoint(discovery.requiredString("jwks_uri"))
        return try { JWKSet.parse(http.json(Request.Builder().url(url).build()).toString()) }
        catch (e: com.wynh.appapp.ai.network.ApiFailure) { throw e }
        catch (_: Exception) { throw AuthFailure("OpenAI signing keys could not be validated. Please try again.") }
    }

    override suspend fun revoke(registration: Registration) {
        val refresh = registration.credentials?.refreshToken ?: return
        val url = trustedEndpoint(discovery().requiredString("revocation_endpoint"))
        val body = FormBody.Builder().add("token", refresh).add("token_type_hint", "refresh_token")
            .add("client_id", registration.clientId).build()
        http.empty(Request.Builder().url(url).post(body).build())
    }

    private suspend fun discovery(): JsonObject = http.json(Request.Builder().url(OpenAiAuth.DISCOVERY).build()).also {
        if (it.requiredString("issuer") != OpenAiAuth.ISSUER) throw AuthFailure("The OpenAI issuer could not be verified.")
    }

    private fun trustedEndpoint(value: String): String {
        val url = value.toHttpUrl()
        require(url.isHttps && url.host == "auth.openai.com" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty())
        return value
    }

    private suspend fun token(vararg fields: Pair<String, String>): TokenGrant {
        val form = FormBody.Builder().apply { fields.forEach { add(it.first, it.second) } }.build()
        val data = http.json(Request.Builder().url(OpenAiAuth.TOKEN).post(form).build())
        if (data.requiredString("token_type").lowercase() != "bearer") throw AuthFailure("OpenAI returned an unsupported token type.")
        val expiry = data.get("expires_in")?.asLong ?: throw AuthFailure("OpenAI did not provide token expiry information.")
        if (expiry !in 1..2_592_000) throw AuthFailure("OpenAI returned invalid token expiry information.")
        return TokenGrant(data.requiredString("access_token"), data.optionalString("refresh_token"),
            data.optionalString("id_token"), expiry,
            data.optionalString("scope")?.split(' ')?.filter { it.isNotBlank() }?.toSet())
    }
}

private fun JsonObject.requiredString(name: String): String = optionalString(name)
    ?: throw AuthFailure("OpenAI returned an incomplete authentication response.")
private fun JsonObject.optionalString(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
