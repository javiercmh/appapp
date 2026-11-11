package com.wynh.appapp.share

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

class SharePolicyTest {
    private fun parse(json: String) = SharePolicy.parse(JsonParser.parseString(json).asJsonObject)
    private fun code(expected: String, block: () -> Unit) {
        try { block(); fail("Expected $expected") } catch (e: ShareException) { assertEquals(expected, e.code) }
    }
    @Test fun typesAndActions() {
        assertEquals(SharePolicy.Request.Availability, parse("""{"action":"availability"}"""))
        listOf("{}", """{"action":"wat","options":{}}""", """{"action":"text","options":{"text":7}}""",
            """{"action":"text","options":{"text":""}}""", """{"action":"text","options":{"text":"x","title":null}}""",
            """{"action":"file","options":{"name":"a","mimeType":"image/png","content":"x"}}""")
            .forEach { code("invalid_arguments") { parse(it) } }
        assertEquals(SharePolicy.Request.Text("x", "Story"), parse("""{"action":"text","options":{"text":"x","title":"Story"}}"""))
        val text = "x".repeat(SharePolicy.MAX_TEXT)
        assertEquals(text, (parse("""{"action":"text","options":{"text":"$text"}}""") as SharePolicy.Request.Text).text)
        code("too_large") { parse("""{"action":"text","options":{"text":"${text}x"}}""") }
    }
    @Test fun namesAreSafeAndMimeDeterminesExtension() {
        assertEquals("_secret.json", SharePolicy.filename("../secret.exe", "application/json"))
        assertEquals("share.txt", SharePolicy.filename("...", "text/plain"))
        assertEquals("a_b.md", SharePolicy.filename("a\\b.js", "text/markdown"))
        assertEquals("a_b.csv", SharePolicy.filename("a\u0000b.png", "text/csv"))
        assertEquals(120, SharePolicy.filename("a".repeat(500) + ".exe", "application/json").length)
        assertEquals("share.json", SharePolicy.filename("", "application/json"))
    }
    @Test fun fileCapCountsEncodedBytes() {
        assertEquals(SharePolicy.MAX_FILE_BYTES, SharePolicy.bytes("a".repeat(SharePolicy.MAX_FILE_BYTES)).size)
        code("too_large") { SharePolicy.bytes("a".repeat(SharePolicy.MAX_FILE_BYTES + 1)) }
        code("too_large") { SharePolicy.bytes("あ".repeat(SharePolicy.MAX_FILE_BYTES / 3 + 1)) }
        assertArrayEquals(byteArrayOf(), SharePolicy.bytes(""))
    }
    @Test fun lifecycleAndOriginRejectStaleWork() {
        val state = ShareSessionState("https://test.app.local")
        assertTrue(state.accepts("https://test.app.local/", true))
        for (origin in listOf("https://other.app.local", "http://test.app.local", "null", "https://test.app.local.evil")) assertFalse(state.accepts(origin, true))
        assertFalse(state.accepts("https://test.app.local", false))
        assertEquals("not_foreground", state.refusal(true, false))
        state.foreground = true
        assertNull(state.refusal(true, false))
        assertEquals("not_foreground", state.refusal(false, false))
        assertEquals("busy", state.refusal(true, true))
        val page = state.generation
        state.pageChanged(); assertFalse(state.current(page))
        state.close(); assertFalse(state.current(state.generation)); assertFalse(state.accepts("https://test.app.local", true))
    }
    @Test fun outstandingChooserAndPreparationExcludeOtherRequests() {
        val state = ShareLaunchState()
        val first = Any(); val second = Any()
        assertTrue(state.acquire(first)); assertFalse(state.acquire(second))
        state.release(second); assertFalse(state.acquire(second))
        state.launched(first); assertFalse(state.acquire(second))
        state.release(first); assertFalse(state.acquire(second))
        state.chooserOpen = false
        assertTrue(state.acquire(second))
        state.release(second); assertTrue(state.acquire(first))
    }
    @Test fun cacheIsolationExpiryAndBytePreservation() {
        val root = Files.createTempDirectory("share-cache").toFile()
        try {
            val request = SharePolicy.Request.Document("story.json", "application/json", "{\"text\":\"日本語\"}")
            val first = ShareCache.write(root, "one", request)
            val second = ShareCache.write(root, "two", request)
            val third = ShareCache.write(root, "one", request)
            assertNotEquals(first, third)
            assertTrue(first.canonicalPath.startsWith(File(root, "shares/one").canonicalPath + "/"))
            assertArrayEquals(request.content.toByteArray(), second.readBytes())
            first.parentFile!!.setLastModified(System.currentTimeMillis() - ShareCache.RETENTION_MS - 1000)
            ShareCache.sweep(root)
            assertFalse(first.exists()); assertTrue(second.exists()); assertTrue(third.exists())
            ShareCache.discard(third); assertFalse(third.exists()); assertTrue(second.exists())
        } finally { root.deleteRecursively() }
    }
}
