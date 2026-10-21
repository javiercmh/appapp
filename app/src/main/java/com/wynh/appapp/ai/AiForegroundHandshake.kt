package com.wynh.appapp.ai

/**
 * Tracks whether the request service has reached the foreground. Every start request gets a new
 * epoch that travels with the intent, and every callback names its epoch, so a confirmation,
 * failure, timeout or destruction belonging to an earlier service cannot affect a replacement.
 * Call from one thread (the main thread).
 */
internal class ForegroundHandshake {
    enum class State { NONE, STARTING, FOREGROUND }

    var state = State.NONE
        private set
    var epoch = 0L
        private set

    fun begin(): Long { epoch += 1; state = State.STARTING; return epoch }

    /** True when this confirms the pending start. */
    fun confirm(e: Long): Boolean {
        if (e != epoch || state != State.STARTING) return false
        state = State.FOREGROUND; return true
    }

    /** True when this fails the pending start (denial or timeout). */
    fun fail(e: Long): Boolean {
        if (e != epoch || state != State.STARTING) return false
        state = State.NONE; return true
    }

    /** The service object that carried [e] went away. Returns the state it left, or null if stale. */
    fun destroyed(e: Long): State? {
        if (e != epoch || state == State.NONE) return null
        val previous = state; state = State.NONE; return previous
    }

    /** The service decided it has nothing to run and is stopping. */
    fun idle() { if (state == State.FOREGROUND) state = State.NONE }

    /** The foreground state was lost after it was reached. True if [e] is current. */
    fun lost(e: Long): Boolean {
        if (e != epoch || state != State.FOREGROUND) return false
        state = State.NONE; return true
    }

    /** The running service is gone although the state says otherwise. */
    fun reset() { state = State.NONE }
}
