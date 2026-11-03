package com.wynh.appapp.ai.network

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class CustomEndpointInferenceTest {
    @Test fun `only plain https urls are accepted and normalized`() {
        assertEquals("https://api.example.com/v1", CustomEndpoint.normalizeUrl(" https://api.example.com/v1/ "))
        assertNull(CustomEndpoint.normalizeUrl("http://api.example.com/v1"))
        assertNull(CustomEndpoint.normalizeUrl("https://user:pw@api.example.com/v1"))
        assertNull(CustomEndpoint.normalizeUrl("https://api.example.com/v1?key=1"))
        assertNull(CustomEndpoint.normalizeUrl("https://api.example.com/v1#x"))
        assertNull(CustomEndpoint.normalizeUrl("not a url"))
    }
    @Test fun `keys must be printable ascii without whitespace`() {
        assertTrue(CustomEndpoint.validKey("sk-abc_123"))
        assertFalse(CustomEndpoint.validKey(""))
        assertFalse(CustomEndpoint.validKey("a b"))
        assertFalse(CustomEndpoint.validKey("a\nb"))
    }
    @Test fun `models are parsed de-duplicated and empty lists rejected`() {
        val ok = JsonParser.parseString("""{"data":[{"id":"a"},{"id":"a"},{"id":"b"},{"x":1}]}""").asJsonObject
        assertEquals(listOf("a", "b"), CustomEndpointInferenceRepository.parseModels(ok).map { it.slug })
        assertThrows(ApiFailure::class.java) { CustomEndpointInferenceRepository.parseModels(JsonParser.parseString("""{"data":[]}""").asJsonObject) }
        assertThrows(ApiFailure::class.java) { CustomEndpointInferenceRepository.parseModels(JsonParser.parseString("{}").asJsonObject) }
    }
    @Test fun `only a complete stop response yields text`() {
        fun text(json: String) = CustomEndpointInferenceRepository.parseText(JsonParser.parseString(json).asJsonObject)
        assertEquals("hi", text("""{"choices":[{"finish_reason":"stop","message":{"content":"hi"}}]}"""))
        assertThrows(ApiFailure::class.java) { text("""{"choices":[{"finish_reason":"length","message":{"content":"hi"}}]}""") }
        assertThrows(ApiFailure::class.java) { text("""{"choices":[{"finish_reason":"stop","message":{"refusal":"no","content":"hi"}}]}""") }
        assertThrows(ApiFailure::class.java) { text("""{"choices":[]}""") }
    }
}
