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

    private fun live(id: String): List<Activity> = synchronized(this) { sessions[id].orEmpty().mapNotNull { it.get() } }
    fun pause(id: String) { live(id).forEach { (it as? UpdatableSubappSession)?.pauseForUpdate() } }
    fun resume(id: String) { live(id).forEach { (it as? UpdatableSubappSession)?.resumeAfterUpdate() } }
    fun close(id: String) {
        val activities = synchronized(this) { sessions.remove(id).orEmpty().mapNotNull { it.get() } }
        activities.forEach { it.finish() }
    }
}
