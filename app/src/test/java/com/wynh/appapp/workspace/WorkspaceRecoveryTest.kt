package com.wynh.appapp.workspace

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

class WorkspaceRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun root() = File(temporary.newFolder(), "workspace").apply { mkdirs(); File(this, "index.html").writeText("old") }
    @Test fun atomicFailureKeepsOldBytesAndCleansTemporaryFile() {
        val root = root(); val file = File(root, "data.json").apply { writeText("saved") }
        assertTrue(runCatching { WorkspaceFiles.atomicWrite(file, "replacement".toByteArray()) { error("disk failure") } }.isFailure)
        assertEquals("saved", file.readText()); assertEquals(setOf("index.html", "data.json"), root.list()!!.toSet())
        WorkspaceFiles(root).writeText("data.json", "new"); assertEquals("new", file.readText())
    }
    @Test fun readFailureDoesNotBecomeAnEmptyDocument() {
        val root = root(); File(root, "bad.json").writeBytes(byteArrayOf(-1, -2))
        assertTrue(runCatching { WorkspaceFiles(root).readText("bad.json") }.isFailure)
        assertArrayEquals(byteArrayOf(-1, -2), File(root, "bad.json").readBytes())
    }
    @Test fun sharedPathFixtureMatchesNativeRules() {
        val fixture = JsonParser.parseReader(javaClass.getResourceAsStream("/workspace-paths.json")!!.reader()).asJsonArray
        fixture.forEach { entry ->
            val input = entry.asJsonObject.get("input").asString
            val expected = entry.asJsonObject.get("normalized").takeUnless { it.isJsonNull }?.asString
            assertEquals(input, expected, WorkspaceFiles.normalize(input))
        }
    }
    @Test fun restoreKeepsEveryUnknownAndBinaryAssetByteForByte() {
        val root = root(); val bytes = byteArrayOf(0, -1, -2, 42)
        listOf("book.pdf", "speech.mp3", "font.otf", "photo.png", "unknown.bin").forEach { File(root, it).writeBytes(bytes) }
        File(root, "new.js").writeText("new")
        WorkspaceOperations.restoreText(root, mapOf("index.html" to "restored", "data/records.json" to "[1]", "book.pdf" to "old corrupt text"))
        assertEquals("restored", File(root, "index.html").readText()); assertEquals("[1]", File(root, "data/records.json").readText())
        assertFalse(File(root, "new.js").exists())
        listOf("book.pdf", "speech.mp3", "font.otf", "photo.png", "unknown.bin").forEach { assertArrayEquals(bytes, File(root, it).readBytes()) }
    }
    @Test fun restoreNeverDeletesOrOverwritesTextFilesThatAreNotValidUtf8() {
        val root = root(); val windows1252 = byteArrayOf(0x63, 0xE9.toByte(), 0x0A)
        File(root, "legacy.csv").writeBytes(windows1252)       // not in the snapshot: must not be deleted
        File(root, "export.txt").writeBytes(windows1252)       // in the snapshot: must not be overwritten
        File(root, "gone.txt").writeText("valid text, absent from the snapshot")
        val result = WorkspaceOperations.restoreText(root, mapOf("index.html" to "restored", "export.txt" to "older text", "tool.exe" to "x"))
        assertArrayEquals(windows1252, File(root, "legacy.csv").readBytes())
        assertArrayEquals(windows1252, File(root, "export.txt").readBytes())
        assertFalse(File(root, "gone.txt").exists()); assertEquals("restored", File(root, "index.html").readText())
        assertEquals(setOf("legacy.csv", "export.txt"), result.keptUnreadable.toSet())
        assertEquals(listOf("tool.exe"), result.ignoredEntries); assertFalse(File(root, "tool.exe").exists())
    }
    @Test fun pythonFilesAreVersionedAsText() {
        assertTrue(WorkspaceFiles.isHistoryText("dev_server.py")); assertFalse(WorkspaceFiles.isHistoryText("app.log"))
        assertEquals("café", WorkspaceFiles.decodeUtf8("café".toByteArray())); assertNull(WorkspaceFiles.decodeUtf8(byteArrayOf(0x63, 0xE9.toByte())))
    }
    @Test fun reservationWithoutReplacementKeepsTheDocumentGenerationButStillExcludesCompetitors() {
        val root = root(); val before = WorkspaceOperations.version(root)
        val lease = WorkspaceOperations.reserve(root, replacement = false)
        assertEquals(before, WorkspaceOperations.version(root)); assertTrue(WorkspaceOperations.isBusy(root))
        assertTrue(runCatching { WorkspaceOperations.reserve(root, replacement = false) }.isFailure)
        lease.close()
        WorkspaceOperations.run(root, replacement = false) { }; assertEquals(before, WorkspaceOperations.version(root))
        WorkspaceOperations.run(root) { }; assertEquals(before + 1, WorkspaceOperations.version(root))
    }
    @Test fun idleRecoveryRestoresInterruptedWorkspaceButNeverWaitsForAnOwner() {
        val root = root(); val old = File(root.parentFile, "workspace_import_old")
        assertTrue(root.renameTo(old)); assertFalse(root.exists())
        WorkspaceOperations.recoverIfIdle(root); assertEquals("old", File(root, "index.html").readText()); assertFalse(old.exists())
        val busy = root(); val staging = File(busy.parentFile, "workspace_import_staging").apply { mkdirs() }
        WorkspaceOperations.reserve(busy).use { WorkspaceOperations.recoverIfIdle(busy) { fail("recovery ran during an operation") } }
        assertTrue("another operation owns the workspace, so its staging is not touched", staging.exists())
    }
    @Test fun logAppendNeverCreatesAWorkspaceThatWasDeleted() {
        val deleted = File(temporary.newFolder(), "workspace")
        assertTrue(runCatching { WorkspaceFiles(deleted).appendBounded("app.log", "late line\n") }.isFailure)
        assertFalse(deleted.exists()); assertFalse(deleted.parentFile!!.list()!!.contains("workspace"))
    }
    @Test fun logWithInvalidUtf8StillTruncatesAndKeepsAppending() {
        val root = root(); File(root, "app.log").writeBytes(ByteArray(400) { if (it % 7 == 0) 0xFF.toByte() else 'a'.code.toByte() })
        val files = WorkspaceFiles(root)
        files.appendBounded("app.log", "after\n", maxBytes = 100)
        val text = files.readText("app.log")   // strict decoding succeeds only because truncation re-encoded the tail
        assertTrue(text.startsWith("[System] Log truncated\n")); assertTrue(text.endsWith("after\n")); assertTrue(text.length < 200)
        files.appendBounded("app.log", "again\n", maxBytes = 100); assertTrue(files.readText("app.log").endsWith("again\n"))
    }
    @Test fun restoreFailureLeavesCompleteOldWorkspace() {
        val root = root(); File(root, "data.json").writeText("old records")
        assertTrue(runCatching { WorkspaceOperations.restoreText(root, mapOf("index.html" to "new")) { from, to ->
            if (from.name == "workspace_import_staging") false else from.renameTo(to)
        } }.isFailure)
        assertEquals("old", File(root, "index.html").readText()); assertEquals("old records", File(root, "data.json").readText())
    }
    @Test fun interruptedBeforeActivationRecoversOldWorkspace() {
        val root = root()
        runCatching { WorkspaceOperations.restoreText(root, mapOf("index.html" to "new")) { from, to ->
            if (from == root) { check(from.renameTo(to)); throw AssertionError("process ended") }
            from.renameTo(to)
        } }
        assertFalse(root.exists()); WorkspaceOperations.recover(root)
        assertEquals("old", File(root, "index.html").readText())
    }
    @Test fun interruptedAfterActivationKeepsCompleteNewWorkspace() {
        val root = root()
        runCatching { WorkspaceOperations.restoreText(root, mapOf("index.html" to "new")) { from, to ->
            val renamed = from.renameTo(to)
            if (to == root && renamed) throw AssertionError("process ended")
            renamed
        } }
        WorkspaceOperations.recover(root); assertEquals("new", File(root, "index.html").readText())
    }
    @Test fun operationOwnershipIsPerWorkspaceAndClearsOnFailure() {
        val root = root(); val other = root(); val refused = AtomicBoolean(false)
        assertTrue(runCatching { WorkspaceOperations.run(root) {
            WorkspaceOperations.checkWritable(root)
            Thread { refused.set(runCatching { WorkspaceOperations.checkWritable(root) }.isFailure); WorkspaceOperations.checkWritable(other) }.apply { start(); join() }
            error("operation failed")
        } }.isFailure)
        assertTrue(refused.get()); assertFalse(WorkspaceOperations.isBusy(root))
    }
    @Test fun symbolicLinksAreRejectedBeforeStagingCanCopyExternalBytes() {
        val root = root(); val outside = temporary.newFile().apply { writeText("secret") }
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        assertTrue(runCatching { WorkspaceOperations.restoreText(root, mapOf("index.html" to "new")) }.isFailure)
        assertEquals("old", File(root, "index.html").readText()); assertEquals("secret", outside.readText())
    }
    @Test fun reservationBlocksCompetingOperationsBeforeDiskWorkStarts() {
        val root = root(); val other = root(); val previous = WorkspaceOperations.version(root)
        val lease = WorkspaceOperations.reserve(root)
        assertTrue(WorkspaceOperations.isBusy(root)); assertEquals(previous + 1, WorkspaceOperations.version(root))
        assertEquals(0L, WorkspaceOperations.version(other))
        assertTrue(runCatching { WorkspaceOperations.reserve(root) }.isFailure)
        assertTrue(runCatching { WorkspaceOperations.checkWritable(root) }.isFailure)
        lease.run { WorkspaceOperations.checkWritable(root) }
        lease.close(); assertFalse(WorkspaceOperations.isBusy(root))
        assertTrue(runCatching { lease.run { fail("closed lease executed") } }.isFailure)
    }
}
