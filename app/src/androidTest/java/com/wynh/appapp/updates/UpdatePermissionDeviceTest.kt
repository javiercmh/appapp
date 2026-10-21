package com.wynh.appapp.updates

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.*
import com.wynh.appapp.R
import org.hamcrest.Matchers.allOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wynh.appapp.SubappLibraryActivity
import com.wynh.appapp.grants.SubappCapability
import com.wynh.appapp.grants.SubappGrants
import com.wynh.appapp.workspace.SubappRecord
import com.wynh.appapp.workspace.SubappRepository
import kotlinx.coroutines.launch
import org.hamcrest.Matchers.containsString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Drives the real [UpdateController] review dialog with a locally staged package (no GitHub
 * networking) against a disposable library app, and checks that cancelling or choosing
 * "Update anyway" for a changed publisher never changes the app's grants or persisted reminders.
 */
@RunWith(AndroidJUnit4::class)
class UpdatePermissionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val source = GitHubSource("test", "app", "main")
    private val sha = "b".repeat(40)

    private fun stage(version: String, publisher: String): GitHubUpdateDownloader.Download {
        val dir = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "staged-update-").toFile()
        val manifest = """{"name":"Disposable","version":"$version","app2Update":{"appId":"$publisher","minimumHostVersionCode":1,"github":{"owner":"test","repository":"app","branch":"main"},"managedFiles":["index.html","manifest.json"],"seedFiles":[]}}"""
        File(dir, "manifest.json").writeText(manifest)
        File(dir, "index.html").writeText("<!doctype html><p>version $version</p>")
        return GitHubUpdateDownloader.Download(UpdatePackage.parse(manifest), sha, dir)
    }

    private fun screenshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            java.io.FileOutputStream(File(context.cacheDir, name)).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun waitFor(message: String, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < until) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(50)
        }
        fail(message)
    }

    private fun alarms(id: String) = context.getSharedPreferences("runtime_scheduled_alarms_$id", Context.MODE_PRIVATE)
    private fun posted(id: String) = context.getSharedPreferences("runtime_notifications_$id", Context.MODE_PRIVATE)

    @Test fun reviewCancelAndUpdateAnywayKeepGrantsAndPersistedReminders() {
        val repository = SubappRepository(context); repository.ensureMigrated()
        val record: SubappRecord = repository.createStarter()
        try {
            val engine = WorkspaceUpdater(record.directory)
            val first = stage("1", "test.device")
            engine.install(first.pkg, first.directory, sha, 1)
            first.directory.deleteRecursively()

            SubappGrants.setGranted(context, record.id, SubappCapability.NOTIFICATIONS, true)
            SubappGrants.setGranted(context, record.id, SubappCapability.NETWORK, true)
            val reminder = JSONObject().put("7", JSONObject().put("at", System.currentTimeMillis() + 3_600_000).put("title", "Reminder").put("message", "Persisted"))
            check(alarms(record.id).edit().putString("runtime_scheduled_alarms", reminder.toString()).commit())
            check(posted(record.id).edit().putStringSet("ids", setOf("7")).commit())
            val grantsBefore = SubappGrants.store(context).granted(record.id)
            val remindersBefore = alarms(record.id).getString("runtime_scheduled_alarms", null)
            val postedBefore = posted(record.id).getStringSet("ids", null)
            assertEquals(setOf(SubappCapability.NOTIFICATIONS, SubappCapability.NETWORK), grantsBefore)
            assertTrue(com.wynh.appapp.bridge.NativeStorageBridge.hasPersistedAlarms(context, record.id))

            fun assertUntouched() {
                assertEquals(grantsBefore, SubappGrants.store(context).granted(record.id))
                assertFalse(SubappGrants.isGranted(context, record.id, SubappCapability.AI))
                assertEquals(remindersBefore, alarms(record.id).getString("runtime_scheduled_alarms", null))
                assertEquals(postedBefore, posted(record.id).getStringSet("ids", null))
            }

            ActivityScenario.launch(SubappLibraryActivity::class.java).use { scenario ->
                fun openReview(download: GitHubUpdateDownloader.Download) {
                    // review() returns once the dialog is shown, so wait for that instead of polling the UI.
                    val shown = CountDownLatch(1)
                    var failure: Throwable? = null
                    scenario.onActivity { activity ->
                        activity.lifecycleScope.launch {
                            try { UpdateController(activity, record).review(download, source) }
                            catch (t: Throwable) { failure = t }
                            finally { shown.countDown() }
                        }
                    }
                    assertTrue("review dialog was not shown", shown.await(15, TimeUnit.SECONDS))
                    failure?.let { throw it }
                    instrumentation.waitForIdleSync()
                    onView(withText("Review app update")).inRoot(isDialog()).check(matches(isDisplayed()))
                    // The message scrolls on its own, so its start is shown while both choices stay fully visible.
                    onView(withText(containsString("The publisher identity or source differs"))).inRoot(isDialog()).check(matches(isDisplayed()))
                    onView(withId(R.id.update_review_message)).inRoot(isDialog())
                        .check(matches(withText(containsString("Existing permissions are kept. Current permissions: Notifications, Internet. Permissions that are off stay off."))))
                    onView(withId(R.id.update_review_confirm)).inRoot(isDialog()).check(matches(allOf(isCompletelyDisplayed(), withText("Update anyway"), isEnabled())))
                    onView(withId(R.id.update_review_cancel)).inRoot(isDialog()).check(matches(allOf(isCompletelyDisplayed(), withText(android.R.string.cancel), isEnabled())))
                }

                // Cancel: nothing changes and the staged download is removed.
                val cancelled = stage("2", "other.device")
                openReview(cancelled)
                screenshot("update-identity-warning.png")
                onView(withId(R.id.update_review_cancel)).inRoot(isDialog()).perform(click())
                waitFor("staged download was not removed") { !cancelled.directory.exists() }
                assertTrue(File(record.workspaceDir, "index.html").readText().contains("version 1"))
                assertEquals("test.device", engine.installed()!!.appId)
                assertUntouched()

                // Update anyway: new code and identity are installed; every decision and reminder survives.
                val accepted = stage("2", "other.device")
                openReview(accepted)
                onView(withId(R.id.update_review_confirm)).inRoot(isDialog()).perform(click())
                waitFor("update did not finish") { File(record.workspaceDir, "index.html").readText().contains("version 2") && engine.installed()?.appId == "other.device" }
                assertUntouched()
                screenshot("update-identity-accepted.png")
            }
            assertUntouched()
        } finally { repository.delete(record.id) }
    }
}
