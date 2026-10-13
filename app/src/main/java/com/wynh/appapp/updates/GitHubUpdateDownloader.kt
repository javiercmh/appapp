package com.wynh.appapp.updates

import com.google.gson.JsonParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class GitHubUpdateDownloader(private val cache: File, private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    data class Download(val pkg: UpdatePackage, val commit: String, val directory: File)
    /** Subject lines of the newest commits, stopping at [since] (the installed commit) when it is found. Best effort. */
    fun recentSubjects(source: GitHubSource, since: String?, limit: Int = 8): List<String> {
        val repo = "${source.owner}/${source.repository}"
        val branch = URLEncoder.encode(source.branch, "UTF-8").replace("+", "%20")
        val text = GitHubClient(connect, "AppApp-Updater", MAX_BYTES)
            .get("https://api.github.com/repos/$repo/commits?sha=$branch&per_page=30", 1024 * 1024).toString(Charsets.UTF_8)
        val subjects = mutableListOf<String>()
        for (item in JsonParser.parseString(text).asJsonArray) {
            val commit = item.asJsonObject
            if (commit.get("sha").asString == since) break
            subjects.add(commit.getAsJsonObject("commit").get("message").asString.lineSequence().first().trim().take(120))
        }
        return subjects.filter { it.isNotEmpty() }.take(limit)
    }

    fun download(source: GitHubSource, hostVersion: Long): Download {
        val client = GitHubClient(connect, "AppApp-Updater", MAX_BYTES, timeoutMillis = 5 * 60_000)
        fun get(url: String, limit: Int) = client.get(url, limit.toLong())
        fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        val repo = "${source.owner}/${source.repository}"
        val commit = JsonParser.parseString(get("https://api.github.com/repos/$repo/commits/${encode(source.branch)}", 1024 * 1024).toString(Charsets.UTF_8)).asJsonObject.get("sha").asString
        require(commit.matches(Regex("[a-f0-9]{40}"))) { "GitHub returned an invalid commit" }
        val root = "https://raw.githubusercontent.com/$repo/$commit/"
        val manifest = get(root + "manifest.json", 256 * 1024)
        val pkg = UpdatePackage.parse(manifest.toString(Charsets.UTF_8))
        require(pkg.source == source) { "Package GitHub source differs from the selected source" }
        require(pkg.minimumHostVersion <= hostVersion) { "Update App² first (requires host version code ${pkg.minimumHostVersion})" }
        val directory = File(cache, "update-${java.util.UUID.randomUUID()}")
        check(directory.mkdirs())
        try {
            File(directory, "manifest.json").writeBytes(manifest)
            (pkg.managed + pkg.seeds - "manifest.json").forEach { path ->
                val content = get(root + safePath(path).split('/').joinToString("/", transform = ::encode), 8 * 1024 * 1024)
                val file = File(directory, path); check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
                file.writeBytes(content)
            }
            return Download(pkg, commit, directory)
        } catch (e: Exception) { directory.deleteRecursively(); throw e }
    }

    private companion object { const val MAX_BYTES = 32L * 1024 * 1024 }
}
