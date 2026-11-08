package com.wynh.appapp.updates

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test

class UpdateAvailabilityTest {
    /** Just enough SharedPreferences for string values. */
    internal class MemoryPrefs : SharedPreferences {
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
    private val store = UpdateAvailability(MemoryPrefs())

    @Test
    fun remembersAnUnchangedCommitOnlyForItsSource() {
        assertNull(store.knownUnchanged("a", main))
        store.recordUnchanged("a", main, "2".repeat(40))
        assertEquals("2".repeat(40), store.knownUnchanged("a", main))
        assertNull(store.knownUnchanged("a", dev))
        assertNull(store.knownUnchanged("b", main))
    }
}
