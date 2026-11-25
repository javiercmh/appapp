package com.wynh.appapp.updates

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files

class VersionUpdateCheckerTest {
    private val source = GitHubSource("owner", "app", "feature/test")
    private val commit = "a".repeat(40)
    private class Response(url: URL, private val body: String, private val status: Int = 200) : HttpURLConnection(url) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream() = body.byteInputStream()
    }
    private fun release(tag: String, pre: Boolean = false, draft: Boolean = false) =
        """{"tag_name":"$tag","prerelease":$pre,"draft":$draft,"html_url":"https://untrusted.invalid/"}"""

    @Test fun onlyStrictlyNewerVersionsQualify() {
        assertTrue(VersionUpdateChecker.newer("1.2.10", "1.2.9"))
        assertFalse(VersionUpdateChecker.newer("v1.2.0", "1.2"))
        assertFalse(VersionUpdateChecker.newer("1.2.9", "1.2.10"))
        assertTrue(VersionUpdateChecker.newer("1.2", "1.2-rc.2"))
        assertFalse(VersionUpdateChecker.newer("1.2-dev.10", "1.2"))
        for (installed in listOf(null, "unknown", "1.2147483648")) {
            assertTrue(runCatching { VersionUpdateChecker.newer("2.0", installed) }.isFailure)
        }
    }
    @Test fun stableLinkIsTrustedAndOnlyNewVersionsQualify() {
        val latest = VersionUpdateChecker.parseRelease(release("v1.10.0"), false)!!
        assertEquals("1.10.0", latest.version)
        assertEquals("https://github.com/javiercmh/appapp/releases/tag/v1.10.0", latest.url)
        assertTrue(latest.isNewer("1.9.1", 999, false))
        assertFalse(latest.isNewer("1.10.0", 1, false))
        assertFalse(latest.isNewer("1.11.0", 1, false))
        assertNull(VersionUpdateChecker.parseRelease(release("v2.0", pre = true), false))
        assertNull(VersionUpdateChecker.parseRelease(release("v2.0", draft = true), false))
        assertTrue(runCatching { VersionUpdateChecker.parseRelease(release("malformed"), false) }.isFailure)
    }
    @Test fun devUsesHighestBuildRatherThanReleaseOrderOrBaseVersion() {
        val checker = VersionUpdateChecker { url -> Response(url, "[${release("v9.0")},${release("dev-9", true)},${release("dev-11", true)},${release("dev-100", true, true)},${release("dev-10", true)}]") }
        val latest = checker.hostRelease(true)!!
        assertEquals(11L, latest.devBuild)
        assertTrue(latest.isNewer("99.0-dev.10", 10, true))
        assertFalse(latest.isNewer("1.0-dev.11", 11, true))
        assertFalse(latest.isNewer("1.0-dev.12", 12, true))
    }
    @Test fun subappFetchesOnlyCommitAndPinnedManifest() {
        val urls = mutableListOf<URL>()
        val checker = VersionUpdateChecker { url ->
            urls.add(url)
            Response(url, if (url.host == "api.github.com") """{"sha":"$commit"}""" else """{"version":"2.1"}""")
        }
        val result = checker.subapp(source)
        assertEquals("2.1", result.version)
        assertEquals(commit, result.commit)
        assertEquals(2, urls.size)
        assertTrue(urls.first().toString().endsWith("/commits/feature%2Ftest"))
        assertEquals("https://raw.githubusercontent.com/owner/app/$commit/manifest.json", urls.last().toString())
    }
    @Test fun malformedVersionsAndResponsesAreFailuresNotNoUpdates() {
        for (manifest in listOf("{}", """{"version":2}""", """{"version":"latest"}""", "not json")) {
            assertTrue(runCatching { VersionUpdateChecker.manifestVersion(manifest) }.isFailure)
        }
        for (status in listOf(403, 404, 429, 500)) {
            assertTrue(runCatching { VersionUpdateChecker { url -> Response(url, "{}", status) }.hostRelease(false) }.isFailure)
        }
    }

    @Test fun sessionDetectsSameVersionManagedFileUpdatesAndSkipsUnmanagedCommits() {
        val root = Files.createTempDirectory("version-check-session").toFile()
        try {
            val installedCommit = "1".repeat(40)
            val newCommit = "2".repeat(40)
            val manifest = """{"name":"Working Hours","version":"1.1.0","app2Update":{"appId":"com.wynh.workinghours","minimumHostVersionCode":1,"github":{"owner":"owner","repository":"app","branch":"feature/test"},"managedFiles":["index.html","manifest.json"],"seedFiles":[]}}"""
            val staged = File(root, "staged").apply { mkdirs() }
            File(staged, "manifest.json").writeText(manifest)
            File(staged, "index.html").writeText("v1")
            val appDir = File(root, "app").apply { File(this, "workspace").mkdirs() }
            val updater = WorkspaceUpdater(appDir)
            updater.install(UpdatePackage.parse(manifest), staged, installedCommit, 100, source = source)

            // 1) Same version with a managed file changed -> Available
            val managedCompare = """{"status":"ahead","ahead_by":1,"behind_by":0,"total_commits":1,"commits":[{"commit":{"message":"Add feature"}}],"files":[{"filename":"index.html","status":"modified"}]}"""
            val checkerWithChanges = VersionUpdateChecker { url ->
                Response(url, when {
                    "/compare/" in url.path -> managedCompare
                    url.host == "api.github.com" -> """{"sha":"$newCommit"}"""
                    else -> manifest
                })
            }
            val available = checkerWithChanges.SubappCheckSession().check("wh", updater)
            assertEquals(VersionUpdateChecker.SubappCheckResult.Available(VersionUpdateChecker.PublishedVersion(source, newCommit, "1.1.0")), available)

            // 2) Same version with only an unmanaged file changed -> UpToDate, and cached in UpdateAvailability
            val compareCalls = mutableListOf<String>()
            val unmanagedCompare = """{"status":"ahead","ahead_by":1,"behind_by":0,"total_commits":1,"commits":[{"commit":{"message":"Update CI"}}],"files":[{"filename":".github/ci.yml","status":"modified"}]}"""
            val checkerUnmanaged = VersionUpdateChecker { url ->
                if ("/compare/" in url.path) compareCalls.add(url.path)
                Response(url, when {
                    "/compare/" in url.path -> unmanagedCompare
                    url.host == "api.github.com" -> """{"sha":"$newCommit"}"""
                    else -> manifest
                })
            }
            val availability = UpdateAvailability(UpdateAvailabilityTest.MemoryPrefs())
            assertEquals(VersionUpdateChecker.SubappCheckResult.UpToDate, checkerUnmanaged.SubappCheckSession(availability).check("wh", updater))
            assertEquals(1, compareCalls.size)
            assertEquals(newCommit, availability.knownUnchanged("wh", source))
            // Second check skips /compare/ because the commit is already known unchanged
            assertEquals(VersionUpdateChecker.SubappCheckResult.UpToDate, checkerUnmanaged.SubappCheckSession(availability).check("wh", updater))
            assertEquals(1, compareCalls.size)

            // 3) Same version with behind commit -> UpToDate
            val behindCompare = """{"status":"behind","ahead_by":0,"behind_by":1,"total_commits":0,"commits":[],"files":[{"filename":"index.html","status":"modified"}]}"""
            val checkerBehind = VersionUpdateChecker { url ->
                Response(url, when {
                    "/compare/" in url.path -> behindCompare
                    url.host == "api.github.com" -> """{"sha":"$newCommit"}"""
                    else -> manifest
                })
            }
            assertEquals(VersionUpdateChecker.SubappCheckResult.UpToDate, checkerBehind.SubappCheckSession().check("wh", updater))

            // 4) Failed /compare/ (e.g. force-pushed commit 404) falls back to Available
            val expectedAvailable = VersionUpdateChecker.SubappCheckResult.Available(
                VersionUpdateChecker.PublishedVersion(source, newCommit, "1.1.0")
            )
            val checkerFailedCompare = VersionUpdateChecker { url ->
                when {
                    "/compare/" in url.path -> Response(url, "{}", 404)
                    url.host == "api.github.com" -> Response(url, """{"sha":"$newCommit"}""")
                    else -> Response(url, manifest)
                }
            }
            assertEquals(expectedAvailable, checkerFailedCompare.SubappCheckSession().check("wh", updater))

            // 5) Diverged commit falls back to Available
            val divergedCompare = """{"status":"diverged","ahead_by":1,"behind_by":1,"total_commits":1,"commits":[],"files":[{"filename":".github/ci.yml","status":"modified"}]}"""
            val checkerDiverged = VersionUpdateChecker { url ->
                Response(url, when {
                    "/compare/" in url.path -> divergedCompare
                    url.host == "api.github.com" -> """{"sha":"$newCommit"}"""
                    else -> manifest
                })
            }
            assertEquals(expectedAvailable, checkerDiverged.SubappCheckSession().check("wh", updater))

            // 6) Truncated ahead file list (300 files) falls back to Available
            val truncatedFiles = (1..Comparison.MAX_FILES).joinToString(",") {
                """{"filename":"docs/f$it.txt","status":"modified"}"""
            }
            val truncatedCompare = """{"status":"ahead","ahead_by":1,"behind_by":0,"total_commits":1,"commits":[{"commit":{"message":"Many docs"}}],"files":[$truncatedFiles]}"""
            val checkerTruncated = VersionUpdateChecker { url ->
                Response(url, when {
                    "/compare/" in url.path -> truncatedCompare
                    url.host == "api.github.com" -> """{"sha":"$newCommit"}"""
                    else -> manifest
                })
            }
            assertEquals(expectedAvailable, checkerTruncated.SubappCheckSession().check("wh", updater))
        } finally {
            root.deleteRecursively()
        }
    }
}
