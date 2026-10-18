package com.wynh.appapp.workspace

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LibraryRecoveryDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun damagedIndexRecoveryPreservesValidOrderAndWorkspaceBytes() {
        val repository = SubappRepository(context); repository.ensureMigrated()
        val index = File(context.filesDir, "subapps/index.json")
        val before = index.readBytes()
        val one = repository.createStarter(); val two = repository.createStarter()
        try {
            File(one.workspaceDir, "data/sentinel.json").writeText("one")
            File(two.workspaceDir, "data/sentinel.json").writeText("two")
            index.writeText("[\"${two.id}\",\"bad\",\"${one.id}\",\"${two.id}\"]")
            val recovered = repository.recoverLibrary()
            assertEquals(listOf(two.id, one.id), recovered.take(2).map { it.id })
            assertEquals("one", File(one.workspaceDir, "data/sentinel.json").readText())
            assertEquals("two", File(two.workspaceDir, "data/sentinel.json").readText())
            assertEquals(recovered.map { it.id }, SubappRepository(context).list().map { it.id })
        } finally {
            // Restore a valid index even if the test stopped before deliberate recovery.
            WorkspaceFiles.atomicWrite(index, before)
            repository.delete(one.id); repository.delete(two.id)
            WorkspaceFiles.atomicWrite(index, before)
        }
    }

    @Test fun refusedDeletionLeavesWorkspaceAndReminderStateUntouched() {
        val repository = SubappRepository(context); repository.ensureMigrated()
        val record = repository.createStarter()
        val preferences = context.getSharedPreferences("runtime_scheduled_alarms_${record.id}", 0)
        try {
            preferences.edit().putString("test-sentinel", "keep").commit()
            WorkspaceOperations.reserve(record.workspaceDir).use {
                assertTrue(runCatching { repository.delete(record.id) }.isFailure)
                assertTrue(File(record.workspaceDir, "index.html").isFile)
                assertEquals("keep", preferences.getString("test-sentinel", null))
            }
        } finally { preferences.edit().clear().commit(); repository.delete(record.id) }
    }
}
