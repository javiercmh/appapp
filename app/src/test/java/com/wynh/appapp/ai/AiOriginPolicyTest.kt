package com.wynh.appapp.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiOriginPolicyTest {
    private val appOrigin = "https://test-app-id.app.local"

    @Test fun `accepts only the exact bound main-frame origin`() {
        assertTrue(AiOriginPolicy.allows(appOrigin, true, appOrigin))
        assertFalse(AiOriginPolicy.allows(appOrigin, false, appOrigin))
        assertFalse(AiOriginPolicy.allows("https://other.app.local", true, appOrigin))
        assertFalse(AiOriginPolicy.allows("http://test-app-id.app.local", true, appOrigin))
        assertFalse(AiOriginPolicy.allows("https://test-app-id.app.local.evil", true, appOrigin))
    }
}
