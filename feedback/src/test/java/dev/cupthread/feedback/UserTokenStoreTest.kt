package dev.cupthread.feedback

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class UserTokenStoreTest {

    @Test
    fun mintsAndDurablyCommitsTokenOnFirstAccess() {
        val prefs = ThreadSafeFakeSharedPreferences()
        val store = UserTokenStore(prefs)

        val token = store.token

        assertNotNull(token)
        assertTrue(token.isNotBlank())
        assertEquals(token, prefs.getString("user_token", null))
        assertEquals("Token must be saved using commit() for durability", 1, prefs.commitCount.get())
        assertEquals("apply() must not be used for critical token mint", 0, prefs.applyCount.get())
    }

    @Test
    fun concurrentFirstAccessAcrossThreadsReturnsIdenticalToken() {
        val prefs = ThreadSafeFakeSharedPreferences()
        val store = UserTokenStore(prefs)

        val threadCount = 40
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)
        val tokens = Collections.synchronizedList(mutableListOf<String>())

        for (i in 0 until threadCount) {
            executor.submit {
                try {
                    startLatch.await()
                    tokens.add(store.token)
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        // Unleash all threads simultaneously
        startLatch.countDown()
        assertTrue("Timed out waiting for concurrent accessors", doneLatch.await(5, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals(threadCount, tokens.size)
        val distinctTokens = tokens.toSet()
        assertEquals(
            "All concurrent callers must receive the exact same token string, but got: $distinctTokens",
            1,
            distinctTokens.size
        )
        val singleToken = distinctTokens.first()
        assertEquals(singleToken, prefs.getString("user_token", null))
        assertEquals(1, prefs.commitCount.get())
    }

    @Test
    fun concurrentFirstAccessWithMultipleStoreInstancesReturnsIdenticalToken() {
        val prefs = ThreadSafeFakeSharedPreferences()

        val threadCount = 30
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)
        val tokens = Collections.synchronizedList(mutableListOf<String>())

        for (i in 0 until threadCount) {
            executor.submit {
                try {
                    startLatch.await()
                    // Each thread creates its own store instance sharing the same SharedPreferences
                    val localStore = UserTokenStore(prefs)
                    tokens.add(localStore.token)
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        assertTrue("Timed out waiting for concurrent accessors", doneLatch.await(5, TimeUnit.SECONDS))
        executor.shutdown()

        val distinctTokens = tokens.toSet()
        assertEquals(
            "Multiple store instances accessing uninitialized prefs concurrently must mint one identity",
            1,
            distinctTokens.size
        )
        assertEquals(distinctTokens.first(), prefs.getString("user_token", null))
    }

    @Test
    fun memoizesTokenInMemoryForFastSubsequentReads() {
        val prefs = ThreadSafeFakeSharedPreferences()
        val store = UserTokenStore(prefs)

        val tokenFirst = store.token
        val readsBefore = prefs.getStringCount.get()

        // Subsequent reads should use the in-memory cached token without hitting prefs
        val tokenSecond = store.token
        val tokenThird = store.token

        assertEquals(tokenFirst, tokenSecond)
        assertEquals(tokenFirst, tokenThird)
        assertEquals(
            "Subsequent reads should be served from memory cache without additional prefs reads",
            readsBefore,
            prefs.getStringCount.get()
        )
    }

    @Test
    fun reusesExistingTokenFromStorageWithoutReMinting() {
        val prefs = ThreadSafeFakeSharedPreferences()
        val existingUuid = "11111111-2222-3333-4444-555555555555"
        prefs.putStringDirectly("user_token", existingUuid)

        val store = UserTokenStore(prefs)
        val token = store.token

        assertEquals(existingUuid, token)
        assertEquals(
            "Must not commit or re-mint when token already exists",
            0,
            prefs.commitCount.get()
        )
    }

    @Test
    fun preservesTokenAcrossProcessRestart() {
        val prefs = ThreadSafeFakeSharedPreferences()

        // Process 1: First install / launch
        val store1 = UserTokenStore(prefs)
        val token1 = store1.token

        // Process 2: Relaunch
        val store2 = UserTokenStore(prefs)
        val token2 = store2.token

        assertEquals(token1, token2)
        assertEquals("Should only have committed once during first mint", 1, prefs.commitCount.get())
    }

    /**
     * Thread-safe fake SharedPreferences implementation tracking operations.
     */
    private class ThreadSafeFakeSharedPreferences : SharedPreferences {
        private val storage = ConcurrentHashMap<String, Any>()
        val commitCount = AtomicInteger(0)
        val applyCount = AtomicInteger(0)
        val getStringCount = AtomicInteger(0)

        fun putStringDirectly(key: String, value: String) {
            storage[key] = value
        }

        override fun getAll(): MutableMap<String, *> = HashMap(storage)

        override fun getString(key: String?, defValue: String?): String? {
            getStringCount.incrementAndGet()
            if (key == null) return defValue
            val value = storage[key]
            return if (value is String) value else defValue
        }

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (storage[key] as? Set<String>)?.toMutableSet() ?: defValues

        override fun getInt(key: String?, defValue: Int): Int =
            (storage[key] as? Int) ?: defValue

        override fun getLong(key: String?, defValue: Long): Long =
            (storage[key] as? Long) ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float =
            (storage[key] as? Float) ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            (storage[key] as? Boolean) ?: defValue

        override fun contains(key: String?): Boolean =
            key != null && storage.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) {}

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) {}

        private inner class FakeEditor : SharedPreferences.Editor {
            private val pending = ConcurrentHashMap<String, Any?>()
            private var clearPending = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = values?.toSet()
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun remove(key: String?): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = this
            }

            override fun clear(): SharedPreferences.Editor = apply {
                clearPending = true
            }

            override fun commit(): Boolean {
                commitCount.incrementAndGet()
                flush()
                return true
            }

            override fun apply() {
                applyCount.incrementAndGet()
                flush()
            }

            private fun flush() {
                synchronized(storage) {
                    if (clearPending) storage.clear()
                    for ((k, v) in pending) {
                        if (v === this) {
                            storage.remove(k)
                        } else if (v != null) {
                            storage[k] = v
                        } else {
                            storage.remove(k)
                        }
                    }
                }
            }
        }
    }
}
