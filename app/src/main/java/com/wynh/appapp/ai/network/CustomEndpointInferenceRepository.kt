package com.wynh.appapp.ai.network

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** A user-entered OpenAI-compatible service: HTTPS base URL (the part before `/models`) and API key. */
data class CustomEndpoint(val baseUrl: String, val apiKey: String) {
    companion object {
        /** Returns a normalized HTTPS base URL, or null when it is not acceptable. */
        fun normalizeUrl(raw: String): String? {
            val url: HttpUrl = raw.trim().toHttpUrlOrNull() ?: return null
            if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() ||
                url.query != null || url.fragment != null || url.host.isBlank()) return null
            return url.toString().trimEnd('/')
        }
        fun validKey(key: String) = key.isNotBlank() && key.length <= 8192 && key.all { it.code in 33..126 }
    }
}

/** Generic OpenAI-compatible Chat Completions adapter. No raw errors or credentials reach subapps. */
class CustomEndpointInferenceRepository(
    private val http: HttpGateway,
    private val endpoint: () -> CustomEndpoint?
) : InferenceRepository {
    suspend fun verify(candidate: CustomEndpoint): Int = parseModels(http.json(request("models", candidate).build(), maxBytes = 8_388_608, errors = ::errorFor)).size

    override suspend fun models(): List<ModelChoice> {
        val config = endpoint() ?: throw notConfigured()
        val models = parseModels(http.json(request("models", config).build(), maxBytes = 8_388_608, errors = ::errorFor))
        if (endpoint() != config) throw ApiFailure(ApiFailure.Kind.AUTHENTICATION, "The custom endpoint changed. Load models again.")
        return models
    }
    override suspend fun check(model: String): String = generate(model, "Follow the user's request precisely.", "Say exactly: Hello, AppApp!")
    override suspend fun generate(model: String, instructions: String, input: String, effort: String?): String {
        if (effort != null) throw ApiFailure(ApiFailure.Kind.INVALID_REQUEST, "Reasoning options are not supported for custom endpoints.")
        val config = endpoint() ?: throw notConfigured()
        if (models().none { it.slug == model }) throw ApiFailure(ApiFailure.Kind.INVALID_REQUEST, "Choose a model from the current custom endpoint model list.")
        val messages = JsonArray().apply {
            if (instructions.isNotBlank()) add(JsonObject().apply { addProperty("role", "system"); addProperty("content", instructions) })
            add(JsonObject().apply { addProperty("role", "user"); addProperty("content", input) })
        }
        val body = JsonObject().apply { addProperty("model", model); addProperty("stream", false); add("messages", messages) }
        return parseText(http.json(request("chat/completions", config)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build(), maxBytes = 4_194_304, errors = ::errorFor))
    }
    private fun request(path: String, config: CustomEndpoint): Request.Builder {
        if (!CustomEndpoint.validKey(config.apiKey))
            throw ApiFailure(ApiFailure.Kind.AUTHENTICATION, "The custom endpoint key has an invalid format. Replace it in App² Settings → AI.")
        val base = CustomEndpoint.normalizeUrl(config.baseUrl) ?: throw notConfigured()
        return Request.Builder().url("$base/$path").header("Authorization", "Bearer ${config.apiKey}")
    }
    companion object {
        private fun notConfigured() = ApiFailure(ApiFailure.Kind.AUTHENTICATION, "Add a custom endpoint in App² Settings → AI.")
        fun parseModels(response: JsonObject): List<ModelChoice> {
            val rows = response.get("data")?.takeIf { it.isJsonArray }?.asJsonArray ?: throw HttpGateway.malformed()
            val models = rows.mapNotNull { row -> runCatching {
                val id = row.asJsonObject.get("id")?.asString?.takeIf { it.isNotBlank() && it.length <= 512 } ?: return@runCatching null
                ModelChoice(id, id)
            }.getOrNull() }.distinctBy { it.slug }
            if (models.isEmpty()) throw ApiFailure(ApiFailure.Kind.PERMISSION, "The custom endpoint returned no models.")
            return models
        }
        fun parseText(response: JsonObject): String = try { parseCompletedText(response) }
        catch (e: ApiFailure) { throw e }
        catch (_: Exception) { throw HttpGateway.malformed() }

        private fun parseCompletedText(response: JsonObject): String {
            val choices = response.get("choices")?.takeIf { it.isJsonArray }?.asJsonArray ?: throw HttpGateway.malformed()
            if (choices.size() < 1) throw HttpGateway.malformed()
            val choice = choices[0].asJsonObject
            if (choice.get("finish_reason")?.takeIf { !it.isJsonNull }?.asString != "stop")
                throw ApiFailure(ApiFailure.Kind.INVALID_RESPONSE, "The custom endpoint did not return a complete response.")
            val message = choice.getAsJsonObject("message") ?: throw HttpGateway.malformed()
            if (message.get("refusal")?.let { !it.isJsonNull && it.asString.isNotBlank() } == true)
                throw ApiFailure(ApiFailure.Kind.INVALID_RESPONSE, "The custom endpoint declined this request.")
            return message.get("content")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                ?.takeIf { it.isNotBlank() } ?: throw HttpGateway.malformed()
        }
        fun errorFor(status: Int, @Suppress("UNUSED_PARAMETER") code: String?): ApiFailure = when (status) {
            401 -> ApiFailure(ApiFailure.Kind.AUTHENTICATION, "The custom endpoint rejected the key. Replace it in App² Settings → AI.")
            402 -> ApiFailure(ApiFailure.Kind.USAGE_LIMIT, "The custom endpoint reports insufficient credit or quota.")
            403 -> ApiFailure(ApiFailure.Kind.PERMISSION, "The custom endpoint does not permit this request.")
            429 -> ApiFailure(ApiFailure.Kind.USAGE_LIMIT, "The custom endpoint's rate limit or quota was reached. Try again later.")
            400, 404, 422 -> ApiFailure(ApiFailure.Kind.INVALID_REQUEST, "The custom endpoint could not accept this model or request. Check the endpoint URL and model.")
            else -> ApiFailure(ApiFailure.Kind.SERVICE, "The custom endpoint could not complete the request. Try again later.")
        }
    }
}
