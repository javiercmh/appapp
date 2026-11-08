package com.wynh.appapp.ai

import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import com.wynh.appapp.SubappRuntimeActivity
import com.wynh.appapp.ai.auth.EncryptedApiKeyStore
import com.wynh.appapp.ai.auth.AuthFailure
import com.wynh.appapp.workspace.SubappRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AiExposureDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun js(web: android.webkit.WebView, source: String): String {
        val latch = CountDownLatch(1); var value = ""
        instrumentation.runOnMainSync { web.evaluateJavascript(source) { value = it; latch.countDown() } }
        assertTrue(latch.await(10, TimeUnit.SECONDS)); return value
    }
    private fun await(web: android.webkit.WebView, expression: String): String {
        val end = SystemClock.uptimeMillis() + 20000
        while (SystemClock.uptimeMillis() < end) {
            val value = js(web, expression)
            if (value != "null" && value != "false") return value
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out waiting for test page")
    }
    private fun web(view: android.view.View): android.webkit.WebView? {
        if (view is android.webkit.WebView) return view
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) web(view.getChildAt(i))?.let { return it }
        return null
    }
    @Test fun bridgeExposesOnlySelectedProviderAndEmitsModeChanges() {
        val context = instrumentation.targetContext
        val manager = (context.applicationContext as AppAiApplication).ai
        val previous = manager.exposure
        val repository = SubappRepository(context); repository.ensureMigrated()
        val record = repository.createStarter()
        try {
            File(record.workspaceDir, "index.html").writeText("""<!doctype html><main>AI Exposure Test</main>
                <script type="module">
                import {ai} from './js/bridge.js';
                window.ai=ai;window.events=[];
                addEventListener('app2:ai:connection',e=>events.push(e.detail));
                </script>""")
            val intent = Intent(context, SubappRuntimeActivity::class.java).setData(Uri.parse("appapp://subapp/${record.id}"))
            ActivityScenario.launch<SubappRuntimeActivity>(intent).use { scenario ->
                lateinit var page: android.webkit.WebView
                scenario.onActivity { page = web(it.findViewById(android.R.id.content))!! }
                assertEquals("true", await(page, "!!window.ai"))
                AiProviderRegistry.modes.forEach { mode ->
                    instrumentation.runOnMainSync { manager.setExposure(mode) }
                    js(page, "window.providersResult=null;ai.providers().then(r=>window.providersResult=JSON.stringify(r));")
                    val result = JsonParser.parseString(await(page, "window.providersResult")).asString
                    val providers = JsonParser.parseString(result).asJsonObject.getAsJsonArray("providers")
                    assertEquals(1, providers.size()); assertEquals(mode, providers[0].asJsonObject.get("id").asString)
                    val hidden = if (mode == "chatgpt") "openrouter" else "chatgpt"
                    js(page, "window.errorCode=null;ai.models('$hidden').catch(e=>window.errorCode=e.code);")
                    assertEquals("\"provider\"", await(page, "window.errorCode"))
                }
                assertEquals("true", await(page, "events.some(e=>e.providerId==='combined')"))
            }
        } finally {
            instrumentation.runOnMainSync { manager.setExposure(previous) }
            repository.delete(record.id)
        }
    }
    @Test fun encryptedKeySurvivesRecreationAndCorruptionIsNotSilentlyDiscarded() {
        val target = instrumentation.targetContext
        val root = File(target.cacheDir, "ai-key-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(target) { override fun getNoBackupFilesDir() = root }
        val alias = "appapp.ai.test.${UUID.randomUUID()}"
        val store = EncryptedApiKeyStore(context, alias)
        try {
            assertNull(store.read()); store.write("test-only-api-key")
            val file = File(root, "appapp-openrouter-key.enc")
            assertFalse(file.readBytes().toString(Charsets.UTF_8).contains("test-only-api-key"))
            assertEquals("test-only-api-key", EncryptedApiKeyStore(context, alias).read())
            file.writeBytes(byteArrayOf(1, 2, 3))
            assertThrows(AuthFailure::class.java) { store.read() }
            assertTrue(file.exists())
            store.reset(); assertNull(store.read())
        } finally { store.reset(); root.deleteRecursively() }
    }
}
