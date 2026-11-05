package com.wynh.appapp.updates

import android.content.Context
import android.content.SharedPreferences
import com.wynh.appapp.workspace.SubappRecord

/**
 * Remembers, per app, the newest commit seen on its followed branch, so the library can show
 * "Update available" without the user tapping Update. A newer commit does not always change the
 * app's files (a docs-only commit, for example), so a full check that finds no publisher changes
 * marks that commit as known; the badge then stays off until the branch moves again.
 */
class UpdateAvailability internal constructor(private val prefs: SharedPreferences, private val now: () -> Long = System::currentTimeMillis) {
    private data class Seen(val source: String, val latest: String, val checkedAt: Long, val unchanged: String?)

    private fun read(appId: String): Seen? = prefs.getString(appId, null)?.split('\n')?.takeIf { it.size == 4 }?.let {
        Seen(it[0], it[1], it[2].toLongOrNull() ?: 0, it[3].ifEmpty { null })
    }
    private fun write(appId: String, seen: Seen) {
        prefs.edit().putString(appId, listOf(seen.source, seen.latest, seen.checkedAt.toString(), seen.unchanged.orEmpty()).joinToString("\n")).apply()
    }

    /** True when the branch has a commit that is neither installed nor known to leave the app unchanged. */
    fun available(appId: String, installed: InstalledUpdate?): Boolean {
        installed ?: return false
        val seen = read(appId)?.takeIf { it.source == installed.source.display } ?: return false
        return seen.latest != installed.commit && seen.latest != seen.unchanged
    }

    /** True when [installed]'s source is unchecked, was checked under another source, or longer ago than [CHECK_INTERVAL]. */
    fun due(appId: String, installed: InstalledUpdate?): Boolean {
        installed ?: return false
        val seen = read(appId) ?: return true
        return seen.source != installed.source.display || now() - seen.checkedAt >= CHECK_INTERVAL
    }

    fun recordLatest(appId: String, source: GitHubSource, commit: String) {
        val old = read(appId)?.takeIf { it.source == source.display }
        write(appId, Seen(source.display, commit, now(), old?.unchanged)); changes.value++
    }

    /** The commit whose package a full check found identical to the installed one. */
    fun knownUnchanged(appId: String, source: GitHubSource): String? = read(appId)?.takeIf { it.source == source.display }?.unchanged

    fun recordUnchanged(appId: String, source: GitHubSource, commit: String) {
        write(appId, Seen(source.display, commit, now(), commit)); changes.value++
    }

    fun forget(appId: String) { prefs.edit().remove(appId).apply() }

    companion object {
        const val CHECK_INTERVAL = 6 * 60 * 60 * 1000L
        /** Bumped whenever a record changes, so the library can redraw its badges. */
        val changes = kotlinx.coroutines.flow.MutableStateFlow(0)
        fun of(context: Context) = UpdateAvailability(context.applicationContext.getSharedPreferences("update_availability", Context.MODE_PRIVATE))

        /**
         * Checks the linked apps in [records] that are due, one small GitHub request each. Failures
         * are ignored; the next library visit tries again. Returns true when any badge may have changed.
         */
        fun refresh(context: Context, records: List<SubappRecord>): Boolean {
            val store = of(context)
            var changed = false
            for (record in records) {
                val installed = runCatching { WorkspaceUpdater(record.directory).installed() }.getOrNull() ?: continue
                if (!store.due(record.id, installed)) continue
                val latest = runCatching { GitHubUpdateDownloader(context.cacheDir).latestCommit(installed.source) }.getOrNull() ?: continue
                store.recordLatest(record.id, installed.source, latest)
                changed = true
            }
            return changed
        }
    }
}
