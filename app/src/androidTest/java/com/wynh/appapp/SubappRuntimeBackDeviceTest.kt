package com.wynh.appapp

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.NoActivityResumedException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wynh.appapp.workspace.SubappRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SubappRuntimeBackDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun js(web: android.webkit.WebView, source: String): String {
        val latch = CountDownLatch(1)
        var result = ""
        instrumentation.runOnMainSync { web.evaluateJavascript(source) { result = it; latch.countDown() } }
        assertTrue("JavaScript evaluation timed out", latch.await(10, TimeUnit.SECONDS))
        return result
    }

    private fun await(web: android.webkit.WebView, expression: String): String {
        val deadline = SystemClock.uptimeMillis() + 20000
        while (SystemClock.uptimeMillis() < deadline) {
            val value = js(web, expression)
            if (value != "null" && value != "false") return value
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out waiting for: $expression")
    }

    @Test fun androidBackClosesHistoryReaderAndExitsFromShelf() {
        val context = instrumentation.targetContext
        val repository = SubappRepository(context)
        repository.ensureMigrated()
        val record = repository.createStarter()
        try {
            java.io.File(record.workspaceDir, "index.html").writeText(
                """<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
                    <main id="shelf">My stories</main><main id="reader" hidden>Reader</main>
                    <script>
                    window.closeCount=0;
                    window.showReader=()=>{history.pushState({screen:'reader'},'', '#reader');document.querySelector('#shelf').hidden=true;document.querySelector('#reader').hidden=false};
                    window.onpopstate=()=>{window.closeCount++;document.querySelector('#reader').hidden=true;document.querySelector('#shelf').hidden=false};
                    </script>""".trimIndent()
            )
            val intent = Intent(context, SubappRuntimeActivity::class.java)
                .setData(Uri.parse("appapp://subapp/${record.id}"))
            ActivityScenario.launch<SubappRuntimeActivity>(intent).use { scenario ->
                lateinit var web: android.webkit.WebView
                scenario.onActivity { activity ->
                    web = findWeb(activity.findViewById(android.R.id.content))!!
                }
                assertEquals("\"My stories\"", await(web, "document.querySelector('#shelf').textContent.trim()"))

                js(web, "showReader()")
                assertEquals("true", await(web, "!document.querySelector('#reader').hidden"))
                pressBack()
                assertEquals("true", await(web, "!document.querySelector('#shelf').hidden && document.querySelector('#reader').hidden"))
                assertEquals("1", js(web, "window.closeCount"))
                scenario.onActivity { assertFalse("Back from reader must keep runtime alive", it.isFinishing) }

                // Repeat the Android path, then close a reader from the web app itself.
                repeat(3) {
                    js(web, "showReader()")
                    assertEquals("true", await(web, "!document.querySelector('#reader').hidden"))
                    pressBack()
                    assertEquals("true", await(web, "document.querySelector('#shelf').hidden === false && document.querySelector('#reader').hidden"))
                }
                js(web, "showReader();history.back()")
                assertEquals("true", await(web, "document.querySelector('#shelf').hidden === false && document.querySelector('#reader').hidden"))
                scenario.onActivity { assertFalse("Closing a reader must keep runtime alive", it.isFinishing) }

                // Once all pushed entries have been traversed, Back delegates to Android.
                val exited = try {
                    pressBack()
                    false
                } catch (_: NoActivityResumedException) {
                    true // Espresso reports the expected task exit as having no resumed activity.
                }
                assertTrue("Back from the shelf must exit the runtime", exited)
            }
        } finally {
            repository.delete(record.id)
        }
    }

    private fun findWeb(view: android.view.View): android.webkit.WebView? {
        if (view is android.webkit.WebView) return view
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) findWeb(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
