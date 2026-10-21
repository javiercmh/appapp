package com.wynh.appapp.ai.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiFailure(val kind: Kind, val userMessage: String, val serviceCode: String? = null) : Exception(userMessage) {
    enum class Kind {
        AUTHENTICATION, PERMISSION, USAGE_LIMIT, NETWORK, INVALID_RESPONSE, SERVICE, INVALID_REQUEST, PROVIDER,
        /** Bridge codes are the lower-cased names; these serve operation sessions. */
        BACKGROUND_SERVICE, REQUEST_IN_PROGRESS, NOT_FOUND, SESSION_ENDED, SESSION_CANCELLED, SESSION_IDLE, SESSION_TIMEOUT
    }
}

class HttpGateway(val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
    .callTimeout(180, TimeUnit.SECONDS).retryOnConnectionFailure(false)
    .followRedirects(false).followSslRedirects(false).build()) {

    suspend fun json(request: Request, maxBytes: Long = 1_048_576, errors: (Int, String?) -> ApiFailure = ::errorFor): JsonObject = request(request, errors) { response ->
        val text = boundedBody(response, maxBytes)
        try { JsonParser.parseString(text).asJsonObject } catch (_: Exception) { throw malformed() }
    }

    suspend fun textStream(request: Request): String = request(request) { response ->
        val mediaType = response.body?.contentType()
        // Some successful responses omit Content-Type. Recognize actual SSE framing
        // without accepting arbitrary JSON/HTML or weakening terminal-event validation.
        val untypedEventStream = response.header("Content-Type") == null &&
            response.peekBody(1_024).string().trimStart().let {
                it.startsWith("data:") || it.startsWith("event:") || it.startsWith(":")
            }
        if ((mediaType?.type != "text" || mediaType.subtype != "event-stream") && !untypedEventStream) {
            val format = when {
                mediaType == null -> "missing content type"
                mediaType.type == "application" && mediaType.subtype == "json" -> "application/json"
                mediaType.type == "text" && mediaType.subtype == "html" -> "text/html"
                mediaType.type == "text" && mediaType.subtype == "plain" -> "text/plain"
                else -> "other content type"
            }
            throw ApiFailure(ApiFailure.Kind.INVALID_RESPONSE,
                "OpenAI did not return the requested event stream (HTTP ${response.code}; $format). Please try again.")
        }
        response.body?.byteStream()?.bufferedReader(Charsets.UTF_8)?.let(SseTextParser::read) ?: throw malformed()
    }

    suspend fun empty(request: Request) { request(request) { Unit } }

    private suspend fun <T> request(request: Request, errors: (Int, String?) -> ApiFailure = ::errorFor, consume: (Response) -> T): T {
        coroutineContext.ensureActive()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(connectionFailure(e, request.url.host))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            if (!it.isSuccessful) {
                                val code = runCatching {
                                    val error = JsonParser.parseString(boundedBody(it)).asJsonObject.get("error")
                                    if (error?.isJsonPrimitive == true) error.asString else error?.asJsonObject?.get("code")?.asString
                                }.getOrNull()
                                throw errors(it.code, code)
                            }
                            val result = consume(it)
                            if (continuation.isActive) continuation.resume(result)
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(
                                if (e is ApiFailure || e is com.wynh.appapp.ai.auth.AuthFailure) e
                                else if (e is IOException) ApiFailure(ApiFailure.Kind.NETWORK, "The response was interrupted. Please try again.")
                                else malformed())
                        }
                    }
                }
            })
        }
    }

    private fun boundedBody(response: Response, maxBytes: Long = 1_048_576): String {
        val source = response.body?.source() ?: throw malformed()
        if (source.request(maxBytes + 1)) throw malformed()
        return source.readUtf8()
    }

    companion object {
        fun connectionFailure(error: IOException, host: String? = null): ApiFailure {
            val detail = when (error) {
                is java.net.UnknownHostException -> "The service hostname could not be resolved. Check your network or private DNS settings."
                is javax.net.ssl.SSLException -> "A secure connection to the AI service could not be established. Check the device date and network configuration."
                is java.net.SocketTimeoutException -> "The connection timed out. Please try again."
                else -> "The connection failed. Check your internet connection and try again."
            }
            // Class names are safe diagnostics; exception messages can contain request details.
            val endpoint = host?.takeIf { it in setOf("auth.openai.com", "api.openai.com") }?.let { " · $it" } ?: ""
            return ApiFailure(ApiFailure.Kind.NETWORK, "$detail (${error.javaClass.simpleName}$endpoint)")
        }
        fun malformed() = ApiFailure(ApiFailure.Kind.INVALID_RESPONSE, "The service returned an unexpected response. Please try again.")
        fun errorFor(status: Int, code: String?): ApiFailure = when {
            code == "subscription_sharing_usage_limit_exceeded" || code == "subscription_sharing_usage_unavailable" || status == 429 ->
                ApiFailure(ApiFailure.Kind.USAGE_LIMIT, "ChatGPT plan usage is unavailable or has reached a limit. Review app access and usage in ChatGPT settings, then try again later.")
            status == 401 || code == "invalid_grant" || code == "invalid_token" -> ApiFailure(ApiFailure.Kind.AUTHENTICATION, "Your ChatGPT session needs attention. Sign in again.", code)
            status == 403 -> ApiFailure(ApiFailure.Kind.PERMISSION, "This account does not permit this request. Review ChatGPT plan access in ChatGPT settings.")
            else -> ApiFailure(ApiFailure.Kind.SERVICE, "The service could not complete the request. Please try again.")
        }
    }
}
