package com.wynh.appapp.ai

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wynh.appapp.SubappRuntimeActivity
import com.wynh.appapp.ai.network.ApiFailure
import com.wynh.appapp.grants.SubappCapability
import com.wynh.appapp.grants.SubappGrants
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Drives the real manager, request service and notification manager (fake provider only) through
 * foreground denial, timeout and confirmation, and through operation-session ownership, cancellation,
 * expiry and cleanup. Nothing here contacts a provider.
 */
@RunWith(AndroidJUnit4::class)
class AiSessionDeviceTest : AiDeviceFixture() {
    private fun timings(idle: Long = AiLeaseTable.IDLE_MS, max: Long = AiLeaseTable.MAX_MS, handshake: Long = AppAiManager.HANDSHAKE_MS) {
        manager.timings = AppAiManager.Timings(idle, max, handshake)
    }

    // ---- Startup gate: denial, timeout, stale handshake, confirmation --------------------------

    @Test fun startupDenialFinishesWithBackgroundServiceAndNeverCallsTheProvider() {
        manager.foregroundStarter = AppAiManager.ForegroundStarter { throw IllegalStateException("denied by test") }
        val app = newApp("Denied")
        val id = start(app, 9801, "denied")
        val result = resultOf(id)
        assertEquals("background_service", result.errorCode)
        assertEquals("The provider was never reached", 0, fake.calls.get())
        assertTrue(posted().isEmpty()); assertFalse(AiRequestService.isRunning())
        manager.acknowledge(id); assertFalse(manager.hasRequest(app))
    }

    @Test fun serviceThatNeverConfirmsTimesOutWithoutCallingTheProvider() {
        timings(handshake = 300)
        val real = manager.foregroundStarter
        manager.foregroundStarter = AppAiManager.ForegroundStarter { }
        val app = newApp("Silent")
        val id = start(app, 9811, "silent")
        assertEquals("background_service", resultOf(id).errorCode)
        assertEquals(0, fake.calls.get())
        manager.acknowledge(id)
        // A later start with the real service works: the failed epoch left nothing behind.
        manager.foregroundStarter = real
        val next = start(app, 9811, "after")
        assertTrue(fake.entered.getValue("after").await(10, TimeUnit.SECONDS))
        finish("after"); assertEquals("done:after", resultOf(next).text)
        assertEquals(1, fake.calls.get())
    }

    @Test fun lateConfirmationOfATimedOutStartIsIgnoredAndTheServiceStops() {
        timings(handshake = 200)
        val real = manager.foregroundStarter
        manager.foregroundStarter = AppAiManager.ForegroundStarter { epoch ->
            Handler(Looper.getMainLooper()).postDelayed({ real.start(epoch) }, 900)
        }
        val app = newApp("Late")
        val id = start(app, 9821, "late")
        assertEquals("background_service", resultOf(id).errorCode)
        // The stale service start arrives after the timeout: it must not run the provider or leave a service behind.
        SystemClock.sleep(2000)
        assertEquals(0, fake.calls.get())
        eventually("stale service stopped") { serviceStopped() }
        assertTrue(posted().isEmpty())
        manager.acknowledge(id)
        manager.foregroundStarter = real
        val next = start(app, 9821, "fresh")
        assertTrue(fake.entered.getValue("fresh").await(10, TimeUnit.SECONDS))
        finish("fresh"); assertEquals("done:fresh", resultOf(next).text)
    }

    @Test fun providerRunsOnlyAfterTheServiceIsInTheForeground() {
        val app = newApp("Confirmed")
        val id = start(app, 9831, "confirmed")
        assertTrue(fake.entered.getValue("confirmed").await(10, TimeUnit.SECONDS))
        assertEquals(true, fake.serviceWasUp["confirmed"])
        val note = eventually("foreground notification") { forApp("Confirmed")?.takeIf { isForeground(it) } }
        assertEquals(AiNotificationCopy.DEFAULT_TITLE, text(note, android.app.Notification.EXTRA_TITLE))
        finish("confirmed"); assertEquals("done:confirmed", resultOf(id).text)
    }

    @Test fun laterLossOfForegroundGivesRunningRequestsATerminalErrorWithoutClaimingTheyNeverRan() {
        val app = newApp("Lost")
        val session = beginOk(app, 9841)
        val id = start(app, 9841, "lost", session)
        assertTrue(fake.entered.getValue("lost").await(10, TimeUnit.SECONDS))
        onMain { manager.onForegroundFailed(manager.foregroundEpoch, "ForegroundServiceStartNotAllowedException") }
        val result = resultOf(id)
        assertEquals("background_service", result.errorCode)
        assertEquals("The provider had been called", 1, fake.calls.get())
        assertFalse("Does not claim the step never ran", result.error!!.contains("never", ignoreCase = true))
        assertEquals(SessionEnd.DENIED.kind, failureOf { start(app, 9841, "after", session) }.kind)
        assertEquals("No further provider call", 1, fake.calls.get())
    }

    // ---- Sessions: begin, gap, ownership ------------------------------------------------------

    @Test fun sessionKeepsTheServiceUpBetweenStagesWithoutAnotherServiceStart() {
        val counting = CountingStarter(manager.foregroundStarter); manager.foregroundStarter = counting
        timings(idle = 20_000)
        val app = newApp("Stages")
        val session = beginOk(app, 9851)
        assertEquals("One service start for the whole operation", 1, counting.starts.get())
        val one = start(app, 9851, "stage1", session)
        assertTrue(fake.entered.getValue("stage1").await(10, TimeUnit.SECONDS))
        finish("stage1"); assertEquals("done:stage1", resultOf(one).text); manager.acknowledge(one)
        // The gap between stages: nothing is running, yet the service stays foreground with an honest notification.
        SystemClock.sleep(1500)
        heldContinuously("Stages", body = AiNotificationCopy.LEASE_BODY)
        assertTrue(AiRequestService.isRunning())
        val two = start(app, 9851, "stage2", session)
        assertTrue(fake.entered.getValue("stage2").await(10, TimeUnit.SECONDS))
        assertEquals("The second stage needed no service start", 1, counting.starts.get())
        finish("stage2"); assertEquals("done:stage2", resultOf(two).text); manager.acknowledge(two)
        assertTrue(onMain { manager.endSession(app, 9851, session) })
        assertTrue("Ending twice is harmless", onMain { manager.endSession(app, 9851, session) })
        eventually("service stops with the session") { serviceStopped() }
        assertTrue(posted().isEmpty())
    }

    @Test fun beginIsRefusedWhenTheOwningActivityIsNotResumedAndStartsNoService() {
        val counting = CountingStarter(manager.foregroundStarter); manager.foregroundStarter = counting
        val app = newApp("Hidden")
        val (id, failure) = begin(app, 9861, resumed = false)
        assertNull(id); assertEquals(ApiFailure.Kind.BACKGROUND_SERVICE, failure?.kind)
        assertEquals(0, counting.starts.get()); assertEquals(0, fake.calls.get()); assertFalse(AiRequestService.isRunning())
    }

    @Test fun beginNeverCallsTheProviderAndNeedsAValidGrant() {
        val app = newApp("Grant")
        onMain { SubappGrants.setGranted(context, app, SubappCapability.AI, false) }
        assertEquals(ApiFailure.Kind.PERMISSION, begin(app, 9871).second?.kind)
        onMain { SubappGrants.setGranted(context, app, SubappCapability.AI, true) }
        beginOk(app, 9871)
        assertEquals("Beginning a session is not inference", 0, fake.calls.get())
    }

    @Test fun sessionsAreOwnedByAppAndTask() {
        val a = newApp("Owner A"); val b = newApp("Owner B")
        val session = beginOk(a, 9881)
        assertEquals(ApiFailure.Kind.NOT_FOUND, failureOf { start(a, 9882, "wrong-task", session) }.kind)
        assertEquals(ApiFailure.Kind.NOT_FOUND, failureOf { start(b, 9883, "wrong-app", session) }.kind)
        assertEquals(0, fake.calls.get())
        assertFalse(onMain { manager.endSession(a, 9882, session) })
        assertFalse(onMain { manager.endSession(b, 9881, session) })
        assertTrue("The owner still has it", onMain { manager.endSession(a, 9881, session) })
    }

    @Test fun anotherTaskOfTheSameAppCannotTakeOverALiveSession() {
        val a = newApp("Two Tasks")
        beginOk(a, 9891)
        assertEquals(ApiFailure.Kind.REQUEST_IN_PROGRESS, begin(a, 9892).second?.kind)
    }

    @Test fun beginAdoptsTheSameTasksRunningRequestWithoutDiscardingOrReissuingIt() {
        val a = newApp("Adopt")
        val running = start(a, 9895, "adopted")
        assertTrue(fake.entered.getValue("adopted").await(10, TimeUnit.SECONDS))
        val session = beginOk(a, 9895)
        assertEquals("Still the same request, still running", false, manager.result(running)?.done)
        assertEquals("Not reissued", 1, fake.calls.get())
        finish("adopted"); assertEquals("done:adopted", resultOf(running).text); manager.acknowledge(running)
        val next = start(a, 9895, "next", session)
        assertTrue(fake.entered.getValue("next").await(10, TimeUnit.SECONDS)); finish("next"); resultOf(next)
    }

    // ---- Cancellation, expiry, revocation ------------------------------------------------------

    @Test fun notificationCancelOnAnIdleSessionBlocksLaterStartsWithoutAProviderCall() {
        val app = newApp("Cancel Idle")
        val session = beginOk(app, 9901)
        val note = eventually("session notification") { forApp("Cancel Idle") }
        action(note, "Cancel").send()
        eventually("notification gone") { gone("Cancel Idle") }
        assertEquals(ApiFailure.Kind.SESSION_CANCELLED, failureOf { start(app, 9901, "blocked", session) }.kind)
        assertEquals(0, fake.calls.get())
        assertTrue("Ending a cancelled session is still harmless", onMain { manager.endSession(app, 9901, session) })
    }

    @Test fun notificationCancelAbortsTheWholeOperationAndKeepsATerminalResult() {
        val app = newApp("Cancel Run")
        val session = beginOk(app, 9911)
        val id = start(app, 9911, "running", session)
        assertTrue(fake.entered.getValue("running").await(10, TimeUnit.SECONDS))
        action(heldContinuously("Cancel Run", heldMs = 0, body = AiNotificationCopy.DEFAULT_BODY), "Cancel").send()
        val result = resultOf(id)
        assertEquals("session_cancelled", result.errorCode)
        manager.acknowledge(id)
        assertEquals(ApiFailure.Kind.SESSION_CANCELLED, failureOf { start(app, 9911, "next", session) }.kind)
        assertEquals("No inference after the cancel", 1, fake.calls.get())
        eventually("cleanup") { serviceStopped() }
    }

    @Test fun aRetainedCancelFromTheFirstStageStillCancelsTheLiveSessionInTheGapAndTheNextStage() {
        val app = newApp("Retained Cancel")
        val session = beginOk(app, 9401)
        val one = start(app, 9401, "gap1", session)
        assertTrue(fake.entered.getValue("gap1").await(10, TimeUnit.SECONDS))
        // The Cancel the user could still be looking at while stage one ran.
        val retained = action(heldContinuously("Retained Cancel", heldMs = 0, body = AiNotificationCopy.DEFAULT_BODY), "Cancel")
        finish("gap1"); assertEquals("done:gap1", resultOf(one).text); manager.acknowledge(one)
        heldContinuously("Retained Cancel", heldMs = 0, body = AiNotificationCopy.LEASE_BODY)   // the idle gap
        retained.send()
        eventually("the session is cancelled in the gap") { if (!onMain { manager.isSessionLive(app, 9401, session) }) true else null }
        assertEquals(ApiFailure.Kind.SESSION_CANCELLED, failureOf { start(app, 9401, "gap2", session) }.kind)
        assertEquals("No inference after the Cancel", 1, fake.calls.get())

        // The same, with the next stage already running when the old action is used.
        val again = newApp("Retained Cancel Two")
        val second = beginOk(again, 9402)
        val a = start(again, 9402, "stageA", second); assertTrue(fake.entered.getValue("stageA").await(10, TimeUnit.SECONDS))
        val oldAction = action(heldContinuously("Retained Cancel Two", heldMs = 0, body = AiNotificationCopy.DEFAULT_BODY), "Cancel")
        finish("stageA"); resultOf(a); manager.acknowledge(a)
        val b = start(again, 9402, "stageB", second); assertTrue(fake.entered.getValue("stageB").await(10, TimeUnit.SECONDS))
        oldAction.send()
        assertEquals("session_cancelled", resultOf(b).errorCode)
        assertEquals("gap1, stageA and stageB only: nothing after the Cancel", 3, fake.calls.get())
        assertEquals(ApiFailure.Kind.SESSION_CANCELLED, failureOf { start(again, 9402, "stageC", second) }.kind)
        assertEquals(3, fake.calls.get())
    }

    @Test fun anActionFromAnEndedSessionCannotCancelANewSession() {
        val app = newApp("Old Action")
        val first = beginOk(app, 9411)
        val oldAction = action(heldContinuously("Old Action", heldMs = 0, body = AiNotificationCopy.LEASE_BODY), "Cancel")
        assertTrue(onMain { manager.endSession(app, 9411, first) })
        eventually("first session's notification gone") { gone("Old Action") }
        val second = beginOk(app, 9411)
        assertNotEquals(first, second)
        heldContinuously("Old Action", heldMs = 0, body = AiNotificationCopy.LEASE_BODY)
        oldAction.send()
        SystemClock.sleep(1000)
        assertTrue("The new session is untouched", onMain { manager.isSessionLive(app, 9411, second) })
        val id = start(app, 9411, "survives-old-action", second)
        assertTrue(fake.entered.getValue("survives-old-action").await(10, TimeUnit.SECONDS))
        finish("survives-old-action"); assertEquals("done:survives-old-action", resultOf(id).text)
    }

    @Test fun aDroppedUpdateThatOnlyChangedTheCancelTargetOrAppNameIsRepaired() {
        val a = newApp("Finger A"); val b = newApp("Finger B")
        beginOk(a, 9421); beginOk(b, 9422)
        // The lower notification id holds the foreground, so the second app's notification is an ordinary one.
        val other = eventually("the second app's notification") {
            posted().firstOrNull { text(it, android.app.Notification.EXTRA_SUB_TEXT) == "Finger B" && !isForeground(it) && text(it, android.app.Notification.EXTRA_TEXT) == AiNotificationCopy.LEASE_BODY }
        }
        val real = other.notification.extras.getString(AiRequestService.EXTRA_FINGERPRINT)
        assertNotNull(real)
        // An impostor with the same title, text and app name but a different identity, as a shed update would leave.
        val impostor = androidx.core.app.NotificationCompat.Builder(context, "ai_requests")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(text(other, android.app.Notification.EXTRA_TITLE)).setContentText(text(other, android.app.Notification.EXTRA_TEXT))
            .setSubText("Finger B").setOngoing(true)
            .addExtras(android.os.Bundle().apply { putString(AiRequestService.EXTRA_FINGERPRINT, "stale-cancel-target") }).build()
        notifications.notify(other.id, impostor)
        eventually("the impostor is posted") { posted().firstOrNull { it.id == other.id && it.notification.extras.getString(AiRequestService.EXTRA_FINGERPRINT) == "stale-cancel-target" } }
        assertEquals("Same visible text", text(other, android.app.Notification.EXTRA_TEXT), text(posted().first { it.id == other.id }, android.app.Notification.EXTRA_TEXT))
        AiRequestService.reconcile()   // schedules the single verification; nothing re-posts by itself
        eventually("the intended notification is restored") { posted().firstOrNull { it.id == other.id && it.notification.extras.getString(AiRequestService.EXTRA_FINGERPRINT) == real } }
    }

    @Test fun cancellingASessionKeepsAlreadyCompletedResultsForSaving() {
        val app = newApp("Keep Done")
        val session = beginOk(app, 9921)
        val id = start(app, 9921, "finished", session)
        assertTrue(fake.entered.getValue("finished").await(10, TimeUnit.SECONDS))
        finish("finished"); assertEquals("done:finished", resultOf(id).text)
        // The idle session's own notification (its Cancel names the session); the finished request's stale one is ignored by design.
        action(heldContinuously("Keep Done", heldMs = 0, body = AiNotificationCopy.LEASE_BODY), "Cancel").send()
        eventually("session cancelled") { gone("Keep Done") }
        assertEquals("The completed result is still there to save", "done:finished", manager.result(id)?.text)
        assertNull(manager.result(id)?.error)
        manager.acknowledge(id); assertFalse(manager.hasRequest(app))
    }

    @Test fun idleSessionExpiresAndReportsWhy() {
        timings(idle = 400)
        val app = newApp("Idle")
        val session = beginOk(app, 9931)
        eventually("idle expiry") { serviceStopped() }
        assertTrue(posted().isEmpty())
        assertEquals(ApiFailure.Kind.SESSION_IDLE, failureOf { start(app, 9931, "late", session) }.kind)
        assertEquals(0, fake.calls.get())
    }

    @Test fun hardCapTerminalizesARunningRequestWithATimeoutError() {
        timings(max = 600)
        val app = newApp("Cap")
        val session = beginOk(app, 9941)
        val id = start(app, 9941, "capped", session)
        assertTrue(fake.entered.getValue("capped").await(10, TimeUnit.SECONDS))
        assertEquals("session_timeout", resultOf(id).errorCode)
        assertEquals(ApiFailure.Kind.SESSION_TIMEOUT, failureOf { start(app, 9941, "next", session) }.kind)
        assertEquals(1, fake.calls.get())
    }

    @Test fun androidServiceTimeoutEndsSessionsAndGivesLeaseRequestsATerminalError() {
        val app = newApp("OS Timeout"); val other = newApp("Legacy Timeout")
        val session = beginOk(app, 9951)
        val leased = start(app, 9951, "leased", session); val legacy = start(other, 9952, "legacy")
        assertTrue(fake.entered.getValue("leased").await(10, TimeUnit.SECONDS) && fake.entered.getValue("legacy").await(10, TimeUnit.SECONDS))
        onMain { manager.onServiceTimeout() }
        assertEquals("session_timeout", resultOf(leased).errorCode)
        assertNull("Legacy requests keep their existing cancel behaviour", manager.result(legacy))
    }

    @Test fun revokingAiEndsAnIdleSessionEvenWithoutARequest() {
        val app = newApp("Revoke")
        val session = beginOk(app, 9961)
        eventually("session notification") { forApp("Revoke") }
        onMain { SubappGrants.setGranted(context, app, SubappCapability.AI, false) }
        eventually("notification gone") { gone("Revoke") }
        onMain { SubappGrants.setGranted(context, app, SubappCapability.AI, true) }
        assertEquals(ApiFailure.Kind.SESSION_CANCELLED, failureOf { start(app, 9961, "after", session) }.kind)
        assertEquals(0, fake.calls.get())
    }

    @Test fun replacedPageReleasesItsSessionButNotARunningRequest() {
        val app = newApp("Reload")
        val session = beginOk(app, 9971)
        val id = start(app, 9971, "survivor", session)
        assertTrue(fake.entered.getValue("survivor").await(10, TimeUnit.SECONDS))
        onMain { manager.releaseTask(9971) }
        assertEquals(false, manager.result(id)?.done)
        // The request, not the released session, now owns the notification, and it stays up.
        heldContinuously("Reload", body = AiNotificationCopy.DEFAULT_BODY)
        finish("survivor"); assertEquals("done:survivor", resultOf(id).text); manager.acknowledge(id)
        assertEquals("A replaced page's session is unknown, not resumable", ApiFailure.Kind.NOT_FOUND, failureOf { start(app, 9971, "again", session) }.kind)
        assertEquals(1, fake.calls.get())
        eventually("service stops") { serviceStopped() }
    }

    @Test fun twoAppsKeepIsolatedSessionsAndNotifications() {
        val a = newApp("Iso A"); val b = newApp("Iso B")
        val sa = beginOk(a, 9981); val sb = beginOk(b, 9982)
        eventually("both notifications") { if (forApp("Iso A") != null && forApp("Iso B") != null) true else null }
        action(forApp("Iso A")!!, "Cancel").send()
        eventually("A gone") { gone("Iso A") }
        heldContinuously("Iso B")
        assertEquals(ApiFailure.Kind.SESSION_CANCELLED, failureOf { start(a, 9981, "a", sa) }.kind)
        val id = start(b, 9982, "b", sb)
        assertTrue(fake.entered.getValue("b").await(10, TimeUnit.SECONDS))
        finish("b"); assertEquals("done:b", resultOf(id).text)
    }

    // ---- Unexpected service destruction, delayed handshakes, stale starts ----------------------

    @Test fun unexpectedServiceDestructionEndsWhatDependedOnItAndKeepsCompletedResults() {
        val doomed = newApp("Doomed"); val finished = newApp("Finished")
        // A normal idle shutdown first: it must not be mistaken for a failure, and the next start must work.
        val warm = start(finished, 9301, "warm"); assertTrue(fake.entered.getValue("warm").await(10, TimeUnit.SECONDS))
        finish("warm"); resultOf(warm); manager.acknowledge(warm)
        eventually("normal idle stop") { serviceStopped() }
        val done = start(finished, 9301, "done"); assertTrue(fake.entered.getValue("done").await(10, TimeUnit.SECONDS))
        finish("done"); assertEquals("done:done", resultOf(done).text)           // completed, not acknowledged
        val session = beginOk(doomed, 9302)
        val running = start(doomed, 9302, "running", session)
        assertTrue(fake.entered.getValue("running").await(10, TimeUnit.SECONDS))
        val callsBefore = fake.calls.get()

        onMain { context.stopService(android.content.Intent(context, AiRequestService::class.java)) }

        val result = resultOf(running)
        assertEquals("background_service", result.errorCode)
        assertEquals(SessionEnd.DENIED.kind, failureOf { start(doomed, 9302, "after", session) }.kind)
        assertEquals("No provider call after the service was lost", callsBefore, fake.calls.get())
        assertEquals("A completed result is preserved for saving", "done:done", manager.result(done)?.text)
        assertNull(manager.result(done)?.error)
        eventually("cleanup") { if (posted().isEmpty() && !AiRequestService.isRunning()) true else null }
        manager.acknowledge(done); manager.acknowledge(running)
        // Recovery is a fresh start with a fresh epoch, not a leftover state.
        val reborn = start(doomed, 9302, "reborn")
        assertTrue(fake.entered.getValue("reborn").await(10, TimeUnit.SECONDS))
        finish("reborn"); assertEquals("done:reborn", resultOf(reborn).text)
    }

    private fun cancelledDuringHandshake(name: String, task: Int, expected: ApiFailure.Kind, cause: (String, String) -> Unit) {
        val real = manager.foregroundStarter
        val counting = CountingStarter(delayedStarter(real, 800)); manager.foregroundStarter = counting
        val app = newApp(name)
        val future = beginAsync(app, task)
        assertFalse("The handshake is still pending", future.isDone)
        cause(app, manager.liveSessionId(app)!!)
        val (id, failure) = future.get(15, TimeUnit.SECONDS)
        assertNull("No late successful begin", id)
        assertEquals(expected, failure?.kind)
        assertEquals(0, fake.calls.get())
        eventually("no service leak") { if (posted().isEmpty() && !AiRequestService.isRunning()) true else null }
        assertEquals(1, counting.starts.get())
        // The next operation starts cleanly from the same state.
        manager.foregroundStarter = real
        val session = beginOk(app, task)
        val next = start(app, task, "after-$name", session)
        assertTrue(fake.entered.getValue("after-$name").await(10, TimeUnit.SECONDS))
        finish("after-$name"); assertEquals("done:after-$name", resultOf(next).text)
        assertEquals(1, fake.calls.get())
    }

    @Test fun notificationCancelDuringTheHandshakeFailsBeginWithoutALateSession() =
        cancelledDuringHandshake("Handshake Cancel", 9311, ApiFailure.Kind.SESSION_CANCELLED) { _, id -> onMain { manager.cancelFromNotification("lease:$id") } }

    @Test fun revocationDuringTheHandshakeFailsBeginWithoutALateSession() =
        cancelledDuringHandshake("Handshake Revoke", 9312, ApiFailure.Kind.SESSION_CANCELLED) { app, _ ->
            onMain { SubappGrants.setGranted(context, app, SubappCapability.AI, false) }
            onMain { SubappGrants.setGranted(context, app, SubappCapability.AI, true) }
        }

    @Test fun finalCloseDuringTheHandshakeFailsBeginWithoutALateSession() =
        cancelledDuringHandshake("Handshake Close", 9313, ApiFailure.Kind.NOT_FOUND) { _, _ -> onMain { manager.cancelTask(9313) } }

    @Test fun replacedSessionDuringTheHandshakeAnswersTheOldBeginAsNotFound() {
        val real = manager.foregroundStarter
        manager.foregroundStarter = delayedStarter(real, 600)
        val app = newApp("Handshake Replace")
        val first = beginAsync(app, 9314)
        val second = beginAsync(app, 9314)   // replaces the idle session while the first handshake is pending
        val (oldId, oldFailure) = first.get(15, TimeUnit.SECONDS)
        assertNull(oldId); assertEquals(ApiFailure.Kind.NOT_FOUND, oldFailure?.kind)
        val (newId, newFailure) = second.get(15, TimeUnit.SECONDS)
        assertNull(newFailure); assertNotNull(newId)
        assertEquals(0, fake.calls.get())
        assertTrue(onMain { manager.isSessionLive(app, 9314, newId!!) })
    }

    @Test fun aStartFromAnEarlierAttemptArrivingLateDoesNotDisturbTheReplacementService() {
        timings(handshake = 200)
        val real = manager.foregroundStarter
        manager.foregroundStarter = delayedStarter(real, 900)           // epoch 1 will arrive long after its timeout
        val app = newApp("Stale Start")
        val failed = start(app, 9321, "stale-first")
        assertEquals("background_service", resultOf(failed).errorCode)
        assertEquals(0, fake.calls.get())
        manager.acknowledge(failed)
        manager.foregroundStarter = real
        val replacement = start(app, 9321, "replacement")                 // epoch 2, confirmed normally
        assertTrue(fake.entered.getValue("replacement").await(10, TimeUnit.SECONDS))
        SystemClock.sleep(1800)                                           // the epoch-1 start command lands in here
        assertEquals("The replacement request was not stopped", false, manager.result(replacement)?.done)
        assertTrue(AiRequestService.isRunning())
        heldContinuously("Stale Start")
        assertEquals("Only the replacement reached the provider", 1, fake.calls.get())
        finish("replacement"); assertEquals("done:replacement", resultOf(replacement).text)
        manager.acknowledge(replacement)
        eventually("service stops once") { serviceStopped() }
    }

    // ---- Activity rotation versus final close -------------------------------------------------

    /**
     * Recreating the activity (rotation) loads a new page, which cannot hold the old page's session
     * id, so the session is released while a running request continues. Final close is different:
     * it cancels the task's requests too.
     */
    @Test fun rotationReleasesThePageSessionButKeepsItsRequestWhileFinalCloseCancelsIt() {
        val app = newApp("Real Task Session")
        ActivityScenario.launch<SubappRuntimeActivity>(runtimeIntent(app)).use { scenario ->
            var task = -1
            scenario.onActivity { task = it.taskId }
            val session = beginOk(app, task)
            val id = start(app, task, "across-rotation", session)
            assertTrue(fake.entered.getValue("across-rotation").await(10, TimeUnit.SECONDS))
            scenario.recreate()
            eventually("the new page released the old session") { if (!onMain { manager.isSessionLive(app, task, session) }) true else null }
            assertEquals("Rotation preserves the native request", false, manager.result(id)?.done)
            heldContinuously("Real Task Session")
            scenario.close()
            eventually("final close cancels the task's request") { if (manager.result(id) == null) true else null }
            eventually("service stops") { serviceStopped() }
        }
    }
}
