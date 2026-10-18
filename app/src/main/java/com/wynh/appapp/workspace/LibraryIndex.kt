package com.wynh.appapp.workspace

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File
import java.util.UUID

sealed interface LibraryState {
    data class Loaded(val ids: List<String>) : LibraryState
    data object Missing : LibraryState
    data class Unreadable(val reason: String) : LibraryState
}

/** An unreadable library must never become an empty replacement. */
class LibraryIndex(private val file: File) {
    fun read(): LibraryState {
        if (!file.exists()) return LibraryState.Missing
        return try {
            val json = JsonParser.parseString(file.readText())
            require(json.isJsonArray) { "Expected an app identity list" }
            val ids = json.asJsonArray.map { value ->
                require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
                value.asString.also { require(validId(it)) { "Invalid app identity" } }
            }
            require(ids.distinct().size == ids.size) { "Duplicate app identities" }
            LibraryState.Loaded(ids)
        } catch (e: Exception) { LibraryState.Unreadable(e.message ?: "Could not read library") }
    }
    fun ids(): List<String> = when (val value = read()) {
        is LibraryState.Loaded -> value.ids
        LibraryState.Missing -> {
            check(file.parentFile?.listFiles().orEmpty().none { it.isDirectory && validId(it.name) }) {
                "The library index is missing. Recover the library before making changes."
            }
            emptyList()
        }
        is LibraryState.Unreadable -> error("The library index could not be read. Recover the library before making changes.")
    }
    fun write(ids: List<String>) {
        ids() // A corrupt index is never silently replaced by normal operations.
        require(ids.all(::validId) && ids.distinct().size == ids.size)
        WorkspaceFiles.atomicWrite(file, Gson().toJson(ids).toByteArray(Charsets.UTF_8))
    }
    /**
     * Deliberate recovery only: keep every valid identity in its stored order, then append discovered
     * directories. A damaged file is copied aside first, so no bytes are lost by rewriting it.
     */
    fun recover(discovered: List<String>) {
        require(discovered.all(::validId))
        val state = read()
        val current = when (state) {
            is LibraryState.Loaded -> state.ids
            is LibraryState.Unreadable -> {
                file.copyTo(File(file.parentFile, "index.damaged-${System.currentTimeMillis()}.json"), overwrite = false)
                salvage()
            }
            LibraryState.Missing -> emptyList()
        }
        val ids = (current + discovered.sorted()).distinct()
        WorkspaceFiles.atomicWrite(file, Gson().toJson(ids).toByteArray(Charsets.UTF_8))
    }

    /** Valid identities from a damaged file, in order: array elements when it parses, else a text scan of a truncated file. */
    private fun salvage(): List<String> {
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        val parsed = runCatching { JsonParser.parseString(text) }.getOrNull()
        val candidates = if (parsed != null && parsed.isJsonArray) {
            parsed.asJsonArray.mapNotNull { value -> value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString }
        } else UUID_TEXT.findAll(text).map { it.value }.toList()
        return candidates.filter(::validId).distinct()
    }

    companion object {
        private val UUID_TEXT = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        fun validId(id: String): Boolean = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
    }
}
