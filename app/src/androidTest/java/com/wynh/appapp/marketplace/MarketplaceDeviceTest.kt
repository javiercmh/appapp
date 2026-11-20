package com.wynh.appapp.marketplace

import android.content.Intent
import android.os.SystemClock
import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.withDecorView
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.wynh.appapp.R
import com.wynh.appapp.SubappLibraryActivity
import com.wynh.appapp.updates.GitHubSubappInstaller
import com.wynh.appapp.updates.WorkspaceUpdater
import com.wynh.appapp.workspace.LibraryController
import com.wynh.appapp.workspace.SubappRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class MarketplaceDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private class FakeResponse(
        url: URL,
        private val body: ByteArray,
        private val status: Int = 200,
    ) : HttpURLConnection(url) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream() = body.inputStream()
        override fun getErrorStream() = if (status >= 400) body.inputStream() else null
    }

    private fun waitUntil(message: String = "Timed out waiting for condition", predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000L
        while (!predicate()) {
            check(SystemClock.uptimeMillis() < deadline) { message }
            SystemClock.sleep(40)
        }
        instrumentation.waitForIdleSync()
    }

    @Test
    fun tabsSwitchBetweenYourAppsAndDiscoverAndPersistAcrossRecreation() {
        ActivityScenario.launch<SubappLibraryActivity>(Intent(context, SubappLibraryActivity::class.java)).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.app_list).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.discover_list).visibility)
                val refreshItem = activity.findViewById<MaterialToolbar>(R.id.library_toolbar).menu.findItem(R.id.action_refresh_catalog)
                assertFalse(refreshItem.isVisible)
            }

            onView(withId(R.id.nav_discover)).perform(click())
            instrumentation.waitForIdleSync()

            waitUntil("Discover cards did not bind") {
                var ready = false
                scenario.onActivity { activity ->
                    val list = activity.findViewById<RecyclerView>(R.id.discover_list)
                    ready = list.visibility == View.VISIBLE &&
                        list.adapter?.itemCount == 5 &&
                        list.findViewHolderForAdapterPosition(1) != null
                }
                ready
            }

            scenario.onActivity { activity ->
                assertEquals(View.GONE, activity.findViewById<View>(R.id.app_list).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.discover_list).visibility)
                val refreshItem = activity.findViewById<MaterialToolbar>(R.id.library_toolbar).menu.findItem(R.id.action_refresh_catalog)
                assertTrue(refreshItem.isVisible)
                activity.findViewById<RecyclerView>(R.id.discover_list)
                    .findViewHolderForAdapterPosition(1)!!.itemView
                    .findViewById<View>(R.id.catalog_card).performClick()
            }

            waitUntil("Detail bottom sheet did not appear") {
                runCatching { onView(withId(R.id.detail_primary_action)).check(matches(isDisplayed())) }.isSuccess
            }
            onView(withId(R.id.detail_source_button)).check(matches(isDisplayed()))
            onView(withId(R.id.detail_spec_category)).check(matches(isDisplayed()))
            onView(withId(R.id.detail_spec_uses)).check(matches(isDisplayed()))
            onView(withId(R.id.detail_spec_third_value)).check(matches(isDisplayed()))
            onView(withId(R.id.detail_more_action)).check(matches(withEffectiveVisibility(Visibility.GONE)))
            pressBack()
            instrumentation.waitForIdleSync()

            scenario.recreate()
            instrumentation.waitForIdleSync()

            scenario.onActivity { activity ->
                assertEquals(R.id.nav_discover, activity.findViewById<BottomNavigationView>(R.id.library_bottom_nav).selectedItemId)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.discover_list).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.app_list).visibility)
            }
        }
    }

    @Test
    fun installingBundledStarterStaysInDiscoverAndMarksCardInstalled() {
        val repository = SubappRepository(context)
        repository.ensureMigrated()
        val beforeIds = repository.list().map { it.id }
        val testCacheRoot = File(context.cacheDir, "marketplace-starter-device-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val customCatalogJson = """
            {
              "schemaVersion": 1,
              "apps": [
                {
                  "id": "starter-fixture",
                  "name": "Starter Fixture",
                  "author": "Built-in",
                  "category": "Template",
                  "license": "MIT",
                  "summary": "Fixture starter entry",
                  "description": "Disposable starter fixture",
                  "capabilities": ["template"],
                  "offlineOnly": true,
                  "minHostCompatibility": 1,
                  "iconKey": "starter_demo",
                  "source": {"type": "bundled_starter"}
                }
              ]
            }
        """.trimIndent()

        val scenario = ActivityScenario.launch<SubappLibraryActivity>(Intent(context, SubappLibraryActivity::class.java))
        try {
            scenario.onActivity { activity ->
                val controller = ViewModelProvider(activity)[LibraryController::class.java]
                controller.catalogRepositoryOverride = MarketplaceCatalogRepository(
                    cacheRoot = testCacheRoot,
                    bundledVersionCode = 1,
                    readBundled = { customCatalogJson },
                )
                controller.loadMarketplaceLocal()
            }

            onView(withId(R.id.nav_discover)).perform(click())
            instrumentation.waitForIdleSync()

            waitUntil("Custom fixture card did not appear") {
                var ready = false
                scenario.onActivity { activity ->
                    val list = activity.findViewById<RecyclerView>(R.id.discover_list)
                    ready = list.findViewHolderForAdapterPosition(1)?.itemView
                        ?.findViewById<View>(R.id.catalog_action)?.isEnabled == true
                }
                ready
            }

            scenario.onActivity { activity ->
                activity.findViewById<RecyclerView>(R.id.discover_list)
                    .findViewHolderForAdapterPosition(1)!!.itemView
                    .findViewById<View>(R.id.catalog_action).performClick()
            }

            waitUntil("Starter installation did not complete") {
                val afterIds = repository.list().map { it.id }
                var busy = true
                scenario.onActivity { activity ->
                    busy = ViewModelProvider(activity)[LibraryController::class.java].task.value.busy
                }
                !busy && afterIds.size == beforeIds.size + 1
            }

            val created = repository.list().first { it.id !in beforeIds }
            val provenance = File(created.directory, "import-source.json").readText(Charsets.UTF_8)
            assertTrue("Missing catalogId in provenance", "\"catalogId\":\"starter-fixture\"" in provenance)

            // Must stay on SubappLibraryActivity / Discover and expose Edit code inside the overflow menu.
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.discover_list).visibility)
                activity.findViewById<RecyclerView>(R.id.discover_list)
                    .findViewHolderForAdapterPosition(1)!!.itemView
                    .findViewById<View>(R.id.catalog_card).performClick()
            }

            waitUntil("Detail sheet for installed starter did not show overflow menu button") {
                runCatching { onView(withId(R.id.detail_more_action)).check(matches(isDisplayed())) }.isSuccess
            }
            onView(withId(R.id.detail_source_button)).check(matches(withEffectiveVisibility(Visibility.GONE)))
            onView(withId(R.id.detail_primary_action)).check(matches(isEnabled()))
            onView(withId(R.id.detail_copies_note)).check(matches(withEffectiveVisibility(Visibility.GONE)))
            onView(withId(R.id.detail_more_action)).perform(click())
            val popupRoot = withDecorView(hasDescendant(withId(R.id.detail_menu_configure)))
            waitUntil("Overflow menu did not open") {
                runCatching {
                    onView(withId(R.id.detail_menu_configure)).inRoot(popupRoot).check(matches(isDisplayed()))
                }.isSuccess
            }
            onView(withId(R.id.detail_menu_shortcut)).inRoot(popupRoot).check(matches(isDisplayed()))
            onView(withId(R.id.detail_menu_backup)).inRoot(popupRoot).check(matches(isDisplayed()))
            onView(withId(R.id.detail_menu_permissions)).inRoot(popupRoot).check(matches(isDisplayed()))
            onView(withId(R.id.detail_menu_delete)).inRoot(popupRoot).check(matches(isDisplayed()))
            onView(isRoot()).inRoot(popupRoot).perform(ViewActions.pressBack())
            instrumentation.waitForIdleSync()
            pressBack()

            // Discover never adds a second copy, even when asked directly by the controller. The
            // screen consumes a failed task as soon as it arrives, so check the outcome: the request
            // ran (the task went busy and settled) and the library gained no app.
            val countAfterInstall = repository.list().size
            val settled = CountDownLatch(1)
            var watcher: Job? = null
            scenario.onActivity { activity ->
                val controller = ViewModelProvider(activity)[LibraryController::class.java]
                watcher = CoroutineScope(Dispatchers.Unconfined).launch {
                    var sawBusy = false
                    controller.task.first { task ->
                        if (task.busy) sawBusy = true
                        sawBusy && !task.busy
                    }
                    settled.countDown()
                }
                controller.installCatalogApp(controller.marketplace.value.apps.single())
            }
            assertTrue("Duplicate install request did not run", settled.await(15, TimeUnit.SECONDS))
            watcher?.cancel()
            assertEquals(countAfterInstall, repository.list().size)
        } finally {
            scenario.close()
            repository.list().filter { it.id !in beforeIds }.forEach { repository.delete(it.id) }
            repository.reorder(beforeIds)
            testCacheRoot.deleteRecursively()
        }
    }

    @Test
    fun manualRefreshAndGitHubInstallLinkUpdates() {
        val repository = SubappRepository(context)
        repository.ensureMigrated()
        val beforeIds = repository.list().map { it.id }
        val testRoot = File(context.cacheDir, "marketplace-github-device-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val commit = "e".repeat(40)
        val initialCatalog = """
            {
              "schemaVersion": 1,
              "apps": [
                {
                  "id": "starter-demo",
                  "name": "Starter Demo",
                  "author": "Built-in",
                  "category": "Template",
                  "license": "MIT",
                  "summary": "Starter",
                  "description": "Starter",
                  "capabilities": ["template"],
                  "offlineOnly": true,
                  "minHostCompatibility": 1,
                  "iconKey": "starter_demo",
                  "source": {"type": "bundled_starter"}
                }
              ]
            }
        """.trimIndent()
        val remoteCatalog = """
            {
              "schemaVersion": 1,
              "apps": [
                {
                  "id": "fake-lingo",
                  "name": "Fake Lingo",
                  "author": "tester",
                  "category": "Language",
                  "license": "MIT",
                  "summary": "Remote fixture app",
                  "description": "Tests manual refresh and update-linked GitHub install",
                  "capabilities": ["ai"],
                  "offlineOnly": false,
                  "minHostCompatibility": 4,
                  "iconKey": "lingomage",
                  "source": {
                    "type": "github",
                    "owner": "owner",
                    "repository": "fake-lingo",
                    "branch": "main",
                    "publisherAppId": "com.wynh.fakelingo"
                  }
                }
              ]
            }
        """.trimIndent()
        val appManifest = """
            {
              "name": "Fake Lingo",
              "version": "1.2.0",
              "description": "Remote fixture app",
              "app2Update": {
                "appId": "com.wynh.fakelingo",
                "minimumHostVersionCode": 4,
                "github": {"owner": "owner", "repository": "fake-lingo", "branch": "main"},
                "managedFiles": ["index.html", "manifest.json"],
                "seedFiles": []
              }
            }
        """.trimIndent()

        val fakeCatalogRepo = MarketplaceCatalogRepository(
            cacheRoot = File(testRoot, "catalog"),
            bundledVersionCode = 1,
            readBundled = { initialCatalog },
            connect = { url ->
                if (url.toString().endsWith("/manifest.json")) {
                    FakeResponse(url, appManifest.toByteArray())
                } else {
                    FakeResponse(url, remoteCatalog.toByteArray())
                }
            },
        )
        val fakeInstaller = GitHubSubappInstaller(File(testRoot, "installer"), connect = { url ->
            val path = url.toString()
            when {
                url.host == "api.github.com" && url.path.endsWith("/commits/main") ->
                    FakeResponse(url, commit.toByteArray())
                path.endsWith("/manifest.json") ->
                    FakeResponse(url, appManifest.toByteArray())
                path.endsWith("/index.html") ->
                    FakeResponse(url, "<html><body>ok</body></html>".toByteArray())
                else -> FakeResponse(url, byteArrayOf(), 404)
            }
        })

        val scenario = ActivityScenario.launch<SubappLibraryActivity>(Intent(context, SubappLibraryActivity::class.java))
        try {
            scenario.onActivity { activity ->
                val controller = ViewModelProvider(activity)[LibraryController::class.java]
                controller.catalogRepositoryOverride = fakeCatalogRepo
                controller.subappInstallerOverride = fakeInstaller
                controller.loadMarketplaceLocal()
            }

            onView(withId(R.id.nav_discover)).perform(click())
            instrumentation.waitForIdleSync()

            scenario.onActivity { activity ->
                activity.findViewById<MaterialToolbar>(R.id.library_toolbar)
                    .menu.performIdentifierAction(R.id.action_refresh_catalog, 0)
            }

            waitUntil("Remote catalog did not sync and show sync chip") {
                var synced = false
                scenario.onActivity { activity ->
                    val list = activity.findViewById<RecyclerView>(R.id.discover_list)
                    val header = list.findViewHolderForAdapterPosition(0)?.itemView
                    val syncChip = header?.findViewById<View>(R.id.discover_sync_status)
                    val cardReady = list.findViewHolderForAdapterPosition(1)?.itemView
                        ?.findViewById<View>(R.id.catalog_action)?.isEnabled == true
                    synced = syncChip?.visibility == View.VISIBLE && cardReady
                }
                synced
            }

            scenario.onActivity { activity ->
                activity.findViewById<RecyclerView>(R.id.discover_list)
                    .findViewHolderForAdapterPosition(1)!!.itemView
                    .findViewById<View>(R.id.catalog_action).performClick()
            }

            waitUntil("GitHub catalog app install did not complete") {
                val afterIds = repository.list().map { it.id }
                var busy = true
                scenario.onActivity { activity ->
                    busy = ViewModelProvider(activity)[LibraryController::class.java].task.value.busy
                }
                !busy && afterIds.size == beforeIds.size + 1
            }

            val installed = repository.list().first { it.id !in beforeIds }
            val updateState = WorkspaceUpdater(installed.directory).installed()
            assertNotNull("Expected update-state.json to be written for app2Update repo", updateState)
            assertEquals("com.wynh.fakelingo", updateState!!.appId)
            assertEquals("1.2.0", updateState.version)
            assertEquals(commit, updateState.commit)
            assertTrue("\"catalogId\":\"fake-lingo\"" in File(installed.directory, "import-source.json").readText())
        } finally {
            scenario.close()
            repository.list().filter { it.id !in beforeIds }.forEach { repository.delete(it.id) }
            repository.reorder(beforeIds)
            testRoot.deleteRecursively()
        }
    }

    @Test
    fun severalManualCopiesAreManagedFromYourAppsNotFromTheDetailSheet() {
        val repository = SubappRepository(context)
        repository.ensureMigrated()
        val beforeIds = repository.list().map { it.id }
        val testCacheRoot = File(context.cacheDir, "marketplace-copies-device-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val catalogJson = """
            {
              "schemaVersion": 1,
              "apps": [
                {
                  "id": "copies-fixture",
                  "name": "Copies Fixture",
                  "category": "Template",
                  "license": "MIT",
                  "summary": "Fixture",
                  "description": "Disposable fixture with two copies",
                  "capabilities": ["template"],
                  "offlineOnly": true,
                  "minHostCompatibility": 1,
                  "iconKey": "starter_demo",
                  "source": {"type": "bundled_starter"}
                }
              ]
            }
        """.trimIndent()
        val provenance = """{"catalogId":"copies-fixture"}"""
        val copies = listOf(repository.createStarter(provenance), repository.createStarter(provenance)).map { it.id }

        val scenario = ActivityScenario.launch<SubappLibraryActivity>(Intent(context, SubappLibraryActivity::class.java))
        try {
            scenario.onActivity { activity ->
                val controller = ViewModelProvider(activity)[LibraryController::class.java]
                controller.catalogRepositoryOverride = MarketplaceCatalogRepository(
                    cacheRoot = testCacheRoot,
                    bundledVersionCode = 1,
                    readBundled = { catalogJson },
                )
                controller.loadMarketplaceLocal()
                controller.refresh()
            }
            onView(withId(R.id.nav_discover)).perform(click())

            waitUntil("Card did not show both copies as installed") {
                var ready = false
                scenario.onActivity { activity ->
                    val holder = activity.findViewById<RecyclerView>(R.id.discover_list).findViewHolderForAdapterPosition(1)
                    val controller = ViewModelProvider(activity)[LibraryController::class.java]
                    ready = holder != null && controller.marketplace.value.apps.singleOrNull()?.id == "copies-fixture" &&
                        controller.catalog.value.records.count { it.id in copies } == 2
                }
                ready
            }
            scenario.onActivity { activity ->
                activity.findViewById<RecyclerView>(R.id.discover_list)
                    .findViewHolderForAdapterPosition(1)!!.itemView
                    .findViewById<View>(R.id.catalog_card).performClick()
            }
            waitUntil("Detail sheet did not appear") {
                runCatching { onView(withId(R.id.detail_copies_note)).check(matches(isDisplayed())) }.isSuccess
            }
            onView(withId(R.id.detail_more_action)).check(matches(withEffectiveVisibility(Visibility.GONE)))
            onView(withId(R.id.detail_primary_action)).perform(click())
            instrumentation.waitForIdleSync()

            scenario.onActivity { activity ->
                assertEquals(R.id.nav_your_apps, activity.findViewById<BottomNavigationView>(R.id.library_bottom_nav).selectedItemId)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.app_list).visibility)
            }
            // Leaving the sheet must not have opened, changed or removed either copy.
            assertEquals(copies.toSet(), repository.list().map { it.id }.filter { it in copies }.toSet())
        } finally {
            scenario.close()
            repository.list().filter { it.id !in beforeIds }.forEach { repository.delete(it.id) }
            repository.reorder(beforeIds)
            testCacheRoot.deleteRecursively()
        }
    }
}
