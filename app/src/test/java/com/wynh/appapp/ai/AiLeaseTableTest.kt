package com.wynh.appapp.ai

import org.junit.Assert.*
import org.junit.Test

class AiLeaseTableTest {
    private var now = 1_000L
    private val table = AiLeaseTable({ now }, idleMs = 90, maxMs = 1_000)
    private fun open(app: String = "a", task: Int = 1, id: String = "s1", adopt: Boolean = false) =
        table.open(app, task, id, 7, AiPresentation(), adopt)

    @Test fun `ownership requires app and task and foreign ids are unknown`() {
        open()
        assertTrue(table.lookup("a", 1, "s1") is LeaseLookup.Live)
        assertSame(LeaseLookup.Unknown, table.lookup("a", 2, "s1"))
        assertSame(LeaseLookup.Unknown, table.lookup("b", 1, "s1"))
        assertSame(LeaseLookup.Unknown, table.lookup("a", 1, "other"))
    }

    @Test fun `idle deadline starts without a request and clears while one runs`() {
        val lease = open()
        now += 80; table.setBusy(lease, true)
        now += 500; assertTrue(table.due().isEmpty())
        table.setBusy(lease, false)
        now += 89; assertTrue(table.due().isEmpty())
        now += 1; assertEquals(SessionEnd.IDLE, table.due().single().second)
    }

    @Test fun `hard cap wins and applies even while busy`() {
        val lease = open(); table.setBusy(lease, true)
        now += 999; assertTrue(table.due().isEmpty())
        now += 1; assertEquals(SessionEnd.TIMEOUT, table.due().single().second)
    }

    @Test fun `ended session is remembered once per app with its reason and replaced by the next end`() {
        open(); table.end("a", SessionEnd.CANCELLED)
        assertEquals(SessionEnd.CANCELLED, (table.lookup("a", 1, "s1") as LeaseLookup.Ended).reason)
        assertNull(table.leaseOf("a"))
        open(id = "s2"); assertSame(LeaseLookup.Unknown, table.lookup("a", 1, "s1"))
        table.end("a", SessionEnd.IDLE)
        assertSame(LeaseLookup.Unknown, table.lookup("a", 1, "s1"))
        assertEquals(1, table.rememberedCount())
        table.forget("a"); assertEquals(0, table.rememberedCount())
    }

    @Test fun `memory stays bounded by apps however many sessions end`() {
        repeat(500) { n -> open(id = "s$n"); table.end("a", SessionEnd.RELEASED) }
        assertEquals(1, table.rememberedCount())
    }

    @Test fun `ended lookup is task checked`() {
        open(); table.end("a", SessionEnd.TIMEOUT)
        assertSame(LeaseLookup.Unknown, table.lookup("a", 2, "s1"))
    }

    @Test fun `adopting a running lease re-keys it and keeps its deadlines and notification`() {
        val first = open(); val cap = first.hardDeadline
        now += 400
        val adopted = open(id = "s2", adopt = true)
        assertSame(first, adopted); assertEquals("s2", adopted.id); assertEquals(cap, adopted.hardDeadline)
        assertSame(LeaseLookup.Unknown, table.lookup("a", 1, "s1"))
        assertTrue(table.lookup("a", 1, "s2") is LeaseLookup.Live)
    }

    @Test fun `replacing an idle lease resets deadlines and reuses its notification`() {
        val first = open(); now += 400
        val replaced = table.open("a", 1, "s2", 99, AiPresentation(), adopt = false)
        assertNotSame(first, replaced); assertEquals(first.notificationId, replaced.notificationId)
        assertEquals(now + 1_000, replaced.hardDeadline)
    }

    @Test fun `another task of the app cannot take over a live lease`() {
        open()
        assertThrows(IllegalStateException::class.java) { open(task = 2, id = "s2") }
        assertTrue(table.lookup("a", 1, "s1") is LeaseLookup.Live)
    }

    @Test fun `two apps are isolated`() {
        open("a", 1, "sa"); open("b", 2, "sb")
        table.end("a", SessionEnd.CANCELLED)
        assertTrue(table.lookup("b", 2, "sb") is LeaseLookup.Live)
        assertEquals(listOf("sb"), table.all().map { it.id })
    }

    @Test fun `next deadline is the earliest of cap and idle`() {
        open(); assertEquals(1_090L, table.nextDeadline())
        table.setBusy(table.leaseOf("a")!!, true); assertEquals(2_000L, table.nextDeadline())
    }
}
