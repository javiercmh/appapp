package com.wynh.appapp.ai.auth

import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class OAuthAttemptTest {
    private val callback = "http://127.0.0.1:54321/auth/callback"

    @Test fun `initial registration uses dynamic client and exact redirect`() {
        val attempt = OAuthAttempt(callback, "urn:uuid:test")
        val params = query(attempt.authorizationUrl())
        assertEquals(OpenAiAuth.DYNAMIC_CLIENT, params["client_id"])
        assertEquals("AppApp", params["agent_name_hint"])
        assertEquals(callback, params["redirect_uri"])
        assertEquals(OpenAiAuth.RESOURCE, params["resource"])
        assertEquals(OpenAiAuth.scopes, params["scope"]!!.split(' ').toSet())
        assertEquals("S256", params["code_challenge_method"])
        assertNotEquals(attempt.verifier, params["code_challenge"])
    }

    @Test fun `PKCE matches the RFC 7636 test vector`() {
        val attempt = OAuthAttempt(callback, "host", verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", attempt.challenge)
    }

    @Test fun `new registration requires issued client and matching state`() {
        val attempt = OAuthAttempt(callback, "host", state = "expected")
        assertFails { attempt.validateCallback("/auth/callback?state=wrong&code=code&client_id=oaiapp_test") }
        assertFails { attempt.validateCallback("/auth/callback?state=expected&code=code") }
        assertFails { attempt.validateCallback("/auth/callback?state=expected&code=code&client_id=dynamic_agent_client") }
        assertFails { attempt.validateCallback("/auth/callback?state=expected&state=expected&code=code&client_id=oaiapp_test") }
        assertFails { attempt.validateCallback("/callback?state=expected&code=code&client_id=oaiapp_test") }
        assertFails { attempt.validateCallback("http://evil.example/auth/callback?state=expected&code=code&client_id=oaiapp_test") }
        val result = attempt.validateCallback("/auth/callback?state=expected&code=code&client_id=oaiapp_test") as CallbackResult.Success
        assertEquals("oaiapp_test", result.clientId)
    }

    @Test fun `returning callback may omit but cannot replace client id`() {
        val saved = Registration("oaiapp_saved", Identity(OpenAiAuth.ISSUER, "subject", "test@example.com"), null)
        val attempt = OAuthAttempt(callback, "host", saved, state = "expected")
        assertEquals("oaiapp_saved", query(attempt.authorizationUrl())["client_id"])
        assertFalse(query(attempt.authorizationUrl()).containsKey("agent_name_hint"))
        assertEquals("oaiapp_saved", (attempt.validateCallback("/auth/callback?state=expected&code=code") as CallbackResult.Success).clientId)
        assertFails { attempt.validateCallback("/auth/callback?state=expected&code=code&client_id=oaiapp_other") }
    }

    @Test fun `denial validates state before returning error`() {
        val attempt = OAuthAttempt(callback, "host", state = "expected")
        assertFails { attempt.validateCallback("/auth/callback?state=wrong&error=access_denied") }
        assertTrue(attempt.validateCallback("/auth/callback?state=expected&error=access_denied") is CallbackResult.Denied)
    }

    @Test fun `retry after invalid grant reuses issued client without dynamic registration`() {
        val attempt = OAuthAttempt(callback, "host", state = "state", retryClientId = "issued-retry")
        assertEquals("issued-retry", query(attempt.authorizationUrl())["client_id"])
        assertFalse(query(attempt.authorizationUrl()).containsKey("agent_name_hint"))
        assertEquals("issued-retry", (attempt.validateCallback("/auth/callback?state=state&code=new-code") as CallbackResult.Success).clientId)
        assertFails { attempt.validateCallback("/auth/callback?state=state&code=new-code&client_id=other") }
    }

    private fun query(url: String) = URI(url).rawQuery.split('&').associate {
        val p = it.split('=', limit = 2)
        URLDecoder.decode(p[0], "UTF-8") to URLDecoder.decode(p[1], "UTF-8")
    }
    private fun assertFails(action: () -> Unit) {
        try { action(); fail("Expected an invalid callback") } catch (_: AuthFailure) { }
    }
}
