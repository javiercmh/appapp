package com.wynh.appapp.updates

import android.app.Dialog
import android.content.Intent
import android.os.SystemClock
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.RootMatchers.isDialog
import com.google.gson.Gson
import com.wynh.appapp.R
import com.wynh.appapp.SubappLibraryActivity
import com.wynh.appapp.workspace.SubappRecord
import com.wynh.appapp.workspace.SubappRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class VersionUpdatesDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val source = GitHubSource("fixture", "updates", "main")
    private val commit = "c".repeat(40)
    private class Response(url: URL, private val body: String) : HttpURLConnection(url) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = 200
        override fun getInputStream() = body.byteInputStream()
    }
    private fun link(record: SubappRecord, version: String) {
        File(record.workspaceDir, "manifest.json").writeText("""{"name":"Update UX fixture","version":"$version"}""")
        File(record.directory, "update-state.json").writeText(Gson().toJson(InstalledUpdate("test.fixture", version, source, "a".repeat(40), emptyMap(), emptySet())))
    }
    private fun waitUntil(predicate: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 10_000
        while (!predicate()) { check(SystemClock.uptimeMillis() < until) { "Timed out waiting for update UI" }; SystemClock.sleep(30) }
        instrumentation.waitForIdleSync()
    }

    @Test fun compactSummarySharesRequestsAndKeepsIndicatorsOnFailure() {
        val root = File(context.cacheDir, "version-check-device-test").apply { deleteRecursively(); mkdirs() }
        val records = (1..2).map { index -> SubappRecord("version-fixture-$index", File(root, "$index")).also {
            it.workspaceDir.mkdirs(); link(it, "$index.0")
        } }
        val fail = AtomicBoolean(false)
        val urls = CopyOnWriteArrayList<String>()
        val checker = VersionUpdateChecker { url ->
            urls.add(url.toString())
            if (fail.get()) throw java.net.UnknownHostException("fixture offline")
            Response(url, when {
                url.path.endsWith("/releases/latest") -> """{"draft":false,"prerelease":false,"tag_name":"v999.0"}"""
                url.host == "api.github.com" -> """{"sha":"$commit"}"""
                else -> """{"version":"2.0"}"""
            })
        }
        val scenario = ActivityScenario.launch<SubappLibraryActivity>(Intent(context, SubappLibraryActivity::class.java))
        lateinit var dialog: Dialog
        try {
            scenario.onActivity { activity ->
                dialog = Dialog(activity).apply { setContentView(R.layout.dialog_library_settings) }
                val controller = SettingsUpdateController(activity, dialog, { records }, {}, {}, checker)
                dialog.setOnDismissListener { controller.close() }
                dialog.show()
                dialog.findViewById<View>(R.id.settings_check_updates).performClick()
            }
            waitUntil { var enabled = false; scenario.onActivity { enabled = dialog.findViewById<View>(R.id.settings_check_updates).isEnabled }; enabled }
            scenario.onActivity {
                assertEquals(View.VISIBLE, dialog.findViewById<View>(R.id.settings_view_release).visibility)
                assertEquals(View.VISIBLE, dialog.findViewById<View>(R.id.settings_review_updates).visibility)
                assertEquals(View.VISIBLE, dialog.findViewById<View>(R.id.settings_subapp_update_status).visibility)
                assertNotNull(SubappUpdateIndicators.of(context).available(records[0]))
                assertNull(SubappUpdateIndicators.of(context).available(records[1]))
                assertEquals(3, urls.size) // host + one shared commit lookup and manifest
                fail.set(true)
                dialog.findViewById<View>(R.id.settings_check_updates).performClick()
            }
            waitUntil { var enabled = false; scenario.onActivity { enabled = dialog.findViewById<View>(R.id.settings_check_updates).isEnabled }; enabled }
            scenario.onActivity {
                assertEquals(View.GONE, dialog.findViewById<View>(R.id.settings_view_release).visibility)
                assertTrue(dialog.findViewById<View>(R.id.settings_check_updates).isEnabled)
                assertNotNull(SubappUpdateIndicators.of(context).available(records[0]))
                dialog.dismiss()
            }
        } finally {
            scenario.close()
            records.forEach { SubappUpdateIndicators.of(context).clear(it.id) }
            root.deleteRecursively()
        }
    }

    @Test fun badgeSurvivesRecreationAndHighlightsOnlyTheMarkedAppsMenu() {
        val repository = SubappRepository(context)
        repository.ensureMigrated()
        val original = repository.list().map { it.id }
        val records = (1..2).map { repository.createStarter().also { record -> link(record, "1.0") } }
        repository.reorder(records.map { it.id } + original)
        val store = SubappUpdateIndicators.of(context)
        store.record(records[0].id, VersionUpdateChecker.PublishedVersion(source, commit, "2.0"))
        val scenario = ActivityScenario.launch<SubappLibraryActivity>(Intent(context, SubappLibraryActivity::class.java))
        try {
            fun assertBadgesAndOpen() {
                waitUntil { var ready = false; scenario.onActivity { ready = it.findViewById<RecyclerView>(R.id.app_list).findViewHolderForAdapterPosition(2) != null }; ready }
                scenario.onActivity { activity ->
                    val list = activity.findViewById<RecyclerView>(R.id.app_list)
                    val marked = list.findViewHolderForAdapterPosition(1)!!.itemView
                    val plain = list.findViewHolderForAdapterPosition(2)!!.itemView
                    assertEquals(View.VISIBLE, marked.findViewById<View>(R.id.update_dot).visibility)
                    assertEquals(View.GONE, plain.findViewById<View>(R.id.update_dot).visibility)
                    assertNotEquals(plain.findViewById<View>(R.id.app_menu).contentDescription, marked.findViewById<View>(R.id.app_menu).contentDescription)
                    marked.findViewById<View>(R.id.app_menu).performClick()
                }
                onView(withId(R.id.update_menu_label)).check(matches(isDisplayed()))
                onView(withId(R.id.update_menu_label)).check { view, error ->
                    if (error != null) throw error
                    assertTrue((view as android.widget.TextView).typeface.isBold)
                }
                pressBack()
            }
            assertBadgesAndOpen()
            scenario.recreate()
            assertBadgesAndOpen()
        } finally {
            scenario.close()
            records.forEach { store.clear(it.id); repository.delete(it.id) }
            repository.reorder(original)
        }
    }
    @Test fun guidedReviewsSkipUpdateAndStopWithoutChangingUserFiles() {
        val root = File(context.cacheDir, "guided-update-device-test").apply { deleteRecursively(); mkdirs() }
        fun manifest(version: String) = """{"name":"Review fixture","version":"$version","app2Update":{"appId":"test.review","minimumHostVersionCode":1,"github":{"owner":"fixture","repository":"updates","branch":"main"},"managedFiles":["index.html","manifest.json"],"seedFiles":[]}}"""
        val initial = File(root, "initial").apply { mkdirs() }
        File(initial, "manifest.json").writeText(manifest("1.0"))
        File(initial, "index.html").writeText("old code")
        val records = (1..3).map { index -> SubappRecord("guided-fixture-$index", File(root, "$index")).also { record ->
            record.workspaceDir.mkdirs()
            File(record.workspaceDir, "index.html").writeText("fixture")
            WorkspaceUpdater(record.directory).install(UpdatePackage.parse(manifest("1.0")), initial, "a".repeat(40), 100)
            File(record.workspaceDir, "data").mkdirs()
            File(record.workspaceDir, "data/records.json").writeText("personal fixture records")
        } }
        val indicators = SubappUpdateIndicators.of(context)
        records.forEach { indicators.record(it.id, VersionUpdateChecker.PublishedVersion(source, commit, "2.0")) }
        val downloader = GitHubUpdateDownloader(context.cacheDir) { url -> Response(url,
            if (url.path.endsWith("manifest.json")) manifest("2.0") else "new code") }
        val scenario = ActivityScenario.launch<SubappLibraryActivity>(Intent(context, SubappLibraryActivity::class.java))
        try {
            scenario.onActivity { it.reviewUpdates(records, downloader) }
            waitUntil { runCatching { onView(withId(R.id.update_review_confirm)).inRoot(isDialog()).check(matches(isDisplayed())) }.isSuccess }
            onView(withId(R.id.update_review_secondary)).inRoot(isDialog()).perform(click())
            instrumentation.waitForIdleSync()
            waitUntil { runCatching { onView(withId(R.id.update_review_confirm)).inRoot(isDialog()).check(matches(isDisplayed())) }.isSuccess }
            assertEquals("1.0", WorkspaceUpdater(records[0].directory).currentVersion())
            onView(withId(R.id.update_review_confirm)).inRoot(isDialog()).perform(click())
            waitUntil { WorkspaceUpdater(records[1].directory).currentVersion() == "2.0" }
            instrumentation.waitForIdleSync()
            waitUntil { runCatching { onView(withId(R.id.update_review_confirm)).inRoot(isDialog()).check(matches(isDisplayed())) }.isSuccess }
            onView(withId(R.id.update_review_cancel)).inRoot(isDialog()).perform(click())
            assertNotNull(indicators.available(records[0]))
            assertNull(indicators.available(records[1]))
            assertNotNull(indicators.available(records[2]))
            assertEquals("new code", File(records[1].workspaceDir, "index.html").readText())
            records.forEach { assertEquals("personal fixture records", File(it.workspaceDir, "data/records.json").readText()) }
        } finally {
            scenario.close()
            records.forEach { indicators.clear(it.id) }
            root.deleteRecursively()
        }
    }

}
