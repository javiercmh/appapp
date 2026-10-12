package com.wynh.appapp.ai

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class AiRequestLedgerTest {
    @Test fun `provider removal cancels only its pending work and retains terminal results`() {
        val ledger = AiRequestLedger()
        val running = Job(); val other = Job(); val complete = Job()
        ledger.begin("running", AiRequestOwner("a", 1, "openrouter", running))
        ledger.begin("other", AiRequestOwner("b", 2, "chatgpt", other))
        ledger.begin("complete", AiRequestOwner("c", 3, "openrouter", complete))
        ledger.finish(AiRequestResult("complete", text = "saved later", done = true)); complete.complete()
        ledger.cancelProvider("openrouter")
        assertTrue(running.isCancelled); assertTrue(other.isActive)
        assertEquals("saved later", ledger.results["complete"]?.text)
        assertEquals("authentication", ledger.results["running"]?.errorCode)
        ledger.finish(AiRequestResult("running", text = "late", done = true))
        assertNull(ledger.results["running"]?.text)
        ledger.acknowledge("complete"); assertFalse(ledger.hasRequest("c"))
        ledger.acknowledge("other"); assertTrue(ledger.hasRequest("b"))
        ledger.cancel("other"); ledger.finish(AiRequestResult("other", text = "late", done = true))
        assertNull(ledger.results["other"])
    }
    @Test fun `cancelling queued work releases queue and active count excludes retained results`() = runBlocking {
        val ledger = AiRequestLedger(); val lock = Mutex(); val release = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        val first = launch { lock.withLock { entered.complete(Unit); release.await() } }
        entered.await()
        ledger.begin("first", AiRequestOwner("a", 1, "chatgpt", first))
        val queued = launch(start = CoroutineStart.LAZY) { lock.withLock { fail("Cancelled queued work must not run") } }
        ledger.begin("queued", AiRequestOwner("b", 2, "openrouter", Job())); ledger.bind("queued", queued); queued.start()
        yield(); assertEquals(2, ledger.activeRequestCount())
        ledger.cancelProvider("openrouter"); queued.join()
        assertEquals(1, ledger.activeRequestCount())
        release.complete(Unit); first.join()
        lock.withLock { }
        assertEquals(0, ledger.activeRequestCount())
        assertTrue(ledger.hasRequest("b"))
    }
    @Test fun `removal before job binding prevents job from starting`() {
        val ledger = AiRequestLedger(); ledger.begin("id", AiRequestOwner("a", 1, "chatgpt", Job()))
        ledger.cancel("id")
        val next = Job(); ledger.bind("id", next)
        assertTrue(next.isCancelled)
    }
}
