package com.wynh.appapp.audit

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wynh.appapp.MainActivity
import com.wynh.appapp.SubappRuntimeActivity
import com.wynh.appapp.ai.AppAiApplication
import com.wynh.appapp.workspace.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.*

@RunWith(AndroidJUnit4::class)
class StoreAuditTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private fun js(web: WebView, code: String): String {
        val latch = CountDownLatch(1); var result = ""
        inst.runOnMainSync { web.evaluateJavascript(code) { result = it; latch.countDown() } }
        check(latch.await(10, TimeUnit.SECONDS)); return result
    }
    private fun waitFor(web: WebView, code: String): String {
        val end = SystemClock.uptimeMillis() + 15000
        var value = ""
        while (SystemClock.uptimeMillis() < end) {
            value = js(web, code)
            if (value != "null" && value != "false" && value != "\"\"") return value
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out: $code, last=$value")
    }
    private fun web(v: android.view.View): WebView? {
        if(v is WebView) return v
        if(v is android.view.ViewGroup) for(i in 0 until v.childCount) web(v.getChildAt(i))?.let { return it }
        return null
    }
    private fun fixture(block: (SubappRepository, SubappRecord) -> Unit) {
        val repo = SubappRepository(ctx); repo.ensureMigrated()
        val record = repo.createStarter()
        File(record.workspaceDir,"manifest.json").writeText("""{"name":"Disposable Store Audit","main":"index.html"}""")
        File(record.workspaceDir,"index.html").writeText("""<!doctype html><title>Disposable Store Audit</title><main>Store audit fixture</main><script>window.ready=true;</script>""")
        File(record.workspaceDir,"data/audit.txt").writeText("AUDIT-ONLY")
        try { block(repo,record) } finally { repo.delete(record.id) }
    }
    private fun page(record: SubappRecord, editor: Boolean, block: (WebView) -> Unit) {
        val intent = if(editor) Intent(ctx,MainActivity::class.java).putExtra(MainActivity.EXTRA_SUBAPP_ID,record.id)
                     else Intent(ctx,SubappRuntimeActivity::class.java).setData(Uri.parse("appapp://subapp/${record.id}"))
        ActivityScenario.launch<android.app.Activity>(intent).use { scenario ->
            lateinit var view: WebView
            scenario.onActivity { view=web(it.findViewById(android.R.id.content))!! }
            waitFor(view,"window.ready===true")
            block(view)
        }
    }
    private fun iframe(editor: Boolean) = fixture { _, r -> page(r,editor) { w ->
        val inner = "<script>parent.postMessage({audit:true,origin:location.origin,storage:typeof AndroidStorage,ai:typeof AppAI,value:AndroidStorage.readFile('data/audit.txt'),write:AndroidStorage.writeFile('data/iframe.txt','IFRAME-AUDIT')},'*');</script>"
        js(w,"window.audit=null;addEventListener('message',e=>{if(e.data.audit)window.audit=e.data});var f=document.createElement('iframe');f.sandbox='allow-scripts';f.srcdoc="+org.json.JSONObject.quote(inner)+";document.body.append(f)")
        val result=waitFor(w,"window.audit&&JSON.stringify(window.audit)")
        println("AUDIT iframe editor=$editor $result")
        assertTrue(result.contains("AUDIT-ONLY")); assertTrue(result.contains("undefined"))
        assertEquals("IFRAME-AUDIT",File(r.workspaceDir,"data/iframe.txt").readText())
    } }
    @Test fun opaqueIframeReadsWritesEditorWorkspace() = iframe(true)
    @Test fun opaqueIframeReadsWritesRuntimeWorkspace() = iframe(false)
    @Test fun bridgeTraversalDoesNotReadOtherWorkspace() = fixture { repo,r ->
        val other=repo.createStarter()
        try {
            File(other.workspaceDir,"data/audit-secret.txt").writeText("OTHER-AUDIT")
            page(r,false) { w ->
                assertEquals("\"\"",js(w,"AndroidStorage.readFile('../${other.id}/workspace/data/audit-secret.txt')"))
                assertEquals("\"\"",js(w,"AndroidStorage.readFile('../../${other.id}/workspace/data/audit-secret.txt')"))
                assertEquals("\"AUDIT-ONLY\"",js(w,"AndroidStorage.readFile('data/audit.txt')"))
            }
        } finally { repo.delete(other.id) }
    }
    @Test fun disconnectedAiRejectsWithoutGeneration() = fixture { _,r ->
        val ai=(ctx.applicationContext as AppAiApplication).ai
        org.junit.Assume.assumeFalse("No live credentials allowed",ai.registry.exposed().connected)
        page(r,false) { w ->
            js(w,"window.audit=null;AppAI.onmessage=e=>window.audit=e.data;AppAI.postMessage(JSON.stringify({id:'audit',action:'start',data:{providerId:'chatgpt',model:'audit-nonexistent',input:'audit'}}))")
            val result=waitFor(w,"window.audit"); println("AUDIT disconnected $result"); assertTrue(result.contains("error"))
        }
    }
    private fun zip(vararg entries: Pair<String,ByteArray>): ByteArray {
        val out=ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (name,bytes) -> z.putNextEntry(ZipEntry(name)); z.write(bytes);z.closeEntry() } }
        return out.toByteArray()
    }
    private fun rejects(bytes: ByteArray) = fixture { _,r ->
        val wm=WorkspaceManager(ctx,workspaceDir=r.workspaceDir)
        val h=WorkspaceHistoryManager(ctx,appDirectory=r.directory)
        val result=WorkspacePackageManager.importFromInputStream(ByteArrayInputStream(bytes),wm,h,false)
        println("AUDIT zip rejected=${result.isFailure} reason=${result.exceptionOrNull()?.message}")
        assertTrue(result.isFailure);assertEquals("AUDIT-ONLY",File(r.workspaceDir,"data/audit.txt").readText())
    }
    @Test fun zipTraversalRejected() = rejects(zip("index.html" to "ok".toByteArray(),"../escape.txt" to "test".toByteArray()))
    @Test fun zipOversizeRejected() = rejects(zip("index.html" to ByteArray(8*1024*1024+1)))
    @Test fun zipEntryCountRejected() = rejects(zip(*Array(1025) { "file$it.txt" to byteArrayOf(1) }))
    @Test fun runtimeBlocksExternalNavigation() = fixture { _,r -> page(r,false) { w ->
        js(w,"location.href='https://example.com/'")
        SystemClock.sleep(1500)
        assertEquals("\"https://${r.id}.app.local\"",js(w,"location.origin"))
    } }
    @Test fun editorAllowsExternalNavigationWithBridge() = fixture { _,r -> page(r,true) { w ->
        js(w,"location.href='https://example.com/'")
        val end=SystemClock.uptimeMillis()+20000
        var result=""
        while(SystemClock.uptimeMillis()<end) {
            result=js(w,"location.origin")
            if(result=="\"https://example.com\"") break
            SystemClock.sleep(200)
        }
        assertEquals("\"https://example.com\"",result)
        val read=js(w,"typeof AndroidStorage==='object'?AndroidStorage.readFile('data/audit.txt'):'NO-BRIDGE'")
        println("AUDIT editor external read=$read")
        assertEquals("\"AUDIT-ONLY\"",read)
    } }
}
