package com.wynh.appapp.grants

import com.wynh.appapp.ai.AiStartRateLimiter
import org.junit.Assert.*
import org.junit.Test

class SubappGrantStoreTest {
    private class MemoryStorage : GrantStorage {
        val map = HashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun put(values: Map<String, String?>) { values.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v } }
    }

    @Test fun `grants are per app and revocable`() {
        val store = SubappGrantStore(MemoryStorage())
        assertFalse(store.isGranted("a", SubappCapability.AI))
        assertTrue(store.set("a", SubappCapability.AI, true))
        assertFalse(store.set("a", SubappCapability.AI, true))
        assertTrue(store.isGranted("a", SubappCapability.AI))
        assertFalse(store.isGranted("b", SubappCapability.AI))
        store.set("a", SubappCapability.NETWORK, true)
        assertEquals(setOf(SubappCapability.AI, SubappCapability.NETWORK), store.clear("a"))
        assertTrue(store.granted("a").isEmpty())
    }

    @Test fun `migration keeps network for all apps and reminders, never AI, and runs once`() {
        val store = SubappGrantStore(MemoryStorage())
        assertTrue(store.migrateExisting(listOf("a", "b")) { it == "a" })
        assertEquals(setOf(SubappCapability.NETWORK, SubappCapability.NOTIFICATIONS), store.granted("a"))
        assertEquals(setOf(SubappCapability.NETWORK), store.granted("b"))
        assertFalse(store.migrateExisting(listOf("c")) { true })
        assertTrue(store.granted("c").isEmpty())
    }

    @Test fun `rate limiter allows a burst then blocks until the window passes`() {
        var now = 0L
        val limiter = AiStartRateLimiter(maxStarts = 2, windowMillis = 1000) { now }
        assertTrue(limiter.tryAcquire("a")); assertTrue(limiter.tryAcquire("a"))
        assertFalse(limiter.tryAcquire("a"))
        assertTrue(limiter.tryAcquire("b"))
        now = 1000
        assertTrue(limiter.tryAcquire("a"))
    }
}
