package com.wynh.appapp.ai

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.test.platform.app.InstrumentationRegistry
import com.wynh.appapp.SubappRuntimeActivity
import com.wynh.appapp.ai.network.ApiFailure
import com.wynh.appapp.ai.network.InferenceRepository
import com.wynh.appapp.ai.network.ModelChoice
import com.wynh.appapp.grants.SubappCapability
import com.wynh.appapp.grants.SubappGrants
import com.wynh.appapp.workspace.SubappRepository
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Before
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Forwards each service start to [real] only after [delayMs], to hold a handshake open. */
internal fun delayedStarter(real: AppAiManager.ForegroundStarter, delayMs: Long) = AppAiManager.ForegroundStarter { epoch ->
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ real.start(epoch) }, delayMs)
}

/** Counts service start requests and forwards them to the real starter. */
internal class CountingStarter(private val real: AppAiManager.ForegroundStarter) : AppAiManager.ForegroundStarter {
    val starts = AtomicInteger()
    override fun start(epoch: Long) { starts.incrementAndGet(); real.start(epoch) }
}

/**
 * Shared setup for the operation-session device tests: the real [AppAiManager], [AiRequestService]
 * and Android notification manager with a deterministic fake provider (no account, network or
 * provider usage). Every hook the tests change is restored in [restore].
 */
abstract class AiDeviceFixture {
    protected class Fake : InferenceRepository {
        val entered = ConcurrentHashMap<String, CountDownLatch>()
        val gates = ConcurrentHashMap<String, CompletableDeferred<String>>()
        /** Every call that reached the provider, ever. */
        val calls = AtomicInteger()
        /** Whether the request service existed when the provider was reached. */
        val serviceWasUp = ConcurrentHashMap<String, Boolean>()
        fun expect(input: String) { entered[input] = CountDownLatch(1); gates[input] = CompletableDeferred() }
        override suspend fun models() = listOf(ModelChoice("fake", "Fake"))
        override suspend fun check(model: String) = "ok"
        override suspend fun generate(model: String, instructions: String, input: String, effort: String?): String {
            calls.incrementAndGet()
            serviceWasUp[input] = AiRequestService.isRunning()
            entered.getValue(input).countDown()
            return gates.getValue(input).await()
        }
    }

    protected val instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val context = instrumentation.targetContext
    protected val manager = (context.applicationContext as AppAiApplication).ai
    protected val notifications: NotificationManager = context.getSystemService(NotificationManager::class.java)
    protected val fake = Fake()
    private lateinit var repository: SubappRepository
    private lateinit var originalRegistry: AiProviderRegistry
    private lateinit var originalStarter: AppAiManager.ForegroundStarter
    private lateinit var originalTimings: AppAiManager.Timings
    private val created = mutableListOf<String>()

    /** Replaces the fake provider for this test; the original registry is restored in [restore]. */
    protected fun useProvider(provider: InferenceRepository) =
        setRegistry(AiProviderRegistry(listOf(AiProvider("chatgpt", "Fake", provider) { true }), "chatgpt"))

    private fun setRegistry(value: AiProviderRegistry) =
        AppAiManager::class.java.getDeclaredField("registry").apply { isAccessible = true }.set(manager, value)

    @Before fun installFixture() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, "android.permission.POST_NOTIFICATIONS")
        originalRegistry = manager.registry
        originalStarter = manager.foregroundStarter
        originalTimings = manager.timings
        setRegistry(AiProviderRegistry(listOf(AiProvider("chatgpt", "Fake", fake) { true }), "chatgpt"))
        repository = SubappRepository(context).also { it.ensureMigrated() }
    }

    @After fun restore() {
        instrumentation.runOnMainSync { manager.cancelAll() }
        // Let the service stop so the next test starts from a clean handshake.
        val end = SystemClock.uptimeMillis() + 15_000
        while (AiRequestService.isRunning() && SystemClock.uptimeMillis() < end) SystemClock.sleep(100)
        val leaked = AiRequestService.isRunning()
        manager.foregroundStarter = originalStarter
        manager.timings = originalTimings
        created.forEach { runCatching { repository.delete(it) } }
        setRegistry(originalRegistry)
        if (leaked) throw AssertionError("The request service was still running after ${testName.methodName}")
    }

    @get:org.junit.Rule val testName = org.junit.rules.TestName()

    protected fun newApp(name: String): String {
        val record = repository.createStarter()
        java.io.File(record.workspaceDir, "manifest.json").writeText("""{"name":"$name","main":"index.html"}""")
        SubappGrants.setGranted(context, record.id, SubappCapability.AI, true)
        created += record.id
        return record.id
    }

    protected fun <T> onMain(block: () -> T): T {
        var value: Result<T>? = null
        instrumentation.runOnMainSync { value = runCatching(block) }
        return value!!.getOrThrow()
    }

    protected fun start(appId: String, task: Int, input: String, sessionId: String? = null, title: String? = null,
                        body: String? = null, open: Intent? = null): String {
        fake.expect(input)
        return onMain { manager.start(appId, task, "chatgpt", "fake", "", input, null, title, body, open, sessionId) }
    }

    /** Starts a session the way the bridge does; returns the id or the failure. */
    protected fun begin(appId: String, task: Int, resumed: Boolean = true, body: String? = null): Pair<String?, ApiFailure?> {
        val latch = CountDownLatch(1); var out: Pair<String?, ApiFailure?> = null to null
        instrumentation.runOnMainSync {
            manager.beginSession(appId, task, "chatgpt", "fake", "Working", body, resumed, null) { id, failure -> out = id to failure; latch.countDown() }
        }
        if (!latch.await(15, TimeUnit.SECONDS)) throw AssertionError("beginSession never answered")
        return out
    }

    protected fun beginOk(appId: String, task: Int): String = begin(appId, task).let { (id, failure) ->
        id ?: throw AssertionError("beginSession failed: ${failure?.kind} ${failure?.userMessage}")
    }

    /** Like [begin] but returns at once, so the test can act while the foreground handshake is pending. */
    protected fun beginAsync(appId: String, task: Int): java.util.concurrent.CompletableFuture<Pair<String?, ApiFailure?>> {
        val future = java.util.concurrent.CompletableFuture<Pair<String?, ApiFailure?>>()
        instrumentation.runOnMainSync {
            manager.beginSession(appId, task, "chatgpt", "fake", "Working", null, true, null) { id, failure -> future.complete(id to failure) }
        }
        return future
    }

    /**
     * Notification publication is asynchronous (the system posts it on its own thread), so presence is
     * awaited. Once it is up it must then never disappear for [heldMs]: a real gap still fails.
     */
    protected fun heldContinuously(name: String, heldMs: Long = 400, body: String? = null): StatusBarNotification {
        val first = eventually("notification for $name${body?.let { " saying \"$it\"" }.orEmpty()}") {
            forApp(name)?.takeIf { isForeground(it) && (body == null || text(it, Notification.EXTRA_TEXT) == body) }
        }
        val end = SystemClock.uptimeMillis() + heldMs
        while (SystemClock.uptimeMillis() < end) {
            if (forApp(name) == null) throw AssertionError("The notification for $name disappeared after it was published")
            SystemClock.sleep(15)
        }
        return first
    }

    protected fun failureOf(block: () -> Unit): ApiFailure {
        try { block() } catch (e: ApiFailure) { return e }
        throw AssertionError("Expected an ApiFailure")
    }

    protected fun <T> eventually(what: String, read: () -> T?): T {
        val end = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < end) { read()?.let { return it }; SystemClock.sleep(100) }
        throw AssertionError("Timed out waiting for $what")
    }

    protected fun posted(): List<StatusBarNotification> =
        notifications.activeNotifications.filter { it.packageName == context.packageName && it.id >= AiRequestService.NOTIFICATION_BASE }
    protected fun text(sbn: StatusBarNotification, key: String) = sbn.notification.extras.getCharSequence(key)?.toString()
    protected fun forApp(name: String) = posted().firstOrNull { text(it, Notification.EXTRA_SUB_TEXT) == name }
    protected fun gone(name: String) = if (forApp(name) == null) true else null
    protected fun action(sbn: StatusBarNotification, title: String): PendingIntent =
        sbn.notification.actions.first { it.title.toString() == title }.actionIntent
    protected fun isForeground(sbn: StatusBarNotification) = sbn.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0
    protected fun finish(input: String, text: String = "done:$input") = fake.gates.getValue(input).complete(text)
    protected fun serviceStopped() = if (!AiRequestService.isRunning()) true else null
    protected fun resultOf(id: String) = eventually("result $id") { manager.result(id)?.takeIf { it.done } }
    protected fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }

    protected fun runtimeIntent(appId: String) = Intent(context, SubappRuntimeActivity::class.java)
        .setAction(Intent.ACTION_VIEW).setData(Uri.parse("appapp://subapp/$appId"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS)
}
