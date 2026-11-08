package com.wynh.appapp.updates

import org.junit.Assert.assertEquals
import org.junit.Test

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
        assertEquals(true, UpdateReviewCopy.isOlder("1.2.47-dev.3", "1.2.46"))
        assertEquals(false, UpdateReviewCopy.isOlder("beta", "1.0"))
    }
}
