package com.wynh.appapp.workspace

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LibraryPublicationTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun build(directory: File) { File(directory, "workspace").mkdirs(); File(directory, "workspace/index.html").writeText("new app") }
    @Test fun publishesOnlyAfterDirectoryPreparation() {
        val publisher = LibraryPublication(temporary.root)
        val app = publisher.publish { directory ->
            assertEquals(emptyList<String>(), LibraryIndex(File(temporary.root, "index.json")).ids())
            build(directory)
        }
        assertEquals(listOf(app.id), LibraryIndex(File(temporary.root, "index.json")).ids())
        assertEquals("new app", File(app.workspaceDir, "index.html").readText())
    }
    @Test fun failedBuildDoesNotRegisterOrLeaveDirectories() {
        assertTrue(runCatching { LibraryPublication(temporary.root).publish { directory -> build(directory); error("copy failed") } }.isFailure)
        assertEquals(listOf("index.json"), temporary.root.list()!!.toList())
        assertEquals(emptyList<String>(), LibraryIndex(File(temporary.root, "index.json")).ids())
    }
    @Test fun interruptedPublicationIsRecoveredWithoutTouchingRegisteredApps() {
        val publisher = LibraryPublication(temporary.root); val retained = publisher.publish(build = ::build)
        runCatching { publisher.publish(beforeIndexWrite = { throw AssertionError("process ended") }, build = ::build) }
        assertTrue(File(temporary.root, "new-app-pending").isFile)
        LibraryPublication(temporary.root).recover()
        assertEquals(listOf(retained.id), LibraryIndex(File(temporary.root, "index.json")).ids())
        assertEquals(setOf("index.json", retained.id), temporary.root.list()!!.toSet())
        assertEquals("new app", File(retained.workspaceDir, "index.html").readText())
    }
    @Test fun completedIndexPublicationSurvivesPendingCleanup() {
        val publisher = LibraryPublication(temporary.root); val retained = publisher.publish(build = ::build)
        File(temporary.root, "new-app-pending").writeText(retained.id)
        publisher.recover(); assertTrue(retained.workspaceDir.isDirectory)
        assertEquals(listOf(retained.id), LibraryIndex(File(temporary.root, "index.json")).ids())
    }
}
