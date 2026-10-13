package com.wynh.appapp.workspace

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WorkspacePackageManagerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val limits = WorkspacePackageManager.ZipLimits(entries = 8, entryBytes = 64 * 1024, totalBytes = 128 * 1024)
    private fun archive(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
    }.toByteArray()
    private fun workspace(): File = File(temporary.newFolder(), "workspace").also { root ->
        File(root, "data").mkdirs()
        File(root, "index.html").writeText("existing app")
        File(root, "data/records.json").writeText("[1,2,3]")
    }
    private fun assertIntact(workspace: File) {
        assertEquals("existing app", File(workspace, "index.html").readText())
        assertEquals("[1,2,3]", File(workspace, "data/records.json").readText())
        assertEquals(listOf("workspace"), workspace.parentFile!!.list()!!.toList())
    }
    private fun import(workspace: File, bytes: ByteArray, rename: (File, File) -> Boolean = File::renameTo) =
        runCatching { WorkspacePackageManager.replaceFromZip(bytes.inputStream(), workspace, limits, rename) }

    @Test fun replacesWorkspaceWithValidArchive() {
        val root = workspace()
        val result = import(root, archive("index.html" to "new".toByteArray(), "__MACOSX/._index.html" to "meta".toByteArray(), "js/app.js" to "code".toByteArray()))
        assertEquals(listOf("index.html", "app.js"), result.getOrThrow())
        assertEquals("new", File(root, "index.html").readText())
        assertFalse(File(root, "data/records.json").exists())
        assertFalse(File(root, "__MACOSX").exists())
        assertEquals(listOf("workspace"), root.parentFile!!.list()!!.toList())
    }

    @Test fun rejectsCompressionBombInSkippedEntries() {
        val root = workspace()
        val bomb = ByteArray(1024 * 1024)
        listOf("__MACOSX/._bomb", "folder/.DS_Store").forEach { name ->
            val result = import(root, archive("index.html" to "new".toByteArray(), name to bomb))
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("size limit"))
            assertIntact(root)
        }
    }

    @Test fun skippedAndDirectoryEntriesCountTowardEntryLimit() {
        val root = workspace()
        val metadata = (0 until 8).map { "__MACOSX/._$it" to ByteArray(0) }.toTypedArray()
        assertEquals("Too many ZIP entries", import(root, archive("index.html" to "new".toByteArray(), *metadata)).exceptionOrNull()?.message)
        assertIntact(root)
        val directories = (0 until 8).map { "dir$it/" to ByteArray(0) }.toTypedArray()
        assertEquals("Too many ZIP entries", import(root, archive("index.html" to "new".toByteArray(), *directories)).exceptionOrNull()?.message)
        assertIntact(root)
    }

    @Test fun rejectsTimedOutOrCancelledImport() {
        val root = workspace()
        val expired = runCatching { WorkspacePackageManager.replaceFromZip(archive("index.html" to "new".toByteArray()).inputStream(), root, limits.copy(timeoutMillis = 0)) }
        assertEquals("ZIP import timed out", expired.exceptionOrNull()?.message)
        Thread.currentThread().interrupt()
        assertTrue(import(root, archive("index.html" to "new".toByteArray())).exceptionOrNull() is InterruptedException)
        assertIntact(root)
    }

    @Test fun failedActivationRestoresOldWorkspace() {
        val root = workspace()
        val result = import(root, archive("index.html" to "new".toByteArray())) { from, to ->
            if (from.name == "workspace_import_staging") false else from.renameTo(to)
        }
        assertEquals("Could not activate imported workspace", result.exceptionOrNull()?.message)
        assertIntact(root)
        val unmoved = import(root, archive("index.html" to "new".toByteArray())) { _, _ -> false }
        assertTrue(unmoved.isFailure)
        assertIntact(root)
    }

    @Test fun diskFailureDuringExtractionLeavesWorkspaceIntact() {
        val root = workspace()
        // A directory where the staged file must be written makes the extraction write fail.
        val staging = File(root.parentFile, "workspace_import_staging")
        val failing = object : java.io.InputStream() {
            val source = archive("index.html" to "new".toByteArray(), "js/app.js" to "code".toByteArray()).inputStream()
            override fun read(): Int = source.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                File(staging, "js").takeIf { staging.isDirectory && !it.exists() }?.writeText("blocks directory")
                return source.read(b, off, len)
            }
        }
        assertTrue(runCatching { WorkspacePackageManager.replaceFromZip(failing, root, limits) }.isFailure)
        assertIntact(root)
    }

    @Test fun recoversWorkspaceLeftAsideByInterruptedSwap() {
        val root = workspace()
        val old = File(root.parentFile, "workspace_import_old")
        check(root.renameTo(old))
        File(root.parentFile, "workspace_import_staging").mkdirs()
        WorkspacePackageManager.recoverImport(root)
        assertIntact(root)
    }
}
