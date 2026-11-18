package com.wynh.appapp.updates

import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

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
}
