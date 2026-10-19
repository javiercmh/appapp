package com.wynh.appapp.ai

import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

class AiNotificationTest {
    @Test fun `missing or blank copy falls back to legacy defaults`() {
        assertEquals(AiNotificationCopy.DEFAULT_TITLE, AiNotificationCopy.title(null))
        assertEquals(AiNotificationCopy.DEFAULT_TITLE, AiNotificationCopy.title(" \n\t "))
        assertEquals(AiNotificationCopy.DEFAULT_BODY, AiNotificationCopy.body(""))
    }

    @Test fun `copy is single line, bounded and keeps whole code points`() {
        assertEquals("Writing the next chapter", AiNotificationCopy.title("Writing\n the\u0000 next\tchapter"))
        val long = AiNotificationCopy.title("x".repeat(500))
        assertEquals(AiNotificationCopy.MAX_TITLE, long.codePointCount(0, long.length))
        assertTrue(long.endsWith("…"))
        val emoji = AiNotificationCopy.body("😀".repeat(400))
        assertEquals(AiNotificationCopy.MAX_BODY, emoji.codePointCount(0, emoji.length))
        assertFalse(emoji.dropLast(1).last().isHighSurrogate())
    }

    @Test fun `reconciler promotes the next request and cancels only finished notifications`() {
        val reconciler = AiNotificationReconciler()
        val a = AiNotificationEntry("a", 10); val b = AiNotificationEntry("b", 11); val c = AiNotificationEntry("c", 12)
        var plan = reconciler.plan(listOf(b, a))
        assertEquals(a, plan.foreground); assertEquals(listOf(b), plan.others); assertTrue(plan.cancel.isEmpty())
        plan = reconciler.plan(listOf(a, b, c))
        assertEquals(a, plan.foreground); assertEquals(listOf(b, c), plan.others)
        plan = reconciler.plan(listOf(b, c))
        assertEquals(b, plan.foreground); assertEquals(listOf(c), plan.others); assertEquals(listOf(10), plan.cancel)
        plan = reconciler.plan(listOf(b, c))
        assertEquals(b, plan.foreground); assertTrue("Repeated reconciliation is idempotent", plan.cancel.isEmpty())
        plan = reconciler.plan(emptyList())
        assertNull(plan.foreground); assertEquals(listOf(11, 12), plan.cancel)
    }

    @Test fun `a new request never steals the foreground from a running one`() {
        val reconciler = AiNotificationReconciler()
        val late = AiNotificationEntry("late", 30); val early = AiNotificationEntry("early", 20)
        assertEquals(late, reconciler.plan(listOf(late)).foreground)
        assertEquals(late, reconciler.plan(listOf(late, early)).foreground)
    }

    @Test fun `ledger lists only unfinished requests with their presentation`() {
        val ledger = AiRequestLedger()
        val first = AiPresentation(notificationId = 1, title = "One", appName = "App A")
        ledger.begin("a", AiRequestOwner("appA", 1, "chatgpt", Job(), first))
        ledger.begin("b", AiRequestOwner("appB", 2, "chatgpt", Job(), AiPresentation(notificationId = 2)))
        ledger.begin("c", AiRequestOwner("appC", 3, "chatgpt", Job(), AiPresentation(notificationId = 3)))
        assertEquals(setOf("a", "b", "c"), ledger.unfinished().map { it.first }.toSet())
        ledger.finish(AiRequestResult("a", text = "done", done = true))
        ledger.cancel("b")
        val remaining = ledger.unfinished()
        assertEquals(listOf("c"), remaining.map { it.first })
        assertEquals(AiNotificationCopy.DEFAULT_TITLE, remaining.single().second.presentation.title)
        assertTrue("A result awaiting acknowledgement still blocks its app", ledger.hasRequest("appA"))
        assertTrue("Concurrent requests from different apps are accepted", ledger.hasRequest("appC"))
    }

    @Test fun `the same subapp cannot hold two requests but different subapps can`() {
        val ledger = AiRequestLedger()
        ledger.begin("one", AiRequestOwner("same", 1, "chatgpt", Job()))
        assertThrows(IllegalStateException::class.java) { ledger.begin("two", AiRequestOwner("same", 2, "chatgpt", Job())) }
        ledger.begin("three", AiRequestOwner("other", 3, "chatgpt", Job()))
        assertEquals(2, ledger.unfinished().size)
    }
}
