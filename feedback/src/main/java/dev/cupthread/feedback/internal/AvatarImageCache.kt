package dev.cupthread.feedback.internal

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.util.Collections

/**
 * Process-wide, byte-bounded LRU cache for decoded avatar bitmaps (issue #141).
 *
 * Entries are keyed by the hinted avatar URL (the width hint is part of the key, so one logical
 * avatar occupies one entry per requested width). The budget is measured in bytes via
 * [sizeOfBytes] — by default `Bitmap.allocationByteCount` — and defaults to 1/16 of the maximum
 * JVM heap, so a session that scrolls through many commenters can no longer pin unbounded
 * megabytes of bitmaps. Eviction is least-recently-used; a bitmap currently on screen is also
 * held by the composable's state, so eviction never blanks a visible avatar.
 *
 * [budgetBytes] and [sizeOfBytes] are injectable so JVM unit tests can exercise eviction
 * without real bitmaps or an Android runtime.
 */
internal class AvatarImageCache(
    budgetBytes: Int = defaultBudgetBytes(),
    private val sizeOfBytes: (ImageBitmap) -> Int = { it.asAndroidBitmap().allocationByteCount }
) {
    private val lru = ByteBoundedLru<ImageBitmap>(budgetBytes, sizeOfBytes)

    fun get(key: String): ImageBitmap? = lru.get(key)

    fun put(key: String, bitmap: ImageBitmap) {
        lru.put(key, bitmap)
    }

    /** Test hook: drops every entry. */
    internal fun clear() {
        lru.clear()
    }

    companion object {
        /** Shared process-wide cache; avatars are host-app-global content. */
        val shared: AvatarImageCache = AvatarImageCache()

        /** One-sixteenth of the max heap, bounded to [Int.MAX_VALUE] for huge heaps. */
        internal fun defaultBudgetBytes(): Int {
            val budget = Runtime.getRuntime().maxMemory() / 16
            return if (budget > Int.MAX_VALUE) Int.MAX_VALUE else budget.toInt().coerceAtLeast(1)
        }
    }
}

/**
 * Minimal thread-safe, byte-bounded LRU (mirrors `android.util.LruCache` semantics: access order,
 * entries larger than the whole budget are inserted but evicted immediately, `get` refreshes
 * recency). Pure Kotlin so JVM unit tests need no Android runtime.
 */
internal class ByteBoundedLru<V>(
    private val budgetBytes: Int,
    private val sizeOfBytes: (V) -> Int
) {
    private val map = Collections.synchronizedMap(object : LinkedHashMap<String, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?): Boolean {
            if (currentBytes() <= budgetBytes) return false
            eldest?.let { totalBytes -= sizeOfBytes(it.value) }
            return true
        }
    })

    private var totalBytes = 0

    fun get(key: String): V? {
        // LinkedHashMap in access order refreshes recency on read.
        return map[key]
    }

    fun put(key: String, value: V) {
        val size = sizeOfBytes(value)
        val previous = map.put(key, value)
        synchronized(this) {
            totalBytes += size - (previous?.let(sizeOfBytes) ?: 0)
        }
        trim()
    }

    fun clear() {
        map.clear()
        synchronized(this) { totalBytes = 0 }
    }

    private fun currentBytes(): Int = synchronized(this) { totalBytes }

    private fun trim() {
        while (true) {
            val over = currentBytes() > budgetBytes
            if (!over) return
            val eldest: MutableMap.MutableEntry<String, V>? = synchronized(map) {
                map.entries.firstOrNull()
            }
            if (eldest == null) return
            val removed = map.remove(eldest.key)
            if (removed != null) {
                synchronized(this) { totalBytes -= sizeOfBytes(removed) }
            }
        }
    }
}
