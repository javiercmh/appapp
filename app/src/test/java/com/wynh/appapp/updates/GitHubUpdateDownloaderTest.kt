package com.wynh.appapp.updates

import com.wynh.appapp.BuildConfig
import org.junit.Test
import org.junit.Rule
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.net.HttpURLConnection
import java.net.URL

class GitHubUpdateDownloaderTest {
    @get:Rule val temp = TemporaryFolder()
    private val source = GitHubSource("owner", "repo", "main")
    private val commit = "c".repeat(40)
    private val manifest = """{"version":"1","app2Update":{"appId":"test.publisher","minimumHostVersionCode":4,"github":{"owner":"owner","repository":"repo","branch":"main"},"managedFiles":["index.html","manifest.json"],"seedFiles":[]}}"""
    private fun downloaderFor(minimum: Int) = GitHubUpdateDownloader(temp.root) { url ->
        Response(url, when {
            url.host == "api.github.com" -> """{"sha":"$commit"}""".toByteArray()
            url.path.endsWith("manifest.json") -> manifest.replace("\"minimumHostVersionCode\":4", "\"minimumHostVersionCode\":$minimum").toByteArray()
            else -> "<html>ok</html>".toByteArray()
        })
    }
    private class Response(url: URL, val body: ByteArray, val status: Int = 200) : HttpURLConnection(url) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream() = body.inputStream()
    }
    @Test fun allAssetsUseResolvedCommitAndUnlistedFilesAreNotRequested() {
        val urls = mutableListOf<String>()
        val downloader = GitHubUpdateDownloader(temp.root) { url ->
            urls.add(url.toString())
            Response(url, when {
                url.host == "api.github.com" -> """{"sha":"$commit"}""".toByteArray()
                url.path.endsWith("manifest.json") -> manifest.toByteArray()
                else -> "<html>ok</html>".toByteArray()
            })
        }
        val result = downloader.download(source, 4)
        assertEquals(commit, result.commit)
        assertEquals(3, urls.size)
        assertTrue(urls.drop(1).all { it.contains("/$commit/") })
        assertTrue(result.directory.resolve("index.html").isFile)
        assertFalse(result.directory.resolve("android").exists())
    }
    @Test fun lingoMageRequirement4IsSupportedByCurrentHostLevel() {
        val level = BuildConfig.HOST_COMPATIBILITY_LEVEL.toLong()
        assertEquals(11L, level)
        assertEquals(commit, downloaderFor(4).download(source, level).commit)
        assertEquals(commit, downloaderFor(11).download(source, level).commit)
    }
    @Test fun requirement12IsRejectedByLevel11WithBothLevelsInMessage() {
        val error = assertThrows(IllegalArgumentException::class.java) { downloaderFor(12).download(source, 11) }
        assertTrue(error.message!!.contains("12"))
        assertTrue(error.message!!.contains("11"))
        assertTrue(error.message!!.contains("Update App²"))
        assertTrue(temp.root.listFiles()!!.isEmpty())
    }
    @Test fun rejectsRedirectsOversizedFilesAndUnsupportedHost() {
        assertThrows(IllegalArgumentException::class.java) { GitHubUpdateDownloader(temp.root) { Response(it, byteArrayOf(), 302) }.download(source, 4) }
        assertThrows(IllegalArgumentException::class.java) {
            GitHubUpdateDownloader(temp.root) { url -> Response(url, if (url.host == "api.github.com") """{"sha":"$commit"}""".toByteArray() else manifest.toByteArray()) }.download(source, 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GitHubUpdateDownloader(temp.root) { url -> Response(url, when {
                url.host == "api.github.com" -> """{"sha":"$commit"}""".toByteArray()
                url.path.endsWith("manifest.json") -> manifest.toByteArray()
                else -> ByteArray(8 * 1024 * 1024 + 1)
            }) }.download(source, 4)
        }
        assertTrue(temp.root.listFiles()!!.isEmpty())
    }
}
