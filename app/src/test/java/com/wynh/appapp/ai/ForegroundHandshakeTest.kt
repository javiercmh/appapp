package com.wynh.appapp.ai

import com.wynh.appapp.ai.ForegroundHandshake.State
import org.junit.Assert.*
import org.junit.Test

class ForegroundHandshakeTest {
    @Test fun `confirmation moves a pending start to foreground once`() {
        val h = ForegroundHandshake(); val e = h.begin()
        assertEquals(State.STARTING, h.state)
        assertTrue(h.confirm(e)); assertEquals(State.FOREGROUND, h.state)
        assertFalse("A repeated confirmation is a no-op", h.confirm(e))
    }

    @Test fun `denial and timeout fail only the pending epoch`() {
        val h = ForegroundHandshake(); val e = h.begin()
        assertTrue(h.fail(e)); assertEquals(State.NONE, h.state)
        assertFalse(h.fail(e))
    }

    @Test fun `stale confirmation timeout and destruction cannot touch a replacement service`() {
        val h = ForegroundHandshake()
        val old = h.begin(); assertTrue(h.fail(old))
        val replacement = h.begin()
        assertFalse(h.confirm(old)); assertFalse(h.fail(old)); assertNull(h.destroyed(old)); assertFalse(h.lost(old))
        assertEquals(State.STARTING, h.state)
        assertTrue(h.confirm(replacement))
        assertNull("The old service going away must not reset the replacement", h.destroyed(old))
        assertEquals(State.FOREGROUND, h.state)
    }

    @Test fun `destruction reports the state it left and resets`() {
        val h = ForegroundHandshake(); val e = h.begin()
        assertEquals(State.STARTING, h.destroyed(e)); assertEquals(State.NONE, h.state)
        val f = h.begin(); h.confirm(f)
        assertEquals(State.FOREGROUND, h.destroyed(f))
        assertNull(h.destroyed(f))
    }

    @Test fun `losing foreground later is distinct from a failed start`() {
        val h = ForegroundHandshake(); val e = h.begin()
        assertFalse("Not foreground yet", h.lost(e))
        h.confirm(e)
        assertFalse(h.fail(e)); assertTrue(h.lost(e)); assertEquals(State.NONE, h.state)
    }

    @Test fun `idle service lets the next request start a new epoch`() {
        val h = ForegroundHandshake(); val e = h.begin(); h.confirm(e)
        h.idle(); assertEquals(State.NONE, h.state)
        assertTrue(h.begin() > e)
    }
}
