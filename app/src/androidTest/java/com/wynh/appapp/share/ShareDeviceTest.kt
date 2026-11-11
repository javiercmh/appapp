package com.wynh.appapp.share

import android.app.Instrumentation
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.wynh.appapp.MainActivity
import com.wynh.appapp.SubappRuntimeActivity
import com.wynh.appapp.workspace.SubappRepository
import com.wynh.appapp.workspace.WorkspaceOperations
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ShareDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun js(web: WebView, source: String): String {
        val latch = CountDownLatch(1); var result = ""
        instrumentation.runOnMainSync { web.evaluateJavascript(source) { result = it; latch.countDown() } }
        assertTrue(latch.await(10, TimeUnit.SECONDS)); return result
    }
    private fun await(web: WebView, source: String): String {
        val end = SystemClock.uptimeMillis() + 20000
        while (SystemClock.uptimeMillis() < end) {
            val result = js(web, source)
            if (result != "null" && result != "false") return result
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out: $source")
    }
    private fun web(view: android.view.View): WebView? {
        if (view is WebView) return view
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) web(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().decodeToString() }
    private fun initialize(web: WebView) {
        await(web, "document.readyState === 'complete' && !!window.AppShare")
        js(web, "import(location.origin+'/js/bridge.js').then(m=>window.testShare=m.share).catch(e=>window.shareImportError=String(e))")
        assertEquals("true", await(web, "window.shareImportError || !!window.testShare"))
    }
    private fun command(web: WebView, expression: String): String {
        js(web, "window.shareResult=null;($expression).then(v=>shareResult=v).catch(e=>shareResult={error:e.code})")
        return await(web, "window.shareResult")
    }
    private fun launcher(activity: AppCompatActivity) = when (activity) {
        is MainActivity -> activity.shareLauncher
        is SubappRuntimeActivity -> activity.shareLauncher
        else -> throw AssertionError("Unexpected host ${activity.javaClass}")
    }
    /** Reads the activity-owned chooser state on the main thread, waiting for a recreated instance if asked. */
    private fun chooserOpen(scenario: ActivityScenario<AppCompatActivity>, replacing: AppCompatActivity? = null): Pair<AppCompatActivity, Boolean> {
        val end = SystemClock.uptimeMillis() + 20000
        while (SystemClock.uptimeMillis() < end) {
            var result: Pair<AppCompatActivity, Boolean>? = null
            runCatching { scenario.onActivity { if (it !== replacing) result = it to launcher(it).chooserOpen } }
            result?.let { return it }
            SystemClock.sleep(100)
        }
        throw AssertionError("No recreated host")
    }
    private fun awaitChooserReleased(scenario: ActivityScenario<AppCompatActivity>) {
        val end = SystemClock.uptimeMillis() + 20000
        while (chooserOpen(scenario).second) {
            if (SystemClock.uptimeMillis() > end) throw AssertionError("Chooser result never released the host")
            SystemClock.sleep(100)
        }
    }
    @Test fun shareContractInEditorAndStandalone() {
        val repository = SubappRepository(context); repository.ensureMigrated()
        val record = repository.createStarter()
        val wrapperBytes = java.io.File(record.workspaceDir, "js/bridge.js").readBytes()
        assertArrayEquals(context.assets.open("starter_template/js/bridge.js").use { it.readBytes() }, wrapperBytes)
        println("Share fixture bridge SHA256: " + java.security.MessageDigest.getInstance("SHA-256").digest(wrapperBytes).joinToString("") { "%02x".format(it) })
        val captured = java.util.concurrent.atomic.AtomicReference<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action == Intent.ACTION_CHOOSER) captured.set(Intent(intent))
                return null
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            val intents = listOf(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SUBAPP_ID, record.id),
                Intent(context, SubappRuntimeActivity::class.java).setData(Uri.parse("appapp://subapp/${record.id}")))
            for ((mode, intent) in intents.withIndex()) {
                ActivityScenario.launch<AppCompatActivity>(intent).use { scenario ->
                    lateinit var web: WebView
                    lateinit var host: AppCompatActivity
                    scenario.onActivity { host = it; web = web(it.findViewById(android.R.id.content))!! }
                    initialize(web)
                    assertEquals("{\"available\":true,\"file\":true}", command(web, "testShare.availability()"))
                    assertEquals("{\"error\":\"too_large\"}", command(web, "testShare.text({text:'x'.repeat(100001)})"))
                    assertEquals("{\"error\":\"too_large\"}", command(web, "testShare.file({name:'huge.json',mimeType:'application/json',content:'あ'.repeat(3500000)})"))
                    assertEquals("{\"error\":\"invalid_arguments\"}", command(web, "testShare.file({name:'a',mimeType:'image/png',content:'x'})"))
                    WorkspaceOperations.reserve(record.workspaceDir, replacement = false).use {
                        assertEquals("{\"error\":\"busy\"}", command(web, "testShare.availability()"))
                    }
                    scenario.moveToState(Lifecycle.State.CREATED)
                    assertEquals("{\"error\":\"not_foreground\"}", command(web, "testShare.text({text:'background'})"))
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    js(web, "window.foreignShare=null;addEventListener('message',e=>{if(e.data && e.data.shareProbe)foreignShare=e.data.value});var frame=document.createElement('iframe');frame.sandbox='allow-scripts';frame.srcdoc='<script>parent.postMessage({shareProbe:true,value:typeof AppShare},\"*\")<\\/script>';document.body.append(frame)")
                    assertEquals("\"undefined\"", await(web, "window.foreignShare"))
                    // Actual system chooser, with its real subject and text extras.
                    captured.set(null)
                    assertEquals("{\"launched\":true}", command(web, "testShare.text({title:'Share fixture',text:'Story 日本語'})"))
                    assertNotNull(captured.get())
                    @Suppress("DEPRECATION")
                    val textSend = captured.get()!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
                    assertEquals(Intent.ACTION_SEND, textSend.action)
                    assertEquals("text/plain", textSend.type)
                    assertEquals("Story 日本語", textSend.getStringExtra(Intent.EXTRA_TEXT))
                    assertEquals("Share fixture", textSend.getStringExtra(Intent.EXTRA_SUBJECT))
                    assertEquals("Share fixture", captured.get()!!.getStringExtra(Intent.EXTRA_TITLE))
                    SystemClock.sleep(600)
                    shell("screencap -p /sdcard/Download/appapp-share-text-$mode.png")
                    // The outstanding chooser holds the activity: no other preparation can start.
                    lateinit var exclusive: Any
                    instrumentation.runOnMainSync {
                        exclusive = launcher(host).chooserOpen to launcher(host).acquire(Any())
                    }
                    assertEquals(true to false, exclusive)
                    // Recreate the paused host while the chooser is still open, as on rotation.
                    instrumentation.runOnMainSync { host.recreate() }
                    val (recreated, retained) = chooserOpen(scenario, replacing = host)
                    assertTrue("Saved state must restore the outstanding chooser", retained)
                    host = recreated
                    shell("input keyevent KEYCODE_BACK")
                    awaitChooserReleased(scenario)
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    scenario.onActivity { web = web(it.findViewById(android.R.id.content))!! }
                    initialize(web)
                    val content = "{\"story\":\"日本語\"}"
                    captured.set(null)
                    assertEquals("{\"launched\":true}", command(web, "testShare.file({name:'../story.exe',mimeType:'application/json',content:${Gson().toJson(content)}})"))
                    @Suppress("DEPRECATION")
                    val fileSend = captured.get()!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
                    assertEquals("application/json", fileSend.type)
                    assertTrue(fileSend.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                    @Suppress("DEPRECATION")
                    val uri = fileSend.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
                    assertEquals("${context.packageName}.fileprovider", uri.authority)
                    assertEquals(uri, fileSend.clipData!!.getItemAt(0).uri)
                    SystemClock.sleep(600)
                    shell("screencap -p /sdcard/Download/appapp-share-file-$mode.png")
                    shell("input keyevent KEYCODE_BACK")
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    val received = CountDownLatch(1)
                    var receiverResult: Intent? = null
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) { receiverResult = intent; received.countDown() }
                    }
                    context.registerReceiver(receiver, IntentFilter("com.wynh.appapp.test.SHARE_RECEIVED"), Context.RECEIVER_EXPORTED)
                    try {
                        // Replay the exact send Intent to a test-only external receiver. No account or network needed.
                        scenario.onActivity { activity -> activity.startActivity(Intent(fileSend).setComponent(ComponentName(instrumentation.context.packageName, ShareReceiverActivity::class.java.name))) }
                        assertTrue("External receiver did not return", received.await(15, TimeUnit.SECONDS))
                        assertNull(receiverResult!!.getStringExtra("error"))
                        assertArrayEquals(content.toByteArray(), receiverResult.getByteArrayExtra("content"))
                        assertEquals("application/json", receiverResult.getStringExtra("mime"))
                        assertEquals("_story.json", receiverResult.getStringExtra("name"))
                    } finally { context.unregisterReceiver(receiver) }
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    awaitChooserReleased(scenario)
                    // The largest accepted text reaches the real chooser intact.
                    captured.set(null)
                    assertEquals("{\"launched\":true}", command(web, "testShare.text({text:'語'.repeat(${SharePolicy.MAX_TEXT})})"))
                    @Suppress("DEPRECATION")
                    val maxSend = captured.get()!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
                    assertEquals("語".repeat(SharePolicy.MAX_TEXT), maxSend.getStringExtra(Intent.EXTRA_TEXT))
                    SystemClock.sleep(600)
                    shell("input keyevent KEYCODE_BACK")
                    awaitChooserReleased(scenario)
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    assertEquals("{\"available\":true,\"file\":true}", command(web, "testShare.availability()"))
                }
            }
        } finally { instrumentation.removeMonitor(monitor); repository.delete(record.id) }
    }
}
