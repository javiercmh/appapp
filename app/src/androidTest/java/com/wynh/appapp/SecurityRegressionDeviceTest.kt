package com.wynh.appapp

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wynh.appapp.grants.SubappCapability
import com.wynh.appapp.grants.SubappGrants
import com.wynh.appapp.workspace.SubappRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Regression tests for the store-review findings; the unsafe behavior they once reproduced must stay denied. */
@RunWith(AndroidJUnit4::class)
class SecurityRegressionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun js(web: android.webkit.WebView, source: String): String {
        val latch = CountDownLatch(1); var result = ""
        instrumentation.runOnMainSync { web.evaluateJavascript(source) { result = it; latch.countDown() } }
        assertTrue("JavaScript timed out", latch.await(10, TimeUnit.SECONDS)); return result
    }
    private fun await(web: android.webkit.WebView, expression: String): String {
        val until = SystemClock.uptimeMillis() + 20000
        while (SystemClock.uptimeMillis() < until) {
            val v = js(web, expression); if (v != "null" && v != "false") return v
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out: $expression")
    }
    private fun findWeb(view: android.view.View): android.webkit.WebView? {
        if (view is android.webkit.WebView) return view
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) findWeb(view.getChildAt(i))?.let { return it }
        return null
    }

    private val page = """<!doctype html><meta name="viewport" content="width=device-width">
        <body><a id="out" href="https://example.com/">out</a>
        <script>
        window.results = {};
        window.results.own = (function(){ try {
          AndroidStorage.writeFile('data/audit.txt','SENTINEL');
          return AndroidStorage.readFile('data/audit.txt'); } catch(e) { return 'ERR '+e; } })();
        window.results.memoryAlias = typeof AndroidMemory + '/' + typeof AndroidNotification;
        window.results.rawHidden = typeof __app2NativeBridge;
        window.results.escape = AndroidStorage.readFile('../../index.json') + '|' + AndroidStorage.readFile('data/%2e%2e/x');
        var f = document.createElement('iframe');
        f.sandbox = 'allow-scripts';
        f.srcdoc = '<script>var r="none";try{r=typeof AndroidStorage}catch(e){} try{r+="|"+__app2NativeBridge.call("","readFile","[\\"data/audit.txt\\"]")}catch(e){r+="|threw"} parent.postMessage("frame:"+r,"*")<\/script>';
        window.addEventListener('message', function(e){ if (String(e.data).indexOf('frame:')==0) window.results.frame = e.data; });
        document.body.appendChild(f);
        fetch('https://example.com/').then(function(){ window.results.net='allowed'; }).catch(function(){ window.results.net='blocked'; });
        window.results.ready = true;
        </script></body>"""

    @Test fun bridgeNavigationAndNetworkPolicy() {
        val context = instrumentation.targetContext
        val repository = SubappRepository(context); repository.ensureMigrated()
        val record = repository.createStarter()
        try {
            java.io.File(record.workspaceDir, "index.html").writeText(page)
            SubappGrants.setGranted(context, record.id, SubappCapability.NETWORK, false)
            val intents = listOf(
                Intent(context, SubappRuntimeActivity::class.java).setData(Uri.parse("appapp://subapp/${record.id}")),
                Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SUBAPP_ID, record.id)
            )
            for (intent in intents) {
                ActivityScenario.launch<androidx.appcompat.app.AppCompatActivity>(intent).use { scenario ->
                    lateinit var web: android.webkit.WebView
                    scenario.onActivity { activity ->
                        web = findWeb(activity.findViewById(android.R.id.content))!!
                    }
                    await(web, "window.results && window.results.ready === true && !!window.results.frame && !!window.results.net")
                    val r = { key: String -> js(web, "String(window.results.$key)") }
                    assertEquals("\"SENTINEL\"", r("own"))               // existing bridge API still works in the app's own page
                    assertEquals("\"object/object\"", r("memoryAlias"))
                    assertEquals("\"undefined\"", r("rawHidden"))
                    assertEquals("\"|\"", r("escape"))                   // traversal returns nothing
                    val frame = r("frame")                               // opaque iframe: no wrapper, and the raw object rejects a tokenless call
                    assertTrue(frame, frame.startsWith("\"frame:undefined|") && !frame.contains("SENTINEL"))
                    assertEquals("\"blocked\"", r("net"))                // offline by default
                    // Even a direct load of an external page returns to the app, with the bridge intact only there.
                    instrumentation.runOnMainSync { web.loadUrl("https://example.com/") }
                    await(web, "location.hostname.endsWith('.app.local') && !!window.results && window.results.ready === true")
                    assertEquals("\"object\"", js(web, "typeof AndroidStorage"))
                }
            }
        } finally { repository.delete(record.id) }
    }
}
