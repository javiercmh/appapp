package com.wynh.appapp.updates

import com.wynh.appapp.host.HostCompatibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class GitHubSubappInstallerTest {
    @get:Rule val temp = TemporaryFolder()
    private val source = GitHubSource("owner", "repo", "main")
    private val commit = "d".repeat(40)

    private class Response(
        url: URL,
        private val resolveBody: (accept: String?) -> ByteArray,
        val status: Int = 200,
    ) : HttpURLConnection(url) {
        constructor(url: URL, body: ByteArray, status: Int = 200) : this(url, { body }, status)

        private var acceptHeader: String? = null
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun setRequestProperty(key: String?, value: String?) {
            if (key.equals("Accept", ignoreCase = true)) acceptHeader = value
        }
        override fun getResponseCode() = status
        override fun getInputStream() = resolveBody(acceptHeader).inputStream()
        override fun getErrorStream() = if (status >= 400) resolveBody(acceptHeader).inputStream() else null
    }

    @Test
    fun installsApp2UpdateRepoWithHashesAndIgnoresUnlistedFiles() {
        val manifest = """
            {
              "name": "LingoMage",
              "version": "1.4.2",
              "app2Update": {
                "appId": "com.wynh.lingomage",
                "minimumHostVersionCode": 4,
                "github": {"owner": "owner", "repository": "repo", "branch": "main"},
                "managedFiles": ["index.html", "manifest.json", "js/app.js"],
                "seedFiles": ["data/stories.json"]
              }
            }
        """.trimIndent()
        val requested = Collections.synchronizedList(mutableListOf<String>())
        val progressEvents = Collections.synchronizedList(mutableListOf<Pair<Int, Int>>())
        val installer = GitHubSubappInstaller(temp.root, connect = { url ->
            val path = url.toString()
            requested.add(path)
            when {
                url.host == "api.github.com" && url.path.endsWith("/commits/main") ->
                    Response(url, commit.toByteArray())
                path == "https://raw.githubusercontent.com/owner/repo/$commit/manifest.json" ->
                    Response(url, manifest.toByteArray())
                path == "https://raw.githubusercontent.com/owner/repo/$commit/index.html" ->
                    Response(url, "<html>hello</html>".toByteArray())
                path == "https://raw.githubusercontent.com/owner/repo/$commit/js/app.js" ->
                    Response(url, "console.log('ok');".toByteArray())
                path == "https://raw.githubusercontent.com/owner/repo/$commit/data/stories.json" ->
                    Response(url, "[]".toByteArray())
                else -> Response(url, """{"message":"Not Found"}""".toByteArray(), 404)
            }
        })

        val installed = installer.download(source, HostCompatibility.LEVEL.toLong()) { done, total ->
            progressEvents.add(done to total)
        }
        try {
            assertEquals(commit, installed.commit)
            assertEquals(source, installed.source)
            assertNotNull(installed.installedUpdate)
            val state = installed.installedUpdate!!
            assertEquals("com.wynh.lingomage", state.appId)
            assertEquals("1.4.2", state.version)
            assertEquals(setOf("data/stories.json"), state.seeds)
            assertEquals(setOf("index.html", "manifest.json", "js/app.js"), state.hashes.keys)
            assertEquals(digest(File(installed.directory, "index.html")), state.hashes["index.html"])
            assertEquals(digest(File(installed.directory, "js/app.js")), state.hashes["js/app.js"])
            assertTrue(File(installed.directory, "data/stories.json").isFile)
            assertTrue(progressEvents.isNotEmpty())
            assertEquals(4 to 4, progressEvents.last())
            assertFalse(requested.any { "git/trees" in it })
        } finally {
            installed.directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsIncompatibleHostLevelBeforeStaging() {
        val manifest = """
            {
              "name": "FutureApp",
              "version": "2.0.0",
              "app2Update": {
                "appId": "com.example.future",
                "minimumHostVersionCode": 99,
                "github": {"owner": "owner", "repository": "repo", "branch": "main"},
                "managedFiles": ["index.html", "manifest.json"],
                "seedFiles": []
              }
            }
        """.trimIndent()
        val installer = GitHubSubappInstaller(temp.root, connect = { url ->
            when {
                url.host == "api.github.com" -> Response(url, commit.toByteArray())
                url.path.endsWith("/manifest.json") -> Response(url, manifest.toByteArray())
                else -> throw AssertionError("Unexpected asset request: $url")
            }
        })

        assertThrows(IllegalArgumentException::class.java) {
            installer.download(source, 11L)
        }
        assertTrue(temp.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun fallsBackToFolderImportWhenManifestLacksApp2Update() {
        val plainManifest = """{"name":"Working Hours","version":"1.0.0"}"""
        val installer = GitHubSubappInstaller(temp.root, connect = { url ->
            val path = url.toString()
            when {
                path == "https://api.github.com/repos/owner/repo/commits/main" ->
                    Response(url, { accept ->
                        if (accept == "application/vnd.github.sha") commit.toByteArray()
                        else """{"sha":"$commit","commit":{"tree":{"sha":"tree-$commit"}}}""".toByteArray()
                    })
                path == "https://api.github.com/repos/owner/repo/git/trees/tree-$commit?recursive=1" ->
                    Response(url, """{"truncated":false,"tree":[{"path":"index.html","type":"blob","mode":"100644","size":12},{"path":"manifest.json","type":"blob","mode":"100644","size":40}]}""".toByteArray())
                path == "https://raw.githubusercontent.com/owner/repo/$commit/manifest.json" ->
                    Response(url, plainManifest.toByteArray())
                path == "https://raw.githubusercontent.com/owner/repo/$commit/index.html" ->
                    Response(url, "<html>wh</html>".toByteArray())
                else -> Response(url, """{"message":"Not Found"}""".toByteArray(), 404)
            }
        })

        val installed = installer.download(source, 11L)
        try {
            assertEquals(commit, installed.commit)
            assertNull(installed.installedUpdate)
            assertTrue(File(installed.directory, "index.html").isFile)
            assertEquals("<html>wh</html>", File(installed.directory, "index.html").readText())
        } finally {
            installed.directory.deleteRecursively()
        }
    }

    @Test
    fun fallsBackToFolderImportWhenRootManifestIsMissing() {
        val installer = GitHubSubappInstaller(temp.root, connect = { url ->
            val path = url.toString()
            when {
                path == "https://api.github.com/repos/owner/repo/commits/main" ->
                    Response(url, { accept ->
                        if (accept == "application/vnd.github.sha") commit.toByteArray()
                        else """{"sha":"$commit","commit":{"tree":{"sha":"tree-$commit"}}}""".toByteArray()
                    })
                path == "https://api.github.com/repos/owner/repo/git/trees/tree-$commit?recursive=1" ->
                    Response(url, """{"truncated":false,"tree":[{"path":"index.html","type":"blob","mode":"100644","size":12}]}""".toByteArray())
                path == "https://raw.githubusercontent.com/owner/repo/$commit/index.html" ->
                    Response(url, "<html>plain</html>".toByteArray())
                else -> Response(url, """{"message":"Not Found"}""".toByteArray(), 404)
            }
        })

        val installed = installer.download(source, 11L)
        try {
            assertNull(installed.installedUpdate)
            assertTrue(File(installed.directory, "index.html").isFile)
        } finally {
            installed.directory.deleteRecursively()
        }
    }

    @Test
    fun manifestServerErrorFailsInsteadOfFallingBackToFolderImport() {
        var folderImportStarted = false
        val installer = GitHubSubappInstaller(temp.root, connect = { url ->
            val path = url.toString()
            when {
                path == "https://api.github.com/repos/owner/repo/commits/main" ->
                    Response(url, { accept ->
                        if (accept == "application/vnd.github.sha") commit.toByteArray()
                        else """{"sha":"$commit","commit":{"tree":{"sha":"tree-$commit"}}}""".toByteArray()
                    })
                path == "https://raw.githubusercontent.com/owner/repo/$commit/manifest.json" ->
                    Response(url, """{"message":"Server Error"}""".toByteArray(), 500)
                else -> {
                    folderImportStarted = true
                    Response(url, """{"message":"Not Found"}""".toByteArray(), 404)
                }
            }
        })

        val error = assertThrows(GitHubHttpException::class.java) { installer.download(source, 11L) }
        assertEquals(500, error.status)
        assertFalse(folderImportStarted)
        assertTrue(temp.root.listFiles().orEmpty().none { it.isDirectory })
    }

    @Test
    fun cancellationStopsDownloadAndCleansStagingDirectory() {
        val manifest = """
            {
              "name": "CancelTest",
              "version": "1.0.0",
              "app2Update": {
                "appId": "com.example.cancel",
                "minimumHostVersionCode": 1,
                "github": {"owner": "owner", "repository": "repo", "branch": "main"},
                "managedFiles": ["index.html", "manifest.json", "a.js", "b.js", "c.js", "d.js", "e.js"],
                "seedFiles": []
              }
            }
        """.trimIndent()
        val inFlightStarted = CountDownLatch(1)
        val allowFinish = CountDownLatch(1)
        val installer = GitHubSubappInstaller(temp.root, connect = { url ->
            val path = url.toString()
            when {
                url.host == "api.github.com" -> Response(url, commit.toByteArray())
                path.endsWith("/manifest.json") -> Response(url, manifest.toByteArray())
                path.endsWith("/index.html") -> {
                    inFlightStarted.countDown()
                    allowFinish.await(2, TimeUnit.SECONDS)
                    Response(url, "<html></html>".toByteArray())
                }
                else -> Response(url, "ok".toByteArray())
            }
        })

        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                installer.download(source, 11L)
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        worker.start()
        assertTrue(inFlightStarted.await(5, TimeUnit.SECONDS))
        worker.interrupt()
        allowFinish.countDown()
        worker.join(5_000)

        assertFalse(worker.isAlive)
        assertTrue(failure.get() is InterruptedException)
        assertTrue("staging folder left behind", temp.root.listFiles().orEmpty().isEmpty())
    }
}
