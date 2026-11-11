package com.wynh.appapp.channel

import com.wynh.appapp.updates.WorkspaceLocks
import com.wynh.appapp.workspace.WorkspaceOperations
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DocumentGuardTest {
    private val base: File = Files.createTempDirectory("guard").toFile()
    private val root = File(base, "workspace").apply { mkdirs() }
    private val guard = DocumentGuard(root)
    private val pool = Executors.newCachedThreadPool()
    @After fun tearDown() { pool.shutdownNow(); base.deleteRecursively() }
    private fun busy(block: () -> Unit) = assertEquals("busy", assertThrows(ChannelException::class.java) { block() }.code)

    @Test fun aCurrentRequestReadsUnderTheLock() {
        assertEquals("read", guard.underLock(guard.version(), { true }) { "read" })
    }

    @Test fun aQueuedReadFromAnOldPageIsRefusedAfterThePageChanged() {
        val oldVersion = guard.version()
        val page = AtomicInteger(0)
        val lock = WorkspaceLocks.forWorkspace(root)
        var ran = false
        val queued = synchronized(lock) {
            // Accepted and queued: waiting for the workspace lock.
            val future = pool.submit<String> {
                try { guard.underLock(oldVersion, { page.get() == 0 }) { ran = true; "read" } } catch (e: ChannelException) { e.code }
            }
            Thread.sleep(200)
            // The page is replaced and the new page binds the current generation meanwhile.
            page.incrementAndGet(); guard.pageStarted()
            future
        }
        assertEquals("busy", queued.get(10, TimeUnit.SECONDS))
        assertFalse(ran)
    }

    @Test fun aReplacementAdvancesTheGenerationSoOldRequestsAreRefusedEvenAfterTheNewPageBound() {
        val oldVersion = guard.version()
        WorkspaceOperations.run(root) { }
        guard.pageStarted()
        assertNotEquals(oldVersion, guard.version())
        var ran = false
        busy { guard.underLock(oldVersion) { ran = true } }
        assertFalse(ran)
        assertEquals("a request of the new page works", "ok", guard.underLock(guard.version()) { "ok" })
    }

    @Test fun aReservedWorkspaceRefusesAndAClosedSessionRefuses() {
        val version = guard.version()
        WorkspaceOperations.reserve(root, replacement = false).use { busy { guard.underLock(version) { } } }
        busy { guard.underLock(version, { false }) { } }
        assertFalse(WorkspaceOperations.isBusy(root))
    }
}
