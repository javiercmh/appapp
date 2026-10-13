package com.wynh.appapp.runtime

import com.wynh.appapp.runtime.SubappWebViews.Navigation.*
import org.junit.Assert.assertEquals
import org.junit.Test

class SubappNavigationTest {
    private val id = "0f8fad5b-d9cb-469f-a165-70867728950e"
    private val host = "$id.app.local"
    private fun main(scheme: String?, host: String?, gesture: Boolean = false, redirect: Boolean = false) =
        SubappWebViews.decide(scheme, host, id, mainFrame = true, gesture = gesture, redirect = redirect)

    @Test fun `own https origin is allowed, other schemes on it are not`() {
        assertEquals(ALLOW, main("https", host))
        assertEquals(ALLOW, main("HTTPS", host.uppercase(), gesture = true))
        assertEquals(BLOCK, main("http", host, gesture = true))
        assertEquals(BLOCK, main("javascript", host))
    }

    @Test fun `foreign main-frame navigation leaves only on a deliberate gesture`() {
        assertEquals(EXTERNAL, main("https", "example.com", gesture = true))
        assertEquals(EXTERNAL, main("http", "example.com", gesture = true))
        assertEquals(EXTERNAL, main("mailto", null, gesture = true))
        assertEquals(EXTERNAL, main("tel", null, gesture = true))
        assertEquals(BLOCK, main("https", "example.com"))
        assertEquals(BLOCK, main("https", "example.com", gesture = true, redirect = true))
        assertEquals(BLOCK, main("intent", "example.com", gesture = true))
        assertEquals(BLOCK, main("file", null, gesture = true))
        assertEquals(BLOCK, main("content", "com.example", gesture = true))
        assertEquals(BLOCK, main("data", null, gesture = true))
        assertEquals(BLOCK, main(null, null, gesture = true))
    }

    @Test fun `lookalike and other subapp hosts are foreign`() {
        assertEquals(BLOCK, main("https", "other.app.local"))
        assertEquals(BLOCK, main("https", "$host.evil.com"))
        assertEquals(BLOCK, main("https", "x$host"))
    }

    @Test fun `subframes are left to the page`() {
        assertEquals(ALLOW, SubappWebViews.decide("https", "example.com", id, mainFrame = false, gesture = false, redirect = true))
        assertEquals(BLOCK, SubappWebViews.decide("http", host, id, mainFrame = false, gesture = false, redirect = false))
    }
}

class SubappNetworkPolicyTest {
    @Test fun `loopback private and link-local literals are local network`() {
        listOf("localhost", "127.0.0.1", "10.1.2.3", "192.168.0.5", "172.16.0.1", "172.31.255.1", "169.254.169.254",
            "100.64.0.1", "[::1]", "::1", "fe80::1", "fd00::1", "printer.local", "db.internal", "0.0.0.0", null
        ).forEach { org.junit.Assert.assertTrue(it.toString(), SubappWebViews.isLocalNetworkHost(it)) }
        listOf("example.com", "api.openai.com", "8.8.8.8", "172.32.0.1", "172.15.0.1", "192.169.0.1", "1.1.1.1")
            .forEach { org.junit.Assert.assertFalse(it, SubappWebViews.isLocalNetworkHost(it)) }
    }

    @Test fun `offline policy keeps remote hosts out`() {
        val policy = SubappWebViews.offlinePolicy("abc")
        org.junit.Assert.assertTrue(policy.contains("connect-src 'self' https://abc.app.local"))
        org.junit.Assert.assertFalse(policy.contains("https:;") || policy.contains(" *"))
    }
}
