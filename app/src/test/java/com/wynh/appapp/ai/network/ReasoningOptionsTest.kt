package com.wynh.appapp.ai.network

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ReasoningOptionsTest {
    private fun levels(raw: String, slug: String) = ReasoningOptions.levels(JsonParser.parseString(raw).asJsonObject, slug)
    @Test fun `advertised options override family defaults and reject unknown effort`() {
        assertEquals(listOf("medium", "xhigh"), levels("""{"supported_reasoning_levels":[{"effort":"medium"},{"effort":"xhigh"},{"effort":"unsupported"},{"effort":"medium"}]}""", "gpt-6-test"))
        assertEquals(emptyList<String>(), levels("""{"supported_reasoning_levels":[]}""", "gpt-6-test"))
    }
    @Test fun `unrecognized models keep defaults and known reasoning families use common levels`() {
        assertEquals(emptyList<String>(), levels("{}", "unknown"))
        assertEquals(listOf("low", "medium", "high"), levels("{}", "gpt-6-test"))
    }
}
