package com.wynh.appapp.updates

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceUpdaterTest {
    @get:Rule val temp = TemporaryFolder()
    private val sha1 = "a".repeat(40)
    private val sha2 = "b".repeat(40)
    private fun packageFiles(version: String, extra: String = "js/old.js"): Pair<UpdatePackage, File> {
        val files = temp.newFolder()
        val manifest = """{"version":"$version","app2Update":{"appId":"test.publisher","minimumHostVersionCode":3,"github":{"owner":"test","repository":"app","branch":"main"},"managedFiles":["index.html","manifest.json","data/definitions.json","$extra"],"seedFiles":["data/settings.json"]}}"""
        write(files, "manifest.json", manifest)
        write(files, "index.html", "version $version")
        write(files, "data/definitions.json", version)
        write(files, extra, "code $version")
        write(files, "data/settings.json", "seed")
        return UpdatePackage.parse(manifest) to files
    }
    private fun write(root: File, path: String, value: String) { val f = File(root, path); f.parentFile!!.mkdirs(); f.writeText(value) }
    private fun workspace(): File = temp.newFolder().also { write(it, "workspace/index.html", "old"); write(it, "workspace/data/stories.json", "saved stories"); write(it, "workspace/data/settings.json", "user settings"); val photo = File(it, "workspace/photos/photo.png"); photo.parentFile!!.mkdirs(); photo.writeBytes(byteArrayOf(0, -1, 42, 0)) }

    @Test fun updatePreservesUserFilesAndRemovesOnlyManaged() {
        val root = workspace(); val engine = WorkspaceUpdater(root)
        val (v1, files1) = packageFiles("1")
        engine.install(v1, files1, sha1, 3)
        write(root, "workspace/js/unowned.js", "user file")
        val (v2, files2) = packageFiles("2", "js/new.js")
        val preview = engine.preview(v2, files2)
        assertEquals(listOf("js/old.js"), preview.removed)
        engine.install(v2, files2, sha2, 3)
        assertFalse(File(root, "workspace/js/old.js").exists())
        assertEquals("user file", File(root, "workspace/js/unowned.js").readText())
        assertEquals("user settings", File(root, "workspace/data/settings.json").readText())
        assertArrayEquals(byteArrayOf(0, -1, 42, 0), File(root, "workspace/photos/photo.png").readBytes())

    }

    @Test fun unchangedPublisherDoesNotOfferToOverwriteLocalEdits() {
        val root = workspace()
        val engine = WorkspaceUpdater(root)
        val (pkg, files) = packageFiles("1")
        assertFalse(engine.publisherUnchanged(pkg, files))
        engine.install(pkg, files, sha1, 3)
        assertTrue(engine.publisherUnchanged(pkg, files))
        write(root, "workspace/index.html", "local edit")
        assertTrue(engine.publisherUnchanged(pkg, files))
        write(files, "index.html", "publisher change without version bump")
        assertFalse(engine.publisherUnchanged(pkg, files))
        assertEquals("local edit", File(root, "workspace/index.html").readText())
    }

    @Test fun restoredManifestVersionOffersLatestPublisherAgain() {
        val root = workspace()
        val engine = WorkspaceUpdater(root)
        val (v12, files12) = packageFiles("1.2")
        val (v13, files13) = packageFiles("1.3")
        engine.install(v12, files12, sha1, 3)
        engine.install(v13, files13, sha2, 3)
        assertTrue(engine.publisherUnchanged(v13, files13))
        // History restores the old manifest and text files, not native installation metadata.
        v12.managed.forEach { path -> write(root, "workspace/$path", File(files12, path).readText()) }
        val reopened = WorkspaceUpdater(root)
        assertEquals("1.2", reopened.currentVersion())
        assertEquals("1.3", reopened.installed()!!.version)
        assertFalse(reopened.publisherUnchanged(v13, files13))
        reopened.install(v13, files13, sha2, 3)
        assertTrue(reopened.publisherUnchanged(v13, files13))
        assertEquals("user settings", File(root, "workspace/data/settings.json").readText())
    }

    @Test fun missingOrUnreadableCurrentVersionCannotBeUpToDate() {
        val root = workspace(); val engine = WorkspaceUpdater(root)
        val (pkg, files) = packageFiles("1.3")
        engine.install(pkg, files, sha2, 3)
        for (manifest in listOf("broken", "{}", "{\"version\":null}", "{\"version\":13}", "{\"version\":\"\"}")) {
            write(root, "workspace/manifest.json", manifest)
            assertNull(engine.currentVersion())
            assertFalse(engine.publisherUnchanged(pkg, files))
        }
        File(root, "workspace/manifest.json").delete()
        assertNull(engine.currentVersion())
        assertFalse(engine.publisherUnchanged(pkg, files))
    }

    @Test fun editedManagedFilesAreReportedAndMissingSeedsAreCreated() {
        val root = workspace(); File(root, "workspace/data/settings.json").delete()
        val engine = WorkspaceUpdater(root); val (pkg, files) = packageFiles("1")
        engine.install(pkg, files, sha1, 3)
        assertEquals("seed", File(root, "workspace/data/settings.json").readText())
        write(root, "workspace/index.html", "local edit")
        assertTrue(engine.preview(pkg, files).edited.contains("index.html"))
    }

    @Test fun failedValidationLeavesWorkspaceUnchanged() {
        val root = workspace(); val engine = WorkspaceUpdater(root); val (pkg, files) = packageFiles("1")
        for (host in listOf(1L, 3L)) {
            if (host == 3L) File(files, "index.html").delete()
            assertThrows(IllegalArgumentException::class.java) { engine.install(pkg, files, sha1, host) }
            assertEquals("old", File(root, "workspace/index.html").readText())
            assertEquals("saved stories", File(root, "workspace/data/stories.json").readText())
        }
    }

    @Test fun interruptedSwapRestoresBinaryBackupAndMetadataOnStartup() {
        val root = workspace(); val engine = WorkspaceUpdater(root); val (pkg, files) = packageFiles("1")
        engine.install(pkg, files, sha1, 3)
        // Simulate interruption after workspace replacement but before committing metadata.
        File(root, "update-journal").writeText("recover")
        write(root, "workspace/index.html", "incomplete")
        File(root, "workspace/photos/photo.png").writeBytes(byteArrayOf(12))
        WorkspaceUpdater(root).recover()
        assertEquals("old", File(root, "workspace/index.html").readText())
        assertArrayEquals(byteArrayOf(0, -1, 42, 0), File(root, "workspace/photos/photo.png").readBytes())
        assertNull(engine.installed())
        assertFalse(File(root, "update-journal").exists())
    }

    @Test fun rejectsCodeChangedAfterReview() {
        val root = workspace(); val engine = WorkspaceUpdater(root); val (pkg, files) = packageFiles("1")
        val reviewed = engine.reviewedFiles(pkg)
        write(root, "workspace/index.html", "edit after review")
        assertThrows(IllegalArgumentException::class.java) { engine.install(pkg, files, sha1, 3, reviewed) }
        assertEquals("edit after review", File(root, "workspace/index.html").readText())
    }

    @Test fun rejectsCodeDirectoryConflictWithoutChangingLiveFiles() {
        val root = workspace(); val engine = WorkspaceUpdater(root); val (pkg, files) = packageFiles("1")
        write(root, "workspace/js/old.js/child", "user directory")
        assertThrows(IllegalArgumentException::class.java) { engine.install(pkg, files, sha1, 3) }
        assertEquals("old", File(root, "workspace/index.html").readText())
        assertEquals("user directory", File(root, "workspace/js/old.js/child").readText())
    }

    @Test fun rejectsTraversalOverlapAndIdentityChanges() {
        for (path in listOf("../escape", "/escape", "a//b", "a\\b", ".git/config", "a/../b")) assertThrows(IllegalArgumentException::class.java) { safePath(path) }
        val root = workspace(); val engine = WorkspaceUpdater(root); val (pkg, files) = packageFiles("1")
        engine.install(pkg, files, sha1, 3)
        assertThrows(IllegalArgumentException::class.java) { engine.preview(pkg.copy(appId = "other.publisher"), files) }
        assertThrows(IllegalArgumentException::class.java) { UpdatePackage.parse(File(files, "manifest.json").readText().replace("js/old.js", "data")) }
    }
}
