package com.wynh.appapp.saveas

import com.google.gson.JsonParser
import com.wynh.appapp.channel.ChannelException
import com.wynh.appapp.workspace.WorkspaceFiles
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SaveAsPolicyTest {
    private val root: File = Files.createTempDirectory("save-as").toFile()
    @After fun tearDown() { root.deleteRecursively() }
    private fun options(json: String) = JsonParser.parseString(json).asJsonObject
    private fun code(block: () -> Unit) = assertThrows(ChannelException::class.java) { block() }.code

    @Test fun textAcceptsOnlyAllowlistedTypesAndForcesTheExtension() {
        val html = SaveAsPolicy.parse("text", options("""{"name":"../notes.txt","mimeType":"text/html","content":"<p>x</p>"}""")) as SaveAsPolicy.Request.Text
        assertEquals("notes.html", html.name)
        assertEquals("invalid_arguments", code { SaveAsPolicy.parse("text", options("""{"name":"a","mimeType":"application/pdf","content":""}""")) })
        assertEquals("invalid_arguments", code { SaveAsPolicy.parse("text", options("""{"name":"a","mimeType":"text/plain"}""")) })
        val big = "x".repeat(SaveAsPolicy.MAX_TEXT_BYTES + 1)
        assertEquals("too_large", code { SaveAsPolicy.parse("text", options("""{"name":"a","mimeType":"text/plain","content":"$big"}""")) })
    }

    @Test fun fileFallsBackToOctetStreamAndKeepsABenignExtension() {
        val unknown = SaveAsPolicy.parse("file", options("""{"path":"data/export/archive.bin2"}""")) as SaveAsPolicy.Request.File
        assertEquals(SaveAsPolicy.OCTET, unknown.mimeType)
        assertEquals("archive.bin2", unknown.name)
        val named = SaveAsPolicy.parse("file", options("""{"path":"a.png","name":"Holiday photo.PNG","mimeType":"IMAGE/PNG"}""")) as SaveAsPolicy.Request.File
        assertEquals("image/png", named.mimeType)
        assertEquals("Holiday_photo.PNG", named.name)
        // No executable blacklist: the extension is data, and the picker is the user's decision.
        assertEquals("tool.apk", SaveAsPolicy.safeName("tool.apk", null))
        assertEquals("invalid_arguments", code { SaveAsPolicy.parse("file", options("""{"path":"a","mimeType":"not a type"}""")) })
        assertEquals("invalid_arguments", code { SaveAsPolicy.parse("other", options("{}")) })
    }

    @Test fun safeNameIsABoundedBasename() {
        assertEquals("file", SaveAsPolicy.safeName("...", null))
        assertEquals("evil.txt", SaveAsPolicy.safeName("a/b\\..\\evil.txt", null))
        assertEquals("x_y.json", SaveAsPolicy.safeName("x y", "json"))
        assertTrue(SaveAsPolicy.safeName("a".repeat(500) + ".txt", null).length <= 120)
        assertEquals("a", SaveAsPolicy.safeName("a.", null))
    }

    @Test fun snapshotReadsOnlyRegularValidatedFiles() {
        val workspace = File(root, "workspace").apply { mkdirs() }
        File(workspace, "data").mkdirs()
        File(workspace, "data/blob.bin").writeBytes(byteArrayOf(0, 1, 2, -1))
        val files = WorkspaceFiles(workspace)
        val target = File(root, "snapshot")
        SaveAsSource.snapshot(files, "data/blob.bin", target)
        assertArrayEquals(byteArrayOf(0, 1, 2, -1), target.readBytes())
        assertEquals("not_found", assertThrows(SaveAsSource.Refusal::class.java) { SaveAsSource.snapshot(files, "data", File(root, "d")) }.code)
        assertEquals("invalid_arguments", assertThrows(SaveAsSource.Refusal::class.java) { SaveAsSource.snapshot(files, "../x", File(root, "d")) }.code)
        val outside = File(root, "outside.txt").apply { writeText("secret") }
        Files.createSymbolicLink(File(workspace, "link.txt").toPath(), outside.toPath())
        assertEquals("invalid_arguments", assertThrows(SaveAsSource.Refusal::class.java) { SaveAsSource.snapshot(files, "link.txt", File(root, "d")) }.code)
    }

    @Test fun stagingIsBoundedSweptAndInterruptedWritesAreDiscarded() {
        val staging = SaveAsStaging.shared(File(root, "saveas"))
        // A failed fill removes its directory and releases its admission.
        assertThrows(java.io.IOException::class.java) { staging.stage("app", "bad") { throw java.io.IOException() } }
        assertFalse(staging.exists("app", "bad"))
        assertEquals(0, staging.activeCount())
        staging.stage("app", "r1") { it.writeText("1") }
        staging.discard("app", "r1")
        assertFalse(File(root, "saveas/app").exists())
        assertEquals(0, staging.activeCount())
    }

    @Test fun admissionIsAtomicAcrossThreadsAndEveryWayOfObtainingTheStore() {
        val directory = File(root, "saveas")
        val threads = 24
        val start = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
        val results = (0 until threads).map { i ->
            pool.submit<String> {
                // Each caller asks for the store separately, as every session does.
                val staging = SaveAsStaging.shared(File(directory.path))
                start.await()
                try { staging.stage("app$i", "r$i") { it.writeText("x") }; "ok" } catch (e: StagingRefused) { e.code }
            }
        }
        start.countDown()
        val outcomes = results.map { it.get(20, java.util.concurrent.TimeUnit.SECONDS) }
        pool.shutdownNow()
        assertEquals(SaveAsStaging.MAX_ACTIVE, outcomes.count { it == "ok" })
        assertEquals(threads - SaveAsStaging.MAX_ACTIVE, outcomes.count { it == "busy" })
        assertEquals(SaveAsStaging.MAX_ACTIVE, SaveAsStaging.shared(directory).activeCount())
        assertEquals(SaveAsStaging.MAX_ACTIVE, directory.walkTopDown().count { it.isFile })
    }

    @Test fun sweepsSkipRequestsThisProcessOwnsButDiscardInterruptedAndExpiredOrphans() {
        val directory = File(root, "saveas")
        val staging = SaveAsStaging.shared(directory)
        staging.stage("app", "owned") { it.writeText("o") }
        fun orphan(id: String, marker: Boolean = false) = File(directory, "app/$id").apply { mkdirs(); File(this, "data").writeText(id); if (marker) File(this, "writing").writeText("1") }
        val later = System.currentTimeMillis() + SaveAsStaging.RETENTION_MS + 1000
        val interrupted = orphan("interrupted", marker = true).also { it.setLastModified(later) }
        val old = orphan("old").also { it.setLastModified(1000) }
        val adopted = orphan("adopted").also { it.setLastModified(1000) }
        assertTrue(staging.adopt("app", "adopted"))
        staging.markWriting("app", "owned")
        staging.sweep()
        assertTrue("an owned request is not swept by file age", staging.exists("app", "owned"))
        assertTrue("an adopted (restored) request is owned too", adopted.exists())
        assertFalse(old.exists())
        assertTrue(interrupted.exists())
        // Ownership that never led to a write (the picker result never came) expires; a write in progress does not.
        staging.sweep(now = later)
        assertTrue("a request being written is never swept, however old", staging.exists("app", "owned"))
        assertFalse("abandoned ownership expires", adopted.exists())
        assertEquals(1, staging.activeCount())
        assertTrue(interrupted.exists())
        // At process start nothing is owned: an interrupted write is discarded, never retried.
        SaveAsStaging.shared(File(root, "fresh")).also { fresh ->
            val leftover = File(root, "fresh/app/r").apply { mkdirs(); File(this, "writing").writeText("1"); File(this, "data").writeText("x") }
            fresh.sweep(startup = true)
            assertFalse(leftover.exists())
        }
        staging.sweep(startup = true)
        assertFalse("startup sweep also removes interrupted writes that are not owned", interrupted.exists())
        assertTrue(staging.adopt("app", "owned"))
        assertFalse("a request that is gone cannot be adopted", staging.adopt("app", "nothing"))
    }

    @Test fun deletionRacesCannotLeaveOrphanStaging() {
        val directory = File(root, "saveas")
        val staging = SaveAsStaging.shared(directory)
        // Deleted between the existence check and admission.
        val refused = assertThrows(StagingRefused::class.java) {
            staging.stage("app", "r1", exists = { staging.deleteApp("app"); true }) { it.writeText("x") }
        }
        assertEquals("gone", refused.code)
        assertFalse(File(directory, "app").exists())
        assertEquals(0, staging.activeCount())
        // Deleted while the data file is being filled.
        assertThrows(java.io.IOException::class.java) {
            staging.stage("app", "r2") { target -> staging.deleteApp("app"); java.io.FileOutputStream(target).use { it.write(1) } }
        }
        assertFalse(File(directory, "app").exists())
        assertEquals(0, staging.activeCount())
        // Another app is unaffected, and a later request of the same app works.
        staging.stage("other", "r3") { it.writeText("y") }
        staging.deleteApp("app")
        assertTrue(staging.exists("other", "r3"))
        staging.stage("app", "r4") { it.writeText("z") }
        assertTrue(staging.exists("app", "r4"))
    }
}
