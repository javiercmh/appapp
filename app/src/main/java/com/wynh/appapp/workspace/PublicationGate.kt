package com.wynh.appapp.workspace

/**
 * Decides, atomically, whether an import is cancelled or published. Cancel is honoured until
 * publication begins; once it has begun, cancel is refused so the screen never reports
 * "cancelling" for an app that is being registered.
 */
class PublicationGate {
    private var cancelled = false
    private var publishing = false

    @Synchronized fun reset() { cancelled = false; publishing = false }

    /** True when the request was accepted and the import will stop before publication. */
    @Synchronized fun cancel(): Boolean {
        if (publishing) return false
        cancelled = true
        return true
    }

    /** False when the import was cancelled and must not publish. */
    @Synchronized fun beginPublication(): Boolean {
        if (cancelled) return false
        publishing = true
        return true
    }
}
