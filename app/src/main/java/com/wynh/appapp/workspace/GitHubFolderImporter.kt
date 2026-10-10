package com.wynh.appapp.workspace

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Downloads a public GitHub repository or repository subfolder into a temporary directory. */
object GitHubFolderImporter {
    private data class FolderLink(val owner: String, val repo: String, val branch: String, val path: String)

    fun download(context: Context, link: String): Result<File> = runCatching {
        val parsed = resolveDefaultBranch(parse(link))
        val destination = File(context.cacheDir, "github_import_${System.currentTimeMillis()}")
        check(destination.mkdirs()) { "Could not create temporary import folder" }
        try {
            downloadDirectory(parsed, parsed.path, destination)
            check(File(destination, "index.html").isFile) { "The selected GitHub folder must contain index.html" }
            destination
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
        return FolderLink(owner, repo, branch, path)
    }

    private fun resolveDefaultBranch(link: FolderLink): FolderLink {
        if (link.branch.isNotBlank()) return link
        val repository = JSONObject(requestText("https://api.github.com/repos/${link.owner}/${link.repo}"))
        val branch = repository.optString("default_branch")
        require(branch.isNotBlank()) { "Could not find the repository's default branch" }
        return link.copy(branch = branch)
    }

    private fun downloadDirectory(link: FolderLink, path: String, destination: File) {
        val contentsPath = if (path.isBlank()) "contents" else "contents/${encodePath(path)}"
        val apiUrl = "https://api.github.com/repos/${link.owner}/${link.repo}/$contentsPath?ref=${encode(link.branch)}"
        val response = requestText(apiUrl)
        val entries = runCatching { JSONArray(response) }.getOrElse {
            throw IllegalArgumentException("GitHub did not return a folder listing")
        }
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            val name = entry.getString("name")
            require(name != "." && name != ".." && !name.contains('/') && !name.contains('\\')) { "GitHub returned an unsafe file name" }
            val childPath = listOf(path, name).filter { it.isNotBlank() }.joinToString("/")
            val output = File(destination, name)
            when (entry.getString("type")) {
                "dir" -> {
                    check(output.mkdirs()) { "Could not create folder $name" }
                    downloadDirectory(link, childPath, output)
                }
                "file" -> {
                    val downloadUrl = entry.optString("download_url")
                    require(downloadUrl.startsWith("https://raw.githubusercontent.com/")) { "GitHub returned an invalid file URL" }
                    requestBytes(downloadUrl).inputStream().use { input -> output.outputStream().use(input::copyTo) }
                }
            }
        }
    }

    private fun requestText(address: String): String = requestBytes(address).toString(Charsets.UTF_8).also {
        if (it.startsWith("{")) {
            val error = runCatching { JSONObject(it).optString("message") }.getOrNull()
            if (!error.isNullOrBlank()) throw IllegalArgumentException(error)
        }
    }

    private fun requestBytes(address: String): ByteArray {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "AppApp-Android")
        return connection.useConnection {
            val status = connection.responseCode
            if (status !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }
                val message = runCatching { JSONObject(detail.orEmpty()).optString("message") }.getOrNull()
                throw IllegalArgumentException(message?.takeIf { it.isNotBlank() } ?: "GitHub request failed ($status)")
            }
            connection.inputStream.use { it.readBytes() }
        }
    }

    private inline fun <T> HttpURLConnection.useConnection(block: () -> T): T = try { block() } finally { disconnect() }

    private fun encodePath(path: String): String = path.split('/').joinToString("/") { encode(it) }
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
