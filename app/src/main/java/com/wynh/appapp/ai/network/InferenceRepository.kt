package com.wynh.appapp.ai.network

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.wynh.appapp.ai.auth.AuthRepository
import com.wynh.appapp.ai.auth.AuthenticationRepository
import com.wynh.appapp.ai.auth.OpenAiAuth
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class ModelChoice(val slug: String, val displayName: String, val reasoningLevels: List<String> = emptyList())

object ReasoningOptions {
    val allowed = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")
    fun levels(model: JsonObject, slug: String): List<String> {
        val advertised = model.get("supported_reasoning_levels")
        if (advertised != null && advertised.isJsonArray) return advertised.asJsonArray.mapNotNull {
            val value = if (it.isJsonObject) it.asJsonObject.get("effort") else it
            value?.takeIf { v -> v.isJsonPrimitive && v.asJsonPrimitive.isString }?.asString?.takeIf(allowed::contains)
        }.distinct()
        // Conservative common efforts for documented reasoning model families.
        return if (listOf("gpt-5", "gpt-6", "o1", "o3", "o4").any(slug::startsWith)) listOf("low", "medium", "high") else emptyList()
    }
}

interface InferenceRepository {
    suspend fun models(): List<ModelChoice>
    suspend fun check(model: String): String
    suspend fun generate(model: String, instructions: String, input: String, effort: String? = null): String
}

class OpenAiInferenceRepository(private val http: HttpGateway, private val auth: AuthenticationRepository) : InferenceRepository {
    private val catalog = java.util.concurrent.ConcurrentHashMap<String, List<ModelChoice>>()
    fun clearCatalog() = catalog.clear()
    override suspend fun models(): List<ModelChoice> {
        val registration = auth.activeRegistration()
        val credentials = registration.credentials!!
        val data = http.json(Request.Builder().url("${OpenAiAuth.RESOURCE}/models")
            .header("Authorization", "Bearer ${credentials.accessToken}").build())
        val choices = data.getAsJsonArray("models")?.mapNotNull { item ->
            val model = item.asJsonObject
            if (model.get("visibility")?.asString != "list") null else
                ModelChoice(model.get("slug")?.asString ?: throw HttpGateway.malformed(),
                    model.get("display_name")?.asString ?: throw HttpGateway.malformed(),
                    ReasoningOptions.levels(model, model.get("slug").asString))
        }?.takeIf { it.isNotEmpty() } ?: throw ApiFailure(ApiFailure.Kind.PERMISSION, "No available models were returned for this account.")
        catalog[registration.clientId] = choices
        return choices
    }

    override suspend fun check(model: String): String {
        return generate(model, "Follow the user's request precisely.", "Say exactly: Hello, AppApp!")
    }

    override suspend fun generate(model: String, instructions: String, input: String, effort: String?): String {
        val registration = auth.activeRegistration()
        val credentials = registration.credentials!!
        val selectedModel = catalog[registration.clientId]?.firstOrNull { it.slug == model }
            ?: throw ApiFailure(ApiFailure.Kind.INVALID_RESPONSE, "Choose a model from the current ChatGPT model list.")
        val messages = JsonArray().apply { add(JsonObject().apply {
            addProperty("role", "user"); addProperty("content", input)
        }) }
        val available = selectedModel.reasoningLevels
        val savedEffort = effort?.takeIf { it in available }
        val body = JsonObject().apply {
            addProperty("model", model); add("input", messages)
            addProperty("instructions", instructions)
            addProperty("store", false); addProperty("stream", true)
            if (savedEffort in ReasoningOptions.allowed) add("reasoning", JsonObject().apply { addProperty("effort", savedEffort) })
        }
        return http.textStream(Request.Builder().url("${OpenAiAuth.RESOURCE}/responses")
            .header("Authorization", "Bearer ${credentials.accessToken}")
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build())
    }
}
