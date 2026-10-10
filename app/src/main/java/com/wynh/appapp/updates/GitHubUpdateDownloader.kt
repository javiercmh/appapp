package com.wynh.appapp.updates

import com.google.gson.JsonParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class GitHubUpdateDownloader(private val cache: File, private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    data class Download(val pkg: UpdatePackage, val commit: String, val directory: File)
    fun download(source: GitHubSource, hostVersion: Long): Download {
        var bytes = 0L
        fun get(url: String, limit: Int): ByteArray {
            val connection = connect(URL(url))
            connection.connectTimeout = 15_000; connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "AppApp-Updater")
            try {
                require(connection.responseCode in 200..299) { "GitHub request failed (${connection.responseCode}); check connectivity and repository access" }
                val output = java.io.ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        bytes += count
                        require(output.size() + count <= limit && bytes <= 32 * 1024 * 1024) { "Update exceeds download limit" }
                        output.write(buffer, 0, count)
                    }
                }
                return output.toByteArray()
            } finally { connection.disconnect() }
        }
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
}
