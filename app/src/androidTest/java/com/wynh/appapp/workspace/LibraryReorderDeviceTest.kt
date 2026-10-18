package com.wynh.appapp.workspace

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wynh.appapp.R
import com.wynh.appapp.SubappLibraryActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryReorderDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun fixture(test: (SubappRepository, List<SubappRecord>, ActivityScenario<SubappLibraryActivity>) -> Unit) {
        val context = instrumentation.targetContext
        val repository = SubappRepository(context)
        repository.ensureMigrated()
        val journal = java.io.File(context.cacheDir, "library-reorder-test.json")
        if (journal.isFile) {
            val ids = org.json.JSONArray(journal.readText())
            for (number in 0 until ids.length()) repository.delete(ids.getString(number))
            journal.delete()
        }
        val previous = repository.list().map { it.id }
        val records = (0..9).map { index -> repository.createStarter().also { record ->
            AppIdentityManager(context, record).save("Drag test $index", if (index % 2 == 0) "Short" else "A longer description that wraps onto two lines on a narrow phone", AppIdentityManager.IconChange.Keep)
        } }
        journal.writeText(org.json.JSONArray(records.map { it.id }).toString())
        repository.reorder(records.map { it.id } + previous)
        val scenario = ActivityScenario.launch<SubappLibraryActivity>(Intent(context, SubappLibraryActivity::class.java))
        try { instrumentation.waitForIdleSync(); test(repository, records, scenario) }
        finally {
            scenario.close()
            records.forEach { repository.delete(it.id) }
            repository.reorder(previous)
            journal.delete()
        }
    }
    private fun centers(scenario: ActivityScenario<SubappLibraryActivity>, positions: List<Int>): List<Pair<Float, Float>> {
        var result = emptyList<Pair<Float, Float>>()
        scenario.onActivity { activity ->
            val list = activity.findViewById<RecyclerView>(R.id.app_list)
            result = positions.map { position ->
                val view = list.findViewHolderForAdapterPosition(position)!!.itemView
                val location = IntArray(2); view.getLocationOnScreen(location)
                // Card body above its buttons.
                location[0] + view.width * 0.4f to location[1] + view.height * 0.3f
            }
        }
        return result
    }
    private fun drag(from: Pair<Float, Float>, to: Pair<Float, Float>, cancel: Boolean = false, holdAtEnd: Long = 0, reverse: Boolean = false) {
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float, y: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            instrumentation.sendPointerSync(event); event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, from.first, from.second)
        SystemClock.sleep(700)
        repeat(24) { index ->
            val fraction = (index + 1) / 24f
            send(MotionEvent.ACTION_MOVE, from.first + (to.first - from.first) * fraction, from.second + (to.second - from.second) * fraction)
            SystemClock.sleep(20)
        }
        if (holdAtEnd > 0) SystemClock.sleep(holdAtEnd)
        if (reverse) repeat(24) { index ->
            val fraction = (index + 1) / 24f
            send(MotionEvent.ACTION_MOVE, to.first + (from.first - to.first) * fraction, to.second + (from.second - to.second) * fraction)
            SystemClock.sleep(20)
        }
        val end = if (reverse) from else to
        send(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, end.first, end.second)
        SystemClock.sleep(400)
        instrumentation.waitForIdleSync()
    }
    @Test fun adjacentMovesBothDirectionsPersistAndCancellationRestoresOrder() = fixture { repository, records, scenario ->
        val points = centers(scenario, listOf(1, 2))
        drag(points[0], points[1].first to points[1].second + 40f)
        assertEquals(listOf(records[1].id, records[0].id), repository.list().take(2).map { it.id })
        val reversed = centers(scenario, listOf(1, 2))
        drag(reversed[1], reversed[0].first to reversed[0].second - 40f)
        assertEquals(records.take(2).map { it.id }, repository.list().take(2).map { it.id })
        val cancelled = centers(scenario, listOf(1, 2))
        drag(cancelled[0], cancelled[1].first to cancelled[1].second + 40f, cancel = true)
        assertEquals(records.map { it.id }, repository.list().take(10).map { it.id })
        val reversal = centers(scenario, listOf(1, 2))
        drag(reversal[0], reversal[1].first to reversal[1].second + 40f, reverse = true)
        assertEquals(records.map { it.id }, repository.list().take(10).map { it.id })
        scenario.recreate()
        instrumentation.waitForIdleSync()
        scenario.onActivity { activity ->
            val list = activity.findViewById<RecyclerView>(R.id.app_list)
            assertEquals("Drag test 0", list.findViewHolderForAdapterPosition(1)!!.itemView.findViewById<android.widget.TextView>(R.id.app_name).text.toString())
        }
    }
    @Test fun holdingAtBottomScrollsAndMovesAcrossSeveralRows() = fixture { repository, records, scenario ->
        var bottom = 0f
        scenario.onActivity { activity ->
            val list = activity.findViewById<RecyclerView>(R.id.app_list)
            val location = IntArray(2); list.getLocationOnScreen(location)
            bottom = location[1] + list.height - 12f
        }
        val start = centers(scenario, listOf(1))[0]
        drag(start, start.first to bottom, holdAtEnd = 1800)
        assertTrue(repository.list().indexOfFirst { it.id == records[0].id } >= 3)
    }
    @Test fun failedPersistenceRestoresDisplayedAndSavedOrder() = fixture { repository, records, scenario ->
        val directory = java.io.File(instrumentation.targetContext.filesDir, "subapps")
        assertTrue(directory.setWritable(false, false))
        try {
            val points = centers(scenario, listOf(1, 2))
            drag(points[0], points[1].first to points[1].second + 40f)
            assertEquals(records.map { it.id }, repository.list().take(10).map { it.id })
            scenario.onActivity { activity ->
                val first = activity.findViewById<RecyclerView>(R.id.app_list).findViewHolderForAdapterPosition(1)!!.itemView
                assertEquals("Drag test 0", first.findViewById<android.widget.TextView>(R.id.app_name).text.toString())
            }
        } finally { assertTrue(directory.setWritable(true, true)) }
    }

    @Test fun settingsExportAndRestoreUseDocumentContracts() = fixture { repository, _, scenario ->
        val context = instrumentation.targetContext
        val before = repository.list().map { it.id }
        val file = java.io.File(context.cacheDir, "exports/library-ui-test.zip")
        file.parentFile!!.mkdirs()
        file.writeBytes(byteArrayOf())
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        fun picker(action: String) = instrumentation.addMonitor(android.content.IntentFilter(action).apply { addCategory(Intent.CATEGORY_OPENABLE); addDataType("*/*") },
            android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK, Intent().setData(uri)), true)
        fun settings() {
            scenario.onActivity { activity -> activity.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.library_toolbar).menu.performIdentifierAction(R.id.action_settings, 0) }
            instrumentation.waitForIdleSync()
        }
        fun completion() {
            val deadline = SystemClock.uptimeMillis() + 15_000
            while (true) {
                try {
                    androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(R.string.backup_complete)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                        .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
                    break
                } catch (error: androidx.test.espresso.NoMatchingViewException) {
                    if (SystemClock.uptimeMillis() > deadline) throw error
                    SystemClock.sleep(100)
                }
            }
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(android.R.id.button1)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                .perform(androidx.test.espresso.action.ViewActions.click())
        }
        val export = picker(Intent.ACTION_CREATE_DOCUMENT)
        try {
            settings()
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.settings_backup_export)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                .perform(androidx.test.espresso.action.ViewActions.click())
            completion()
            assertEquals(1, export.hits)
            assertTrue(file.length() > 0)
        } finally { instrumentation.removeMonitor(export) }
        val restore = picker(Intent.ACTION_OPEN_DOCUMENT)
        try {
            settings()
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.settings_backup_restore)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                .perform(androidx.test.espresso.action.ViewActions.click())
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(android.R.id.button1)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                .perform(androidx.test.espresso.action.ViewActions.click())
            completion()
            assertEquals(1, restore.hits)
            assertEquals(before.size * 2, repository.list().size)
            assertEquals(before, repository.list().take(before.size).map { it.id })
        } finally {
            instrumentation.removeMonitor(restore)
            repository.list().filter { it.id !in before }.forEach { repository.delete(it.id) }
            file.delete()
        }
    }

    @Test fun accessibilityMoveDownUsesSamePersistedOrder() = fixture { repository, records, scenario ->
        scenario.onActivity { activity ->
            val card = activity.findViewById<RecyclerView>(R.id.app_list).findViewHolderForAdapterPosition(1)!!.itemView
            val info = card.createAccessibilityNodeInfo()
            val action = info.actionList.first { it.label?.toString() == activity.getString(R.string.library_move_down) }
            assertTrue(card.performAccessibilityAction(action.id, null))
        }
        // Accessibility actions enqueue persistence on I/O; main-loop idleness is not completion.
        val deadline = SystemClock.uptimeMillis() + 10_000
        var busy = true
        while (busy && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { activity -> busy = androidx.lifecycle.ViewModelProvider(activity)[LibraryController::class.java].task.value.busy }
            if (busy) SystemClock.sleep(20)
        }
        assertFalse("Reorder did not finish", busy)
        assertEquals(listOf(records[1].id, records[0].id), repository.list().take(2).map { it.id })
    }
}
