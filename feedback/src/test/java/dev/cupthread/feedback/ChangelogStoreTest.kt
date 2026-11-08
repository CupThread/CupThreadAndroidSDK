package dev.cupthread.feedback

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangelogStoreTest {

    @Test
    fun standardSeenAndUnseenLifecycle() {
        val prefs = InMemoryFakeSharedPreferences()
        val store = ChangelogStore(prefs)

        assertFalse(store.hasSeenChangelog("1.0.0"))
        assertEquals(emptySet<String>(), store.getSeenChangelogs())

        store.markChangelogSeen("1.0.0")
        store.markChangelogSeen("2.0.0")

        assertTrue(store.hasSeenChangelog("1.0.0"))
        assertTrue(store.hasSeenChangelog("2.0.0"))
        assertFalse(store.hasSeenChangelog("3.0.0"))
        assertEquals(setOf("1.0.0", "2.0.0"), store.getSeenChangelogs())

        store.markChangelogUnseen("1.0.0")
        assertFalse(store.hasSeenChangelog("1.0.0"))
        assertTrue(store.hasSeenChangelog("2.0.0"))
        assertEquals(setOf("2.0.0"), store.getSeenChangelogs())

        store.clearSeenChangelogs()
        assertFalse(store.hasSeenChangelog("2.0.0"))
        assertEquals(emptySet<String>(), store.getSeenChangelogs())
    }

    @Test
    fun blankAndWhitespaceHandling() {
        val prefs = InMemoryFakeSharedPreferences()
        val store = ChangelogStore(prefs)

        assertFalse(store.hasSeenChangelog(""))
        assertFalse(store.hasSeenChangelog("   "))

        store.markChangelogSeen("")
        store.markChangelogSeen("   ")
        assertEquals(emptySet<String>(), store.getSeenChangelogs())

        store.markChangelogSeen("  1.0.0  ")
        assertTrue(store.hasSeenChangelog("1.0.0"))
        assertTrue(store.hasSeenChangelog("  1.0.0  "))
        assertEquals(setOf("1.0.0"), store.getSeenChangelogs())

        store.markChangelogUnseen("  1.0.0  ")
        assertFalse(store.hasSeenChangelog("1.0.0"))
        assertEquals(emptySet<String>(), store.getSeenChangelogs())
    }

    @Test
    fun getStringSetReturningNullDoesNotThrowNullPointerException() {
        // Issue #221: When SharedPreferences.getStringSet returns null (e.g. key exists with null
        // or platform mock returns null), ChangelogStore must safely fallback to emptySet()
        // without throwing NullPointerException or crashing during seen queries or updates.
        val nullPrefs = NullReturningSharedPreferences()
        val store = ChangelogStore(nullPrefs)

        assertFalse(store.hasSeenChangelog("1.0.0"))
        assertEquals(emptySet<String>(), store.getSeenChangelogs())

        // markChangelogUnseen on null prefs must not throw
        store.markChangelogUnseen("1.0.0")

        // markChangelogSeen on null prefs must initialize and persist without NPE
        store.markChangelogSeen("1.0.0")
        assertEquals(setOf("1.0.0"), nullPrefs.lastWrittenSet)
    }

    /**
     * Fake SharedPreferences that always returns `null` from `getStringSet`,
     * reproducing edge-case platform behavior where defValues is bypassed or null.
     */
    private class NullReturningSharedPreferences : SharedPreferences {
        var lastWrittenSet: Set<String>? = null

        override fun getAll(): MutableMap<String, *> = HashMap<String, Any>()
        override fun getString(key: String?, defValue: String?): String? = defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = null
        override fun getInt(key: String?, defValue: Int): Int = defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
        override fun contains(key: String?): Boolean = false
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?): SharedPreferences.Editor = this
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                lastWrittenSet = values?.toSet()
                return this
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
            override fun remove(key: String?): SharedPreferences.Editor = this
            override fun clear(): SharedPreferences.Editor = this
            override fun commit(): Boolean = true
            override fun apply() {}
        }
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    /**
     * In-memory fake SharedPreferences for single-threaded lifecycle tests.
     */
    private class InMemoryFakeSharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any>()

        override fun getAll(): MutableMap<String, *> = HashMap(data)
        override fun getString(key: String?, defValue: String?): String? =
            (data[key] as? String) ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (data[key] as? Set<String>)?.toMutableSet() ?: defValues

        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = key != null && data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val updates = mutableMapOf<String, Any?>()
            private var clear = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
                if (key != null) updates[key] = value
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
                if (key != null) updates[key] = values?.toSet()
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
                if (key != null) updates[key] = value
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
                if (key != null) updates[key] = value
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
                if (key != null) updates[key] = value
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
                if (key != null) updates[key] = value
            }
            override fun remove(key: String?): SharedPreferences.Editor = apply {
                if (key != null) updates[key] = null
            }
            override fun clear(): SharedPreferences.Editor = apply {
                clear = true
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clear) data.clear()
                for ((k, v) in updates) {
                    if (v == null) data.remove(k) else data[k] = v
                }
            }
        }
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }
}
