package com.wynh.appapp.ai.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OpenRouterInferenceTest {
    private fun json(raw: String) = JsonParser.parseString(raw).asJsonObject
    private fun model(id: String, pricing: String = """{"prompt":"0","completion":"0","request":"0"}""", output: String = "text") =
        """{"id":"$id","name":"$id","architecture":{"input_modalities":["text"],"output_modalities":["$output"]},"pricing":$pricing}"""
    private fun catalog(vararg models: String) = """{"data":[${models.joinToString(",") }]}"""
    private val success = """{"choices":[{"finish_reason":"stop","message":{"content":"{\"story\":\"Hello\"}","reasoning":"hidden"}}]}"""
    private class Stub(var respond: (Request) -> Pair<Int, String>) {
        val requests = java.util.Collections.synchronizedList(mutableListOf<Request>())
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { chain ->
            val r = chain.request(); requests.add(r)
            val (status, body) = respond(r)
            Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(status).message("test")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val http = HttpGateway(client)
    }
    @Test fun `free catalog requires zero token prices and rejects advertised charges`() {
        val rows = OpenRouterInferenceRepository.parseModels(json(catalog(
            model("free"), model("paid", """{"prompt":"0.01","completion":"0","request":"0"}"""),
            model("unknown", "{}"), model("missing-completion", """{"prompt":"0"}"""), model("missing-request", """{"prompt":"0","completion":"0"}"""),
            model("extra-charge", """{"prompt":"0","completion":"0","request":"0","web_search":"0.1"}"""),
            model("bad", """{"prompt":"unknown","completion":"0","request":"0"}"""), model("image", output = "image")
        )))
        assertEquals(listOf("free", "paid", "unknown", "missing-completion", "missing-request", "extra-charge", "bad"), rows.map { it.choice.slug })
        assertEquals(listOf("free", "missing-request"), rows.filter { it.free }.map { it.choice.slug })
        assertTrue(rows.all { it.choice.reasoningLevels.isEmpty() })
    }
    @Test fun `real public catalog includes free router and free variants without request prices`() = runBlocking {
        val captured = checkNotNull(javaClass.getResourceAsStream("/ai/openrouter-free-models-2026-10-12.json"))
            .bufferedReader().use { it.readText() }
        val stub = Stub { request ->
            when (request.url.encodedPath) {
                "/api/v1/models" -> 200 to captured
                "/api/v1/chat/completions" -> 200 to success
                else -> error("Unexpected request")
            }
        }
        val repository = OpenRouterInferenceRepository(stub.http, { "test-key" }, { false })
        val models = repository.models()
        assertEquals(19, models.size)
        assertTrue(models.any { it.slug == "openrouter/free" && it.displayName == "Free Models Router" })
        assertTrue(models.any { it.slug.endsWith(":free") })
        assertTrue(models.any { !it.slug.endsWith(":free") && it.slug != "openrouter/free" })
        assertEquals(1, stub.requests.size)
        assertEquals("{\"story\":\"Hello\"}", repository.generate("openrouter/free", "instructions", "input"))
        val generated = stub.requests.last()
        val buffer = okio.Buffer(); generated.body!!.writeTo(buffer)
        val body = json(buffer.readUtf8())
        assertEquals("openrouter/free", body.get("model").asString)
        assertEquals(0, body.getAsJsonObject("provider").getAsJsonObject("max_price").get("request").asInt)
        assertEquals(3, stub.requests.size)
    }
    @Test fun `key verification never generates and inference sends exact model and text instructions`() = runBlocking {
        val stub = Stub { r -> 200 to when (r.url.encodedPath) {
            "/api/v1/key" -> """{"data":{"label":"test"}}"""
            "/api/v1/models" -> catalog(model("free"))
            else -> success
        } }
        val repo = OpenRouterInferenceRepository(stub.http, { "test-key" }, { false })
        repo.verifyKey("candidate")
        assertEquals(1, stub.requests.size); assertEquals("/api/v1/key", stub.requests[0].url.encodedPath)
        assertEquals("Bearer candidate", stub.requests[0].header("Authorization"))
        assertEquals("{\"story\":\"Hello\"}", repo.generate("free", "instructions", "input"))
        val request = stub.requests.last(); assertEquals("Bearer test-key", request.header("Authorization"))
        val buffer = okio.Buffer(); request.body!!.writeTo(buffer); val body = json(buffer.readUtf8())
        assertEquals("free", body.get("model").asString); assertFalse(body.get("stream").asBoolean)
        assertEquals("system", body.getAsJsonArray("messages")[0].asJsonObject.get("role").asString)
        assertEquals("input", body.getAsJsonArray("messages")[1].asJsonObject.get("content").asString)
        assertEquals(0, body.getAsJsonObject("provider").getAsJsonObject("max_price").get("prompt").asInt)
        assertFalse(body.has("reasoning"))
    }
    @Test fun `paid opt in and fresh pricing are enforced before generation`() = runBlocking {
        var paid = false; var changed = false
        val stub = Stub { r -> 200 to if (r.url.encodedPath.endsWith("models")) catalog(model("m", if (changed) """{"prompt":"1","completion":"0","request":"0"}""" else """{"prompt":"0","completion":"0","request":"0"}""")) else success }
        val repo = OpenRouterInferenceRepository(stub.http, { "key" }, { paid })
        assertEquals(1, repo.models().size); changed = true
        try { repo.generate("m", "", "input"); fail() } catch (e: ApiFailure) { assertEquals(ApiFailure.Kind.INVALID_REQUEST, e.kind) }
        assertTrue(stub.requests.none { it.url.encodedPath.endsWith("completions") })
        paid = true; assertEquals("{\"story\":\"Hello\"}", repo.generate("m", "", "input"))
        val size = stub.requests.size
        try { repo.generate("m", "", "input", "high"); fail() } catch (e: ApiFailure) { assertEquals(ApiFailure.Kind.INVALID_REQUEST, e.kind) }
        assertEquals(size, stub.requests.size)
    }
    @Test fun `terminal parsing rejects truncation refusal missing text and error payloads`() {
        val bad = listOf("{}", """{"choices":[]}""", """{"choices":[{"finish_reason":"length","message":{"content":"partial"}}]}""",
            """{"choices":[{"finish_reason":"content_filter","message":{"content":"blocked"}}]}""",
            """{"choices":[{"finish_reason":"stop","message":{"content":"","reasoning":"hidden"}}]}""",
            """{"choices":[{"finish_reason":"stop","message":{"content":"text","refusal":"no"}}]}""", """{"error":{"code":429,"message":"secret"}}""")
        bad.forEach { raw -> assertThrows(ApiFailure::class.java) { OpenRouterInferenceRepository.parseText(json(raw)) } }
    }
    @Test fun `provider http errors are safe and do not mention ChatGPT`() = runBlocking {
        listOf(401 to ApiFailure.Kind.AUTHENTICATION, 402 to ApiFailure.Kind.USAGE_LIMIT, 403 to ApiFailure.Kind.PERMISSION,
            429 to ApiFailure.Kind.USAGE_LIMIT, 500 to ApiFailure.Kind.SERVICE).forEach { (status, kind) ->
            val stub = Stub { status to """{"error":{"code":$status,"message":"secret-key"}}""" }
            val repo = OpenRouterInferenceRepository(stub.http, { "key" }, { false })
            try { repo.models(); fail() } catch (e: ApiFailure) { assertEquals(kind, e.kind); assertFalse(e.message!!.contains("secret")); assertFalse(e.message!!.contains("ChatGPT")) }
            assertEquals(1, stub.requests.size)
        }
    }
    @Test fun `invalid header characters are rejected without revealing key material`() = runBlocking {
        val stub = Stub { 200 to "{}" }
        val repo = OpenRouterInferenceRepository(stub.http, { "secret-²" }, { false })
        try { repo.verifyKey("secret-²"); fail() }
        catch (e: ApiFailure) { assertFalse(e.message!!.contains("secret")); assertEquals(ApiFailure.Kind.AUTHENTICATION, e.kind) }
        assertTrue(stub.requests.isEmpty())
    }
    @Test fun `cancellation cancels the underlying http call without retry`() = runBlocking {
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        var capturedCall: Call? = null
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callStart(call: Call) { capturedCall = call }
        }).retryOnConnectionFailure(false).addInterceptor { started.countDown(); release.await(5, TimeUnit.SECONDS); throw IOException("cancelled") }.build()
        val task = launch(Dispatchers.Default) { HttpGateway(client).json(Request.Builder().url("https://openrouter.ai/api/v1/models").build()) }
        assertTrue(started.await(5, TimeUnit.SECONDS)); task.cancelAndJoin()
        assertTrue(capturedCall!!.isCanceled()); release.countDown()
    }
}
