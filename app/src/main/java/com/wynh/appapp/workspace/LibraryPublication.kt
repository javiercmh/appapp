package com.wynh.appapp.workspace

import java.io.File
import java.util.UUID

/** A new app becomes registered only after its complete directory has been prepared. */
class LibraryPublication(private val root: File) {
    private val index = LibraryIndex(File(root, "index.json"))
    private val pending = File(root, "new-app-pending")
    private val staging = File(root, "new-app-staging")

    fun publish(beforeIndexWrite: () -> Unit = {}, build: (File) -> Unit): SubappRecord {
        recover()
        if (index.read() == LibraryState.Missing) index.write(emptyList())
        val current = index.ids()
        val id = UUID.randomUUID().toString()
        val directory = File(root, id)
        WorkspaceFiles.atomicWrite(pending, id.toByteArray(Charsets.UTF_8))
        try {
            check(staging.mkdirs()) { "Could not stage new app" }
            build(staging)
            require(File(staging, "workspace/index.html").isFile) { "App must contain index.html" }
            // As in a directory replacement, the bytes are durable before the app can be registered.
            WorkspaceFiles.syncTree(staging)
            check(staging.renameTo(directory)) { "Could not publish new app" }
            beforeIndexWrite()
            index.write(current + id)
            pending.delete() // Index publication is the commit point; cleanup cannot undo it.
            return SubappRecord(id, directory)
        } catch (e: Exception) { recover(); throw e }
        finally { staging.deleteRecursively() }
    }

    fun recover() {
        if (pending.isFile) {
            val id = pending.readText()
            require(LibraryIndex.validId(id)) { "Invalid pending app identity" }
            if (id !in index.ids()) check(!File(root, id).exists() || File(root, id).deleteRecursively()) { "Could not recover app publication" }
            check(pending.delete()) { "Could not finish app publication recovery" }
        }
        check(!staging.exists() || staging.deleteRecursively()) { "Could not clean app staging" }
    }
}
