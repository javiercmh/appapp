package com.wynh.appapp

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.webkit.WebView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wynh.appapp.workspace.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Every app created here is disposable; no account or live inference is used. */
@RunWith(AndroidJUnit4::class)
class RuntimeOperationsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val page = """<!doctype html><meta name="viewport" content="width=device-width">
        <p>Runtime operation fixture</p><script>
        localStorage.loads = String(Number(localStorage.loads || 0) + 1);
        window.ticks=0;setInterval(()=>window.ticks++,50);
        window.ready=true;
        </script>"""

    private fun findWeb(view: android.view.View): WebView? {
        if (view is WebView) return view
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) findWeb(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun js(web: WebView, source: String): String {
        val done = CountDownLatch(1); var value = ""
        instrumentation.runOnMainSync { web.evaluateJavascript(source) { value = it; done.countDown() } }
        assertTrue("JavaScript timed out", done.await(10, TimeUnit.SECONDS)); return value
    }
    private fun await(web: WebView, expression: String) {
        val until = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < until) {
            if (js(web, expression) == "true") return
            SystemClock.sleep(50)
        }
        fail("Timed out: $expression")
    }
    private fun idle(controller: WorkspaceActionController) {
        val until = SystemClock.uptimeMillis() + 15_000
        while (controller.state.value.busy && SystemClock.uptimeMillis() < until) SystemClock.sleep(20)
        assertFalse("Operation remained busy", controller.state.value.busy)
        assertFalse(controller.state.value.message, controller.state.value.error)
    }
    private fun launch(record: SubappRecord) = ActivityScenario.launch<SubappRuntimeActivity>(
        Intent(context, SubappRuntimeActivity::class.java).setData(Uri.parse("appapp://subapp/${record.id}")))

    @Test fun plainSaveKeepsRuntimeMemoryAndBackgroundReplacementWaitsForResume() {
        val repository = SubappRepository(context); repository.ensureMigrated()
        val record = repository.createStarter()
        val other = repository.createStarter()
        try {
            File(record.workspaceDir, "index.html").writeText(page)
            File(other.workspaceDir, "index.html").writeText(page)
            launch(record).use { scenario ->
                lateinit var web: WebView
                scenario.onActivity { web = findWeb(it.findViewById(android.R.id.content))!! }
                await(web, "window.ready === true")
                val loads = js(web, "Number(localStorage.loads)")
                js(web, "window.unsaved = 'keep me'")
                lateinit var controller: WorkspaceActionController
                instrumentation.runOnMainSync { controller = WorkspaceActionController(context.applicationContext as Application) }
                instrumentation.runOnMainSync {
                    controller.change(record, "Edit fixture") { it.writeFile("note.txt", "saved") }
                }
                idle(controller)
                assertEquals("\"keep me\"", js(web, "window.unsaved"))
                assertEquals(loads, js(web, "Number(localStorage.loads)"))

                scenario.moveToState(Lifecycle.State.CREATED)
                launch(other).use { otherScenario ->
                    lateinit var otherWeb: WebView
                    otherScenario.onActivity { otherWeb = findWeb(it.findViewById(android.R.id.content))!! }
                    await(otherWeb, "window.ready === true")
                    val ticks = js(otherWeb, "window.ticks")
                    js(otherWeb, "window.unsaved = 'other app'")
                    instrumentation.runOnMainSync {
                        controller.execute(record, "Replace fixture") { manager, _ ->
                            WorkspaceOperations.replace(manager.workspaceDir) { staged ->
                                WorkspaceFiles.copyTree(manager.workspaceDir, staged)
                                WorkspaceFiles(staged).writeText("index.html", page)
                            }
                        }
                    }
                    idle(controller)
                    // A stopped task must keep its old document until it becomes foreground again.
                    assertEquals(loads, js(web, "Number(localStorage.loads)"))
                    assertEquals("\"keep me\"", js(web, "window.unsaved"))
                    assertEquals("\"other app\"", js(otherWeb, "window.unsaved"))
                    await(otherWeb, "window.ticks > $ticks")
                }
                scenario.moveToState(Lifecycle.State.RESUMED)
                await(web, "window.ready === true && Number(localStorage.loads) === Number($loads) + 1")
                assertEquals("\"undefined\"", js(web, "typeof window.unsaved"))
                assertEquals("true", js(web, "AndroidStorage.writeFile('data/after.json','fresh')"))
                SystemClock.sleep(150)
                assertEquals((loads.toInt() + 1).toString(), js(web, "Number(localStorage.loads)"))
            }
        } finally { repository.delete(record.id); repository.delete(other.id) }
    }
    @Test fun rendererDeathOffersReloadAndKeepsWorkspaceData() {
        val repository = SubappRepository(context); repository.ensureMigrated()
        val record = repository.createStarter()
        try {
            File(record.workspaceDir, "index.html").writeText(page)
            File(record.workspaceDir, "data/sentinel.json").writeText("keep renderer data")
            launch(record).use { scenario ->
                lateinit var oldWeb: WebView
                scenario.onActivity { oldWeb = findWeb(it.findViewById(android.R.id.content))!! }
                await(oldWeb, "window.ready === true")
                instrumentation.runOnMainSync { assertTrue("Renderer termination refused", oldWeb.webViewRenderProcess!!.terminate()) }
                val until = SystemClock.uptimeMillis() + 15_000
                while (true) {
                    try {
                        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(R.string.runtime_stopped_title))
                            .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
                        break
                    } catch (e: androidx.test.espresso.NoMatchingViewException) {
                        if (SystemClock.uptimeMillis() > until) throw e
                        SystemClock.sleep(50)
                    }
                }
                instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                    java.io.FileOutputStream(File(context.cacheDir, "renderer-dialog.png")).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(R.string.runtime_reload))
                    .perform(androidx.test.espresso.action.ViewActions.click())
                lateinit var freshWeb: WebView
                scenario.onActivity { freshWeb = findWeb(it.findViewById(android.R.id.content))!! }
                assertNotSame(oldWeb, freshWeb)
                await(freshWeb, "window.ready === true")
                assertEquals("keep renderer data", File(record.workspaceDir, "data/sentinel.json").readText())
                assertEquals("true", js(freshWeb, "AndroidStorage.writeFile('data/after.json','fresh')"))
                instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                    java.io.FileOutputStream(File(context.cacheDir, "renderer-reloaded.png")).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
        } finally { repository.delete(record.id) }
    }

}
