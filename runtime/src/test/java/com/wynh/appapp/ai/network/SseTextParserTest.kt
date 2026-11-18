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
        assertFailure("data: {\"type\":\"response.incomplete\"}\n\n", ApiFailure.Kind.INCOMPLETE)
    }

    @Test fun `late usage limits discard partial output`() {
        assertFailure("data: {\"type\":\"response.output_text.delta\",\"delta\":\"Partial\"}\n\n" +
            "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}}\n\n", ApiFailure.Kind.USAGE_LIMIT)
    }

    @Test fun `malformed events stay invalid responses`() {
        assertFailure("data: not-json\n\n", ApiFailure.Kind.INVALID_RESPONSE)
        assertFailure("data: {\"type\":\"response.output_text.delta\"}\n\n" + COMPLETED, ApiFailure.Kind.INVALID_RESPONSE)
    }

    @Test fun `stream size is not bounded when the text is small`() {
        // About 3 MiB of named reasoning events and padded deltas around a short answer.
        val stream = StringBuilder()
        repeat(1_500) {
            stream.append("event: response.reasoning_summary_text.delta\ndata: {\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"${"r".repeat(1_000)}\"}\n\n")
            stream.append(delta("a", padding = 1_000))
        }
        stream.append(COMPLETED)
        assertTrue(stream.length > 2_097_152 + 1_000_000)
        assertEquals("a".repeat(1_500), parse(stream.toString(), max = 10_000))
    }

    @Test fun `named events that repeat the whole text are skipped unread`() {
        val text = "x".repeat(900)
        val repeat = "{\"type\":\"response.output_text.done\",\"text\":\"${"x".repeat(5_000)}\"}"
        val stream = delta(text) + "event: response.output_text.done\ndata: $repeat\n\n" +
            "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"output\":\"${"x".repeat(5_000)}\"}}\n\n"
        // Each skipped line is far above the event bound (2 × 1,000): they are never held.
        assertEquals(text, parse(stream, max = 1_000))
    }

    @Test fun `an unnamed completion is read up to twice the text bound`() {
        val stream = delta("ok") + "data: {\"type\":\"response.completed\",\"pad\":\"${"p".repeat(1_500)}\"}\n\n"
        assertEquals("ok", parse(stream, max = 1_000))
        assertFailure(delta("ok") + "data: {\"type\":\"response.completed\",\"pad\":\"${"p".repeat(2_500)}\"}\n\n",
            ApiFailure.Kind.RESPONSE_TOO_LARGE, max = 1_000)
    }

    @Test fun `text above the bound is too large, not malformed`() {
        assertEquals("a".repeat(1_000), parse(delta("a".repeat(600)) + delta("a".repeat(400)) + COMPLETED, max = 1_000))
        try { parse(delta("a".repeat(600)) + delta("a".repeat(401)) + COMPLETED, max = 1_000); fail() }
        catch (e: ApiFailure) { assertEquals(ApiFailure.Kind.RESPONSE_TOO_LARGE, e.kind); assertTrue(e.userMessage.contains("1,000")) }
    }

    @Test fun `incomplete responses report their reason`() {
        try { parse(delta("Partial") + "data: {\"type\":\"response.incomplete\",\"response\":{\"incomplete_details\":{\"reason\":\"max_output_tokens\"}}}\n\n"); fail() }
        catch (e: ApiFailure) { assertEquals(ApiFailure.Kind.INCOMPLETE, e.kind); assertTrue(e.userMessage.contains("max_output_tokens")) }
    }

    @Test fun `comments ids and overlong event names are ignored`() {
        val stream = ": keep-alive\nid: 7\nretry: 10\n\n" +
            "event: ${"n".repeat(400)}\ndata: not-json-and-skipped\n\n" + delta("fine") + COMPLETED
        assertEquals("fine", parse(stream))
    }

    @Test fun `empty completion and invalid JSON fail without leaking response payload`() {
        try { parse("data: {\"type\":\"response.completed\"}\n\n"); fail() }
        catch (e: ApiFailure) { assertEquals(ApiFailure.Kind.INVALID_RESPONSE, e.kind) }
        try { parse("data: secret-invalid-payload\n\n"); fail() }
        catch (e: ApiFailure) {
            assertEquals(ApiFailure.Kind.INVALID_RESPONSE, e.kind)
            assertFalse(e.userMessage.contains("secret-invalid-payload"))
        }
    }

    private val COMPLETED = "data: {\"type\":\"response.completed\"}\n\n"
    private fun delta(text: String, padding: Int = 0) =
        "data: {\"type\":\"response.output_text.delta\",\"delta\":\"$text\"" + (if (padding > 0) ",\"obfuscation\":\"${"o".repeat(padding)}\"" else "") + "}\n\n"
    private fun parse(value: String, max: Int = HttpGateway.MIN_OUTPUT_UNITS) = SseTextParser.read(value.reader().buffered(), max)
    private fun assertFailure(value: String, kind: ApiFailure.Kind, max: Int = HttpGateway.MIN_OUTPUT_UNITS) {
        try { parse(value, max); fail("Expected failure") } catch (e: ApiFailure) { assertEquals(kind, e.kind) }
    }
}
