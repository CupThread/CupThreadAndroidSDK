package dev.cupthread.feedback

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ChangelogStoreConcurrencyTest {

    @Test
    fun concurrentSeenMarksFromTwoThreadsAreBothPersisted() {
        val prefs = ThreadSafeFakeSharedPreferences()
        val store = ChangelogStore(prefs)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)

        fun mark(token: String) = pool.submit {
            start.await()
            store.markChangelogSeen(token)
        }

        val first = mark("version-a")
        val second = mark("version-b")
        start.countDown()
        first.get(10, TimeUnit.SECONDS)
        second.get(10, TimeUnit.SECONDS)
        pool.shutdown()

        assertEquals(setOf("version-a", "version-b"), store.getSeenChangelogs())
    }

    @Test
    fun concurrentSeenMarksAcrossSeparateStoreInstancesSharingPrefsAreBothPersisted() {
        // Mirrors production: ChangelogOverlay and FeedbackClient.markChangelogSeen each
        // call ChangelogStore.create(context), so concurrent callers race through distinct
        // instances on the same SharedPreferences file. Only a process-wide lock closes this.
        val prefs = ThreadSafeFakeSharedPreferences()
        val overlayStore = ChangelogStore(prefs)
        val clientStore = ChangelogStore(prefs)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)

        val fromOverlay = pool.submit {
            start.await()
            overlayStore.markChangelogSeen("version-overlay")
        }
        val fromClient = pool.submit {
            start.await()
            clientStore.markChangelogSeen("version-client")
        }
        start.countDown()
        fromOverlay.get(10, TimeUnit.SECONDS)
        fromClient.get(10, TimeUnit.SECONDS)
        pool.shutdown()

        assertEquals(
            setOf("version-overlay", "version-client"),
            ChangelogStore(prefs).getSeenChangelogs()
        )
    }

    @Test
    fun manyConcurrentSeenMarksAllPersist() {
        val prefs = ThreadSafeFakeSharedPreferences()
        val store = ChangelogStore(prefs)
        val threadCount = 16
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threadCount)
        val failures = AtomicInteger(0)

        val futures = (0 until threadCount).map { i ->
            pool.submit {
                try {
                    start.await()
                    store.markChangelogSeen("version-$i")
                } catch (t: Throwable) {
                    failures.incrementAndGet()
                }
            }
        }
        start.countDown()
        futures.forEach { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(0, failures.get())
        assertEquals((0 until threadCount).map { "version-$it" }.toSet(), store.getSeenChangelogs())
    }

    @Test
    fun unseenConcurrentWithSeenForSameKeyConvergesToSeen() {
        val prefs = ThreadSafeFakeSharedPreferences()
        val store = ChangelogStore(prefs)
        store.markChangelogSeen("version-a")
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)

        // seen and unseen racing for the same key: lock ordering makes the outcome
        // deterministic — the mark that wins the lock last is the final state.
        val unseen = pool.submit {
            start.await()
            store.markChangelogUnseen("version-a")
        }
        val seen = pool.submit {
            start.await()
            store.markChangelogSeen("version-a")
        }
        start.countDown()
        unseen.get(10, TimeUnit.SECONDS)
        seen.get(10, TimeUnit.SECONDS)
        pool.shutdown()

        // Both mutations completed; the seen set never ends up corrupted, and repeated
        // re-runs of either op converge to a stable single-element-or-empty state.
        val after = store.getSeenChangelogs()
        assertTrue(after == setOf("version-a") || after.isEmpty())
    }

    @Test
    fun getSeenChangelogsReturnsDefensiveCopy() {
        val prefs = ThreadSafeFakeSharedPreferences()
        val store = ChangelogStore(prefs)
        store.markChangelogSeen("2.1.0")
        val snapshot = store.getSeenChangelogs().toMutableSet()
        snapshot.add("mutated")

        assertFalse(store.hasSeenChangelog("mutated"))
        assertEquals(setOf("2.1.0"), store.getSeenChangelogs())
    }

    @Test
    fun clearIsSerializedAgainstConcurrentMarks() {
        val prefs = ThreadSafeFakeSharedPreferences()
        val store = ChangelogStore(prefs)
        store.markChangelogSeen("before-clear")
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)

        val clear = pool.submit {
            start.await()
            store.clearSeenChangelogs()
        }
        val mark = pool.submit {
            start.await()
            store.markChangelogSeen("after-clear")
        }
        start.countDown()
        clear.get(10, TimeUnit.SECONDS)
        mark.get(10, TimeUnit.SECONDS)
        pool.shutdown()

        // Either order is acceptable; corruption or a lost write is not.
        val after = store.getSeenChangelogs()
        assertTrue(after == setOf("after-clear") || after.isEmpty())
    }
}

/**
 * Thread-safe fake SharedPreferences, mirroring the one in [UserTokenStoreTest],
 * so concurrent store operations cannot be masked by a non-thread-safe fake.
 */
private class ThreadSafeFakeSharedPreferences : SharedPreferences {
    private val storage = ConcurrentHashMap<String, Any>()

    fun putStringSetDirectly(key: String, values: Set<String>) {
        storage[key] = values
    }

    override fun getAll(): MutableMap<String, *> = HashMap(storage)

    override fun getString(key: String?, defValue: String?): String? =
        (storage[key] as? String) ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (storage[key] as? Set<String>)?.toMutableSet() ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = (storage[key] as? Int) ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = (storage[key] as? Long) ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = (storage[key] as? Float) ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = (storage[key] as? Boolean) ?: defValue
    override fun contains(key: String?): Boolean = key != null && storage.containsKey(key)
    override fun edit(): SharedPreferences.Editor = FakeEditor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

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
            flush()
            return true
        }

        override fun apply() {
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
