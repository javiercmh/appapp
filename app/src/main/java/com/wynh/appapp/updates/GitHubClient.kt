package com.wynh.appapp.updates

import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Bounded HTTPS client shared by the GitHub importer and updater. Every response is streamed under a
 * per-response cap and a cumulative cap, the whole session has a deadline, and redirects are never
 * followed automatically: up to [maxRedirects] hops are followed only when they stay on https and
 * an allowed GitHub host.
 */
class GitHubClient(
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val userAgent: String,
    private val totalLimit: Long,
    private val maxRedirects: Int = 0,
    timeoutMillis: Long? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val deadline = timeoutMillis?.let { clock() + it }
    var bytes = 0L
        private set

    fun get(address: String, limit: Long, accept: String? = null): ByteArray {
        var url = checked(URL(address))
        for (hop in 0..maxRedirects) {
            checkDeadline()
            val connection = connect(url)
            connection.connectTimeout = 15_000; connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", userAgent)
            accept?.let { connection.setRequestProperty("Accept", it) }
            try {
                val status = connection.responseCode
                if (status in REDIRECTS && hop < maxRedirects) {
                    val location = requireNotNull(connection.getHeaderField("Location")) { "GitHub redirect has no destination" }
                    url = checked(URL(url, location))
                    continue
                }
                if (status !in 200..299) {
                    val detail = runCatching {
                        val body = connection.errorStream?.use { read(it, 64 * 1024, counted = false) } ?: byteArrayOf()
                        JsonParser.parseString(body.toString(Charsets.UTF_8)).asJsonObject.get("message").asString
                    }.getOrNull()?.takeIf { it.isNotBlank() }
                    throw IllegalArgumentException("GitHub request failed ($status)" + (detail?.let { ": $it" } ?: "; check connectivity and repository access"))
                }
                return connection.inputStream.use { read(it, limit, counted = true) }
            } finally { connection.disconnect() }
        }
        throw IllegalArgumentException("GitHub redirected too many times")
    }

    private fun read(input: java.io.InputStream, limit: Long, counted: Boolean): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            checkDeadline()
            val count = input.read(buffer); if (count < 0) break
            if (counted) bytes += count
            require(output.size() + count <= limit && bytes <= totalLimit) { "GitHub download exceeds size limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun checkDeadline() = require(deadline == null || clock() <= deadline) { "GitHub download timed out" }

    private fun checked(url: URL): URL {
        require(url.protocol == "https" && url.host.lowercase() in HOSTS && (url.port == -1 || url.port == 443) && url.userInfo == null) {
            "GitHub redirected to an unsupported location"
        }
        return url
    }

    private companion object {
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
        val HOSTS = setOf("api.github.com", "raw.githubusercontent.com", "codeload.github.com", "objects.githubusercontent.com")
    }
}
