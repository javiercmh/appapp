package com.wynh.appapp.ai

import com.wynh.appapp.ai.network.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import org.junit.Assert.*
import org.junit.Test

class AiProviderRegistryTest {
    private class Fake : InferenceRepository {
        var connected = true
        override suspend fun models() = listOf(ModelChoice("same/id:é", "Model", listOf("high")))
        override suspend fun check(model: String) = "ok"
        override suspend fun generate(model: String, instructions: String, input: String, effort: String?) = "ok"
    }
    @Test fun `combined model ids are stable collision free and route to their source`() = runBlocking {
        val a = Fake(); val b = Fake()
        val registry = AiProviderRegistry(listOf(AiProvider("chatgpt", "ChatGPT", a) { a.connected }, AiProvider("openrouter", "OpenRouter", b) { b.connected }))
        assertEquals("chatgpt", registry.exposed().id)
        registry.setExposure("combined")
        val models = registry.models("combined")
        assertEquals(2, models.size); assertNotEquals(models[0].slug, models[1].slug)
        assertEquals("ChatGPT · Model", models[0].displayName)
        assertEquals("OpenRouter · Model", models[1].displayName)
        assertEquals(listOf("high"), models[0].reasoningLevels)
        assertEquals(models, registry.models("combined"))
        models.forEachIndexed { index, model ->
            val route = registry.route("combined", model.slug)
            assertEquals(if (index == 0) "chatgpt" else "openrouter", route.provider.id)
            assertEquals("same/id:é", route.model)
        }
        a.connected = false
        assertTrue(registry.exposed().connected)
        assertEquals(1, registry.models("combined").size)
        b.connected = false
        assertFalse(registry.exposed().connected)
        assertThrows(ApiFailure::class.java) { registry.route("combined", models[1].slug) }
        Unit
    }
    @Test fun `selected disconnected provider stays selected and hidden calls fail`() = runBlocking {
        val a = Fake(); val b = Fake()
        val r = AiProviderRegistry(listOf(AiProvider("chatgpt", "ChatGPT", a) { a.connected }, AiProvider("openrouter", "OpenRouter", b) { b.connected }))
        assertThrows(ApiFailure::class.java) { r.route("openrouter", "same/id:é") }
        try { r.models("openrouter"); fail() } catch (e: ApiFailure) { assertEquals(ApiFailure.Kind.PROVIDER, e.kind) }
        r.setExposure("openrouter"); b.connected = false
        assertEquals("openrouter", r.exposed().id); assertFalse(r.exposed().connected)
        assertThrows(ApiFailure::class.java) { r.route("chatgpt", "same/id:é") }
        r.setExposure("combined")
        assertThrows(ApiFailure::class.java) { r.route("combined", "bad") }
        assertThrows(ApiFailure::class.java) { r.route("combined", "v1:chatgpt:%%%") }
        assertThrows(IllegalArgumentException::class.java) { r.setExposure("gemini") }
        Unit
    }
    @Test fun `exposure changes reject stale catalogs but do not mutate already resolved routes`() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val inference = object : InferenceRepository {
            override suspend fun models(): List<ModelChoice> { entered.complete(Unit); release.await(); return listOf(ModelChoice("m", "Model")) }
            override suspend fun check(model: String) = "ok"
            override suspend fun generate(model: String, instructions: String, input: String, effort: String?) = "completed $model"
        }
        val registry = AiProviderRegistry(listOf(AiProvider("chatgpt", "ChatGPT", inference) { true }, AiProvider("openrouter", "OpenRouter", Fake()) { true }))
        val captured = registry.route("chatgpt", "m")
        val loading = async {
            try { registry.models("chatgpt"); fail("Old catalogs must not be returned") }
            catch (e: ApiFailure) { assertEquals(ApiFailure.Kind.PROVIDER, e.kind) }
        }
        entered.await(); registry.setExposure("openrouter"); release.complete(Unit); loading.await()
        assertEquals("chatgpt", captured.provider.id)
        assertEquals("completed m", captured.provider.inference.generate(captured.model, "", ""))
        Unit
    }

    @Test fun `dropdown choices follow readiness without changing a saved exposure`() {
        val a = Fake(); val b = Fake()
        val registry = AiProviderRegistry(listOf(AiProvider("chatgpt", "ChatGPT", a) { a.connected }, AiProvider("openrouter", "OpenRouter", b) { b.connected }))
        a.connected = false; b.connected = false
        assertTrue(registry.selectableExposures().isEmpty())
        assertEquals("chatgpt", registry.exposure)
        b.connected = true
        assertEquals(listOf("openrouter"), registry.selectableExposures().map { it.id })
        assertEquals("chatgpt", registry.exposure)
        a.connected = true
        assertEquals(listOf("chatgpt", "openrouter", "combined"), registry.selectableExposures().map { it.id })
        registry.setExposure("combined"); b.connected = false
        assertEquals(listOf("chatgpt"), registry.selectableExposures().map { it.id })
        assertEquals("combined", registry.exposure)
        assertTrue(registry.exposed().connected)
    }

}
