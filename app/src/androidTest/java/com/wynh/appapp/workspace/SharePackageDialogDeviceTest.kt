package com.wynh.appapp.workspace

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.checkbox.MaterialCheckBox
import com.wynh.appapp.R
import com.wynh.appapp.SubappLibraryActivity
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.not
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses a disposable starter app and the real Share & Package dialog; exports nothing. */
@RunWith(AndroidJUnit4::class)
class SharePackageDialogDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun exportButtonsComeFirstAndFileSelectionSurvivesCollapsing() {
        val repository = SubappRepository(context); repository.ensureMigrated()
        val record = repository.createStarter()
        try {
            val total = WorkspaceManager(context, workspaceDir = record.workspaceDir).listFiles().size
            assertTrue(total > 1)
            ActivityScenario.launch(SubappLibraryActivity::class.java).use { scenario ->
                lateinit var dialog: android.app.Dialog
                scenario.onActivity { dialog = it.showPackageDialog(record) }

                // Collapsed by default, with both actions visible above the files toggle.
                onView(withId(R.id.package_files_section)).inRoot(isDialog()).check(matches(not(isDisplayed())))
                onView(withText(R.string.action_share_zip)).inRoot(isDialog()).check(matches(isDisplayed()))
                onView(withText(R.string.action_save_zip)).inRoot(isDialog()).check(matches(isDisplayed()))
                onView(withId(R.id.package_files_toggle_label)).inRoot(isDialog())
                    .check(matches(withText(context.getString(R.string.package_files_toggle, total, total))))
                scenario.onActivity {
                    val share = dialog.findViewById<View>(R.id.package_btn_share_zip)
                    val save = dialog.findViewById<View>(R.id.package_btn_save_zip)
                    val toggle = dialog.findViewById<View>(R.id.package_files_toggle_row)
                    val density = it.resources.displayMetrics.density
                    fun top(view: View) = IntArray(2).also(view::getLocationOnScreen)[1]
                    assertTrue(top(share) < top(save) && top(save) < top(toggle))
                    assertTrue(share.height >= 48 * density && save.height >= 48 * density)
                }

                // Deselect one file while expanded, collapse, expand again: the selection is intact.
                onView(withId(R.id.package_files_toggle_row)).inRoot(isDialog()).perform(click())
                onView(withId(R.id.package_files_section)).inRoot(isDialog()).check(matches(isDisplayed()))
                val row = allOf(withId(R.id.export_item_root), hasDescendant(withText("index.html")))
                val box = allOf(withId(R.id.export_item_checkbox), isDescendantOfA(row))
                onView(row).inRoot(isDialog()).perform(scrollTo(), click())
                onView(box).inRoot(isDialog()).check(matches(isNotChecked()))
                onView(withId(R.id.package_files_toggle_label)).inRoot(isDialog())
                    .check(matches(withText(context.getString(R.string.package_files_toggle, total - 1, total))))
                onView(withId(R.id.package_files_toggle_row)).inRoot(isDialog()).perform(click())
                onView(withId(R.id.package_files_section)).inRoot(isDialog()).check(matches(not(isDisplayed())))
                onView(withId(R.id.package_files_toggle_label)).inRoot(isDialog())
                    .check(matches(withText(context.getString(R.string.package_files_toggle, total - 1, total))))
                onView(withId(R.id.package_files_toggle_row)).inRoot(isDialog()).perform(click())
                onView(box).inRoot(isDialog()).perform(scrollTo()).check(matches(isNotChecked()))
                scenario.onActivity {
                    assertEquals(MaterialCheckBox.STATE_INDETERMINATE, dialog.findViewById<MaterialCheckBox>(R.id.package_master_checkbox).checkedState)
                    dialog.dismiss()
                }
            }
        } finally { repository.delete(record.id) }
    }
}
