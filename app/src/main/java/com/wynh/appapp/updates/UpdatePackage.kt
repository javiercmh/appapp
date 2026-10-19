package com.wynh.appapp.updates

import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class GitHubSource(val owner: String, val repository: String, val branch: String) {
    init {
        require(owner.matches(Regex("[A-Za-z0-9_.-]+")) && repository.matches(Regex("[A-Za-z0-9_.-]+"))) { "Invalid GitHub repository" }
        require(branch.isNotBlank() && branch.length <= 200 && !branch.any { it.isISOControl() }) { "Invalid GitHub branch" }
    }
    val display: String get() = "$owner/$repository ($branch)"
}

data class UpdatePackage(
    val appId: String, val version: String, val minimumHostVersion: Long,
    val managed: Set<String>, val seeds: Set<String>, val source: GitHubSource
) {
    companion object {
        fun parse(text: String): UpdatePackage {
            val root = JsonParser.parseString(text).asJsonObject
            val update = root.getAsJsonObject("app2Update") ?: error("This app has no app2Update metadata")
            fun paths(key: String): Set<String> {
                val items = update.getAsJsonArray(key)?.map { safePath(it.asString) }.orEmpty()
                require(items.size == items.distinct().size) { "Duplicate $key paths" }
                return items.toSet()
            }
            val managed = paths("managedFiles")
            val seeds = paths("seedFiles")
            require(managed.size + seeds.size <= 256 && managed.containsAll(listOf("index.html", "manifest.json"))) { "Package must manage index.html and manifest.json (maximum 256 files)" }
            require(managed.intersect(seeds).isEmpty()) { "Managed and seed files overlap" }
            val all = managed + seeds
            require(all.none { path -> all.any { other -> other.startsWith("$path/") } }) { "File and directory paths overlap" }
            require("app.log" !in all) { "app.log belongs to the user" }
            val id = update.get("appId").asString
            require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{2,127}"))) { "Invalid publisher app ID" }
            val source = update.getAsJsonObject("github")
            val version = root.get("version").asString
            require(version.isNotBlank() && version.length <= 100) { "Invalid version" }
            val minimum = update.get("minimumHostVersionCode").asLong
            require(minimum > 0) { "Invalid minimum host version" }
            return UpdatePackage(id, version, minimum, managed, seeds,
                GitHubSource(source.get("owner").asString, source.get("repository").asString, source.get("branch").asString))
        }
    }
}

/** Shown when a package needs a newer host compatibility level than this App² supports. */
internal fun incompatibleHostMessage(required: Long, supported: Long) =
    "This update requires App² compatibility level $required, but this App² supports level $supported. Update App² first, then try again."

fun safePath(path: String): String {
    require(path.isNotBlank() && path.length <= 240 && !path.contains('\\') && !path.startsWith('/') &&
        path.split('/').all { it.isNotBlank() && it != "." && it != ".." && !it.startsWith('.') && !it.contains(':') && !it.any(Char::isISOControl) }) { "Unsafe package path: $path" }
    return path
}

fun digest(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(32768)
        while (true) { val count = input.read(buffer); if (count < 0) break; md.update(buffer, 0, count) }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/** File operations and updates share one lock per workspace. */
object WorkspaceLocks {
    private val locks = ConcurrentHashMap<String, Any>()
    fun forWorkspace(directory: File): Any = locks.getOrPut(directory.absolutePath) { Any() }
}
