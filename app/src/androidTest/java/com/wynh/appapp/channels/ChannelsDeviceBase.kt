package com.wynh.appapp.channels

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.wynh.appapp.MainActivity
import com.wynh.appapp.SubappRuntimeActivity
import com.wynh.appapp.workspace.SubappRecord
import com.wynh.appapp.workspace.SubappRepository
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shared helpers of the channel device tests: disposable starter subapps, both runtime modes, page
 * scripting and the real system pickers. Failures of system-UI steps
 * report the current page result and the native screen tree instead of retrying silently.
 */
abstract class ChannelsDeviceBase {
    protected val instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val context = instrumentation.targetContext
    protected val created = mutableListOf<String>()
    protected val repository by lazy { SubappRepository(context).also { it.ensureMigrated() } }

    /** Accessibility view IDs are only reported to a service that asks for them. */
    @Before fun reportViewIds() {
        val automation = instrumentation.uiAutomation
        val info = automation.serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
    }

    @After fun cleanUp() { created.forEach { runCatching { repository.delete(it) } } }

    protected fun newSubapp(): SubappRecord = repository.createStarter().also { created += it.id }

    // --- page scripting ---------------------------------------------------------------------

    protected fun js(web: WebView, source: String): String {
        val latch = CountDownLatch(1); var result = ""
        instrumentation.runOnMainSync { web.evaluateJavascript(source) { result = it; latch.countDown() } }
        assertTrue(latch.await(10, TimeUnit.SECONDS)); return result
    }
    protected fun await(web: WebView, source: String, seconds: Int = 20): String {
        val end = SystemClock.uptimeMillis() + seconds * 1000L
        while (SystemClock.uptimeMillis() < end) {
            val result = js(web, source)
            if (result != "null" && result != "false") return result
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out: $source\n${diagnostics(web)}")
    }
    protected fun webOf(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) webOf(view.getChildAt(i))?.let { return it }
        return null
    }
    protected fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    protected fun screenshot(name: String) { SystemClock.sleep(500); shell("screencap -p /sdcard/Download/appapp-channels-$name.png") }

    protected fun initialize(web: WebView) {
        await(web, "document.readyState === 'complete' && !!window.AppSave")
        js(web, "import(location.origin+'/js/bridge.js').then(m=>{window.api=m}).catch(e=>window.importError=String(e))")
        assertEquals("true", await(web, "window.importError || !!window.api"))
    }
    /** Runs an async expression and returns its value, or {error: code}, as JSON. */
    protected fun call(web: WebView, expression: String, seconds: Int = 20): String {
        js(web, "window.result=null;($expression).then(v=>window.result=JSON.stringify(v===undefined?null:v)).catch(e=>window.result=JSON.stringify({error:e.code||String(e)}))")
        return unquote(await(web, "window.result", seconds))
    }
    /** Starts an async expression whose JSON outcome lands in window.result, without waiting for it. */
    protected fun start(web: WebView, expression: String) =
        js(web, "window.result=null;($expression).then(v=>window.result=JSON.stringify(v)).catch(e=>window.result=JSON.stringify({error:e.code||String(e)}))")
    protected fun unquote(value: String) = value.trim('"').replace("\\\"", "\"")

    protected fun modes(record: SubappRecord) = listOf(
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SUBAPP_ID, record.id),
        Intent(context, SubappRuntimeActivity::class.java).setData(Uri.parse("appapp://subapp/${record.id}"))
    )
    protected fun <T> withPage(intent: Intent, block: (ActivityScenario<AppCompatActivity>, WebView) -> T): T =
        ActivityScenario.launch<AppCompatActivity>(intent).use { scenario ->
            lateinit var web: WebView
            scenario.onActivity { web = webOf(it.findViewById(android.R.id.content))!! }
            initialize(web)
            block(scenario, web)
        }

    protected fun awaitUntil(seconds: Int = 20, what: String = "condition", condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + seconds * 1000L
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; SystemClock.sleep(100) }
        throw AssertionError("Condition never held: $what\n${screenTree()}")
    }

    // --- the real system UI -----------------------------------------------------------------

    protected fun windowPackage(): String = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString().orEmpty()

    /** The active window's accessibility tree, for failure messages. */
    protected fun screenTree(): String {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return "No active window (package unknown)."
        val lines = mutableListOf("Active window package: ${root.packageName}")
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (lines.size > 300) return
            lines += "${"  ".repeat(depth)}${node.className} id=${node.viewIdResourceName} text=${node.text} desc=${node.contentDescription} clickable=${node.isClickable}"
            for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        return lines.joinToString("\n")
    }
    protected open fun diagnostics(web: WebView?): String =
        "Page result: ${web?.let { runCatching { js(it, "String(window.result)") }.getOrNull() }}\n${screenTree()}"

    /**
     * The picker's confirm button: `android:id/button1` beneath documentsui's non-clickable
     * `container_save`. Both are stable framework/DocumentsUI IDs; the button's label is not used.
     */
    private fun findPickerSaveButton(): AccessibilityNodeInfo? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        val pickerPackage = root.packageName?.toString()?.takeIf { it.contains("documentsui") } ?: return null
        val container = root.findAccessibilityNodeInfosByViewId("$pickerPackage:id/container_save").firstOrNull() ?: return null
        return container.findAccessibilityNodeInfosByViewId("android:id/button1").firstOrNull { it.isClickable && it.isEnabled }
    }

    /** Waits for a system window owned by a package containing [fragment] (the picker). */
    protected fun awaitSystemWindow(fragment: String, web: WebView?, seconds: Int = 30) {
        val end = SystemClock.uptimeMillis() + seconds * 1000L
        while (SystemClock.uptimeMillis() < end) { if (windowPackage().contains(fragment)) return; SystemClock.sleep(200) }
        throw AssertionError("No window of a package containing '$fragment' appeared.\n${diagnostics(web)}")
    }

    /** Clicks the real picker's Save button; [beforeClick] (the screenshot) runs once, while the picker is visible. */
    protected fun clickPickerSave(web: WebView?, seconds: Int = 20, beforeClick: () -> Unit = {}) {
        val end = SystemClock.uptimeMillis() + seconds * 1000L
        var shown = false
        while (SystemClock.uptimeMillis() < end) {
            val button = findPickerSaveButton()
            if (button != null) {
                if (!shown) { beforeClick(); shown = true }
                if (button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            }
            SystemClock.sleep(200)
        }
        throw AssertionError("The picker's Save button (android:id/button1 under container_save) did not appear or accept a click.\n${diagnostics(web)}")
    }

    /**
     * Clicks the entry named [fileName] in the real open-document picker. The name is fixture data
     * chosen by the test, not interface wording.
     */
    protected fun clickPickerFile(fileName: String, web: WebView?, seconds: Int = 20, beforeClick: () -> Unit = {}) {
        val end = SystemClock.uptimeMillis() + seconds * 1000L
        var shown = false
        while (SystemClock.uptimeMillis() < end) {
            val entry = instrumentation.uiAutomation.rootInActiveWindow?.takeIf { it.packageName?.contains("documentsui") == true }
                ?.findAccessibilityNodeInfosByText(fileName)?.firstOrNull { it.text?.toString() == fileName && it.viewIdResourceName == "android:id/title" }
            if (entry != null) {
                var target: AccessibilityNodeInfo? = entry
                while (target != null && !target.isClickable) target = target.parent
                if (target != null) {
                    if (!shown) { beforeClick(); shown = true }
                    if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
                }
            }
            SystemClock.sleep(200)
        }
        throw AssertionError("The picker did not list a file named $fileName.\n${diagnostics(web)}")
    }

    /** A real touch on the page element [id]: its box in CSS pixels, scaled and offset to the screen. */
    protected fun tapElement(web: WebView, id: String) {
        val encoded = await(web, """(() => {
            const element = document.getElementById('$id');
            if (!element || document.readyState !== 'complete') return null;
            const r = element.getBoundingClientRect();
            const values = [r.left + r.width / 2, r.top + r.height / 2, window.devicePixelRatio];
            return r.width > 0 && r.height > 0 && values.every(Number.isFinite) ? JSON.stringify(values) : null;
        })()""")
        val box = unquote(encoded).trim('[', ']').split(',').map { it.toDouble() }
        val origin = IntArray(2)
        instrumentation.runOnMainSync { web.getLocationOnScreen(origin) }
        shell("input tap ${(origin[0] + box[0] * box[2]).toInt()} ${(origin[1] + box[1] * box[2]).toInt()}")
    }
}
