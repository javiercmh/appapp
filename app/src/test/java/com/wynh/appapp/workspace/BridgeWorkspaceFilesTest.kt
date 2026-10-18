package com.wynh.appapp.workspace

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.Base64

class BridgeWorkspaceFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    private val logs = mutableListOf<String>()

    private fun workspace(): File = File(temporary.root, "subapps/a/workspace").apply { mkdirs() }
    private fun files(root: File) = BridgeWorkspaceFiles(root) { logs += it }

    @Test fun keepsHistoricalMappingForOtherCharacters() {
        assertEquals("my_file.json", WorkspaceFiles.normalize("my file.json"))
        assertEquals("data/x.json", WorkspaceFiles.normalize(" data/x.json"))
        assertEquals("caf_.json", WorkspaceFiles.normalize("café.json"))
        assertEquals("_2e_2e/x", WorkspaceFiles.normalize("%2e%2e/x"))
        assertEquals("C_/x", WorkspaceFiles.normalize("C:/x"))
        assertEquals("_/x", WorkspaceFiles.normalize("~/x"))
    }

    @Test fun acceptsConventionalFormsAndNormalizesHarmlessVariants() {
        assertEquals("data/entries.json", WorkspaceFiles.normalize("data/entries.json"))
        assertEquals("data/entries.json", WorkspaceFiles.normalize("/data/entries.json"))
        assertEquals("data/entries.json", WorkspaceFiles.normalize("./data/entries.json"))
        assertEquals("data/entries.json", WorkspaceFiles.normalize("data//./entries.json"))
        assertEquals("photo_1700000000000.jpg", WorkspaceFiles.normalize("photo_1700000000000.jpg"))
        assertEquals("app.log", WorkspaceFiles.normalize("app.log"))
        assertEquals(".hidden", WorkspaceFiles.normalize(".hidden"))
    }

    @Test fun rejectsUnsafePaths() {
        val rejected = listOf(
            "", "/", "./", "//", "data/", "..", "../x.json", "data/../../x", "a/..", " .. /x", "data\\x.json",
            "..\\..\\x", "x\u0000.json", "x\n.json", "x\u007f"
        )
        for (path in rejected) {
            var reason: String? = null
            assertNull("accepted '$path'", WorkspaceFiles.normalize(path) { reason = it })
            assertNotNull(reason)
        }
    }

    @Test fun enforcesLengthAndDepthLimits() {
        val segment = "a".repeat(BridgeFileLimits.MAX_SEGMENT_CHARS)
        assertEquals(segment, WorkspaceFiles.normalize(segment))
        assertNull(WorkspaceFiles.normalize(segment + "a"))
        assertNull(WorkspaceFiles.normalize("a/".repeat(300) + "b"))
        assertNotNull(WorkspaceFiles.normalize(List(BridgeFileLimits.MAX_SEGMENTS) { "d" }.joinToString("/")))
        assertNull(WorkspaceFiles.normalize(List(BridgeFileLimits.MAX_SEGMENTS + 1) { "d" }.joinToString("/")))
    }

    @Test fun containmentRequiresAPathBoundary() {
        val root = "/data/subapps/a/workspace"
        assertTrue(WorkspaceFiles.isStrictlyInside(root, "$root/data/x.json"))
        assertFalse(WorkspaceFiles.isStrictlyInside(root, "$root-evil/x.json"))
        assertFalse(WorkspaceFiles.isStrictlyInside(root, "${root}evil"))
        assertFalse(WorkspaceFiles.isStrictlyInside(root, root))
        assertFalse(WorkspaceFiles.isStrictlyInside(root, "$root/"))
        assertTrue(WorkspaceFiles.isWithin(root, root))
        assertFalse(WorkspaceFiles.isWithin(root, "/data/subapps/a"))
    }

    @Test fun siblingPrefixWorkspaceIsNeverReached() {
        val root = workspace()
        val sibling = File(root.parentFile, "workspace-evil").apply { mkdirs() }
        File(sibling, "secret.json").writeText("secret")
        val bridge = files(root)
        assertEquals("", bridge.readText("../workspace-evil/secret.json"))
        assertEquals("", bridge.readText("/../workspace-evil/secret.json"))
        assertFalse(bridge.writeText("../workspace-evil/secret.json", "overwritten"))
        assertFalse(bridge.delete("../workspace-evil/secret.json"))
        assertEquals("secret", File(sibling, "secret.json").readText())
        assertFalse(File(root, "secret.json").exists())
    }

    @Test fun roundTripsTextAndBinaryAndCleansUpEmptyDirectories() {
        val root = workspace()
        val bridge = files(root)
        assertTrue(bridge.writeText("/data/nested/entries.json", "[1]"))
        assertEquals("[1]", bridge.readText("data/nested/entries.json"))
        assertTrue(bridge.exists("./data/nested/entries.json"))
        val bytes = byteArrayOf(0, -1, 42, 0, 7)
        val dataUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes)
        assertTrue(bridge.writeBase64("photo_1.jpg", dataUrl))
        assertArrayEquals(bytes, File(root, "photo_1.jpg").readBytes())
        assertEquals(Base64.getEncoder().encodeToString(bytes), bridge.readBase64("photo_1.jpg"))
        assertEquals(setOf("data/nested/entries.json", "photo_1.jpg"), bridge.list().map { it.name }.toSet())
        assertTrue(bridge.delete("data/nested/entries.json"))
        assertFalse(File(root, "data").exists())
        assertTrue(root.isDirectory)
        assertFalse(bridge.delete("data/nested/entries.json"))
    }

    @Test fun missingAndInvalidPathsReturnFailureValues() {
        val bridge = files(workspace())
        assertEquals("", bridge.readText("data/missing.json"))
        assertEquals("", bridge.readBase64("missing.png"))
        assertFalse(bridge.exists("missing.json"))
        assertFalse(bridge.writeText("", "x"))
        assertFalse(bridge.writeBase64("x\u0000.png", "AAAA"))
        assertFalse(bridge.writeBase64("x.png", "data:image/png;base64,"))
        assertFalse(bridge.writeBase64("x.png", "A==A"))
        assertTrue(logs.any { it.startsWith("[Storage Error] Refused write of 'x?.png'") })
    }

    @Test fun refusesSymbolicLinksAnywhereInThePath() {
        val root = workspace()
        val outside = temporary.newFolder("outside")
        File(outside, "secret.json").writeText("secret")
        Files.createSymbolicLink(File(root, "linkdir").toPath(), outside.toPath())
        Files.createSymbolicLink(File(root, "link.json").toPath(), File(outside, "secret.json").toPath())
        File(root, "data").mkdirs()
        Files.createSymbolicLink(File(root, "data/inner.json").toPath(), File(root, "index.html").toPath())
        File(root, "index.html").writeText("index")
        val bridge = files(root)

        for (path in listOf("linkdir/secret.json", "link.json", "data/inner.json")) {
            assertEquals(path, "", bridge.readText(path))
            assertEquals(path, "", bridge.readBase64(path))
            assertFalse(path, bridge.exists(path))
            assertFalse(path, bridge.writeText(path, "overwritten"))
            assertFalse(path, bridge.delete(path))
        }
        assertFalse(bridge.writeText("linkdir/new.json", "x"))
        assertFalse(File(outside, "new.json").exists())
        assertEquals("secret", File(outside, "secret.json").readText())
        assertEquals("index", File(root, "index.html").readText())
        assertTrue(Files.isSymbolicLink(File(root, "link.json").toPath()))
        assertEquals(listOf("index.html"), bridge.list().map { it.name })
        assertEquals(5L, WorkspaceFiles.usedBytes(root))
    }

    @Test fun rejectsOversizedWritesAndReads() {
        val root = workspace()
        val bridge = files(root)
        val tooLongText = "x".repeat(BridgeFileLimits.MAX_TEXT_BYTES.toInt() + 1)
        assertFalse(bridge.writeText("big.txt", tooLongText))
        // Within the char limit but over the byte limit once UTF-8 encoded.
        val multiByte = "\u00e9".repeat(BridgeFileLimits.MAX_TEXT_BYTES.toInt() / 2 + 1)
        assertFalse(bridge.writeText("big.txt", multiByte))
        assertFalse(File(root, "big.txt").exists())

        val tooBig = ByteArray(BridgeFileLimits.MAX_BINARY_BYTES.toInt() + 3)
        assertFalse(bridge.writeBase64("big.png", Base64.getEncoder().encodeToString(tooBig)))
        assertFalse(File(root, "big.png").exists())

        RandomAccessFile(File(root, "huge.json"), "rw").use { it.setLength(BridgeFileLimits.MAX_TEXT_BYTES + 1) }
        assertEquals("", bridge.readText("huge.json"))
        RandomAccessFile(File(root, "huge.png"), "rw").use { it.setLength(BridgeFileLimits.MAX_BINARY_BYTES + 1) }
        assertEquals("", bridge.readBase64("huge.png"))
    }

    @Test fun workspaceQuotaLimitsBridgeWritesOnly() {
        val root = workspace()
        val bridge = files(root)
        // Sparse file: counts its full length against the quota without using real disk space.
        val filler = File(root, "filler.bin")
        RandomAccessFile(filler, "rw").use { it.setLength(BridgeFileLimits.WORKSPACE_QUOTA_BYTES - 10) }
        assertTrue(bridge.writeText("small.json", "1234567890"))
        assertFalse(bridge.writeText("more.json", "1"))
        assertFalse(File(root, "more.json").exists())
        // Replacing a file counts only the net growth.
        assertTrue(bridge.writeText("small.json", "0987654321"))
        assertFalse(bridge.writeText("small.json", "09876543210"))
        assertEquals("0987654321", File(root, "small.json").readText())
        // Deleting frees quota again.
        assertTrue(bridge.delete("small.json"))
        assertTrue(bridge.writeText("more.json", "1"))
    }

    @Test fun directoryAtTargetPathIsNotOverwritten() {
        val root = workspace()
        File(root, "data/sub").mkdirs()
        assertFalse(files(root).writeText("data/sub", "x"))
        assertTrue(File(root, "data/sub").isDirectory)
    }
}
