package com.wynh.appapp

import android.app.Activity
import java.lang.ref.WeakReference

/** Tracks live app-bound screens so deleting an app immediately closes its editor and runtime. */
interface UpdatableSubappSession {
    fun pauseForUpdate()
    fun resumeAfterUpdate()
}

object SubappSessionRegistry {
    private val sessions = mutableMapOf<String, MutableList<WeakReference<Activity>>>()

    @Synchronized fun register(id: String, activity: Activity) {
        sessions.getOrPut(id) { mutableListOf() }.add(WeakReference(activity))
    }

    @Synchronized fun unregister(id: String, activity: Activity) {
        sessions[id]?.removeAll { it.get() == null || it.get() === activity }
        if (sessions[id].isNullOrEmpty()) sessions.remove(id)
    }

    @Synchronized fun pause(id: String) {
        sessions[id]?.forEach { (it.get() as? UpdatableSubappSession)?.pauseForUpdate() }
    }

    @Synchronized fun resume(id: String) {
        sessions[id]?.forEach { (it.get() as? UpdatableSubappSession)?.resumeAfterUpdate() }
    }

    @Synchronized fun close(id: String) {
        sessions.remove(id)?.forEach { it.get()?.finish() }
    }
}
