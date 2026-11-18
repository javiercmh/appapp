package com.wynh.appapp.workspace

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import com.wynh.appapp.updates.digest

class SubappBackupArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun workspace(name: String): File = temporary.newFolder().also { root ->
        File(root, "index.html").writeText(name)
        File(root, "manifest.json").writeText("{\"name\":\"$name\"}")
        File(root, "data/nested").mkdirs()
        File(root, "data/nested/records.json").writeText("[1,2,3]")
        File(root, "photo.png").writeBytes(byteArrayOf(0, -1, 42, 0))
        File(root, "app.log").writeText("log")
    }
    private fun create(workspaces: List<File>): File = temporary.newFile().also { SubappBackupArchive.create(workspaces, it) }
    private fun restore(archive: File): List<File> = SubappBackupArchive.validate(archive.inputStream(), temporary.newFolder())
    private fun hashes(root: File) = root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).invariantSeparatorsPath to digest(it) }
    private fun rewrite(archive: File, change: (String, ByteArray) -> Pair<String, ByteArray>?): File {
        val output = temporary.newFile()
        ZipFile(archive).use { input -> ZipOutputStream(output.outputStream()).use { zip ->
            input.entries().toList().forEach { entry ->
                val changed = change(entry.name, input.getInputStream(entry).use { it.readBytes() })
                if (changed != null) {
                    zip.putNextEntry(ZipEntry(changed.first)); zip.write(changed.second); zip.closeEntry()
                }
            }
        } }
        return output
    }
    @Test fun roundTripPreservesOrderNestedDataAndBinaryBytes() {
        val source = listOf(workspace("second"), workspace("first"))
        val result = restore(create(source))
        assertEquals(source.map(::hashes), result.map(::hashes))
    }
    @Test fun emptyLibraryRoundTrips() { assertTrue(restore(create(emptyList())).isEmpty()) }
    @Test fun rejectsChangedMissingOrUnexpectedFiles() {
        val archive = create(listOf(workspace("one")))
        val mutations = listOf<(String, ByteArray) -> Pair<String, ByteArray>?>(
            { name, bytes -> name to if (name.endsWith("index.html")) "bad".toByteArray() else bytes },
            { name, bytes -> if (name.endsWith("manifest.json")) null else name to bytes },
            { name, bytes -> if (name.endsWith("photo.png")) "apps/0/extra.png" to bytes else name to bytes }
        )
        mutations.forEach { change -> assertThrows(Exception::class.java) { restore(rewrite(archive, change)) } }
    }
    @Test fun rejectsUnsupportedFormatAndTruncatedArchive() {
        val archive = create(listOf(workspace("one")))
        assertThrows(Exception::class.java) {
            restore(rewrite(archive) { name, bytes -> name to if (name == "backup.json") String(bytes).replace("\"version\":1", "\"version\":2").toByteArray() else bytes })
        }
        val broken = temporary.newFile().also { it.writeBytes(archive.readBytes().dropLast(24).toByteArray()) }
        assertThrows(Exception::class.java) { restore(broken) }
    }
    @Test fun rejectsTraversalAndAmbiguousPaths() {
        val root = temporary.newFolder()
        listOf("../escape", "/escape", "apps/../escape", "apps//file", "apps/./file", "apps\\file").forEach {
            assertThrows(IllegalArgumentException::class.java) { checkedZipDestination(root, it) }
        }
        val archive = create(listOf(workspace("one")))
        assertThrows(Exception::class.java) { restore(rewrite(archive) { name, bytes -> if (name.endsWith("photo.png")) "../escape" to bytes else name to bytes }) }
        assertFalse(File(root.parentFile, "escape").exists())
    }
    @Test fun rejectsDuplicatedManifestFiles() {
        val archive = create(listOf(workspace("one")))
        assertThrows(Exception::class.java) {
            restore(rewrite(archive) { name, bytes ->
                if (name != "backup.json") name to bytes else {
                    val json = com.google.gson.JsonParser.parseString(String(bytes)).asJsonObject
                    val files = json.getAsJsonArray("apps")[0].asJsonObject.getAsJsonArray("files")
                    files.add(files[0].deepCopy())
                    name to json.toString().toByteArray()
                }
            })
        }
    }
    @Test fun rejectsDuplicateZipEntries() {
        val workspace = workspace("one")
        File(workspace, "a.txt").writeText("one")
        File(workspace, "b.txt").writeText("two")
        val bytes = create(listOf(workspace)).readBytes()
        val source = "apps/0/b.txt".toByteArray()
        val replacement = "apps/0/a.txt".toByteArray()
        for (index in 0..bytes.size - source.size) {
            if (source.indices.all { bytes[index + it] == source[it] }) replacement.copyInto(bytes, index)
        }
        val duplicate = temporary.newFile().also { it.writeBytes(bytes) }
        assertThrows(Exception::class.java) { restore(duplicate) }
    }

    @Test fun rejectsMissingRequiredWorkspaceFilesAtExport() {
        val root = workspace("one")
        File(root, "manifest.json").delete()
        assertThrows(Exception::class.java) { create(listOf(root)) }
    }
}
