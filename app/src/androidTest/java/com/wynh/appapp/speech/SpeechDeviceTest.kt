package com.wynh.appapp.speech

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wynh.appapp.MainActivity
import com.wynh.appapp.R
import com.wynh.appapp.SubappRuntimeActivity
import com.wynh.appapp.workspace.SubappRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SpeechDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun js(web: SpeechWebView, source: String): String {
        val latch = CountDownLatch(1); var result = ""
        instrumentation.runOnMainSync { web.evaluateJavascript(source) { result = it; latch.countDown() } }
        assertTrue(latch.await(10, TimeUnit.SECONDS)); return result
    }
    private fun await(web: SpeechWebView, expression: String): String {
        val until = SystemClock.uptimeMillis() + 20000
        while (SystemClock.uptimeMillis() < until) {
            val value = js(web, expression)
            if (value != "null" && value != "false") return value
            SystemClock.sleep(100)
        }
        fail("Timed out: $expression"); return ""
    }
    @Test fun speechApiInEditorAndStandalone() {
        val context = instrumentation.targetContext
        val repository = SubappRepository(context); repository.ensureMigrated()
        val record = repository.createStarter()
        try {
            for (path in listOf("index.html", "js/bridge.js", "js/speech-demo.js")) {
                val expected = context.assets.open("starter_template/$path").use { it.readBytes() }
                val actual = java.io.File(record.workspaceDir, path).readBytes()
                assertArrayEquals("Fresh fixture must use packaged code: $path", expected, actual)
                println("Fixture SHA256 $path: " + java.security.MessageDigest.getInstance("SHA-256").digest(actual).joinToString("") { "%02x".format(it) })
            }
            val intents = listOf(
                Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SUBAPP_ID, record.id),
                Intent(context, SubappRuntimeActivity::class.java).setData(Uri.parse("appapp://subapp/${record.id}"))
            )
            for (intent in intents) {
                ActivityScenario.launch<androidx.appcompat.app.AppCompatActivity>(intent).use { scenario ->
                    lateinit var web: SpeechWebView
                    scenario.onActivity { activity ->
                        web = if (activity is MainActivity) activity.findViewById(R.id.web_view)
                        else findWeb(activity.findViewById(android.R.id.content))!!
                    }
                    await(web, "location.hostname.endsWith('.app.local') && document.readyState === 'complete' && !!document.getElementById('speech-status') && !!window.AppSpeech")
                    js(web, "import(location.origin+'/js/bridge.js').then(m=>window.testSpeech=m.speech).catch(e=>window.testSpeechError=e.message)")
                    assertEquals("\"ready\"", await(web, "window.testSpeech ? 'ready' : window.testSpeechError || null"))
                    selection(web, "<span lang='fr-FR'>Bonjour</span>", "fr-FR")
                    selection(web, "<span lang='fr-FR'>Bonjour</span><span lang='de-DE'>Hallo</span>", "")
                    selection(web, "<span>Inherited language</span>", "en")
                    selection(web, "<span lang=''>Unannotated</span>", "")
                    selection(web, "<input lang='es-ES' value='Hola'>", "es-ES")
                    selection(web, "<textarea lang='fr-FR'>Bonjour</textarea>", "fr-FR")
                    js(web, "window.voiceResult=null;testSpeech.voices().then(v=>voiceResult=v).catch(e=>voiceResult={error:e.code})")
                    val voices = await(web, "window.voiceResult")
                    assertTrue(voices, voices.contains("voices"))
                    println("Speech voice count: " + com.google.gson.JsonParser.parseString(voices).asJsonObject.getAsJsonArray("voices").size())
                    for ((language, text) in listOf("fr-FR" to "Bonjour", "de-DE" to "Guten Tag", "ja-JP" to "こんにちは")) {
                        js(web, "window.pronunciationResult=null;testSpeech.speak({text:${com.google.gson.Gson().toJson(text)},language:'$language'}).then(v=>pronunciationResult=v.status).catch(e=>pronunciationResult=e.code)")
                        val result = await(web, "window.pronunciationResult")
                        println("Pronunciation $language: $result")
                        assertTrue(result, result in listOf("\"completed\"", "\"missing_data\"", "\"language\"", "\"synthesis\"", "\"network\""))
                    }
                    js(web, "window.invalidResult=null;testSpeech.speak({text:'Hello',language:''}).catch(e=>invalidResult=e.code)")
                    assertEquals("\"invalid_request\"", await(web, "window.invalidResult"))
                    js(web, "window.stopResult=null;testSpeech.stop().then(v=>stopResult=v)")
                    assertEquals("true", await(web, "window.stopResult"))
                    // A long utterance must be released by Stop even if synthesis is still starting.
                    js(web, "window.playResult=null;testSpeech.speak({text:'Hello. '.repeat(300),language:'en-US'}).then(v=>playResult=v.status).catch(e=>playResult=e.code);setTimeout(()=>testSpeech.stop(),200)")
                    val playback = await(web, "window.playResult")
                    println("Stop result: $playback")
                    assertTrue(playback, playback in listOf("\"cancelled\"", "\"completed\"", "\"missing_data\"", "\"language\"", "\"synthesis\"", "\"network\""))
                    js(web, "window.nativeStopResult=null;testSpeech.speak({text:'Hello. '.repeat(300),language:'en-US'}).then(v=>nativeStopResult=v.status).catch(e=>nativeStopResult=e.code)")
                    SystemClock.sleep(300)
                    scenario.onActivity { activity ->
                        activity.findViewById<android.view.View>(com.google.android.material.R.id.snackbar_action)?.performClick()
                    }
                    val nativeStop = await(web, "window.nativeStopResult")
                    assertEquals("\"cancelled\"", nativeStop)
                    js(web, "window.replacedResult=null;testSpeech.speak({text:'Hello. '.repeat(300),language:'en-US'}).then(v=>replacedResult=v.status).catch(e=>replacedResult=e.code);testSpeech.speak({text:'Hello',language:'en-US'}).catch(()=>{})")
                    val replaced = await(web, "window.replacedResult")
                    assertTrue(replaced, replaced in listOf("\"cancelled\"", "\"missing_data\"", "\"language\"", "\"synthesis\"", "\"network\""))
                    js(web, "window.backgroundResult=null;testSpeech.speak({text:'Hello. '.repeat(300),language:'en-US'}).then(v=>backgroundResult=v.status).catch(e=>backgroundResult=e.code)")
                    scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                    scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                    val background = await(web, "window.backgroundResult")
                    assertTrue(background, background in listOf("\"cancelled\"", "\"unavailable\"", "\"missing_data\"", "\"language\"", "\"synthesis\"", "\"network\""))
                }
            }
        } finally { repository.delete(record.id) }
    }

    private fun selection(web: SpeechWebView, html: String, language: String) {
        js(web, "var fixture=document.getElementById('speech-fixture')||document.body.appendChild(document.createElement('div'));fixture.id='speech-fixture';fixture.innerHTML=${com.google.gson.Gson().toJson(html)};var range=document.createRange();range.selectNodeContents(fixture);var sel=window.getSelection();sel.removeAllRanges();sel.addRange(range);var input=fixture.querySelector('input,textarea');if(input){input.focus();input.setSelectionRange(0,input.value.length)}")
        val latch = CountDownLatch(1); var captured = ""
        instrumentation.runOnMainSync {
            val original = web.speakSelection
            web.speakSelection = { captured = it; web.speakSelection = original; latch.countDown() }
            val mode = web.startActionMode(object : android.view.ActionMode.Callback {
                override fun onCreateActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean {
                    menu.add("Copy"); return true
                }
                override fun onPrepareActionMode(mode: android.view.ActionMode, menu: android.view.Menu) = false
                override fun onActionItemClicked(mode: android.view.ActionMode, item: android.view.MenuItem) = false
                override fun onDestroyActionMode(mode: android.view.ActionMode) { }
            }, android.view.ActionMode.TYPE_FLOATING)!!
            assertTrue((0 until mode.menu.size()).any { mode.menu.getItem(it).title == "Copy" })
            val speak = (0 until mode.menu.size()).map { mode.menu.getItem(it) }.single { it.title == "Speak" }
            assertTrue(mode.menu.performIdentifierAction(speak.itemId, 0))
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        val result = com.google.gson.JsonParser.parseString(captured).asJsonObject
        assertEquals(language, result.get("language").asString)
        assertTrue(result.get("text").asString.isNotBlank())
    }
    private fun findWeb(view: android.view.View): SpeechWebView? {
        if (view is SpeechWebView) return view
        if (view is android.view.ViewGroup) for (index in 0 until view.childCount) findWeb(view.getChildAt(index))?.let { return it }
        return null
    }
}
