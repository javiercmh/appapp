package com.wynh.appapp.updates

import android.content.Context
import android.content.SharedPreferences

/**
 * Remembers, per app, a branch commit whose package a full check found identical to the installed
 * one (a docs-only commit, for example), so the next manual check of that commit can report
 * "up to date" without downloading. App² never checks for updates on its own.
 */
class UpdateAvailability internal constructor(private val prefs: SharedPreferences) {
    /** The commit a full check of [source] found to leave the app unchanged, if any. */
    fun knownUnchanged(appId: String, source: GitHubSource): String? =
        prefs.getString(appId, null)?.split('\n')?.takeIf { it.size == 2 && it[0] == source.display }?.get(1)

    fun recordUnchanged(appId: String, source: GitHubSource, commit: String) {
        prefs.edit().putString(appId, "${source.display}\n$commit").apply()
    }

    companion object {
        fun of(context: Context) = UpdateAvailability(context.applicationContext.getSharedPreferences("update_availability", Context.MODE_PRIVATE))
    }
}
