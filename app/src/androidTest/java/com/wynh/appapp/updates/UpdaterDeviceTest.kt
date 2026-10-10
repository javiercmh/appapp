package com.wynh.appapp.updates

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UpdaterDeviceTest {
    @Test fun invalidZipImportsDoNotReplaceWorkspace() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "zip-device-test")
        root.deleteRecursively(); root.mkdirs()
        val workspace = File(root, "workspace").apply { mkdirs() }
        File(workspace, "index.html").writeText("existing app")
        val manager = com.wynh.appapp.workspace.WorkspaceManager(context, workspaceDir = workspace)
        val history = com.wynh.appapp.workspace.WorkspaceHistoryManager(context, appDirectory = root)
        fun archive(entries: List<Pair<String, ByteArray>>): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            java.util.zip.ZipOutputStream(output).use { zip ->
                entries.forEach { (name, content) -> zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(content); zip.closeEntry() }
            }
            return output.toByteArray()
        }
        try {
            val cases = listOf(
                listOf("../outside" to "bad".toByteArray()),
                listOf("data/only.json" to "{}".toByteArray()),
                listOf("index.html" to ByteArray(8 * 1024 * 1024 + 1)),
                listOf("index.html" to "new".toByteArray(), "a/../index.html" to "alias".toByteArray())
            )
            cases.forEach { entries ->
                val result = com.wynh.appapp.workspace.WorkspacePackageManager.importFromInputStream(archive(entries).inputStream(), manager, history, false)
                assertTrue(result.isFailure)
                assertEquals("existing app", File(workspace, "index.html").readText())
                assertFalse(File(root, "outside").exists())
                assertFalse(root.listFiles()!!.any { it.name.startsWith("workspace_temp_") })
            }
            val result = com.wynh.appapp.workspace.WorkspacePackageManager.importFromInputStream(archive(listOf("index.html" to "valid".toByteArray(), "data/settings.json" to "{}".toByteArray())).inputStream(), manager, history, false)
            assertTrue(result.isSuccess)
            assertEquals("valid", File(workspace, "index.html").readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun updatesAndRecoversRealAndroidFilesWithoutTouchingLibrary() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "updater-device-test")
        root.deleteRecursively(); root.mkdirs()
        fun write(folder: File, path: String, bytes: ByteArray) { val f = File(folder, path); f.parentFile!!.mkdirs(); f.writeBytes(bytes) }
        fun text(folder: File, path: String, value: String) = write(folder, path, value.toByteArray())
        val workspace = File(root, "workspace")
        text(workspace, "index.html", "old")
        text(workspace, "data/settings.json", "personal settings")
        text(workspace, "data/stories.json", "saved stories")
        val binary = byteArrayOf(0, -1, 0, 42)
        write(workspace, "photos/test.png", binary)
        val manifest = """{"version":"1","app2Update":{"appId":"test.device","minimumHostVersionCode":4,"github":{"owner":"test","repository":"app","branch":"main"},"managedFiles":["index.html","manifest.json","data/profiles.json"],"seedFiles":["data/settings.json"]}}"""
        val files = File(root, "download")
        text(files, "manifest.json", manifest); text(files, "index.html", "new")
        text(files, "data/profiles.json", "new profiles"); text(files, "data/settings.json", "seed")
        try {
            val updater = WorkspaceUpdater(root)
            updater.install(UpdatePackage.parse(manifest), files, "a".repeat(40), 4)
            assertEquals("new", File(workspace, "index.html").readText())
            assertEquals("personal settings", File(workspace, "data/settings.json").readText())
            assertEquals("saved stories", File(workspace, "data/stories.json").readText())
            assertArrayEquals(binary, File(workspace, "photos/test.png").readBytes())
            File(root, "update-journal").writeText("recover")
            File(workspace, "index.html").delete()
            WorkspaceUpdater(root).recover()
            assertEquals("old", File(workspace, "index.html").readText())
            assertArrayEquals(binary, File(workspace, "photos/test.png").readBytes())
        } finally { root.deleteRecursively() }
    }
}
