package com.wynh.appapp.channel

import com.google.gson.Gson
import com.wynh.appapp.share.ShareIntake
import com.wynh.appapp.share.ShareSessionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ChannelProcessorTest {
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val work = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val job = SupervisorJob()
    private val state = ShareSessionState("https://t.app.local").apply { foreground = true }
    private val replies = LinkedBlockingQueue<String>()
    @Volatile private var refused: String? = null
    private val handled = LinkedBlockingQueue<ChannelCall>()
    @Volatile private var behavior: suspend (ChannelCall) -> Any? = { mapOf("ok" to true) }
    private fun processor(max: Int = 1000, intake: ShareIntake = ShareIntake(maxPending = 2, maxChars = 10_000)) =
        ChannelProcessor(CoroutineScope(job + main), work, state, intake, max, { refused }) { call -> handled.add(call); behavior(call) }
    private fun ChannelProcessor.send(raw: String) = runBlocking(main) { receive(raw) { replies.add(it) } }
    private fun settle() = runBlocking { job.children.toList().joinAll() }
    private fun reply() = Gson().fromJson(checkNotNull(replies.poll(10, TimeUnit.SECONDS)), Map::class.java)
    private fun msg(id: String, action: String = "go") = """{"id":"$id","action":"$action","options":{"n":1}}"""
    @After fun tearDown() { job.cancel(); (main.executor as java.util.concurrent.ExecutorService).shutdownNow(); (work.executor as java.util.concurrent.ExecutorService).shutdownNow() }

    @Test fun refusalPrecedesEveryCommandAndErrorsMapToCodes() {
        val p = processor()
        refused = "not_foreground"; p.send(msg("a")); assertEquals("not_foreground", (reply()["error"] as Map<*, *>)["code"])
        refused = "busy"; p.send(msg("b")); assertEquals("busy", (reply()["error"] as Map<*, *>)["code"])
        refused = null
        p.send(msg("c")); assertEquals(mapOf("ok" to true), reply()["value"])
        behavior = { throw ChannelException("exists", "x") }
        p.send(msg("d")); assertEquals("exists", (reply()["error"] as Map<*, *>)["code"])
        behavior = { throw IllegalStateException("boom") }
        p.send(msg("e")); assertEquals("failed", (reply()["error"] as Map<*, *>)["code"])
        for (raw in listOf("not json", """{"id":3}""", """{"id":"","action":"x"}""")) p.send(raw)
        settle(); assertNull(replies.poll())
    }

    @Test fun oversizedAndExcessMessagesAreAnsweredFromTheirPrefixOnly() {
        val p = processor(max = 100)
        p.send("""{"id":"big","action":"go","options":{"x":"${"y".repeat(300)}"}}""")
        assertEquals("too_large", (reply()["error"] as Map<*, *>)["code"])
        assertTrue(handled.isEmpty())
    }

    @Test fun aReplyNeverReachesAPageThatWasReplacedOrClosed() {
        val gate = CompletableDeferred<Unit>()
        behavior = { gate.await(); mapOf("late" to true) }
        val p = processor()
        p.send(msg("old")); assertNotNull(handled.poll(10, TimeUnit.SECONDS))
        state.pageChanged()
        gate.complete(Unit); settle()
        assertNull("stale document gets no reply", replies.poll())
        p.send(msg("new")); assertEquals("new", reply()["id"])
        val closing = CompletableDeferred<Unit>()
        behavior = { closing.await(); mapOf("x" to 1) }
        p.send(msg("closing")); assertNotNull(handled.poll(10, TimeUnit.SECONDS)); assertNotNull(handled.poll(10, TimeUnit.SECONDS))
        p.close(); settle()
        assertNull(replies.poll())
        p.send(msg("after")); settle(); assertNull(replies.poll())
    }

    @Test fun intakeIsReleasedWhenTheSessionIsClosedBeforeParsing() {
        val intake = ShareIntake(maxPending = 2, maxChars = 10_000)
        val p = processor(intake = intake)
        runBlocking(main) { p.receive(msg("x")) { replies.add(it) }; p.close() }
        settle()
        assertTrue(runBlocking(main) { intake.admit(10_000).also { if (it) intake.done(10_000) } })
    }
}
