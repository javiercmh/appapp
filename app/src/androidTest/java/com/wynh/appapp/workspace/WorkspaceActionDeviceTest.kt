package com.wynh.appapp.workspace

import android.app.Application
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wynh.appapp.bridge.NativeStorageBridge
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Uses a disposable cache directory and a fresh bridge identity; never a user subapp. */
@RunWith(AndroidJUnit4::class)
class WorkspaceActionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun idle(controller: WorkspaceActionController) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (controller.state.value.busy && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
        assertFalse("operation did not finish", controller.state.value.busy)
    }

    @Test fun onlyAReplacementFencesRunningPagesAndCompletionsSurviveUntilAcknowledged() {
        val directory = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "actions-").toFile()
        val id = UUID.randomUUID().toString()
        try {
            val workspace = File(directory, "workspace").apply { mkdirs() }
            File(workspace, "index.html").writeText("old")
            val record = SubappRecord(id, directory)
            lateinit var controller: WorkspaceActionController
            instrumentation.runOnMainSync { controller = WorkspaceActionController(context.applicationContext as Application) }
            val bridge = NativeStorageBridge(context, id, workspace)
            bridge.pageStarted()
            assertTrue(bridge.writeFile("data/a.json", "1"))

            // A single-file save must not make a running page reload or lose write access.
            instrumentation.runOnMainSync {
                controller.change(record, "Edited note.txt",
                    WorkspaceActionController.Completion(WorkspaceActionController.Kind.FILE_SAVED, "note.txt", "text")) { it.writeFile("note.txt", "text") }
            }
            idle(controller)
            assertEquals("text", File(workspace, "note.txt").readText())
            assertTrue("running page lost write access after a plain save", bridge.writeFile("data/a.json", "2"))
            val saved = controller.state.value.completions.single()
            assertEquals(WorkspaceActionController.Kind.FILE_SAVED, saved.kind)
            instrumentation.runOnMainSync { controller.acknowledge(saved.id) }
            assertTrue(controller.state.value.completions.isEmpty())

            // A factory reset is a replacement: reserved, fenced, off the UI thread, history reset in the same action.
            var onMainThread = true
            instrumentation.runOnMainSync {
                controller.execute(record, "Resetting app…", completion = WorkspaceActionController.Completion(WorkspaceActionController.Kind.RESET)) { manager, history ->
                    onMainThread = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
                    check(WorkspaceOperations.isBusy(workspace))
                    check(manager.factoryResetWorkspace()); check(history.clearAllHistory())
                }
            }
            idle(controller)
            assertFalse("reset ran on the UI thread", onMainThread)
            assertFalse(WorkspaceOperations.isBusy(workspace))
            assertFalse("a page from before the reset can still write", bridge.writeFile("data/a.json", "stale"))
            assertEquals(WorkspaceActionController.Kind.RESET, controller.state.value.completions.single().kind)
            bridge.pageStarted()
            assertTrue("the reloaded page is writable again", bridge.writeFile("data/a.json", "fresh"))
        } finally { directory.deleteRecursively(); context.deleteSharedPreferences("runtime_app_state_$id") }
    }

    @Test fun cleanupRunsWhenAnOperationIsRefused() {
        val directory = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "actions-").toFile()
        try {
            val workspace = File(directory, "workspace").apply { mkdirs(); File(this, "index.html").writeText("old") }
            val staged = File(directory, "staged").apply { mkdirs(); File(this, "x").writeText("x") }
            lateinit var controller: WorkspaceActionController
            instrumentation.runOnMainSync { controller = WorkspaceActionController(context.applicationContext as Application) }
            WorkspaceOperations.reserve(workspace).use {
                instrumentation.runOnMainSync {
                    controller.execute(SubappRecord(UUID.randomUUID().toString(), directory), "Updating", cleanup = { staged.deleteRecursively() }) { _, _ -> fail("must not run") }
                }
                val deadline = SystemClock.uptimeMillis() + 10_000
                while (staged.exists() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
                assertFalse("staged update leaked after a refused operation", staged.exists())
                assertTrue(controller.state.value.error)
            }
        } finally { directory.deleteRecursively() }
    }
}
