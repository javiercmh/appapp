package com.wynh.appapp.workspace

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.swipeUp
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wynh.appapp.R
import com.wynh.appapp.SubappLibraryActivity
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class AppBackupDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun configurePreservesAndSavesDraftAcrossRecreation() = withApp { record, scenario ->
        scenario.onActivity { it.showConfigure(record) }
        onView(withId(R.id.config_input_app_name)).inRoot(isDialog()).perform(replaceText("Backup fixture"), closeSoftKeyboard())
        onView(withId(R.id.config_input_app_desc)).inRoot(isDialog()).perform(replaceText("Restored draft"), closeSoftKeyboard())
        scenario.recreate()
        onView(withId(R.id.config_input_app_name)).inRoot(isDialog()).check(matches(withText("Backup fixture")))
        onView(withId(R.id.config_input_app_desc)).inRoot(isDialog()).check(matches(withText("Restored draft")))
        onView(withId(R.id.config_btn_save)).inRoot(isDialog()).perform(click())
        await("configuration saved") {
            AppIdentityManager(context, record).load().let {
                it.name == "Backup fixture" && it.description == "Restored draft" &&
                    !WorkspaceOperations.isBusy(record.workspaceDir)
            }
        }
    }

    @Test fun backupsExportShareAndRestoreVersions() = withApp { record, scenario ->
        val fixtures = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "backup-test-").toFile()
        val index = File(record.workspaceDir, "index.html")
        val original = index.readText()
        val data = File(record.workspaceDir, "data/entries.json")
        val originalData = data.readText()
        val editedData = """[{"text":"recent personal entry"}]"""
        val personalNote = File(record.workspaceDir, "personal-note.txt")
        val asset = File(record.workspaceDir, "fixture.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val history = WorkspaceHistoryManager(context, appDirectory = record.directory)
        val export = File(fixtures, "export.zip")
        val edited = "<p>Workspace edited after the snapshot</p>"
        val repository = SubappRepository(context)

        try {
            val checkpointCount = history.getSnapshots().size
            scenario.onActivity { it.showAppBackup(record, "Backup fixture") }
            onView(withText("Import from device")).inRoot(isDialog()).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
            onView(withText(R.string.app_backup_versions)).inRoot(isDialog()).check(matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
            onView(withId(R.id.history_save_version)).inRoot(isDialog()).perform(click())
            await("manual version saved") { history.getSnapshots().size == checkpointCount + 1 }
            await("saved version displayed") {
                instrumentation.uiAutomation.rootInActiveWindow
                    ?.findAccessibilityNodeInfosByText("Saved version")?.isNotEmpty() == true
            }
            onView(withId(R.id.backup_close)).inRoot(isDialog()).perform(click())

            index.writeText(edited)
            data.writeText(editedData)
            personalNote.writeText("keep me")

            withResult(Intent.ACTION_CREATE_DOCUMENT, export) {
                chooseBackup(record, scenario, R.string.app_backup_export)
                await("app exported") { runCatching { zipEntries(Uri.fromFile(export)).containsKey("fixture.bin") }.getOrDefault(false) }
            }
            val exported = zipEntries(Uri.fromFile(export))
            assertArrayEquals(asset.readBytes(), exported["fixture.bin"])
            val chooser = java.util.concurrent.atomic.AtomicReference<Intent>()
            val monitor = object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    if (intent.action != Intent.ACTION_CHOOSER) return null
                    chooser.set(intent)
                    return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                }
            }
            instrumentation.addMonitor(monitor)
            try {
                chooseBackup(record, scenario, R.string.library_share)
                onView(withId(R.id.package_btn_share_zip)).inRoot(isDialog()).perform(click())
                await("Android Sharesheet requested") { chooser.get() != null }
                @Suppress("DEPRECATION")
                val send = checkNotNull(chooser.get().getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
                @Suppress("DEPRECATION")
                val sharedZip = checkNotNull(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
                assertEquals("application/zip", send.type)
                assertArrayEquals(asset.readBytes(), zipEntries(sharedZip)["fixture.bin"])
                onView(withId(R.id.backup_close)).inRoot(isDialog())
                    .check(matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
            } finally {
                instrumentation.removeMonitor(monitor)
            }

            scenario.onActivity { it.showAppBackup(record, "Backup fixture") }
            await("saved version displayed") {
                instrumentation.uiAutomation.rootInActiveWindow
                    ?.findAccessibilityNodeInfosByText("Saved version")?.isNotEmpty() == true
            }
            val savedVersionRow = allOf(withId(R.id.history_snapshot_row), hasDescendant(withId(R.id.history_btn_restore)),
                hasDescendant(withText(containsString("Saved version"))))
            val restore = onView(allOf(withId(R.id.history_btn_restore), isDescendantOfA(savedVersionRow))).inRoot(isDialog())
            restore.perform(click())
            val personal = onView(withId(R.id.restore_personal_files)).inRoot(isDialog())
            personal.check(matches(androidx.test.espresso.matcher.ViewMatchers.isNotChecked()))
            onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
            await("version restored and previous bytes snapshotted") {
                !WorkspaceOperations.isBusy(record.workspaceDir) && index.readText() == original &&
                    history.getSnapshots().any { it.label.startsWith("Before restoring") && it.files["index.html"] == edited }
            }
            assertEquals(editedData, data.readText())
            assertEquals("keep me", personalNote.readText())
            assertArrayEquals(byteArrayOf(1, 2, 3), asset.readBytes())

            await("backup dialog reloaded") {
                instrumentation.uiAutomation.rootInActiveWindow
                    ?.findAccessibilityNodeInfosByText("Saved version")?.isNotEmpty() == true
            }
            restore.perform(click())
            personal.perform(click())
            onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
            await("personal files restored by request") {
                !WorkspaceOperations.isBusy(record.workspaceDir) && data.readText() == originalData && !personalNote.exists()
            }
            assertArrayEquals(byteArrayOf(1, 2, 3), asset.readBytes())
            onView(withId(R.id.backup_close)).inRoot(isDialog()).perform(click())

            val before = repository.list().map { it.id }.toSet()
            scenario.onActivity {
                androidx.lifecycle.ViewModelProvider(it)[LibraryController::class.java].importZip(Uri.fromFile(export))
            }
            await("backup opened as a new app") { repository.list().any { it.id !in before } }
            val imported = repository.list().single { it.id !in before }
            try {
                assertEquals(edited, File(imported.workspaceDir, "index.html").readText())
                assertEquals(original, index.readText())
            } finally {
                repository.delete(imported.id)
            }
        } finally {
            fixtures.deleteRecursively()
        }
    }

    @Test fun backupDialogSurvivesCanceledExportAndBackingOutOfShare() = withApp { record, scenario ->
        chooseBackup(record, scenario, R.string.library_share)
        onView(withId(R.id.package_btn_share_zip)).inRoot(isDialog())
            .check(matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
        pressBack()
        val backupClose = onView(withId(R.id.backup_close)).inRoot(isDialog())
        backupClose.check(matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))

        withResult(Intent.ACTION_CREATE_DOCUMENT, null) {
            onView(withText(R.string.app_backup_export)).inRoot(isDialog()).perform(click())
            backupClose.check(matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
        }
        scenario.recreate()
        backupClose.perform(click())
        onView(withText(R.string.app_backup_title))
            .check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
    }

    @Test fun longVersionHistoryScrollsWithBackupActionsVisible() = withApp { record, scenario ->
        val history = WorkspaceHistoryManager(context, appDirectory = record.directory)
        val manager = WorkspaceManager(context, workspaceDir = record.workspaceDir)
        repeat(5) { index ->
            assertTrue(manager.writeFile("index.html", "<p>Fixture version $index</p>"))
            assertNotNull(history.saveSnapshot(manager, "Fixture version $index"))
        }
        scenario.onActivity { it.showAppBackup(record, "Backup fixture") }
        await("versions loaded") {
            instrumentation.uiAutomation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByText("Fixture version 4")?.isNotEmpty() == true
        }
        val scroll = onView(withId(R.id.backup_version_scroll)).inRoot(isDialog())
        scroll.check { view, _ -> assertTrue(view.canScrollVertically(1)) }
        scroll.perform(swipeUp())
        scroll.check { view, _ -> assertTrue(view.canScrollVertically(-1)) }
        for (action in listOf(R.string.history_save_version, R.string.app_backup_export, R.string.library_share)) {
            onView(withText(action)).inRoot(isDialog())
                .check(matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
        }
    }

    private fun withApp(test: (SubappRecord, ActivityScenario<SubappLibraryActivity>) -> Unit) {
        val repository = SubappRepository(context)
        repository.ensureMigrated()
        val record = repository.createStarter()
        try {
            ActivityScenario.launch(SubappLibraryActivity::class.java).use { test(record, it) }
        } finally {
            repository.delete(record.id)
        }
    }

    private fun chooseBackup(record: SubappRecord, scenario: ActivityScenario<SubappLibraryActivity>, action: Int) {
        scenario.onActivity { it.showAppBackup(record, "Backup fixture") }
        onView(withText(action)).inRoot(isDialog()).perform(click())
    }

    private fun withResult(action: String, file: File?, block: () -> Unit) {
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                when {
                    intent.action != action -> null
                    file == null -> Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                    else -> Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(file)))
                }
        }
        instrumentation.addMonitor(monitor)
        try {
            block()
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun zipEntries(uri: Uri): Map<String, ByteArray> {
        val input = checkNotNull(context.contentResolver.openInputStream(uri))
        return ZipInputStream(input).use { zip ->
            buildMap {
                while (true) {
                    val entry = zip.nextEntry ?: break
                    put(entry.name, zip.readBytes())
                    zip.closeEntry()
                }
            }
        }
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        fail("Timed out waiting for $description")
    }
}
