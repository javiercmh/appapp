package com.wynh.appapp.workspace

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wynh.appapp.updates.WorkspaceUpdater
import com.wynh.appapp.updates.UpdatePackage
import com.wynh.appapp.updates.digest
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SubappBackupDeviceTest {
    private fun isolated(test: (android.content.Context, File) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val root = java.nio.file.Files.createTempDirectory(target.cacheDir.toPath(), "backup-device-").toFile()
        File(root, "subapps").mkdirs()
        File(root, "subapps/migration_complete").writeText("")
        val context = object : ContextWrapper(target) { override fun getFilesDir() = root }
        try { test(context, root) } finally { root.deleteRecursively() }
    }
    private fun hashes(root: File) = root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).invariantSeparatorsPath to digest(it) }

    @Test fun roundTripRestoresIndependentCopiesAndKeepsOriginals() = isolated { context, root ->
        val repository = SubappRepository(context)
        repository.ensureMigrated()
        val first = repository.createStarter()
        val second = repository.createStarter()
        WorkspaceManager(context, workspaceDir = first.workspaceDir).writeFile("data/records.json", "[1,2]")
        File(first.workspaceDir, "photo.png").writeBytes(byteArrayOf(0, -1, 42))
        repository.reorder(listOf(second.id, first.id))
        val before = repository.list().associate { it.id to hashes(it.workspaceDir) }
        val staging = File(root, "staging").apply { mkdirs() }
        val staged = repository.stageBackup(staging)
        val archive = File(root, "backup.zip")
        SubappBackupArchive.create(staged, archive)
        val validated = SubappBackupArchive.validate(archive.inputStream(), File(root, "validation").apply { mkdirs() })
        val restored = repository.importBackup(validated)
        assertEquals(listOf(second.id, first.id) + restored.map { it.id }, repository.list().map { it.id })
        assertTrue(restored.none { it.id in before })
        assertEquals(before[second.id], hashes(restored[0].workspaceDir))
        assertEquals(before[first.id], hashes(restored[1].workspaceDir))
        before.forEach { (id, files) -> assertEquals(files, hashes(repository.resolve(id)!!.workspaceDir)) }
        restored.forEach { app ->
            assertTrue(File(app.directory, "workspace_initial_snapshot.json").isFile)
            assertFalse(File(app.directory, "update-state.json").exists())
        }
    }

    @Test fun failedBatchAndInterruptedPublicationKeepExistingApps() = isolated { context, root ->
        val repository = SubappRepository(context)
        repository.ensureMigrated()
        val original = repository.createStarter()
        val before = hashes(original.workspaceDir)
        val good = File(root, "good").apply { mkdirs(); File(this, "index.html").writeText("good"); File(this, "manifest.json").writeText("{}") }
        val missing = File(root, "missing")
        try { repository.importBackup(listOf(good, missing)); fail("Expected failed import") } catch (_: Exception) { }
        assertEquals(listOf(original.id), repository.list().map { it.id })
        assertEquals(before, hashes(original.workspaceDir))
        val id = UUID.randomUUID().toString()
        val library = File(root, "subapps")
        good.copyRecursively(File(library, "$id/workspace"))
        File(library, "backup-import-journal.json").writeText(JSONArray(listOf(id)).toString())
        SubappRepository(context).ensureMigrated()
        assertFalse(File(library, id).exists())
        assertEquals(listOf(original.id), repository.list().map { it.id })
        assertEquals(before, hashes(original.workspaceDir))
    }

    @Test fun committedImportSurvivesInterruptedCleanup() = isolated { context, root ->
        val repository = SubappRepository(context)
        repository.ensureMigrated()
        val original = repository.createStarter()
        File(root, "subapps/backup-import-journal.json").writeText(JSONArray(listOf(original.id)).toString())
        SubappRepository(context).ensureMigrated()
        assertNotNull(repository.resolve(original.id))
        assertFalse(File(root, "subapps/backup-import-journal.json").exists())
    }

    @Test fun actualHistoryRestoreMakesPublisherUpdateAvailableAgain() = isolated { context, root ->
        val directory = File(root, "history").apply { mkdirs() }
        val workspace = File(directory, "workspace").apply { mkdirs(); File(this, "index.html").writeText("original") }
        val manager = WorkspaceManager(context, workspaceDir = workspace)
        val history = WorkspaceHistoryManager(context, appDirectory = directory)
        val engine = WorkspaceUpdater(directory)
        fun install(version: String): Pair<UpdatePackage, File> {
            val files = File(root, "package-$version").apply { mkdirs() }
            val manifest = """{"version":"$version","app2Update":{"appId":"test.backup","minimumHostVersionCode":1,"github":{"owner":"test","repository":"app","branch":"main"},"managedFiles":["index.html","manifest.json"],"seedFiles":[]}}"""
            File(files, "manifest.json").writeText(manifest)
            File(files, "index.html").writeText(version)
            val pkg = UpdatePackage.parse(manifest)
            engine.install(pkg, files, "a".repeat(40), 1)
            return pkg to files
        }
        install("1.2")
        val snapshot = history.saveSnapshot(manager)!!
        val (latest, files) = install("1.3")
        assertTrue(engine.publisherUnchanged(latest, files))
        assertTrue(history.restoreSnapshot(snapshot.id, manager))
        assertFalse(WorkspaceUpdater(directory).publisherUnchanged(latest, files))
        engine.install(latest, files, "a".repeat(40), 1)
        assertTrue(engine.publisherUnchanged(latest, files))
    }
}
