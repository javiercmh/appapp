package com.wynh.appapp.ai

import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import com.wynh.appapp.SubappRuntimeActivity
import com.wynh.appapp.ai.network.InferenceRepository
import com.wynh.appapp.ai.network.ModelChoice
import com.wynh.appapp.grants.SubappCapability
import com.wynh.appapp.grants.SubappGrants
import com.wynh.appapp.workspace.SubappRepository
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Drives the real LingoMage app (its own pages, bridge.js and DOM actions) through a Continue with a
 * fake provider only. LingoMage is not part of this repository: stage a test package on the disposable
 * emulator and pass its path, otherwise the test is skipped.
 *
 *   1. Build a ZIP of LingoMage's manifest `files` with `app2Update.seedDefaults` empty (as App²'s
 *      scripts/package_subapp.py does) and `adb push` it, for example to /data/local/tmp/lingomage-test.zip.
 *   2. adb shell am instrument -w -e class com.wynh.appapp.ai.LingoMageContinuationDeviceTest \
 *        -e lingomageZip /data/local/tmp/lingomage-test.zip com.wynh.appapp.test/androidx.test.runner.AndroidJUnitRunner
 *
 * The test imports the ZIP as a new disposable native app, seeds its own saved story and settings (never
 * touching installed learner apps), and deletes the app afterwards.
 */
@RunWith(AndroidJUnit4::class)
class LingoMageContinuationDeviceTest : AiDeviceFixture() {
    /** Minimal valid German responses by request kind, with gates the test can hold. */
    private class LingoFake : InferenceRepository {
        val kinds = CopyOnWriteArrayList<String>()
        val entered = java.util.concurrent.ConcurrentHashMap<String, CountDownLatch>().also { map ->
            listOf("summary", "language-plan", "continue", "notes").forEach { map[it] = CountDownLatch(1) }
        }
        val gates = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        /** Checks run inside generate(): what must already be durable before this kind can be requested. */
        val violations = CopyOnWriteArrayList<String>()
        var beforeCall: (String) -> Unit = {}

        override suspend fun models() = listOf(ModelChoice("fake", "Fake"))
        override suspend fun check(model: String) = "ok"
        override suspend fun generate(model: String, instructions: String, input: String, effort: String?): String {
            val kind = JsonParser.parseString(input).asJsonObject.get("kind").asString
            kinds += kind
            beforeCall(kind)
            entered[kind]?.countDown()
            gates[kind]?.await()
            return when (kind) {
                "summary" -> """{"characters":["Anna"],"facts":["Anna shops at the market."],"plot":["Anna goes to the market."],"unresolved":[],"uncertainties":[],"situation":["At the market."]}"""
                "language-plan" -> """{"vocabulary":[{"word":"Brot","meaning":"bread"}],"grammar":[],"guidance":"Clear references."}"""
                "continue" -> """{"title":"Kapitel 2","story":"Anna isst das Brot zu Hause."}"""
                "notes" -> """{"glossary":[{"word":"Brot","meaning":"bread","forms":["Brot"]}]}"""
                else -> throw IllegalStateException("Unexpected request kind $kind")
            }
        }
    }

    private val story = """{"id":"it-story","language":"de","title":"Der Markt","activeVersionId":"v1","topic":"","practice":"","difficulty":"A1","createdAt":"2026-01-01T00:00:00.000Z",
        "chapters":[{"id":"c1","activeVersionId":"v1","versions":[{"id":"v1","title":"Kapitel 1","story":"Anna geht zum Markt. Sie kauft frisches Brot.","glossary":[],"difficulty":"A1","scope":"scene","createdAt":"2026-01-01T00:00:00.000Z"}],"prompt":"","help":[]}],
        "paths":[{"id":"original","name":"Original","entries":[{"chapterId":"c1","versionId":"v1"}]}],"activePathId":"original","nextAlternateNumber":1}"""
    // LingoMage deliberately never auto-selects an arbitrary native model, so the saved choice is explicit.
    private val settings = """{"providerId":"chatgpt","model":"fake","targetLanguages":{"de":"enabled"},"standardizedLevels":{"de":"A1"},"readingPreferences":{"followAlong":false}}"""

    private fun js(web: android.webkit.WebView, source: String): String {
        val latch = CountDownLatch(1); var value = ""
        instrumentation.runOnMainSync { web.evaluateJavascript(source) { value = it; latch.countDown() } }
        assertTrue(latch.await(10, TimeUnit.SECONDS)); return value
    }
    private fun await(web: android.webkit.WebView, expression: String, what: String, seconds: Long = 30) {
        val end = SystemClock.uptimeMillis() + seconds * 1000
        while (SystemClock.uptimeMillis() < end) {
            if (js(web, "(()=>{try{return !!($expression)}catch(e){return false}})()") == "true") return
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out waiting for the LingoMage page: $what")
    }
    private fun web(view: android.view.View): android.webkit.WebView? {
        if (view is android.webkit.WebView) return view
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) web(view.getChildAt(i))?.let { return it }
        return null
    }

    /** Copies a file the shell can read (for example under /data/local/tmp) into the test's cache. */
    private fun pullFromShell(path: String, into: File): File {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat $path")).use { input ->
            into.outputStream().use { input.copyTo(it) }
        }
        return into
    }

    private fun unzip(zip: File, into: File) {
        val root = into.canonicalFile
        java.util.zip.ZipInputStream(zip.inputStream()).use { stream ->
            generateSequence { stream.nextEntry }.forEach { entry ->
                val target = File(root, entry.name).canonicalFile
                require(target.path.startsWith(root.path + File.separator)) { "Unsafe path in test package: ${entry.name}" }
                if (entry.isDirectory) target.mkdirs() else { target.parentFile.mkdirs(); target.outputStream().use { stream.copyTo(it) } }
            }
        }
        if (!File(root, "index.html").isFile) root.listFiles()?.singleOrNull { File(it, "index.html").isFile }?.let { nested ->
            nested.listFiles()?.forEach { it.renameTo(File(root, it.name)) }
        }
    }

    private fun read(workspace: File, name: String) = File(workspace, name).takeIf { it.isFile }?.readText().orEmpty()

    @Test fun continueRunsSummaryPlanProseAndNotesAcrossBackgroundingWithOneServiceStart() {
        val path = InstrumentationRegistry.getArguments().getString("lingomageZip")
        assumeTrue("Pass -e lingomageZip <path of a staged LingoMage test package> to run this test", !path.isNullOrBlank())
        val temp = File(context.cacheDir, "lingomage-it-${System.nanoTime()}").apply { mkdirs() }
        val repository = SubappRepository(context).also { it.ensureMigrated() }
        var appId: String? = null
        try {
            val zip = pullFromShell(path!!, File(temp, "package.zip"))
            assumeTrue("The staged package is missing or empty: $path", zip.length() > 0)
            val source = File(temp, "source").apply { mkdirs() }
            unzip(zip, source)
            val record = repository.createImportedFrom(source)
            appId = record.id
            SubappGrants.setGranted(context, record.id, SubappCapability.AI, true)
            val workspace = record.workspaceDir
            File(workspace, "data").mkdirs()
            File(workspace, "data/stories.json").writeText("[$story]")
            File(workspace, "data/settings.json").writeText(settings)
            File(workspace, "data/generation-checkpoints.json").writeText("[]")

            val lingo = LingoFake(); useProvider(lingo)
            lingo.gates["summary"] = CompletableDeferred(); lingo.gates["language-plan"] = CompletableDeferred()
            // What is durable when a stage's request starts proves each result was saved before the NEXT request
            // began. It does not show the order of save versus acknowledge; that stronger invariant is covered by
            // LingoMage's pipeline ordering tests (tests/merged-pipeline-regression.mjs, learning-safeguards.mjs).
            lingo.beforeCall = { kind ->
                when (kind) {
                    "language-plan" -> if (!read(workspace, "data/stories.json").contains("\"memories\"")) lingo.violations += "summary not saved before the plan request"
                    "continue" -> if (!read(workspace, "data/generation-checkpoints.json").contains("\"plan\"")) lingo.violations += "plan not checkpointed before the prose request"
                    "notes" -> if (!read(workspace, "data/generation-checkpoints.json").contains("Anna isst das Brot")) lingo.violations += "prose not checkpointed before the notes request"
                }
            }
            val counting = CountingStarter(manager.foregroundStarter); manager.foregroundStarter = counting

            ActivityScenario.launch<SubappRuntimeActivity>(runtimeIntent(record.id)).use { scenario ->
                lateinit var page: android.webkit.WebView
                scenario.onActivity { page = web(it.findViewById(android.R.id.content))!! }
                await(page, "document.getElementById('model-select') && document.getElementById('model-select').value === 'fake'", "the app to finish start-up and list the fake model")
                // Real app actions: Library, open the story, Continue, submit.
                js(page, "document.querySelector('[data-view=\"library\"]').click()")
                await(page, "document.querySelector('.story-open')", "the saved story")
                js(page, "document.querySelector('.story-open').click()")
                await(page, "document.getElementById('reader-dialog').open", "the reader")
                js(page, "document.getElementById('continue-story').click()")
                await(page, "!document.getElementById('reader-edit-panel').hidden", "the Continue panel")
                js(page, "document.getElementById('reader-edit-submit').click()")

                assertTrue("The summary request starts first", lingo.entered.getValue("summary").await(30, TimeUnit.SECONDS))
                shell("input keyevent KEYCODE_HOME")
                eventually("the activity to stop") { if (!scenario.state.isAtLeast(Lifecycle.State.RESUMED)) true else null }
                SystemClock.sleep(1500)                                         // a gap with the summary held and the app away
                lingo.gates.getValue("summary").complete(Unit)
                assertTrue("The plan starts after the summary while the app is backgrounded", lingo.entered.getValue("language-plan").await(30, TimeUnit.SECONDS))
                assertFalse("Still backgrounded when the plan started", scenario.state.isAtLeast(Lifecycle.State.RESUMED))
                SystemClock.sleep(1500)                                         // and again with the plan held
                lingo.gates.getValue("language-plan").complete(Unit)

                eventually("the final chapter to be saved") {
                    if (read(workspace, "data/stories.json").contains("Anna isst das Brot zu Hause.") && read(workspace, "data/generation-checkpoints.json").trim() == "[]") true else null
                }
                assertTrue("Prose was requested", lingo.entered.getValue("continue").await(5, TimeUnit.SECONDS))
                assertTrue("Notes were requested", lingo.entered.getValue("notes").await(5, TimeUnit.SECONDS))
                assertEquals("Exact request order, no duplicate inference", listOf("summary", "language-plan", "continue", "notes"), lingo.kinds.toList())
                assertEquals("One service start for the whole operation", 1, counting.starts.get())
                assertEquals(emptyList<String>(), lingo.violations.toList())
                assertFalse("The activity stayed in the background throughout", scenario.state.isAtLeast(Lifecycle.State.RESUMED))
                val saved = JsonParser.parseString(read(workspace, "data/stories.json")).asJsonArray[0].asJsonObject
                assertEquals("The story has the original and the new chapter", 2, saved.getAsJsonArray("chapters").size())
                assertEquals("The checkpoint is cleared after the chapter is saved", "[]", read(workspace, "data/generation-checkpoints.json").trim())
                eventually("every request released and the session ended") {
                    if (!manager.hasRequest(record.id) && manager.liveSessionId(record.id) == null) true else null
                }
                eventually("the service stops") { serviceStopped() }
            }
        } finally {
            appId?.let { runCatching { repository.delete(it) } }
            temp.deleteRecursively()
        }
    }
}
