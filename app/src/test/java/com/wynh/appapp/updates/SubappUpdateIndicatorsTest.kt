package com.wynh.appapp.updates

import org.junit.Assert.*
import org.junit.Test

class SubappUpdateIndicatorsTest {
    private val source = GitHubSource("owner", "repo", "main")
    private val update = VersionUpdateChecker.PublishedVersion(source, "a".repeat(40), "2.0")
    @Test fun persistsAcrossInstancesAndRemainsUntilInstalledVersionCatchesUp() {
        val prefs = UpdateAvailabilityTest.MemoryPrefs()
        SubappUpdateIndicators(prefs).record("app", update)
        val recreated = SubappUpdateIndicators(prefs)
        assertEquals(update, recreated.matching("app", source, "1.9"))
        // Merely reading/reviewing or failing a network request does not clear it.
        assertEquals(update, recreated.matching("app", source, "1.9"))
        assertNull(recreated.matching("app", source, "2.0"))
        assertNull(recreated.matching("app", source, "1.9"))
    }
    @Test fun sourceChangesInvalidVersionsAndExplicitClearingRemoveIndicators() {
        val store = SubappUpdateIndicators(UpdateAvailabilityTest.MemoryPrefs())
        store.record("app", update)
        assertNull(store.matching("app", source.copy(branch = "dev"), "1"))
        assertNull(store.matching("app", source, "1"))
        store.record("app", update)
        assertNull(store.matching("app", source, null))
        store.record("app", update)
        store.clear("app")
        assertNull(store.matching("app", source, "1"))
        assertNull(store.matching("different-app", source, "1"))
    }
}
