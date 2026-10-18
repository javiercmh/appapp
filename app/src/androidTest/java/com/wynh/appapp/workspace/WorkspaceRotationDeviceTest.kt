package com.wynh.appapp.workspace

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wynh.appapp.MainActivity
import com.wynh.appapp.R
import com.wynh.appapp.updates.WorkspaceLocks
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class WorkspaceRotationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun savedEditorBaselineIsDeliveredAfterRotationDuringDiskWork() {
        val repository = SubappRepository(context); repository.ensureMigrated()
        val record = repository.createStarter()
        val release = CountDownLatch(1)
        var blocker: Thread? = null
        try {
            WorkspaceFiles.regularFiles(record.workspaceDir).filter { it.name !in setOf("index.html", "manifest.json") }.forEach { it.delete() }
            File(record.workspaceDir, "index.html").writeText("<!doctype html><p>Rotation fixture</p>")
            File(record.workspaceDir, "note.txt").writeText("initial")
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SUBAPP_ID, record.id)).use { scenario ->
                onView(withText("note.txt")).perform(scrollTo(), click())
                onView(withId(R.id.editor_code_input)).perform(replaceText("saved after rotation"), closeSoftKeyboard())
                val entered = CountDownLatch(1)
                blocker = thread(name = "test-workspace-lock") {
                    synchronized(WorkspaceLocks.forWorkspace(record.workspaceDir)) {
                        entered.countDown(); check(release.await(15, TimeUnit.SECONDS))
                    }
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                // The UI queues disk work, which is deliberately held until after recreation.
                onView(withId(R.id.editor_btn_save)).perform(click())
                lateinit var retained: WorkspaceActionController
                scenario.onActivity { retained = ViewModelProvider(it)[WorkspaceActionController::class.java] }
                assertTrue(retained.state.value.busy)
                scenario.recreate()
                release.countDown()
                val until = SystemClock.uptimeMillis() + 15_000
                while (SystemClock.uptimeMillis() < until) {
                    instrumentation.waitForIdleSync()
                    if (!retained.state.value.busy && retained.editorDraft?.initial == "saved after rotation") break
                    SystemClock.sleep(20)
                }
                assertFalse(retained.state.value.message, retained.state.value.error)
                assertEquals("saved after rotation", retained.editorDraft?.initial)
                assertEquals("saved after rotation", File(record.workspaceDir, "note.txt").readText())
                instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                    java.io.FileOutputStream(File(context.cacheDir, "rotation-completed.png")).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                onView(withId(R.id.editor_code_input)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check(androidx.test.espresso.assertion.ViewAssertions.matches(withText("saved after rotation")))
                androidx.test.espresso.Espresso.pressBack()
                instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                    java.io.FileOutputStream(File(context.cacheDir, "rotation-after-back.png")).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                lateinit var activityDecor: android.view.View
                scenario.onActivity { activityDecor = it.window.decorView }
                onView(withId(R.id.hub_btn_run)).inRoot(androidx.test.espresso.matcher.RootMatchers.withDecorView(org.hamcrest.Matchers.`is`(activityDecor))).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
            }
        } finally { release.countDown(); blocker?.join(2000); repository.delete(record.id) }
    }
    @Test fun androidBackAsksBeforeDiscardingAnUnsavedDraft() {
        val repository = SubappRepository(context); repository.ensureMigrated()
        val record = repository.createStarter()
        try {
            WorkspaceFiles.regularFiles(record.workspaceDir).filter { it.name !in setOf("index.html", "manifest.json") }.forEach { it.delete() }
            File(record.workspaceDir, "index.html").writeText("<!doctype html><p>Back fixture</p>")
            File(record.workspaceDir, "note.txt").writeText("initial")
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SUBAPP_ID, record.id)).use {
                onView(withText("note.txt")).perform(scrollTo(), click())
                onView(withId(R.id.editor_code_input)).perform(replaceText("unsaved draft"), closeSoftKeyboard())
                systemBack()
                onView(withText(R.string.discard_changes_title)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                    .check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
                instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                    java.io.FileOutputStream(File(context.cacheDir, "back-discard-confirmation.png")).use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
                assertEquals("initial", File(record.workspaceDir, "note.txt").readText())
                onView(withText(R.string.keep_editing)).perform(click())
                onView(withId(R.id.editor_code_input)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                    .check(androidx.test.espresso.assertion.ViewAssertions.matches(withText("unsaved draft")))
                assertEquals("initial", File(record.workspaceDir, "note.txt").readText())
            }
        } finally { repository.delete(record.id) }
    }

    /**
     * Sends the system Back action. On API 33+ with gesture navigation this is a real edge swipe, which reaches the
     * dialog's OnBackInvokedCallback; elsewhere (API 26-32, or 2/3-button navigation) it falls back to the system Back key.
     */
    private fun systemBack() {
        val gestural = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            Settings.Secure.getInt(context.contentResolver, "navigation_mode", 0) == 2
        if (!gestural) {
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            return
        }
        // Start inside the left back-gesture inset and travel well past the commit threshold.
        val metrics = context.resources.displayMetrics
        val y = metrics.heightPixels / 2
        val swipe = instrumentation.uiAutomation.executeShellCommand(
            "input swipe 2 $y ${metrics.widthPixels / 2} $y 350"
        )
        // Reading to EOF waits for the shell command, so the gesture has finished before the caller asserts.
        android.os.ParcelFileDescriptor.AutoCloseInputStream(swipe).use { it.readBytes() }
        instrumentation.waitForIdleSync()
    }
}
