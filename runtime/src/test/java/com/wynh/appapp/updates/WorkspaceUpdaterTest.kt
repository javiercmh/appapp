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

    @Test fun changedPublisherIdentityInstallsOnlyWhenAccepted() {
        val root = workspace(); val engine = WorkspaceUpdater(root); val (pkg, files) = packageFiles("1")
        engine.install(pkg, files, sha1, 3)
        assertNull(engine.identityMismatch(pkg))
        val manifest = File(files, "manifest.json"); manifest.writeText(manifest.readText().replace("test.publisher", "other.publisher"))
        val renamed = UpdatePackage.parse(manifest.readText())
        assertTrue(engine.identityMismatch(renamed)!!.contains("other.publisher"))
        assertThrows(IllegalArgumentException::class.java) { engine.install(renamed, files, sha2, 3) }
        engine.install(renamed, files, sha2, 3, acceptMismatch = true)
        assertEquals("other.publisher", engine.installed()!!.appId)
        assertNull(engine.identityMismatch(renamed))
    }

    @Test fun followedBranchIsRecordedAndASwitchNeedsConfirmation() {
        val root = workspace(); val engine = WorkspaceUpdater(root); val (pkg, files) = packageFiles("1")
        engine.install(pkg, files, sha1, 3)
        val dev = GitHubSource("test", "app", "dev")
        // Same files from a dev branch whose manifest still declares main: still a change of source.
        assertFalse(engine.publisherUnchanged(pkg, files, dev))
        assertTrue(engine.identityMismatch(pkg, dev)!!.contains("dev"))
        assertThrows(IllegalArgumentException::class.java) { engine.install(pkg, files, sha2, 3, source = dev) }
        engine.install(pkg, files, sha2, 3, acceptMismatch = true, source = dev)
        assertEquals(dev, WorkspaceUpdater(root).installed()!!.source)
        assertNull(engine.identityMismatch(pkg, dev))
        assertTrue(engine.publisherUnchanged(pkg, files, dev))
        // Going back to main is a source change again.
        assertNotNull(engine.identityMismatch(pkg, pkg.source))
    }

    @Test fun parsesTypedSources() {
        assertEquals(GitHubSource("javiercmh", "lingomage", "dev"), parseSource(" javiercmh/lingomage ", " dev "))
        assertEquals(GitHubSource("javiercmh", "lingomage", "main"), parseSource("https://github.com/javiercmh/lingomage.git", "main"))
        assertEquals(GitHubSource("javiercmh", "lingomage", "main"), parseSource("github.com/javiercmh/lingomage/", "main"))
        assertThrows(IllegalArgumentException::class.java) { parseSource("lingomage", "main") }
        assertThrows(IllegalArgumentException::class.java) { parseSource("a/b/c", "main") }
        assertThrows(IllegalArgumentException::class.java) { parseSource("a/b", " ") }
    }

    @Test fun manifestBranchIsOptionalAndDefaultsToMain() {
        val manifest = """{"version":"1","app2Update":{"appId":"test.publisher","minimumHostVersionCode":3,"github":{"owner":"test","repository":"app"},"managedFiles":["index.html","manifest.json"],"seedFiles":[]}}"""
        assertEquals(GitHubSource("test", "app", "main"), UpdatePackage.parse(manifest).source)
    }

    private val repoSource = GitHubSource("test", "app", "main")
    private fun comparison(status: String = "ahead", vararg files: String) =
        Comparison(status, 1, 0, 1, listOf("Change"), files.map { ChangedFile(it, "modified") })

    @Test fun planWithoutPublisherChangesIsUpToDateDespiteLocalEdits() {
        val root = workspace(); val engine = WorkspaceUpdater(root)
        val (pkg, files) = packageFiles("1")
        engine.install(pkg, files, sha1, 3)
        write(root, "workspace/index.html", "local edit")
        val plan = engine.plan(pkg, comparison("ahead", "README.md", ".github/workflows/check.yml"), repoSource)!!
        assertTrue(plan.unchanged)
        assertEquals(pkg.managed, plan.reusable.keys)
    }

    @Test fun planListsPublisherChangesLocalEditsAndRemovalsWithoutDownloading() {
        val root = workspace(); val engine = WorkspaceUpdater(root)
        val (v1, files1) = packageFiles("1")
        engine.install(v1, files1, sha1, 3)
        write(root, "workspace/data/definitions.json", "edited locally")
        val (v2, _) = packageFiles("2", "js/new.js")
        val plan = engine.plan(v2, comparison("ahead", "manifest.json", "index.html", "js/new.js", "js/old.js"), repoSource)!!
        assertFalse(plan.unchanged)
        assertEquals(listOf("js/new.js"), plan.preview.added)
        assertEquals(setOf("manifest.json", "index.html", "data/definitions.json"), plan.preview.changed.toSet())
        assertEquals(listOf("js/old.js"), plan.preview.removed)
        assertEquals(listOf("data/definitions.json"), plan.preview.edited)
        // Only the untouched file can be copied from the workspace; it is edited, so the copy is refused.
        assertEquals(setOf("data/definitions.json"), plan.reusable.keys)
        val target = temp.newFile()
        assertFalse(engine.copyIfUnchanged("data/definitions.json", plan.reusable.getValue("data/definitions.json"), target))
    }

    @Test fun unchangedLocalFilesAreCopiedAndInstallMatchesAFullDownload() {
        val root = workspace(); val engine = WorkspaceUpdater(root)
        val (v1, files1) = packageFiles("1")
        engine.install(v1, files1, sha1, 3)
        val (v2, files2) = packageFiles("2")
        write(files2, "data/definitions.json", "1") // the publisher left this file alone
        val plan = engine.plan(v2, comparison("ahead", "manifest.json", "index.html", "js/old.js"), repoSource)!!
        val staged = temp.newFolder()
        assertTrue(engine.copyIfUnchanged("data/definitions.json", plan.reusable.getValue("data/definitions.json"), File(staged, "definitions.json")))
        assertEquals("1", File(staged, "definitions.json").readText())
        engine.install(v2, files2, sha2, 3)
        assertTrue(engine.publisherUnchanged(v2, files2))
    }

    @Test fun planFallsBackWhenTheComparisonCannotDescribeTheUpdate() {
        val root = workspace(); val engine = WorkspaceUpdater(root)
        val (pkg, files) = packageFiles("1")
        assertNull("nothing installed", engine.plan(pkg, comparison(), repoSource))
        engine.install(pkg, files, sha1, 3)
        assertNull("older commit", engine.plan(pkg, comparison("behind"), repoSource))
        assertNull("diverged", engine.plan(pkg, comparison("diverged"), repoSource))
        assertNull("other repository", engine.plan(pkg, comparison(), GitHubSource("fork", "app", "main")))
        val full = Comparison("ahead", 1, 0, 1, emptyList(), (1..Comparison.MAX_FILES).map { ChangedFile("f$it", "modified") })
        assertNull("truncated file list", engine.plan(pkg, full, repoSource))
        assertNotNull("another branch of the same repository", engine.plan(pkg, comparison(), GitHubSource("test", "app", "dev")))
        assertFalse("a branch switch is never reported as up to date", engine.plan(pkg, comparison(), GitHubSource("test", "app", "dev"))!!.unchanged)
    }
}
