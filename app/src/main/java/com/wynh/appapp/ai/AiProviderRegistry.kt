package com.wynh.appapp.ai

import com.wynh.appapp.ai.network.ApiFailure
import com.wynh.appapp.ai.network.InferenceRepository
import com.wynh.appapp.ai.network.ModelChoice
import java.util.Base64

data class AiProvider(val id: String, val name: String, val inference: InferenceRepository, val connected: () -> Boolean)
data class ExposedProvider(val id: String, val name: String, val connected: Boolean)
data class ModelRoute(val provider: AiProvider, val model: String)

/** Resolves the host's single exposed provider without changing underlying connections. */
class AiProviderRegistry(val providers: List<AiProvider>, initialExposure: String = "chatgpt") {
    @Volatile var exposure: String = initialExposure
        private set
    init { require(initialExposure in modes) }
    fun setExposure(value: String) { require(value in modes); exposure = value }
    fun selectableExposures(): List<ExposedProvider> {
        val ready = providers.filter { it.connected() }.map { ExposedProvider(it.id, it.name, true) }
        return if (ready.size >= 2) ready + ExposedProvider("combined", "Combined", true) else ready
    }
    fun exposed(): ExposedProvider = if (exposure == "combined")
        ExposedProvider("combined", "App² AI", providers.any { it.connected() })
    else provider(exposure).let { ExposedProvider(it.id, it.name, it.connected()) }

    fun provider(id: String): AiProvider = providers.firstOrNull { it.id == id }
        ?: throw ApiFailure(ApiFailure.Kind.PROVIDER, "Unsupported provider.")
    private fun requireExposed(id: String) {
        if (id != exposure) throw ApiFailure(ApiFailure.Kind.PROVIDER, "This provider is not available to subapps. Review App² Settings → AI.")
    }
    suspend fun models(id: String): List<ModelChoice> {
        requireExposed(id)
        if (id != "combined") return provider(id).let {
            requireConnected(it); it.inference.models().also { _ -> requireExposed(id); requireConnected(it) }
        }
        val connected = providers.filter { it.connected() }
        if (connected.isEmpty()) throw ApiFailure(ApiFailure.Kind.AUTHENTICATION, "Connect an AI provider in App² Settings → AI.")
        // A provider failure remains visible; never silently hide its catalog.
        val models = connected.flatMap { p -> p.inference.models().map { model ->
            model.copy(slug = combinedId(p.id, model.slug), displayName = "${p.name} · ${model.displayName}")
        } }
        requireExposed(id)
        connected.forEach(::requireConnected)
        return models
    }
    fun route(id: String, model: String): ModelRoute {
        requireExposed(id)
        val route = if (id == "combined") {
            val parts = model.split(':', limit = 3)
            if (parts.size != 3 || parts[0] != "v1") invalidModel()
            val p = provider(parts[1])
            val decoded = runCatching { String(Base64.getUrlDecoder().decode(parts[2]), Charsets.UTF_8) }.getOrElse { invalidModel() }
            if (decoded.isBlank() || combinedId(p.id, decoded) != model) invalidModel()
            ModelRoute(p, decoded)
        } else ModelRoute(provider(id), model)
        requireConnected(route.provider)
        return route
    }
    private fun requireConnected(p: AiProvider) {
        if (!p.connected()) throw ApiFailure(ApiFailure.Kind.AUTHENTICATION, "Connect ${p.name} in App² Settings → AI.")
    }
    companion object {
        val modes = listOf("chatgpt", "openrouter", "combined")
        fun combinedId(provider: String, model: String): String = "v1:$provider:" +
            Base64.getUrlEncoder().withoutPadding().encodeToString(model.toByteArray(Charsets.UTF_8))
        private fun invalidModel(): Nothing = throw ApiFailure(ApiFailure.Kind.INVALID_REQUEST, "Choose a model from the current model list.")
    }
}
