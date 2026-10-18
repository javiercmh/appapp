package com.wynh.appapp.workspace

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Disposable cache directories only; never a user subapp. */
@RunWith(AndroidJUnit4::class)
class WorkspaceHistoryDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun withApp(block: (appDirectory: File, workspace: File) -> Unit) {
        val directory = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "history-").toFile()
        try {
            val workspace = File(directory, "workspace").apply { mkdirs() }
            File(workspace, "index.html").writeText("<h1>v1</h1>")
            block(directory, workspace)
        } finally { directory.deleteRecursively() }
    }

    @Test fun aNonUtf8TextFileDoesNotBlockVersioningAndSurvivesRestore() = withApp { directory, workspace ->
        val windows1252 = byteArrayOf(0x63, 0xE9.toByte(), 0x0A)
        File(workspace, "legacy.csv").writeBytes(windows1252)
        val manager = WorkspaceManager(context, workspaceDir = workspace)
        val history = WorkspaceHistoryManager(context, appDirectory = directory)
        val first = history.saveSnapshot(manager, "first")
        assertNotNull("one undecodable file must not make saving history fail", first)
        assertFalse("legacy.csv" in first!!.files)
        assertTrue(manager.writeFile("index.html", "<h1>v2</h1>"))
        assertNotNull(history.saveSnapshot(manager, "second"))
        assertTrue(history.restoreSnapshot(first.id, manager))
        assertEquals("<h1>v1</h1>", File(workspace, "index.html").readText())
        assertArrayEquals(windows1252, File(workspace, "legacy.csv").readBytes())
    }

    @Test fun damagedHistoryIsKeptAsideAndSavingContinues() = withApp { directory, workspace ->
        val damaged = "[{\"id\":"
        File(directory, "workspace_history.json").writeText(damaged)
        val manager = WorkspaceManager(context, workspaceDir = workspace)
        val history = WorkspaceHistoryManager(context, appDirectory = directory)
        assertNotNull("a damaged history file must not block saving", history.saveSnapshot(manager, "after damage"))
        val aside = directory.listFiles()!!.single { it.name.startsWith("workspace_history.damaged-") }
        assertEquals("the damaged bytes are retained", damaged, aside.readText())
        assertEquals(1, history.loadUserSnapshots().size)
    }
}
