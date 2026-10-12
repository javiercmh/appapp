package com.wynh.appapp.ai.network

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.math.BigDecimal

/** OpenRouter adapter. No raw errors, credentials or reasoning text reach subapps. */
class OpenRouterInferenceRepository(
    private val http: HttpGateway,
    private val key: () -> String?,
    private val allowPaid: () -> Boolean
) : InferenceRepository {
    suspend fun verifyKey(candidate: String) {
        val data = http.json(request("key", candidate).build(), errors = ::errorFor)
        if (!data.has("data") || !data.get("data").isJsonObject) throw HttpGateway.malformed()
    }
    override suspend fun models(): List<ModelChoice> {
        val token = key() ?: throw ApiFailure(ApiFailure.Kind.AUTHENTICATION, "Add an OpenRouter API key in App² Settings → AI.")
        val response = http.json(request("models", token).build(), maxBytes = 8_388_608, errors = ::errorFor)
        val all = parseModels(response)
        // Do not publish a catalog fetched for credentials or policy that changed in flight.
        if (key() != token) throw ApiFailure(ApiFailure.Kind.AUTHENTICATION, "OpenRouter connection changed. Load models again.")
        return all.filter { allowPaid() || it.free }.map { it.choice }
    }
    override suspend fun check(model: String): String = generate(model, "Follow the user's request precisely.", "Say exactly: Hello, AppApp!")
    override suspend fun generate(model: String, instructions: String, input: String, effort: String?): String {
        if (effort != null) throw ApiFailure(ApiFailure.Kind.INVALID_REQUEST, "OpenRouter reasoning options are not supported in this version.")
        // Refresh pricing immediately before every request, including queued requests.
        val models = models()
        if (models.none { it.slug == model }) throw ApiFailure(ApiFailure.Kind.INVALID_REQUEST, "This OpenRouter model is unavailable or excluded by your free-model setting.")
        val token = key() ?: throw ApiFailure(ApiFailure.Kind.AUTHENTICATION, "Add an OpenRouter API key in App² Settings → AI.")
        val messages = JsonArray().apply {
            if (instructions.isNotBlank()) add(JsonObject().apply { addProperty("role", "system"); addProperty("content", instructions) })
            add(JsonObject().apply { addProperty("role", "user"); addProperty("content", input) })
        }
        val body = JsonObject().apply {
            addProperty("model", model); addProperty("stream", false); add("messages", messages)
            // Zero price ceilings also constrain upstream routing when the free policy is active.
            if (!allowPaid()) add("provider", JsonObject().apply {
                add("max_price", JsonObject().apply { addProperty("prompt", 0); addProperty("completion", 0); addProperty("request", 0) })
            })
        }
        return parseText(http.json(request("chat/completions", token)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build(), errors = ::errorFor))
    }
    private fun request(path: String, token: String): Request.Builder {
        if (token.isBlank() || token.length > 8192 || token.any { it.code !in 33..126 })
            throw ApiFailure(ApiFailure.Kind.AUTHENTICATION, "OpenRouter key has an invalid format. Replace it in App² Settings → AI.")
        return Request.Builder().url("$BASE/$path")
            .header("Authorization", "Bearer $token").header("X-OpenRouter-Title", "AppApp")
    }
    companion object {
        const val BASE = "https://openrouter.ai/api/v1"
        fun parseModels(response: JsonObject): List<OpenRouterModel> {
            val rows = response.get("data")?.takeIf { it.isJsonArray }?.asJsonArray ?: throw HttpGateway.malformed()
            return rows.mapNotNull { row -> runCatching {
                val m = row.asJsonObject
                val architecture = m.getAsJsonObject("architecture") ?: return@runCatching null
                fun text(field: String) = architecture.getAsJsonArray(field)?.any { it.asString == "text" } == true
                if (!text("input_modalities") || !text("output_modalities")) return@runCatching null
                val id = m.get("id")?.asString?.takeIf { it.isNotBlank() } ?: return@runCatching null
                val name = m.get("name")?.asString?.takeIf { it.isNotBlank() } ?: id
                val pricing = m.get("pricing")?.takeIf { it.isJsonObject }?.asJsonObject
                val prices = pricing?.entrySet()?.associate { it.key to it.value.toString().trim('"') } ?: emptyMap()
                // Prompt/completion prices are required. Per-request and other charges
                // are optional metadata; when advertised, they must also be zero.
                val free = listOf("prompt", "completion").all { it in prices } &&
                    prices.isNotEmpty() && prices.values.all { runCatching { BigDecimal(it).compareTo(BigDecimal.ZERO) == 0 }.getOrDefault(false) }
                OpenRouterModel(ModelChoice(id, name), free)
            }.getOrNull() }.distinctBy { it.choice.slug }
        }
        fun parseText(response: JsonObject): String = try { parseCompletedText(response) }
        catch (e: ApiFailure) { throw e }
        catch (_: Exception) { throw HttpGateway.malformed() }

        private fun parseCompletedText(response: JsonObject): String {
            if (response.has("error")) {
                val status = runCatching { response.getAsJsonObject("error").get("code").asInt }.getOrDefault(500)
                throw errorFor(status, null)
            }
            val choices = response.get("choices")?.takeIf { it.isJsonArray }?.asJsonArray ?: throw HttpGateway.malformed()
            if (choices.size() != 1) throw HttpGateway.malformed()
            val choice = choices[0].asJsonObject
            if (choice.get("finish_reason")?.asString != "stop") throw ApiFailure(ApiFailure.Kind.INVALID_RESPONSE, "OpenRouter did not return a complete, unblocked response.")
            val message = choice.getAsJsonObject("message") ?: throw HttpGateway.malformed()
            if (message.get("refusal")?.let { !it.isJsonNull && it.asString.isNotBlank() } == true) throw ApiFailure(ApiFailure.Kind.INVALID_RESPONSE, "OpenRouter declined this request.")
            return message.get("content")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                ?.takeIf { it.isNotBlank() } ?: throw HttpGateway.malformed()
        }
        fun errorFor(status: Int, @Suppress("UNUSED_PARAMETER") code: String?): ApiFailure = when (status) {
            401 -> ApiFailure(ApiFailure.Kind.AUTHENTICATION, "OpenRouter key is invalid or expired. Replace it in App² Settings → AI.")
            402 -> ApiFailure(ApiFailure.Kind.USAGE_LIMIT, "OpenRouter credits are insufficient. Choose a free model or review your OpenRouter account.")
            403 -> ApiFailure(ApiFailure.Kind.PERMISSION, "OpenRouter does not permit this request. Review your account and model access.")
            429 -> ApiFailure(ApiFailure.Kind.USAGE_LIMIT, "OpenRouter quota or rate limit reached. Try again later or review your account limits.")
            400, 404, 422 -> ApiFailure(ApiFailure.Kind.INVALID_REQUEST, "OpenRouter could not accept this model or request. Refresh models and check your selection.")
            else -> ApiFailure(ApiFailure.Kind.SERVICE, "OpenRouter could not complete the request. Try again later.")
        }
    }
}
data class OpenRouterModel(val choice: ModelChoice, val free: Boolean)
