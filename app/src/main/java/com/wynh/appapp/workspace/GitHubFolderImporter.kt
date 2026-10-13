package com.wynh.appapp.workspace

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wynh.appapp.updates.GitHubClient
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * Downloads a public GitHub repository or repository subfolder into a temporary staging directory.
 * The requested branch is resolved to one commit and every listing and file is read at that commit.
 * Limits match ZIP import: 1024 entries, 8 MiB per file and 32 MiB in total.
 */
object GitHubFolderImporter {
    const val MAX_ENTRIES = 1024
    const val MAX_DEPTH = 16
    const val MAX_FILE_BYTES = 8L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 32L * 1024 * 1024
    const val TIMEOUT_MILLIS = 2 * 60_000L
    private const val MAX_LISTING_BYTES = 4L * 1024 * 1024

    data class Import(val directory: File, val commit: String)
    private data class FolderLink(val owner: String, val repo: String, val branch: String, val path: String)

    fun download(
        cacheDir: File,
        link: String,
        connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
        clock: () -> Long = System::currentTimeMillis,
    ): Result<Import> = runCatching {
        val parsed = parse(link)
        val client = GitHubClient(connect, "AppApp-Android", MAX_TOTAL_BYTES + 4 * MAX_LISTING_BYTES, maxRedirects = 3, timeoutMillis = TIMEOUT_MILLIS, clock = clock)
        fun json(url: String): JsonObject = runCatching {
            JsonParser.parseString(client.get(url, MAX_LISTING_BYTES, "application/vnd.github+json").toString(Charsets.UTF_8)).asJsonObject
        }.getOrElse { if (it is IllegalArgumentException) throw it else throw IllegalArgumentException("GitHub returned an invalid response") }
        val repo = "${parsed.owner}/${parsed.repo}"
        val api = "https://api.github.com/repos/$repo"
        val ref = parsed.branch.ifBlank {
            json(api).str("default_branch") ?: throw IllegalArgumentException("Could not find the repository's default branch")
        }
        val head = json("$api/commits/${encode(ref)}")
        val commit = head.str("sha").orEmpty()
        require(commit.matches(Regex("[a-f0-9]{40}"))) { "GitHub returned an invalid commit" }
        var tree = head.getAsJsonObject("commit")?.getAsJsonObject("tree")?.str("sha")
            ?: throw IllegalArgumentException("GitHub returned an invalid commit")
        val folder = parsed.path.split('/').filter { it.isNotBlank() }
        folder.forEach { segment ->
            tree = entries(json("$api/git/trees/$tree")).firstOrNull { it.str("path") == segment && it.str("type") == "tree" }
                ?.str("sha") ?: throw IllegalArgumentException("The GitHub folder was not found at commit ${commit.take(7)}")
        }
        val listing = json("$api/git/trees/$tree?recursive=1")
        require(listing.get("truncated")?.asBoolean != true) { "The GitHub folder is too large to import" }
        val items = entries(listing)
        require(items.size <= MAX_ENTRIES) { "The GitHub folder has more than $MAX_ENTRIES entries" }

        val destination = File(cacheDir, "github_import_${UUID.randomUUID()}")
        check(destination.mkdirs()) { "Could not create temporary import folder" }
        try {
            val seen = mutableSetOf<String>()
            var declared = 0L
            val files = items.mapNotNull { item ->
                val path = item.str("path").orEmpty()
                val target = checkedZipDestination(destination, path)
                require(path.count { it == '/' } < MAX_DEPTH) { "The GitHub folder is nested more than $MAX_DEPTH levels deep" }
                require(seen.add(target.canonicalPath)) { "GitHub returned a duplicate path: $path" }
                // Submodules ("commit") and symbolic links are not workspace files.
                if (item.str("type") != "blob" || item.str("mode") == "120000") return@mapNotNull null
                val size = item.get("size")?.asLong ?: 0L
                declared += size
                require(size <= MAX_FILE_BYTES && declared <= MAX_TOTAL_BYTES) { "The GitHub folder exceeds the import size limit" }
                path to target
            }
            require(files.any { it.first == "index.html" }) { "The selected GitHub folder must contain index.html" }
            val raw = "https://raw.githubusercontent.com/$repo/$commit/" + (folder + "").joinToString("/") { encode(it) }
            var written = 0L
            files.forEach { (path, target) ->
                val bytes = client.get(raw + encodePath(path), MAX_FILE_BYTES)
                written += bytes.size
                require(written <= MAX_TOTAL_BYTES) { "The GitHub folder exceeds the import size limit" }
                check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "Could not create folder for $path" }
                target.writeBytes(bytes)
            }
            Import(destination, commit)
        } catch (error: Exception) {
            destination.deleteRecursively()
            throw error
        }
    }

    private fun parse(link: String): FolderLink {
        val uri = runCatching { URL(link) }.getOrElse { throw IllegalArgumentException("Enter a valid GitHub folder URL") }
        require(uri.protocol == "https" && uri.host.equals("github.com", ignoreCase = true)) {
            "Use a public github.com folder URL"
        }
        val parts = uri.path.trim('/').split('/').filter { it.isNotBlank() }
        require(parts.size >= 2) { "Use a GitHub repository or folder URL" }
        val owner = parts[0]
        val repo = parts[1].removeSuffix(".git")
        var branch = ""
        var path = ""
        if (parts.size > 2) {
            require(parts.size >= 4 && parts[2] == "tree") {
                "Use a repository URL or a link in the form github.com/owner/repository/tree/branch/folder"
            }
            branch = parts[3]
            path = parts.drop(4).joinToString("/")
        }
        require(owner.matches(Regex("[A-Za-z0-9_.-]+")) && repo.matches(Regex("[A-Za-z0-9_.-]+"))) {
            "The GitHub URL must identify a repository"
        }
        require(parts.size <= 2 || branch.isNotBlank()) { "The GitHub folder URL is missing its branch" }
        require(path.split('/').none { it == "." || it == ".." }) { "The GitHub folder URL is invalid" }
        return FolderLink(owner, repo, branch, path)
    }

    private fun entries(listing: JsonObject): List<JsonObject> =
        listing.getAsJsonArray("tree")?.map { it.asJsonObject } ?: throw IllegalArgumentException("GitHub did not return a folder listing")
    private fun JsonObject.str(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
    private fun encodePath(path: String): String = path.split('/').joinToString("/") { encode(it) }
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
