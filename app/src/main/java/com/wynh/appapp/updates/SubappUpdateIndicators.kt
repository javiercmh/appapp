package com.wynh.appapp.updates

import androidx.core.content.edit
import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.wynh.appapp.workspace.SubappRecord

/** Native metadata, outside subapp workspaces and exports. Failed checks do not overwrite it. */
class SubappUpdateIndicators internal constructor(private val prefs: SharedPreferences) {
    private val gson = Gson()
    fun record(id: String, version: VersionUpdateChecker.PublishedVersion) {
        prefs.edit { putString(id, gson.toJson(version)) }
    }
    fun clear(id: String) { prefs.edit { remove(id) } }
    internal fun matching(id: String, source: GitHubSource?, installedVersion: String?): VersionUpdateChecker.PublishedVersion? {
        val value = runCatching { gson.fromJson(prefs.getString(id, null), VersionUpdateChecker.PublishedVersion::class.java) }.getOrNull()
            ?: return null
        if (source != value.source || !runCatching { VersionUpdateChecker.newer(value.version, installedVersion) }.getOrDefault(false)) {
            clear(id); return null
        }
        return value.takeIf { it.commit.matches(Regex("[a-f0-9]{40}")) }
    }
    fun available(record: SubappRecord): VersionUpdateChecker.PublishedVersion? {
        if (!record.workspaceDir.isDirectory) { clear(record.id); return null }
        return runCatching {
            val updater = WorkspaceUpdater(record.directory)
            matching(record.id, updater.installed()?.source, updater.currentVersion())
        }.getOrNull()
    }
    companion object {
        fun of(context: Context) = SubappUpdateIndicators(context.getSharedPreferences("subapp_version_updates", Context.MODE_PRIVATE))
    }
}
