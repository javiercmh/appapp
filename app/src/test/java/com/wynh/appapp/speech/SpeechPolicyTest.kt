package com.wynh.appapp.speech

import org.junit.Assert.*
import org.junit.Test

class SpeechPolicyTest {
    @Test fun `regional fallback cannot cross languages or explicit scripts`() {
        assertTrue(SpeechPolicy.matches(SpeechPolicy.language("fr-CA"), SpeechPolicy.language("fr-FR")))
        assertFalse(SpeechPolicy.matches(SpeechPolicy.language("ja"), SpeechPolicy.language("en")))
        assertFalse(SpeechPolicy.matches(SpeechPolicy.language("zh-Hant"), SpeechPolicy.language("zh-Hans")))
        assertTrue(SpeechPolicy.matches(SpeechPolicy.language("zh-Hant-TW"), SpeechPolicy.language("zh-Hant-HK")))
    }
    @Test fun `language tags and scalars reject invalid input`() {
        for (tag in listOf("", "und", "fr_FR", "en-!")) {
            assertThrows(Exception::class.java) { SpeechPolicy.language(tag) }
        }
        for (rate in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, 2.1)) {
            assertThrows(IllegalArgumentException::class.java) { SpeechPolicy.scalar(rate) }
        }
        assertEquals(0.5f, SpeechPolicy.scalar(0.5))
        assertEquals(2f, SpeechPolicy.scalar(2.0))
    }
}
