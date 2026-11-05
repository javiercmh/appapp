package com.wynh.appapp.updates

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test

class UpdateAvailabilityTest {
    /** Just enough SharedPreferences for string values. */
    private class MemoryPrefs : SharedPreferences {
        val values = HashMap<String, String?>()
        override fun getString(key: String, defValue: String?) = values[key] ?: defValue
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(key: String, value: String?) = apply { values[key] = value }
            override fun remove(key: String) = apply { values.remove(key) }
            override fun apply() {}
            override fun commit() = true
            override fun putStringSet(key: String?, values: MutableSet<String>?) = this
            override fun putInt(key: String?, value: Int) = this
            override fun putLong(key: String?, value: Long) = this
            override fun putFloat(key: String?, value: Float) = this
            override fun putBoolean(key: String?, value: Boolean) = this
            override fun clear() = this
        }
        override fun getAll(): MutableMap<String, *> = values
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String?, defValue: Int) = defValue
        override fun getLong(key: String?, defValue: Long) = defValue
        override fun getFloat(key: String?, defValue: Float) = defValue
        override fun getBoolean(key: String?, defValue: Boolean) = defValue
        override fun contains(key: String?) = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private val main = GitHubSource("owner", "app", "main")
    private val dev = GitHubSource("owner", "app", "dev")
    private fun installed(commit: String, source: GitHubSource = main) = InstalledUpdate("id", "1", source, commit, emptyMap(), emptySet())
    private var time = 1_000_000L
    private val store = UpdateAvailability(MemoryPrefs()) { time }

    @Test
    fun badgeShowsForANewCommitUntilItIsInstalledOrKnownUnchanged() {
        assertFalse(store.available("a", installed("1".repeat(40))))
        store.recordLatest("a", main, "2".repeat(40))
        assertTrue(store.available("a", installed("1".repeat(40))))
        assertFalse(store.available("a", installed("2".repeat(40))))
        store.recordUnchanged("a", main, "2".repeat(40))
        assertFalse(store.available("a", installed("1".repeat(40))))
        store.recordLatest("a", main, "3".repeat(40))
        assertTrue(store.available("a", installed("1".repeat(40))))
    }

    @Test
    fun aSourceChangeIgnoresTheOldResultAndIsDueAtOnce() {
        store.recordLatest("a", main, "2".repeat(40))
        assertFalse(store.available("a", installed("1".repeat(40), dev)))
        assertTrue(store.due("a", installed("1".repeat(40), dev)))
        assertNull(store.knownUnchanged("a", dev))
    }

    @Test
    fun checksAgainOnlyAfterTheInterval() {
        assertTrue(store.due("a", installed("1".repeat(40))))
        store.recordLatest("a", main, "1".repeat(40))
        assertFalse(store.due("a", installed("1".repeat(40))))
        time += UpdateAvailability.CHECK_INTERVAL
        assertTrue(store.due("a", installed("1".repeat(40))))
        assertFalse(store.due("a", null))
    }
}
