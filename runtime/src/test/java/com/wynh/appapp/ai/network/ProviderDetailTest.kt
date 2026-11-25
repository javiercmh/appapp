package com.wynh.appapp.ai.network

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/** Provider error text shown to the user: passed through for opted-in providers, without credentials. */
class ProviderDetailTest {
    private val base = ApiFailure(ApiFailure.Kind.USAGE_LIMIT, "Limit.", "code_x")
    private fun gateway(status: Int, body: String) = HttpGateway(OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("test")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build(), HttpGateway.MIN_OUTPUT_UNITS)
    private fun failure(block: suspend () -> Unit): ApiFailure = try { runBlocking { block() }; fail("Expected failure"); throw AssertionError() }
        catch (e: ApiFailure) { e }

    @Test fun `provider messages are read from string and object error bodies`() {
        fun message(raw: String) = HttpGateway.providerMessage(JsonParser.parseString(raw).asJsonObject.get("error"))
        assertEquals("model 'llama3' not found", message("""{"error":"model 'llama3' not found"}"""))
        assertEquals("Rate limit exceeded", message("""{"error":{"message":"Rate limit exceeded","code":429}}"""))
        assertNull(message("""{"error":{"code":429}}"""))
        assertNull(message("""{"detail":"x"}"""))
    }

    @Test fun `appended text keeps kind and service code and drops credentials`() {
        val secret = "my-local-endpoint-key-123"
        val detail = ProviderDetail("The endpoint", listOf(secret, "short"))
        val result = detail.appendTo(base, "Weekly limit for $secret reached; see https://ollama.com/settings?token=abc\nBearer abc.def sk-proj-ABCDEFGH12345678 ${"x".repeat(40)}")
        assertEquals(ApiFailure.Kind.USAGE_LIMIT, result.kind); assertEquals("code_x", result.serviceCode)
        assertTrue(result.userMessage.contains("Weekly limit for"))
        for (gone in listOf(secret, "https://", "ollama.com", "Bearer", "abc.def", "sk-proj", "x".repeat(32), "\n")) assertFalse(gone, result.userMessage.contains(gone))
    }

    @Test fun `short secrets are not stripped from ordinary words and long text is bounded`() {
        assertTrue(ProviderDetail("E", listOf("key")).clean("Invalid key format")!!.contains("Invalid key format"))
        val long = ProviderDetail("E").clean("word ".repeat(200))!!
        assertTrue(long.length <= ProviderDetail.MAX_UNITS + 1); assertTrue(long.endsWith("…"))
    }

    @Test fun `blank or fully removed text leaves the failure unchanged`() {
        val detail = ProviderDetail("E", listOf("sk-secret-value-123"))
        for (raw in listOf(null, "", "   ", "sk-secret-value-123", "https://example.com/x", "\"\"")) assertSame(base, detail.appendTo(base, raw))
    }

    @Test fun `only opted-in requests carry provider text`() {
        val body = """{"error":"weekly usage limit reached"}"""
        val plain = failure { gateway(429, body).json(Request.Builder().url("http://127.0.0.1/x").build()) }
        assertFalse(plain.userMessage.contains("weekly usage limit reached"))
        val detailed = failure { gateway(429, body).json(Request.Builder().url("http://127.0.0.1/x").build(),
            errors = CustomEndpointInferenceRepository::errorFor, detail = ProviderDetail("The endpoint")) }
        assertEquals(ApiFailure.Kind.USAGE_LIMIT, detailed.kind); assertTrue(detailed.userMessage.contains("weekly usage limit reached"))
    }

    @Test fun `custom endpoint generation errors include the endpoint's reason without its key`() {
        val key = "ollama-cloud-key-0123456789"
        listOf(404 to ApiFailure.Kind.INVALID_REQUEST, 429 to ApiFailure.Kind.USAGE_LIMIT, 500 to ApiFailure.Kind.SERVICE).forEach { (status, kind) ->
            val http = gateway(status, """{"error":"model 'llama3' not found for key $key"}""")
            val repo = CustomEndpointInferenceRepository(http) { CustomEndpoint("http://192.168.1.5:11434/v1", key) }
            val e = failure { repo.models() }
            assertEquals(kind, e.kind)
            assertTrue(e.userMessage.contains("model 'llama3' not found for key"))
            assertFalse(e.userMessage.contains(key))
        }
    }
}
