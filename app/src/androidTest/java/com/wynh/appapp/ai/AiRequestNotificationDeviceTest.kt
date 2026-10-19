package com.wynh.appapp.ai

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wynh.appapp.SubappRuntimeActivity
import com.wynh.appapp.ai.network.ApiFailure
import com.wynh.appapp.ai.network.InferenceRepository
import com.wynh.appapp.ai.network.ModelChoice
import com.wynh.appapp.grants.SubappCapability
import com.wynh.appapp.grants.SubappGrants
import com.wynh.appapp.workspace.SubappRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Drives the real [AppAiManager], [AiRequestService] and Android notification manager with a
 * deterministic fake provider: no account, network or provider usage is involved. The fake is
 * installed in place of the registry for the duration of each test.
 */
@RunWith(AndroidJUnit4::class)
class AiRequestNotificationDeviceTest {
    private class Fake : InferenceRepository {
        val entered = ConcurrentHashMap<String, CountDownLatch>()
        val gates = ConcurrentHashMap<String, CompletableDeferred<String>>()
        fun expect(input: String) { entered[input] = CountDownLatch(1); gates[input] = CompletableDeferred() }
        override suspend fun models() = listOf(ModelChoice("fake", "Fake"))
        override suspend fun check(model: String) = "ok"
        override suspend fun generate(model: String, instructions: String, input: String, effort: String?): String {
            entered.getValue(input).countDown()
            return gates.getValue(input).await()
        }
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = (context.applicationContext as AppAiApplication).ai
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val fake = Fake()
    private lateinit var repository: SubappRepository
    private lateinit var originalRegistry: AiProviderRegistry
    private val created = mutableListOf<String>()

    private fun setRegistry(value: AiProviderRegistry) =
        AppAiManager::class.java.getDeclaredField("registry").apply { isAccessible = true }.set(manager, value)

    @get:org.junit.Rule val testName = org.junit.rules.TestName()

    @Before fun install() {
        if (!testName.methodName.startsWith("backgroundedRequest"))
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, "android.permission.POST_NOTIFICATIONS")
        originalRegistry = manager.registry
        setRegistry(AiProviderRegistry(listOf(AiProvider("chatgpt", "Fake", fake) { true }), "chatgpt"))
        repository = SubappRepository(context).also { it.ensureMigrated() }
    }

    @After fun restore() {
        instrumentation.runOnMainSync { manager.cancelAll() }
        created.forEach { runCatching { repository.delete(it) } }
        setRegistry(originalRegistry)
    }

    private fun newApp(name: String): String {
        val record = repository.createStarter()
        java.io.File(record.workspaceDir, "manifest.json").writeText("""{"name":"$name","main":"index.html"}""")
        SubappGrants.setGranted(context, record.id, SubappCapability.AI, true)
        created += record.id
        return record.id
    }

    private fun start(appId: String, task: Int, input: String, title: String? = null, body: String? = null,
                      open: Intent? = null): String {
        fake.expect(input)
        var id = ""; var failure: Throwable? = null
        // An exception must not escape on the main thread, where it would crash the instrumentation.
        instrumentation.runOnMainSync {
            try { id = manager.start(appId, task, "chatgpt", "fake", "", input, null, title, body, open) }
            catch (e: Throwable) { failure = e }
        }
        failure?.let { throw it }
        return id
    }

    private fun runtimeIntent(appId: String) = Intent(context, SubappRuntimeActivity::class.java)
        .setAction(Intent.ACTION_VIEW).setData(Uri.parse("appapp://subapp/$appId"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS)

    private fun <T> eventually(what: String, read: () -> T?): T {
        val end = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < end) { read()?.let { return it }; SystemClock.sleep(100) }
        throw AssertionError("Timed out waiting for $what")
    }

    private fun posted(): List<StatusBarNotification> =
        notifications.activeNotifications.filter { it.packageName == context.packageName && it.id >= AiRequestService.NOTIFICATION_BASE }

    private fun text(sbn: StatusBarNotification, key: String) = sbn.notification.extras.getCharSequence(key)?.toString()
    private fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    private fun forApp(name: String) = posted().firstOrNull { text(it, Notification.EXTRA_SUB_TEXT) == name }
    private fun gone(name: String) = if (forApp(name) == null) true else null
    private fun action(sbn: StatusBarNotification, title: String): PendingIntent =
        sbn.notification.actions.first { it.title.toString() == title }.actionIntent
    private fun isForeground(sbn: StatusBarNotification) = sbn.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0
    private fun finish(input: String, text: String = "done:$input") = fake.gates.getValue(input).complete(text)

    @Test fun twoAppsRunConcurrentlyWithOwnNotificationsAndOneRequestPerApp() {
        val a = newApp("Alpha Reader"); val b = newApp("Beta Writer")
        val first = start(a, 9101, "a1")
        val second = start(b, 9102, "b1", title = "Writing the next chapter", body = "Safe to switch apps.")
        assertTrue("Both fake generations are in flight at once", fake.entered.getValue("a1").await(10, TimeUnit.SECONDS) && fake.entered.getValue("b1").await(10, TimeUnit.SECONDS))

        val alpha = eventually("Alpha notification") { forApp("Alpha Reader") }
        val beta = eventually("Beta notification") { forApp("Beta Writer") }
        assertNotEquals(alpha.id, beta.id)
        assertEquals("Legacy defaults when no copy is supplied", AiNotificationCopy.DEFAULT_TITLE, text(alpha, Notification.EXTRA_TITLE))
        assertEquals(AiNotificationCopy.DEFAULT_BODY, text(alpha, Notification.EXTRA_TEXT))
        assertEquals("Writing the next chapter", text(beta, Notification.EXTRA_TITLE))
        assertEquals("exactly one notification is the foreground one", 1, listOf(alpha, beta).count { isForeground(it) })

        val duplicate = assertThrows(ApiFailure::class.java) { start(a, 9101, "a2") }
        assertEquals(ApiFailure.Kind.INVALID_REQUEST, duplicate.kind)
        assertEquals("The rejected duplicate did not disturb the running request", false, manager.result(first)?.done)
        assertTrue(manager.ownedByApp(first, a) && !manager.ownedByApp(first, b) && manager.ownedByApp(second, b))

        finish("a1"); finish("b1")
        assertEquals("done:a1", eventually("first result") { manager.result(first)?.takeIf { it.done } }.text)
        eventually("Alpha cleanup") { gone("Alpha Reader") }; eventually("Beta cleanup") { gone("Beta Writer") }
        assertTrue("Results stay available until acknowledged", manager.hasRequest(a) && manager.hasRequest(b))
        manager.acknowledge(first); manager.acknowledge(second)
        assertFalse(manager.hasRequest(a))
    }

    @Test fun cancelIsRequestScopedForegroundIsPromotedAndStaleActionsAreIgnored() {
        val a = newApp("Cancel Alpha"); val b = newApp("Cancel Beta"); val c = newApp("Cancel Gamma")
        val first = start(a, 9201, "a"); val second = start(b, 9202, "b")
        eventually("both notifications") { if (forApp("Cancel Alpha") != null && forApp("Cancel Beta") != null) true else null }
        val primary = posted().first { isForeground(it) }
        val primaryName = text(primary, Notification.EXTRA_SUB_TEXT)!!
        val staleCancel = action(primary, "Cancel")

        staleCancel.send()
        eventually("primary cancelled") { gone(primaryName) }
        val survivorName = if (primaryName == "Cancel Alpha") "Cancel Beta" else "Cancel Alpha"
        val survivor = eventually("promotion") { forApp(survivorName)?.takeIf { isForeground(it) } }
        val (cancelledId, survivorId) = if (primaryName == "Cancel Alpha") first to second else second to first
        assertNull("Cancelled request is released", manager.result(cancelledId))
        assertEquals("Cancel did not touch the other request", false, manager.result(survivorId)?.done)

        val third = start(c, 9203, "c")
        eventually("third notification") { forApp("Cancel Gamma") }
        staleCancel.send() // The request it names no longer exists.
        SystemClock.sleep(1000)
        assertEquals(false, manager.result(survivorId)?.done)
        assertEquals(false, manager.result(third)?.done)
        assertNotNull(forApp(survivorName)); assertNotNull(forApp("Cancel Gamma"))
        assertEquals(survivor.id, posted().first { isForeground(it) }.id)

        action(forApp("Cancel Gamma")!!, "Cancel").send()
        eventually("third cancelled") { gone("Cancel Gamma") }
        assertEquals(false, manager.result(survivorId)?.done)
    }

    @Test fun failureAndCompletionClearNotificationsAndLastRequestStopsTheService() {
        val a = newApp("Failing App"); val b = newApp("Passing App")
        val bad = start(a, 9301, "bad"); val good = start(b, 9302, "good")
        eventually("notifications") { if (posted().size == 2) true else null }
        fake.gates.getValue("bad").completeExceptionally(ApiFailure(ApiFailure.Kind.SERVICE, "Fake outage"))
        val failed = eventually("failure") { manager.result(bad)?.takeIf { it.done } }
        assertEquals("service", failed.errorCode)
        eventually("failed request cleanup") { gone("Failing App") }
        assertNotNull("Other request keeps running and holds the foreground", eventually("promoted") { forApp("Passing App")?.takeIf { isForeground(it) } })
        finish("good")
        eventually("last cleanup") { if (posted().isEmpty()) true else null }
        assertEquals("done:good", manager.result(good)?.text)
    }

    @Test fun openActionBringsUpTheOwningSubapp() {
        val a = newApp("Open Me")
        val monitor = instrumentation.addMonitor(SubappRuntimeActivity::class.java.name, null, false)
        start(a, 9401, "o", open = runtimeIntent(a))
        val open = eventually("notification") { forApp("Open Me") }
        action(open, "Open").send()
        val launched: Activity? = monitor.waitForActivityWithTimeout(15_000)
        assertNotNull("Open launches the subapp activity", launched)
        assertEquals(Uri.parse("appapp://subapp/$a"), launched!!.intent.data)
        instrumentation.runOnMainSync { launched.finish() }
        instrumentation.removeMonitor(monitor)
    }

    @Test fun closingOneTaskCancelsOnlyItsRequestsAndRevocationCancelsUnfinished() {
        val a = newApp("Task One"); val b = newApp("Task Two")
        val one = start(a, 9501, "t1"); val two = start(b, 9502, "t2")
        eventually("notifications") { if (posted().size == 2) true else null }
        instrumentation.runOnMainSync { manager.cancelTask(9501) }
        eventually("task one cleanup") { gone("Task One") }
        assertNull(manager.result(one)); assertEquals(false, manager.result(two)?.done)
        instrumentation.runOnMainSync { SubappGrants.setGranted(context, b, SubappCapability.AI, false) }
        eventually("revocation cleanup") { gone("Task Two") }
        assertNull(manager.result(two))
    }

    /**
     * Revoking a runtime permission kills the process that hosts the tests, so the harness must run
     * `pm revoke com.wynh.appapp android.permission.POST_NOTIFICATIONS` first and select this test.
     * Otherwise it is skipped; it never grants the permission itself.
     */
    @Test fun backgroundedRequestStillCompletesWithoutNotificationPermission() {
        assumeTrue("Requires POST_NOTIFICATIONS to be revoked before the run", !notifications.areNotificationsEnabled())
        val a = newApp("No Permission")
        val id = start(a, 9601, "p")
        shell("input keyevent KEYCODE_HOME")
        assertTrue(fake.entered.getValue("p").await(10, TimeUnit.SECONDS))
        assertTrue("Nothing is shown without permission", posted().isEmpty())
        finish("p")
        assertEquals("done:p", eventually("result") { manager.result(id)?.takeIf { it.done } }.text)
    }

    @Test fun rotationKeepsTheRequestAndClosingTheOwningTaskCancelsOnlyIt() {
        val a = newApp("Real Task"); val b = newApp("Other Task")
        val intent = Intent(context, SubappRuntimeActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("appapp://subapp/$a")).addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS)
        val other = start(b, 9701, "other")
        ActivityScenario.launch<SubappRuntimeActivity>(intent).use { scenario ->
            var task = -1
            scenario.onActivity { task = it.taskId }
            val mine = start(a, task, "mine", open = runtimeIntent(a))
            eventually("both notifications") { if (forApp("Real Task") != null && forApp("Other Task") != null) true else null }
            // Recreation, as for rotation, destroys the activity without finishing it.
            scenario.recreate()
            SystemClock.sleep(500)
            assertEquals("Rotation preserves the native request", false, manager.result(mine)?.done)
            assertNotNull(forApp("Real Task"))
            scenario.close()
            eventually("owning task closed") { gone("Real Task") }
            assertNull("Closing the owning task cancels its request", manager.result(mine))
            assertEquals("Another task's request is untouched", false, manager.result(other)?.done)
            assertNotNull(forApp("Other Task"))
        }
    }
}
