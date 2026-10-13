package com.wynh.appapp.bridge

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class TokenGatedBridgeTest {
    private val calls = mutableListOf<List<Any>>()
    private val token = TokenGatedBridge.newToken()
    private val bridge = TokenGatedBridge(token, mapOf(
        "write" to BridgeMethod(ArgType.STRING, ArgType.STRING) { calls += it; true },
        "schedule" to BridgeMethod(ArgType.INT, ArgType.STRING) { calls += it; it[0] },
        "toggle" to BridgeMethod(ArgType.BOOLEAN) { calls += it; "done" },
        "fail" to BridgeMethod { error("boom") }
    ))

    private fun result(json: String) = JsonParser.parseString(json).asJsonObject

    @Test fun `tokens are random 256-bit hex and compared exactly`() {
        assertTrue(token.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(token, TokenGatedBridge.newToken())
        assertTrue(TokenGatedBridge.tokenMatches(token, token))
        for (bad in listOf(null, "", token.dropLast(1), token + "0", token.replaceFirst(token[0], if (token[0] == 'a') 'b' else 'a'), "undefined")) {
            assertFalse(TokenGatedBridge.tokenMatches(token, bad))
        }
    }

    @Test fun `calls without the token are rejected without side effects`() {
        for (bad in listOf(null, "", "undefined", TokenGatedBridge.newToken())) {
            val r = result(bridge.call(bad, "write", """["data/a.json","x"]"""))
            assertFalse(r["ok"].asBoolean)
        }
        assertTrue(calls.isEmpty())
    }

    @Test fun `valid calls dispatch with typed arguments and return an envelope`() {
        val r = result(bridge.call(token, "write", """["data/a.json","{\"a\":1}"]"""))
        assertTrue(r["ok"].asBoolean)
        assertTrue(r["value"].asBoolean)
        assertEquals(listOf<Any>("data/a.json", "{\"a\":1}"), calls.single())
        assertEquals(7, result(bridge.call(token, "schedule", """[7.9,"1767225600000"]"""))["value"].asInt)
        assertEquals("done", result(bridge.call(token, "toggle", "[true]"))["value"].asString)
    }

    @Test fun `unknown methods, wrong arity, wrong types and exceptions fail`() {
        val failures = listOf(
            "nope" to "[]", "write" to """["only one"]""", "write" to """["a","b","c"]""",
            "write" to """["a",null]""", "write" to """["a",{}]""", "schedule" to """["7","1"]""",
            "schedule" to """[null,"1"]""", "toggle" to "[1]", "toggle" to """["true"]""",
            "write" to "not json", "write" to """{"0":"a"}""", "fail" to "[]"
        )
        for ((method, args) in failures) assertFalse("$method $args", result(bridge.call(token, method, args))["ok"].asBoolean)
        assertFalse(result(bridge.call(token, null, "[]"))["ok"].asBoolean)
        assertFalse(result(bridge.call(token, "write", null))["ok"].asBoolean)
        assertTrue(calls.isEmpty())
    }

    @Test fun `argument coercion keeps the former bridge quirks`() {
        val p = { s: String -> JsonParser.parseString(s) }
        assertEquals("12", TokenGatedBridge.coerce(ArgType.STRING, p("12")))
        assertEquals("true", TokenGatedBridge.coerce(ArgType.STRING, p("true")))
        assertEquals(-3, TokenGatedBridge.coerce(ArgType.INT, p("-3.7")))
        assertEquals(Int.MAX_VALUE, TokenGatedBridge.coerce(ArgType.INT, p("1767225600000")))
        assertEquals(Int.MIN_VALUE, TokenGatedBridge.coerce(ArgType.INT, p("-1e20")))
        assertNull(TokenGatedBridge.coerce(ArgType.INT, p("[1]")))
        assertNull(TokenGatedBridge.coerce(ArgType.BOOLEAN, p("0")))
    }

    @Test fun `shim captures the token and recreates the public objects`() {
        val script = TokenGatedBridge.shimScript(token, mapOf("loadState" to BridgeMethod(ArgType.STRING, ArgType.STRING) { "" }))
        assertTrue(script.contains("\"$token\""))
        assertTrue(script.contains("delete window.${TokenGatedBridge.INTERFACE_NAME}"))
        assertTrue(script.contains("{\"loadState\":2}"))
        for (name in TokenGatedBridge.PUBLIC_NAMES) assertTrue(script.contains("\"$name\""))
    }
}
