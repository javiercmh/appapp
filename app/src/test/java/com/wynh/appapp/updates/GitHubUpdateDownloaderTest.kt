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
    @Test fun aDevBranchDownloadsEvenThoughItsManifestDeclaresMain() {
        val urls = mutableListOf<String>()
        val downloader = GitHubUpdateDownloader(temp.root) { url ->
            urls.add(url.toString())
            Response(url, if (url.host == "api.github.com") """{"sha":"$commit"}""".toByteArray() else if (url.path.endsWith("manifest.json")) manifest.toByteArray() else "ok".toByteArray())
        }
        val result = downloader.download(GitHubSource("owner", "repo", "dev"), 4)
        assertTrue(urls.first().endsWith("/repos/owner/repo/commits/dev"))
        assertEquals(GitHubSource("owner", "repo", "main"), result.pkg.source)
    }
    @Test fun latestCommitAsksForThePlainShaOfTheBranch() {
        var accept: String? = null
        val downloader = GitHubUpdateDownloader(temp.root) { url ->
            object : HttpURLConnection(url) {
                override fun connect() {}
                override fun disconnect() {}
                override fun usingProxy() = false
                override fun getResponseCode() = 200
                override fun getInputStream() = "$commit\n".byteInputStream()
                override fun setRequestProperty(key: String?, value: String?) { if (key == "Accept") accept = value }
            }
        }
        assertEquals(commit, downloader.latestCommit(GitHubSource("owner", "repo", "dev")))
        assertEquals("application/vnd.github.sha", accept)
    }
        @Test fun listsBranchNamesOfTheRepository() {
        val urls = mutableListOf<String>()
        val downloader = GitHubUpdateDownloader(temp.root) { url -> urls.add(url.toString()); Response(url, """[{"name":"dev","commit":{}},{"name":"main","commit":{}}]""".toByteArray()) }
        assertEquals(listOf("dev", "main"), downloader.branches("owner", "repo"))
        assertEquals(listOf("https://api.github.com/repos/owner/repo/branches?per_page=100"), urls)
        assertThrows(IllegalArgumentException::class.java) { downloader.branches("own/er", "repo") }
    }
    @Test fun lingoMageRequirement4IsSupportedByCurrentHostLevel() {
        val level = BuildConfig.HOST_COMPATIBILITY_LEVEL.toLong()
        assertEquals(11L, level)
        assertEquals(commit, downloaderFor(4).download(source, level).commit)
        assertEquals(commit, downloaderFor(11).download(source, level).commit)
    }
    @Test fun unsupportedHostLevelIsRejectedBeforeStaging() {
        assertThrows(IllegalArgumentException::class.java) { downloaderFor(12).download(source, 11) }
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

    private val base = "a".repeat(40)
    private val compareJson = """{"status":"ahead","ahead_by":3,"behind_by":0,"total_commits":3,
        "commits":[{"sha":"1","commit":{"message":"Oldest change\n\nbody"}},{"sha":"2","commit":{"message":"Middle"}},{"sha":"3","commit":{"message":"Newest change"}}],
        "files":[{"filename":"js/app.js","status":"modified","patch":"@@"},{"filename":"js/new.js","status":"renamed","previous_filename":"js/old.js"}]}"""

    @Test fun compareAsksForBothCommitsAndListsNewestSubjectsFirst() {
        val urls = mutableListOf<String>()
        val downloader = GitHubUpdateDownloader(temp.root) { url -> urls.add(url.toString()); Response(url, compareJson.toByteArray()) }
        val comparison = downloader.compare(source, base, commit)
        assertEquals(listOf("https://api.github.com/repos/owner/repo/compare/$base...$commit"), urls)
        assertEquals("ahead", comparison.status)
        assertEquals(listOf("Newest change", "Middle", "Oldest change"), comparison.subjects)
        assertEquals(setOf("js/app.js", "js/new.js", "js/old.js"), comparison.touchedPaths)
        assertTrue(comparison.filesComplete)
        assertThrows(IllegalArgumentException::class.java) { downloader.compare(source, "main", commit) }
    }

    @Test fun aFullFileListIsTreatedAsPossiblyTruncated() {
        val files = (1..Comparison.MAX_FILES).joinToString(",") { """{"filename":"f$it.js","status":"modified"}""" }
        val comparison = Comparison.parse("""{"status":"ahead","ahead_by":1,"behind_by":0,"total_commits":1,"commits":[],"files":[$files]}""")
        assertFalse(comparison.filesComplete)
    }

    @Test fun manifestReadsOnlyTheManifestAtThePinnedCommit() {
        val urls = mutableListOf<String>()
        val downloader = GitHubUpdateDownloader(temp.root) { url -> urls.add(url.toString()); Response(url, manifest.toByteArray()) }
        assertEquals("test.publisher", downloader.manifest(source, commit, 4).appId)
        assertEquals(listOf("https://raw.githubusercontent.com/owner/repo/$commit/manifest.json"), urls)
        assertThrows(IllegalArgumentException::class.java) { downloaderFor(12).manifest(source, commit, 11) }
        assertTrue(temp.root.listFiles()!!.isEmpty())
    }

    @Test fun reusedFilesAreNotRequestedAndProgressReachesTheTotal() {
        val many = manifest.replace("\"managedFiles\":[\"index.html\",\"manifest.json\"]", "\"managedFiles\":[\"index.html\",\"manifest.json\",\"js/a.js\",\"js/b.js\",\"js/c.js\"]")
        val urls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val downloader = GitHubUpdateDownloader(temp.root) { url ->
            urls.add(url.toString())
            Response(url, if (url.path.endsWith("manifest.json")) many.toByteArray() else "fetched".toByteArray())
        }
        val reports = java.util.Collections.synchronizedList(mutableListOf<Pair<Int, Int>>())
        val result = downloader.download(source, 4, commit,
            reuse = { path, target -> if (path == "js/b.js") { target.writeText("local"); true } else false },
            progress = { done, total -> reports.add(done to total) })
        assertEquals("local", result.directory.resolve("js/b.js").readText())
        assertEquals("fetched", result.directory.resolve("js/c.js").readText())
        assertFalse(urls.any { it.endsWith("/js/b.js") })
        assertEquals(4, urls.size) // manifest, index.html, js/a.js, js/c.js
        assertEquals(5 to 5, reports.maxByOrNull { it.first })
    }

    @Test fun aFailedFileRemovesTheWholeStagedDownload() {
        val many = manifest.replace("\"managedFiles\":[\"index.html\",\"manifest.json\"]", "\"managedFiles\":[\"index.html\",\"manifest.json\",\"js/a.js\",\"js/b.js\",\"js/c.js\",\"js/d.js\"]")
        val downloader = GitHubUpdateDownloader(temp.root) { url ->
            when {
                url.path.endsWith("manifest.json") -> Response(url, many.toByteArray())
                url.path.endsWith("/js/c.js") -> Response(url, byteArrayOf(), 404)
                else -> Response(url, "ok".toByteArray())
            }
        }
        assertThrows(IllegalArgumentException::class.java) { downloader.download(source, 4, commit) }
        assertTrue(temp.root.listFiles()!!.isEmpty())
    }

    @Test fun sweepRemovesOnlyStagedUpdates() {
        temp.newFolder("update-1234").resolve("index.html").writeText("left behind")
        temp.newFolder("exports")
        GitHubUpdateDownloader.sweepStaged(temp.root)
        assertEquals(listOf("exports"), temp.root.list()!!.toList())
    }
}
