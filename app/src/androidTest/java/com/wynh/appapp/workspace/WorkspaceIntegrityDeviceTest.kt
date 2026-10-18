package com.wynh.appapp.workspace

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wynh.appapp.bridge.NativeStorageBridge
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Uses only a disposable cache directory and a fresh bridge identity; never a user subapp. */
@RunWith(AndroidJUnit4::class)
class WorkspaceIntegrityDeviceTest {
    @Test fun inspectionDoesNotSeedMissingEntrypointAndBridgeNeedsFreshDocumentAfterReplacement() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "workspace-integrity-").toFile()
        val id = UUID.randomUUID().toString()
        try {
            val root = File(directory, "workspace")
            File(root, "data").mkdirs(); File(root, "data/records.json").writeText("saved")
            val manager = WorkspaceManager(context, workspaceDir = root)
            assertEquals("saved", manager.readFile("data/records.json")); assertFalse(File(root, "index.html").exists())
            manager.listFiles(); assertFalse(File(root, "index.html").exists())
            val bridge = NativeStorageBridge(context, id, root)
            assertTrue(bridge.writeFile("index.html", "old"))
            WorkspaceOperations.run(root) {
                assertFalse(bridge.writeFile("data/records.json", "during operation"))
                WorkspaceOperations.restoreText(root, mapOf("index.html" to "new", "data/records.json" to "restored"))
            }
            assertFalse(bridge.writeFile("data/records.json", "stale document"))
            assertEquals("restored", File(root, "data/records.json").readText())
            bridge.pageStarted()
            assertTrue(bridge.writeFile("data/records.json", "fresh document"))
            assertEquals("fresh document", bridge.readFile("data/records.json"))
        } finally { directory.deleteRecursively(); context.deleteSharedPreferences("runtime_app_state_$id") }
    }
}
