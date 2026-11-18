package com.wynh.appapp.share

import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ShareProcessorTest {
    private val mainExecutor = Executors.newSingleThreadExecutor()
    private val main = mainExecutor.asCoroutineDispatcher()
    private val workExecutor = Executors.newSingleThreadExecutor()
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val job = SupervisorJob()
    private val root: File = Files.createTempDirectory("share-processor").toFile()
    private val state = ShareSessionState("https://test.app.local").apply { foreground = true }
    private val effects = Effects()
    private val replies = LinkedBlockingQueue<String>()

    private inner class Effects : ShareProcessor.Effects {
        @Volatile var refused: String? = null
        @Volatile var version = 0L
        val launch = ShareLaunchState()
        @Volatile var writeGate: CountDownLatch? = null
        @Volatile var writeEntered = CountDownLatch(1)
        val written = LinkedBlockingQueue<File>()
        val discarded = LinkedBlockingQueue<File>()
        val launched = LinkedBlockingQueue<SharePolicy.Request>()
        override fun refusal() = refused
        override fun workspaceVersion() = version
        override fun acquire(owner: Any) = launch.acquire(owner)
        override fun release(owner: Any) = launch.release(owner)
        override fun sweep() = ShareCache.sweep(root)
        override fun write(request: SharePolicy.Request.Document): File {
            val file = ShareCache.write(root, "test", request)
            written.add(file)
            writeGate?.let { writeEntered.countDown(); assertTrue(it.await(10, TimeUnit.SECONDS)) }
            return file
        }
        override fun discard(file: File) { ShareCache.discard(file); discarded.add(file) }
        override fun launch(owner: Any, request: SharePolicy.Request, file: File?) { launch.launched(owner); launched.add(request) }
    }

    private fun processor(intake: ShareIntake = ShareIntake(), maxMessage: Int = ShareMessages.MAX_MESSAGE) =
        ShareProcessor(CoroutineScope(job + main), workExecutor.asCoroutineDispatcher(), ioExecutor.asCoroutineDispatcher(), state, effects, intake, maxMessage)
    private fun <T> onMain(block: () -> T): T = runBlocking(main) { block() }
    private fun ShareProcessor.send(raw: String) = onMain { receive(raw) { replies.add(it) } }
    private fun settle() = runBlocking { job.children.toList().joinAll() }
    private fun message(id: String, action: String, options: Map<String, Any>? = null) =
        Gson().toJson(mapOf("id" to id, "action" to action) + (options?.let { mapOf("options" to it) } ?: emptyMap()))
    private fun file(id: String) = message(id, "file", mapOf("name" to "story", "mimeType" to "application/json", "content" to "{}"))
    private fun reply(): Map<*, *> = Gson().fromJson(checkNotNull(replies.poll(10, TimeUnit.SECONDS)) { "No reply" }, Map::class.java)
    private fun code(reply: Map<*, *>) = (reply["error"] as Map<*, *>)["code"]

    @After fun tearDown() {
        job.cancel(); mainExecutor.shutdownNow(); workExecutor.shutdownNow(); ioExecutor.shutdownNow(); root.deleteRecursively()
    }

    @Test fun refusalPrecedesEveryCommandIncludingAvailability() {
        val share = processor()
        effects.refused = "not_foreground"
        share.send(message("a", "availability")); assertEquals("not_foreground", code(reply()))
        share.send(message("b", "text", mapOf("text" to ""))); assertEquals("not_foreground", code(reply()))
        effects.refused = "busy"
        share.send(message("c", "availability")); assertEquals("busy", code(reply()))
        effects.refused = null
        share.send(message("d", "availability"))
        assertEquals(mapOf("available" to true, "file" to true), reply()["value"])
        share.send(message("e", "text", mapOf("text" to ""))); assertEquals("invalid_arguments", code(reply()))
        // Strict ID and type validation still drops unanswerable messages.
        for (raw in listOf("""{"id":7,"action":"availability"}""", """{"id":"","action":"availability"}""", "not json",
                """{"id":"${"x".repeat(101)}","action":"availability"}""")) share.send(raw)
        settle(); assertNull(replies.poll())
    }

    @Test fun oversizedMessageIsAnsweredFromItsPrefixWithoutParsing() {
        val share = processor(maxMessage = 200)
        // The tail is malformed JSON: a full parse would drop it, so a reply proves only the prefix was read.
        val oversized = """{"id":"big","action":"file","options":{"content":"${"x".repeat(400)}"""
        share.send(oversized)
        val immediate = replies.poll()
        assertNotNull("too_large must be answered before any background work", immediate)
        assertEquals("too_large", code(Gson().fromJson(immediate, Map::class.java)))
        effects.refused = "not_foreground"
        share.send(oversized); assertEquals("not_foreground", code(reply()))
        effects.refused = null
        // Beyond the bounded prefix, or with an invalid ID, nothing can be answered.
        share.send("""{"action":"file","options":{"content":"${"x".repeat(2000)}"},"id":"late"}""")
        share.send("""{"id":"${"x".repeat(101)}","content":"${"x".repeat(400)}"}""")
        settle(); assertNull(replies.poll())
    }

    @Test fun intakeBoundsConcurrentParsing() {
        val intake = ShareIntake(maxPending = 2, maxChars = 1_000)
        assertTrue(intake.admit(600)); assertFalse(intake.admit(401)); assertTrue(intake.admit(400))
        assertFalse(intake.admit(0)); intake.done(600); assertTrue(intake.admit(600))
        val share = processor(ShareIntake(maxPending = 2))
        val parser = CountDownLatch(1)
        workExecutor.execute { parser.await(10, TimeUnit.SECONDS) }
        share.send(message("one", "availability")); share.send(message("two", "availability"))
        share.send(message("three", "availability"))
        assertEquals("busy", code(reply()))
        parser.countDown()
        assertEquals(setOf("one", "two"), setOf(reply()["id"], reply()["id"]))
        share.send(message("four", "availability")); assertEquals("four", reply()["id"])
    }

    /** True only when no admission is outstanding: a whole maximum message fits again. */
    private fun ShareIntake.idle(capacity: Int = ShareMessages.MAX_MESSAGE) = onMain { admit(capacity).also { if (it) done(capacity) } }

    @Test fun closeInTheSameUiTurnReleasesEveryAdmission() {
        val intake = ShareIntake()
        val share = processor(intake)
        onMain {
            repeat(4) { share.receive(message("same-turn-$it", "availability")) { replies.add(it) } }
            share.close()
        }
        settle()
        assertTrue("Admissions cancelled before dispatch must be released", intake.idle())
        assertNull(replies.poll())
        // A message filling the whole character budget, closed before dispatch, releases all of it.
        val capacity = 1_000
        val second = ShareIntake(maxChars = capacity.toLong())
        val big = """{"id":"big","action":"availability","pad":"""".let { it + "x".repeat(capacity - it.length - 2) + "\"}" }
        assertEquals(capacity, big.length)
        val owner = SupervisorJob()
        val other = ShareProcessor(CoroutineScope(owner + main), workExecutor.asCoroutineDispatcher(), ioExecutor.asCoroutineDispatcher(),
            ShareSessionState("https://test.app.local").apply { foreground = true }, effects, second)
        onMain { other.receive(big) { replies.add(it) }; other.close() }
        runBlocking { owner.children.toList().joinAll() }
        assertTrue(second.idle(capacity))
        assertNull(replies.poll())
    }

    @Test fun releaseWaitsForQueuedParseWorkAfterClose() {
        val intake = ShareIntake()
        val share = processor(intake)
        val parser = CountDownLatch(1)
        workExecutor.execute { parser.await(10, TimeUnit.SECONDS) }
        share.send(message("queued", "availability"))
        onMain { share.close() }
        assertFalse("The admission stays held while its parse work is still pending", intake.idle())
        parser.countDown()
        settle()
        assertTrue(intake.idle())
        assertNull(replies.poll())
    }

    @Test fun cancelledScopeTakesNoAdmissionAndAnswersNothing() {
        val intake = ShareIntake(maxPending = 1)
        val share = processor(intake)
        onMain { share.close() }
        repeat(3) { share.send(message("closed-$it", "availability")) }
        share.send("""{"id":"huge","action":"file","options":{"content":"${"x".repeat(300)}"}}""")
        settle()
        assertTrue(intake.idle()); assertNull(replies.poll())
        // A scope cancelled by its owner, with the session state still open, behaves the same.
        val owner = SupervisorJob().apply { cancel() }
        val orphan = ShareProcessor(CoroutineScope(owner + main), workExecutor.asCoroutineDispatcher(), ioExecutor.asCoroutineDispatcher(),
            ShareSessionState("https://test.app.local").apply { foreground = true }, effects, intake, maxMessage = 200)
        onMain {
            orphan.receive(message("orphan", "availability")) { replies.add(it) }
            orphan.receive("""{"id":"orphan-big","pad":"${"x".repeat(300)}"}""") { replies.add(it) }
        }
        runBlocking { owner.children.toList().joinAll() }
        assertTrue(intake.idle()); assertNull(replies.poll())
    }

    @Test fun pageChangeDuringPreparationDiscardsTheCompletedFile() {
        val share = processor()
        effects.writeGate = CountDownLatch(1)
        share.send(file("stale"))
        assertTrue(effects.writeEntered.await(10, TimeUnit.SECONDS))
        onMain { state.pageChanged() }
        effects.writeGate!!.countDown()
        val prepared = effects.written.poll(10, TimeUnit.SECONDS)!!
        assertEquals(prepared, effects.discarded.poll(10, TimeUnit.SECONDS))
        settle()
        assertFalse(prepared.parentFile!!.exists())
        assertNull(replies.poll()); assertTrue(effects.launched.isEmpty())
        assertTrue("A stale preparation must release the activity", effects.launch.acquire(Any()))
    }

    @Test fun disposalCancelsPreparationWithoutLeakingTheCompletedFile() {
        val share = processor()
        effects.writeGate = CountDownLatch(1)
        share.send(file("disposed"))
        assertTrue(effects.writeEntered.await(10, TimeUnit.SECONDS))
        onMain { share.close() }
        effects.writeGate!!.countDown()
        val prepared = effects.written.poll(10, TimeUnit.SECONDS)!!
        assertEquals(prepared, effects.discarded.poll(10, TimeUnit.SECONDS))
        settle()
        assertFalse(prepared.parentFile!!.exists())
        assertNull(replies.poll()); assertTrue(effects.launched.isEmpty())
    }

    @Test fun reservationsDuringPreparationRefuseLaunchAndDiscard() {
        for (reservation in listOf<() -> Unit>({ effects.version++ }, { effects.refused = "busy" })) {
            val share = processor()
            effects.refused = null
            effects.writeEntered = CountDownLatch(1)
            effects.writeGate = CountDownLatch(1)
            share.send(file("reserved"))
            assertTrue(effects.writeEntered.await(10, TimeUnit.SECONDS))
            reservation()
            effects.writeGate!!.countDown()
            assertEquals("busy", code(reply()))
            val prepared = effects.written.poll(10, TimeUnit.SECONDS)!!
            assertEquals(prepared, effects.discarded.poll(10, TimeUnit.SECONDS))
            assertFalse(prepared.exists()); assertTrue(effects.launched.isEmpty())
            effects.writeGate = null
        }
    }

    @Test fun outstandingChooserRefusesNewSharesUntilItsResult() {
        val share = processor()
        share.send(message("first", "text", mapOf("text" to "story")))
        assertEquals(mapOf("launched" to true), reply()["value"])
        assertTrue(effects.launch.chooserOpen)
        share.send(message("text", "text", mapOf("text" to "again"))); assertEquals("busy", code(reply()))
        share.send(file("file")); assertEquals("busy", code(reply()))
        settle(); assertTrue("A refused file share prepares nothing", effects.written.isEmpty())
        effects.launch.chooserOpen = false
        share.send(file("after"))
        assertEquals(mapOf("launched" to true), reply()["value"])
        val prepared = effects.written.poll(10, TimeUnit.SECONDS)!!
        settle(); assertTrue("A launched file stays for the recipient", prepared.exists())
        assertTrue(effects.discarded.isEmpty())
    }

    @Test fun messageBoundCoversWorstCaseEscapingOfValidFiles() {
        // Gson escapes HTML characters too; every UTF-16 unit costs at most six JSON units per encoded byte.
        val gson = Gson()
        for (unit in 0..0xFFFF) {
            val text = unit.toChar().toString()
            assertTrue("U+%04X".format(unit), gson.toJson(text).length - 2 <= 6 * text.toByteArray(Charsets.UTF_8).size)
        }
        val envelope = Gson().toJson(mapOf("id" to "\u0001".repeat(100), "action" to "file",
            "options" to mapOf("name" to "\u0001".repeat(10_000), "mimeType" to "application/json", "content" to "")))
        assertTrue(6L * SharePolicy.MAX_FILE_BYTES + envelope.length <= ShareMessages.MAX_MESSAGE)
        // The worst ratio holds at scale, and a maximum-size valid file is admitted, parsed and answerable.
        fun document(content: String) = Gson().toJson(mapOf("id" to "max", "action" to "file",
            "options" to mapOf("name" to "max", "mimeType" to "text/plain", "content" to content)))
        val escaped = 1024 * 1024
        assertEquals(6 * escaped, document("\u0001".repeat(escaped)).length - document("").length)
        val raw = document("a".repeat(SharePolicy.MAX_FILE_BYTES))
        assertTrue(ShareIntake().admit(raw.length))
        val parsed = ShareMessages.parse(raw)!!.request.getOrThrow() as SharePolicy.Request.Document
        assertEquals(SharePolicy.MAX_FILE_BYTES, SharePolicy.bytes(parsed.content).size)
        assertEquals("max", ShareMessages.peekId(raw))
    }
}
