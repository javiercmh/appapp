package com.wynh.appapp.ai

import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonParser
import com.wynh.appapp.SubappRuntimeActivity
import com.wynh.appapp.ai.network.ApiFailure
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The real runtime activity, WebView, bridge.js wrapper, native bridge, manager and service, with a
 * fake provider. It proves what Android allows here; it does not prove that Android lets a
 * suspended page run JavaScript, so the stage-two start below is driven while the activity is
 * stopped and its outcome is asserted as observed.
 */
@RunWith(AndroidJUnit4::class)
class AiSessionBridgeDeviceTest : AiDeviceFixture() {
    private fun js(web: android.webkit.WebView, source: String): String {
        val latch = CountDownLatch(1); var value = ""
        instrumentation.runOnMainSync { web.evaluateJavascript(source) { value = it; latch.countDown() } }
        assertTrue(latch.await(10, TimeUnit.SECONDS)); return value
    }
    private fun await(web: android.webkit.WebView, expression: String): String {
        val end = SystemClock.uptimeMillis() + 20000
        while (SystemClock.uptimeMillis() < end) {
            val value = js(web, expression)
            if (value != "null" && value != "false" && value != "undefined") return value
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out waiting for page: $expression")
    }
    private fun web(view: android.view.View): android.webkit.WebView? {
        if (view is android.webkit.WebView) return view
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) web(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun page(app: String, script: String) = File(context.filesDir, "subapps/$app/workspace/index.html")
        .writeText("""<!doctype html><main>Session test</main><script type="module">$script</script>""")
    private fun unquote(raw: String) = JsonParser.parseString(raw).asString

    private fun withPage(app: String, block: (ActivityScenario<SubappRuntimeActivity>, android.webkit.WebView, Int) -> Any?) {
        ActivityScenario.launch<SubappRuntimeActivity>(runtimeIntent(app)).use { scenario ->
            lateinit var page: android.webkit.WebView; var task = -1
            scenario.onActivity { page = web(it.findViewById(android.R.id.content))!!; task = it.taskId }
            block(scenario, page, task)
        }
    }
    private fun home(scenario: ActivityScenario<SubappRuntimeActivity>) {
        shell("input keyevent KEYCODE_HOME")
        eventually("activity no longer resumed") { if (!scenario.state.isAtLeast(Lifecycle.State.RESUMED)) true else null }
    }

    @Test fun sessionCarriesStagesAcrossAnIdleGapWhileTheActivityIsBackgrounded() {
        val counting = CountingStarter(manager.foregroundStarter); manager.foregroundStarter = counting
        manager.timings = AppAiManager.Timings(idleMs = 20_000)
        val app = newApp("Bridge Stages")
        page(app, "import {ai} from './js/bridge.js'; window.ai = ai; window.out = {};")
        withPage(app) { scenario, web, task ->
            assertEquals("true", await(web, "!!window.ai"))
            js(web, "ai.sessions.supported().then(v => out.sup = v).catch(e => out.supErr = e.code)")
            assertEquals("true", await(web, "window.out.sup"))
            js(web, "ai.sessions.begin({model: 'fake', title: 'Stage test'}).then(r => out.sid = r.sessionId).catch(e => out.err = e.code)")
            val session = unquote(await(web, "window.out.sid"))
            assertEquals("Beginning a session is not inference", 0, fake.calls.get())
            assertEquals(1, counting.starts.get())

            fake.expect("p1")
            js(web, "ai.start({model: 'fake', input: 'p1', sessionId: out.sid}).then(i => out.r1 = i).catch(e => out.e1 = e.code)")
            val first = unquote(await(web, "window.out.r1"))
            home(scenario)
            assertTrue(fake.entered.getValue("p1").await(10, TimeUnit.SECONDS))
            finish("p1"); assertEquals("done:p1", resultOf(first).text); manager.acknowledge(first)

            // The idle gap, with the activity stopped: the session keeps the service up with an honest notification.
            SystemClock.sleep(2500)
            assertFalse(scenario.state.isAtLeast(Lifecycle.State.RESUMED))
            val gap = eventually("idle notification during the gap") { forApp("Bridge Stages")?.takeIf { isForeground(it) } }
            assertEquals(AiNotificationCopy.LEASE_BODY, text(gap, android.app.Notification.EXTRA_TEXT))

            fake.expect("p2")
            js(web, "ai.start({model: 'fake', input: 'p2', sessionId: out.sid}).then(i => out.r2 = i).catch(e => out.e2 = e.code)")
            assertTrue("Stage two started natively while the activity was stopped", fake.entered.getValue("p2").await(15, TimeUnit.SECONDS))
            assertFalse(scenario.state.isAtLeast(Lifecycle.State.RESUMED))
            assertEquals("Stage two needed no new service start", 1, counting.starts.get())
            val second = unquote(await(web, "window.out.r2"))
            finish("p2"); assertEquals("done:p2", resultOf(second).text); manager.acknowledge(second)

            js(web, "ai.sessions.end(out.sid).then(v => out.ended = v)")
            assertEquals("true", await(web, "window.out.ended"))
            assertFalse(manager.isSessionLive(app, task, session))
            eventually("service stops with the session") { serviceStopped() }
        }
    }

    @Test fun beginFromABackgroundedActivityIsRefusedByTheOwningActivityState() {
        val counting = CountingStarter(manager.foregroundStarter); manager.foregroundStarter = counting
        val app = newApp("Bridge Hidden")
        page(app, "import {ai} from './js/bridge.js'; window.ai = ai; window.out = {};")
        withPage(app) { scenario, web, _ ->
            assertEquals("true", await(web, "!!window.ai"))
            home(scenario)
            js(web, "ai.sessions.begin({model: 'fake'}).then(r => out.sid = r.sessionId).catch(e => out.err = e.code)")
            assertEquals("\"background_service\"", await(web, "window.out.err"))
            assertEquals(0, counting.starts.get()); assertEquals(0, fake.calls.get())
        }
    }

    @Test fun idleExpiryHappensWhileTheActivityIsStopped() {
        manager.timings = AppAiManager.Timings(idleMs = 1_500)
        val app = newApp("Bridge Idle")
        page(app, "import {ai} from './js/bridge.js'; window.ai = ai; window.out = {};")
        withPage(app) { scenario, web, task ->
            assertEquals("true", await(web, "!!window.ai"))
            js(web, "ai.sessions.begin({model: 'fake'}).then(r => out.sid = r.sessionId)")
            val session = unquote(await(web, "window.out.sid"))
            home(scenario)
            eventually("idle expiry") { if (!manager.isSessionLive(app, task, session)) true else null }
            assertEquals(ApiFailure.Kind.SESSION_IDLE, failureOf { start(app, task, "late", session) }.kind)
            assertEquals(0, fake.calls.get())
            eventually("service stops") { serviceStopped() }
        }
    }

    @Test fun reloadingThePageLeavesNoSessionBehindButKeepsARunningRequest() {
        val app = newApp("Bridge Reload")
        page(app, "import {ai} from './js/bridge.js'; window.ai = ai; window.out = {};")
        withPage(app) { _, web, task ->
            assertEquals("true", await(web, "!!window.ai"))
            js(web, "ai.sessions.begin({model: 'fake'}).then(r => out.sid = r.sessionId)")
            val session = unquote(await(web, "window.out.sid"))
            val id = start(app, task, "survives", session)
            assertTrue(fake.entered.getValue("survives").await(10, TimeUnit.SECONDS))
            js(web, "location.reload()")
            eventually("session released by the new page") { if (!manager.isSessionLive(app, task, session)) true else null }
            assertEquals("The request is unaffected by the reload", false, manager.result(id)?.done)
            finish("survives"); assertEquals("done:survives", resultOf(id).text)
        }
    }

    @Test fun unknownCommandAnswersExactlyLikeAnOldHostAndNeverReachesAProvider() {
        val app = newApp("Bridge Unknown")
        page(app, "window.out = {}; AppAI.onmessage = e => { out.reply = e.data; }; AppAI.postMessage(JSON.stringify({id: 'x1', action: 'noSuchAction'}));")
        withPage(app) { _, web, _ ->
            val reply = JsonParser.parseString(unquote(await(web, "window.out.reply"))).asJsonObject
            assertEquals("error", reply.get("type").asString)
            val error = reply.getAsJsonObject("data")
            assertEquals("invalid_request", error.get("code").asString)
            assertEquals("Unsupported AI command.", error.get("message").asString)
            assertEquals(0, fake.calls.get())
        }
    }
}
