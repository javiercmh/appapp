package com.wynh.appapp.workspace

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LibraryIndexTest {
    @get:Rule val temporary = TemporaryFolder()
    private val id = "0f8fad5b-d9cb-469f-a165-70867728950e"
    @Test fun corruptIndexBlocksPublicationAndKeepsBytes() {
        val file = temporary.newFile("index.json").apply { writeText("broken") }; val index = LibraryIndex(file)
        assertTrue(index.read() is LibraryState.Unreadable)
        assertTrue(runCatching { index.write(listOf(id)) }.isFailure)
        assertEquals("broken", file.readText())
        index.recover(listOf(id)); assertEquals(listOf(id), index.ids())
    }
    @Test fun missingIndexWithAppDirectoriesRequiresRecovery() {
        val file = File(temporary.root, "index.json"); val index = LibraryIndex(file)
        assertEquals(LibraryState.Missing, index.read()); assertEquals(emptyList<String>(), index.ids())
        File(temporary.root, id).mkdirs()
        assertTrue(runCatching { index.write(emptyList()) }.isFailure)
        index.recover(listOf(id)); assertEquals(listOf(id), LibraryIndex(file).ids())
    }
    @Test fun validOrderSurvivesRecreationAndDeliberateRecoveryAppendsDiscoveries() {
        val second = "1f8fad5b-d9cb-469f-a165-70867728950e"
        val file = File(temporary.root, "index.json"); val index = LibraryIndex(file)
        index.write(listOf(second)); index.recover(listOf(id,second))
        assertEquals(listOf(second,id), LibraryIndex(file).ids())
        index.write(listOf(id,second)); assertEquals(listOf(id,second), LibraryIndex(file).ids())
    }
    @Test fun recoverySalvagesValidIdentitiesInOrderAndKeepsTheDamagedBytes() {
        val second = "1f8fad5b-d9cb-469f-a165-70867728950e"; val third = "2f8fad5b-d9cb-469f-a165-70867728950e"
        val file = File(temporary.root, "index.json"); val index = LibraryIndex(file)
        val damaged = "[\"$second\",\"../escape\",3,\"$id\",\"$second\"]"
        file.writeText(damaged); assertTrue(index.read() is LibraryState.Unreadable)
        index.recover(listOf(third, id))
        assertEquals(listOf(second, id, third), LibraryIndex(file).ids())
        val aside = temporary.root.listFiles()!!.single { it.name.startsWith("index.damaged-") }
        assertEquals(damaged, aside.readText())
    }
    @Test fun recoverySalvagesIdentitiesFromATruncatedIndex() {
        val second = "1f8fad5b-d9cb-469f-a165-70867728950e"
        val file = File(temporary.root, "index.json"); val index = LibraryIndex(file)
        file.writeText("[\"$second\",\"$id\",\"2f8fad5b-d9cb-469f-a1")
        index.recover(emptyList()); assertEquals(listOf(second, id), LibraryIndex(file).ids())
    }
    @Test fun invalidAndDuplicateIdentitiesAreUnreadable() {
        val file = temporary.newFile("index.json"); val index = LibraryIndex(file)
        listOf("{}", "[3]", "[\"../escape\"]", "[\"$id\",\"$id\"]").forEach { text ->
            file.writeText(text); assertTrue(text, index.read() is LibraryState.Unreadable)
        }
    }
}
