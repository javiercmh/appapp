package com.wynh.appapp.ai.network

import org.junit.Assert.*
import org.junit.Test

class SseTextParserTest {
    @Test fun `returns text only after terminal completion`() {
        val stream = ": heartbeat\n\nevent: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"Hello, \"}\n\n" +
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"AppApp!\"}\n\n" +
            "data: {\"type\":\"response.completed\"}\n\n"
        assertEquals("Hello, AppApp!", parse(stream))
    }

    @Test fun `interrupted stream never becomes a successful response`() {
        assertFailure("data: {\"type\":\"response.output_text.delta\",\"delta\":\"Partial\"}\n\n", ApiFailure.Kind.NETWORK)
        assertFailure("data: {\"type\":\"response.incomplete\"}\n\n", ApiFailure.Kind.INVALID_RESPONSE)
    }

    @Test fun `late usage limits discard partial output`() {
        assertFailure("data: {\"type\":\"response.output_text.delta\",\"delta\":\"Partial\"}\n\n" +
            "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}}\n\n", ApiFailure.Kind.USAGE_LIMIT)
    }

    @Test fun `malformed and oversized events are rejected`() {
        assertFailure("data: not-json\n\n", ApiFailure.Kind.INVALID_RESPONSE)
        assertFailure("data: " + "x".repeat(262_145), ApiFailure.Kind.INVALID_RESPONSE)
    }

    @Test fun `empty completion and invalid JSON have distinct safe diagnostics`() {
        try { parse("data: {\"type\":\"response.completed\"}\n\n"); fail() }
        catch (e: ApiFailure) { assertTrue(e.userMessage.contains("without text output")) }
        try { parse("data: secret-invalid-payload\n\n"); fail() }
        catch (e: ApiFailure) {
            assertTrue(e.userMessage.contains("not valid JSON"))
            assertFalse(e.userMessage.contains("secret-invalid-payload"))
        }
    }

    private fun parse(value: String) = SseTextParser.read(value.reader().buffered())
    private fun assertFailure(value: String, kind: ApiFailure.Kind) {
        try { parse(value); fail("Expected failure") } catch (e: ApiFailure) { assertEquals(kind, e.kind) }
    }
}
