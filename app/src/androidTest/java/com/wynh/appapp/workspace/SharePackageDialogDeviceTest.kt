package com.wynh.appapp.workspace

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.button.MaterialButton
import com.wynh.appapp.R
import com.wynh.appapp.SubappLibraryActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class SharePackageDialogDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun eachSelectionSharesOnlyItsFiles() {
        val repository = SubappRepository(context).also { it.ensureMigrated() }
        val record = repository.createStarter()
        val userFile = "personal note.txt"
        val dataFile = "data/entries.json"
        File(record.workspaceDir, userFile).writeText("disposable fixture")
        val allFiles = WorkspaceManager(context, workspaceDir = record.workspaceDir).listFiles().map { it.name }.toSet()
        val chooser = LinkedBlockingQueue<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CHOOSER) return null
                chooser.offer(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch(SubappLibraryActivity::class.java).use { scenario ->
                fun share(option: Int, summary: Int): Uri {
                    scenario.onActivity {
                        val dialog = it.showPackageDialog(record)
                        assertNotNull(dialog.findViewById<MaterialButton>(R.id.package_btn_share_zip).icon)
                    }
                    onView(withId(R.id.package_both_files)).inRoot(isDialog()).check(matches(isChecked()))
                    if (option != R.id.package_both_files) onView(withId(option)).inRoot(isDialog()).perform(click())
                    onView(withId(R.id.package_selection_summary)).inRoot(isDialog()).check(matches(withText(summary)))
                    onView(withId(R.id.package_btn_share_zip)).inRoot(isDialog()).perform(click())
                    val intent = checkNotNull(chooser.poll(15, TimeUnit.SECONDS)) { "No ZIP Sharesheet opened" }
                    instrumentation.waitForIdleSync()
                    @Suppress("DEPRECATION")
                    val send = checkNotNull(intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
                    @Suppress("DEPRECATION")
                    return checkNotNull(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
                }
                fun entries(uri: Uri) = ZipInputStream(checkNotNull(context.contentResolver.openInputStream(uri))).use { zip ->
                    buildSet {
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            add(entry.name)
                            zip.closeEntry()
                        }
                    }
                }
                val wholeUri = share(R.id.package_both_files, R.string.package_both_summary)
                val appUri = share(R.id.package_app_files, R.string.package_app_summary)
                val userUri = share(R.id.package_user_files, R.string.package_user_summary)
                assertEquals(3, setOf(wholeUri, appUri, userUri).size)
                assertEquals(allFiles, entries(wholeUri))
                val app = entries(appUri)
                val user = entries(userUri)
                assertTrue("index.html" in app)
                assertTrue("css/style.css" in app)
                assertTrue(user.containsAll(listOf(userFile, dataFile)))
                assertTrue(app.intersect(user).isEmpty())
                assertEquals(allFiles, app + user)
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            repository.delete(record.id)
        }
    }
}
