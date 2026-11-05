package com.wynh.appapp.updates

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateCheckFailureCopyTest {
    private val source = GitHubSource("owner", "app", "feature")

    private fun reason(e: Throwable) = UpdateReviewCopy.checkFailure(e, source).substringBefore("\n\n")

    @Test
    fun explainsCommonGitHubFailuresInPlainLanguage() {
        assertEquals("Branch \"feature\" wasn't found in owner/app.",
            reason(IllegalArgumentException("GitHub request failed (422): No commit found for SHA: feature")))
        assertEquals("owner/app wasn't found. Check the name; private repositories aren't supported.",
            reason(IllegalArgumentException("GitHub request failed (404): Not Found")))
        assertEquals("Couldn't reach GitHub. Check your internet connection.", reason(java.net.UnknownHostException("api.github.com")))
        assertEquals("Package is too large", reason(IllegalStateException("Package is too large")))
    }

    @Test
    fun endsWithTheSourceItTried() {
        assertEquals("owner/app (feature)", UpdateReviewCopy.checkFailure(RuntimeException(), source).substringAfter("\n\n"))
    }
}

class UpdateVersionLineTest {
    @Test
    fun saysWhetherTheVersionGoesUpDownOrStays() {
        assertEquals("New version: 1.2.9 → 1.2.10", UpdateReviewCopy.versionLine("1.2.9", "1.2.10"))
        assertEquals("⚠ Older version: 3.1.0 → 1.2.46", UpdateReviewCopy.versionLine("3.1.0", "1.2.46"))
        assertEquals("Version 1.2.46, with file changes", UpdateReviewCopy.versionLine("1.2.46", "1.2.46"))
        assertEquals("Version 1.2, with file changes", UpdateReviewCopy.versionLine("1.2.0", "1.2"))
        assertEquals("Version beta → rc", UpdateReviewCopy.versionLine("beta", "rc"))
        assertEquals("Version 2", UpdateReviewCopy.versionLine(null, "2"))
    }
}

class UpdateVersionCompareTest {
    @Test
    fun preReleasesSortBeforeTheirRelease() {
        val ordered = listOf("1.2.46", "1.2.47-dev.2", "1.2.47-dev.10", "1.2.47-rc.1", "1.2.47", "1.3")
        ordered.zipWithNext().forEach { (low, high) ->
            assertEquals("$low < $high", -1, Integer.signum(UpdateReviewCopy.compareVersions(low, high)!!))
            assertEquals("$high > $low", 1, Integer.signum(UpdateReviewCopy.compareVersions(high, low)!!))
        }
        assertEquals(0, UpdateReviewCopy.compareVersions("v1.2.0", "1.2"))
        assertEquals(null, UpdateReviewCopy.compareVersions("beta", "1.0"))
        assertEquals("New version: 1.2.46 → 1.2.47-dev.3", UpdateReviewCopy.versionLine("1.2.46", "1.2.47-dev.3"))
        assertEquals(true, UpdateReviewCopy.isOlder("1.2.47-dev.3", "1.2.46"))
        assertEquals(false, UpdateReviewCopy.isOlder("beta", "1.0"))
    }
}
