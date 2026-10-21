package com.wynh.appapp.updates

import com.wynh.appapp.grants.GrantStorage
import com.wynh.appapp.grants.SubappCapability
import com.wynh.appapp.grants.SubappGrantStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Drives the real [WorkspaceUpdater] transactions beside a real [SubappGrantStore] holding the
 * app's grants, and checks that no update outcome changes the user's permission decisions.
 */
class UpdatePermissionPreservationTest {
    @get:Rule val temp = TemporaryFolder()
    private val sha1 = "a".repeat(40)
    private val sha2 = "b".repeat(40)
    private val appId = "app-1"

    private class MemoryStorage : GrantStorage {
        val map = HashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun put(values: Map<String, String?>) { values.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v } }
    }

    private fun write(root: File, path: String, value: String) { val f = File(root, path); f.parentFile!!.mkdirs(); f.writeText(value) }

    private fun packageFiles(version: String, publisher: String = "test.publisher", extra: String = "js/app.js"): Pair<UpdatePackage, File> {
        val files = temp.newFolder()
        val manifest = """{"version":"$version","app2Update":{"appId":"$publisher","minimumHostVersionCode":3,"github":{"owner":"test","repository":"app","branch":"main"},"managedFiles":["index.html","manifest.json","$extra"],"seedFiles":[]}}"""
        write(files, "manifest.json", manifest); write(files, "index.html", "version $version"); write(files, extra, "code $version")
        return UpdatePackage.parse(manifest) to files
    }

    private fun workspace(): File = temp.newFolder().also { write(it, "workspace/index.html", "old"); write(it, "workspace/data/records.json", "user records") }

    private fun store(vararg granted: SubappCapability) = SubappGrantStore(MemoryStorage()).also { s -> granted.forEach { s.set(appId, it, true) } }

    @Test fun ordinaryUpdateKeepsEveryDecisionAndGrantsNothingNew() {
        val root = workspace(); val engine = WorkspaceUpdater(root); val grants = store(SubappCapability.AI, SubappCapability.NETWORK)
        val (v1, f1) = packageFiles("1"); engine.install(v1, f1, sha1, 3)
        val (v2, f2) = packageFiles("2"); engine.install(v2, f2, sha2, 3)
        assertEquals("version 2", File(root, "workspace/index.html").readText())
        assertEquals(setOf(SubappCapability.AI, SubappCapability.NETWORK), grants.granted(appId))
        assertFalse(grants.isGranted(appId, SubappCapability.NOTIFICATIONS))
    }

    @Test fun acceptedPublisherIdentityChangeKeepsDecisionsAndCancelChangesNothing() {
        val root = workspace(); val engine = WorkspaceUpdater(root); val grants = store(SubappCapability.NOTIFICATIONS)
        val (v1, f1) = packageFiles("1"); engine.install(v1, f1, sha1, 3)
        val (other, otherFiles) = packageFiles("2", publisher = "other.publisher")
        // The review dialog previews with the mismatch accepted; Cancel then stops there.
        assertNotNull(engine.identityMismatch(other))
        engine.preview(other, otherFiles, acceptMismatch = true)
        assertThrows(IllegalArgumentException::class.java) { engine.install(other, otherFiles, sha2, 3) }
        assertEquals("version 1", File(root, "workspace/index.html").readText())
        assertEquals("test.publisher", engine.installed()!!.appId)
        assertEquals(setOf(SubappCapability.NOTIFICATIONS), grants.granted(appId))
        // "Update anyway".
        engine.install(other, otherFiles, sha2, 3, acceptMismatch = true)
        assertEquals("other.publisher", engine.installed()!!.appId)
        assertEquals("version 2", File(root, "workspace/index.html").readText())
        assertEquals(setOf(SubappCapability.NOTIFICATIONS), grants.granted(appId))
    }

    @Test fun failedUpdateLeavesOldCodeDataAndDecisionsUntouched() {
        val root = workspace(); val engine = WorkspaceUpdater(root); val grants = store(SubappCapability.AI)
        val (v1, f1) = packageFiles("1"); engine.install(v1, f1, sha1, 3)
        File(root, "workspace/js/new.js").mkdirs() // a user directory blocks the package file, failing after the backup
        val (v2, f2) = packageFiles("2", extra = "js/new.js")
        assertTrue(runCatching { engine.install(v2, f2, sha2, 3) }.isFailure)
        assertEquals("version 1", File(root, "workspace/index.html").readText())
        assertEquals("user records", File(root, "workspace/data/records.json").readText())
        assertEquals("1", engine.installed()!!.version)
        assertEquals(setOf(SubappCapability.AI), grants.granted(appId))
    }

    @Test fun interruptedUpdateRecoveryRestoresCodeAndKeepsDecisions() {
        val root = workspace(); val engine = WorkspaceUpdater(root); val grants = store(SubappCapability.NETWORK, SubappCapability.NOTIFICATIONS)
        val (v1, f1) = packageFiles("1"); engine.install(v1, f1, sha1, 3)
        // Simulate a crash mid-activation: the journal exists and the workspace holds half-written code.
        File(root, "update-journal").writeText("recover")
        write(root, "workspace/index.html", "half written")
        WorkspaceUpdater(root).recover()
        assertEquals("old", File(root, "workspace/index.html").readText())
        assertFalse(File(root, "update-journal").exists())
        assertEquals(setOf(SubappCapability.NETWORK, SubappCapability.NOTIFICATIONS), grants.granted(appId))
    }

    @Test fun reviewCopyListsOnlyCurrentGrantsAndStaysShort() {
        val some = UpdateReviewCopy.identityWarning("Source: a → b", setOf(SubappCapability.AI, SubappCapability.NETWORK))
        assertTrue(some.contains("publisher identity or source differs"))
        assertTrue(some.contains("Source: a → b"))
        assertTrue(some.contains("Existing permissions are kept. Current permissions: AI, Internet. Permissions that are off stay off."))
        assertFalse(some.contains("Notifications"))
        assertTrue(UpdateReviewCopy.permissionSummary(emptySet()).startsWith("Existing permissions are kept. Current permissions: none."))
        assertTrue(UpdateReviewCopy.permissionSummary(SubappCapability.entries.toSet()).contains("Current permissions: AI, Notifications, Internet."))
        assertFalse(some.contains("prompt", ignoreCase = true))
    }
}
