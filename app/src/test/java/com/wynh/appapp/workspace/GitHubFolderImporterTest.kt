package com.wynh.appapp.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.HttpURLConnection
import java.net.URL

class GitHubFolderImporterTest {
    @get:Rule val temp = TemporaryFolder()
    private val first = "a".repeat(40)
    private val second = "b".repeat(40)
    private val api = "https://api.github.com/repos/owner/repo"

    private class Response(url: URL, val body: ByteArray, val status: Int = 200, val location: String? = null) : HttpURLConnection(url) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream() = body.inputStream()
        override fun getErrorStream() = if (status >= 400) body.inputStream() else null
        override fun getHeaderField(name: String) = if (name == "Location") location else null
    }

    /** A fake repository whose `main` branch points at [first], then moves to [second] after it is first resolved. */
    private inner class FakeGitHub(
        val tree: List<Pair<String, Int>> = listOf("index.html" to 10, "js/app.js" to 5),
        val routes: Map<String, (URL) -> HttpURLConnection> = emptyMap(),
    ) : (URL) -> HttpURLConnection {
        val urls = mutableListOf<String>()
        var resolutions = 0
        override fun invoke(url: URL): HttpURLConnection {
            val address = url.toString()
            urls.add(address)
            routes[address]?.let { return it(url) }
            val body = when {
                address == "$api/commits/main" -> {
                    val sha = if (resolutions++ == 0) first else second
                    """{"sha":"$sha","commit":{"tree":{"sha":"tree-$sha"}}}"""
                }
                address == "$api/git/trees/tree-$first" -> """{"tree":[{"path":"apps","type":"tree","sha":"sub-$first"}]}"""
                address == "$api/git/trees/sub-$first?recursive=1" || address == "$api/git/trees/tree-$first?recursive=1" ->
                    """{"truncated":false,"tree":[""" + tree.joinToString(",") { (path, size) ->
                        """{"path":"$path","type":"blob","mode":"100644","size":$size}"""
                    } + "]}"
                address.startsWith("https://raw.githubusercontent.com/owner/repo/$first/") -> "content@$first"
                address.startsWith("https://raw.githubusercontent.com/owner/repo/$second/") -> "content@$second"
                else -> return Response(url, """{"message":"Not Found"}""".toByteArray(), 404)
            }
            return Response(url, body.toByteArray())
        }
    }

    private fun import(github: (URL) -> HttpURLConnection, link: String = "https://github.com/owner/repo/tree/main/apps") =
        GitHubFolderImporter.download(temp.root, link, github)

    private fun assertFails(result: Result<*>, message: String) {
        val error = result.exceptionOrNull()
        assertTrue("expected failure containing '$message', got $error", error?.message?.contains(message) == true)
        assertTrue("staging folder was left behind", temp.root.listFiles()!!.isEmpty())
    }

    @Test fun pinsBranchToOneCommitForEveryListingAndFile() {
        val github = FakeGitHub()
        val imported = import(github).getOrThrow()
        assertEquals(first, imported.commit)
        assertEquals(1, github.resolutions)
        val raw = github.urls.filter { it.contains("raw.githubusercontent.com") }
        assertEquals(listOf(
            "https://raw.githubusercontent.com/owner/repo/$first/apps/index.html",
            "https://raw.githubusercontent.com/owner/repo/$first/apps/js/app.js",
        ), raw)
        assertEquals("content@$first", imported.directory.resolve("js/app.js").readText())
        // After the single resolution, nothing else refers to the moving branch name.
        assertTrue(github.urls.drop(1).none { "main" in it })
    }

    @Test fun rejectsOversizedResponseEvenWhenTreeUnderstatesSize() {
        val big = "https://raw.githubusercontent.com/owner/repo/$first/apps/js/app.js"
        val github = FakeGitHub(routes = mapOf(big to { url -> Response(url, ByteArray((GitHubFolderImporter.MAX_FILE_BYTES + 1).toInt())) }))
        assertFails(import(github), "size limit")
    }

    @Test fun rejectsDeclaredOversizeBeforeDownloading() {
        val github = FakeGitHub(tree = listOf("index.html" to 10, "big.bin" to (GitHubFolderImporter.MAX_FILE_BYTES + 1).toInt()))
        assertFails(import(github), "size limit")
        assertTrue(github.urls.none { it.contains("raw.githubusercontent.com") })
    }

    @Test fun rejectsTooManyFiles() {
        val github = FakeGitHub(tree = listOf("index.html" to 1) + (1..GitHubFolderImporter.MAX_ENTRIES).map { "f$it.txt" to 1 })
        assertFails(import(github), "more than")
        assertTrue(github.urls.none { it.contains("raw.githubusercontent.com") })
    }

    @Test fun rejectsTooDeepTree() {
        val deep = (1..GitHubFolderImporter.MAX_DEPTH).joinToString("/") { "d$it" } + "/file.txt"
        assertFails(import(FakeGitHub(tree = listOf("index.html" to 1, deep to 1))), "nested")
    }

    @Test fun rejectsTraversalAndAbsolutePaths() {
        assertFails(import(FakeGitHub(tree = listOf("index.html" to 1, "../escape.js" to 1))), "Unsafe")
        assertFails(import(FakeGitHub(tree = listOf("index.html" to 1, "/etc/passwd" to 1))), "Unsafe")
    }

    @Test fun rejectsRedirectToForeignHostAndFollowsGitHubRedirect() {
        val start = "$api/commits/main"
        val foreign = FakeGitHub(routes = mapOf(start to { url -> Response(url, byteArrayOf(), 302, "https://evil.example/commits/main") }))
        assertFails(import(foreign), "unsupported location")
        assertTrue(foreign.urls.none { "evil.example" in it })
        val insecure = FakeGitHub(routes = mapOf(start to { url -> Response(url, byteArrayOf(), 301, "http://api.github.com/repos/owner/repo/commits/main") }))
        assertFails(import(insecure), "unsupported location")

        val renamedApi = "https://api.github.com/repos/owner/renamed"
        // The allowed hop is followed manually and resolves to the same pinned commit.
        val chained = FakeGitHub(routes = mapOf(start to { url -> Response(url, byteArrayOf(), 307, "$renamedApi/commits/main") },
            "$renamedApi/commits/main" to { url -> Response(url, """{"sha":"$first","commit":{"tree":{"sha":"tree-$first"}}}""".toByteArray()) }))
        assertEquals(first, import(chained).getOrThrow().commit)
    }

    @Test fun rejectsEndlessRedirects() {
        val start = "$api/commits/main"
        val loop = FakeGitHub(routes = mapOf(start to { url -> Response(url, byteArrayOf(), 302, start) }))
        assertFails(import(loop), "GitHub request failed (302)")
    }

    @Test fun enforcesOverallDeadline() {
        var now = 0L
        val github = FakeGitHub(routes = mapOf("$api/git/trees/tree-$first" to { url ->
            now += GitHubFolderImporter.TIMEOUT_MILLIS + 1
            Response(url, """{"tree":[{"path":"apps","type":"tree","sha":"sub-$first"}]}""".toByteArray())
        }))
        assertFails(GitHubFolderImporter.download(temp.root, "https://github.com/owner/repo/tree/main/apps", github) { now }, "timed out")
    }

    @Test fun requiresIndexHtmlAndReportsApiErrors() {
        assertFails(import(FakeGitHub(tree = listOf("readme.md" to 1))), "index.html")
        assertFails(import(FakeGitHub(), "https://github.com/owner/repo/tree/missing"), "Not Found")
    }
}
