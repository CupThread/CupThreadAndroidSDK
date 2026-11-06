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
    budgetBytes: Long = defaultBudgetBytes(),
    private val sizeOfBytes: (ImageBitmap) -> Long = { it.asAndroidBitmap().allocationByteCount.toLong() }
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

        /** One-sixteenth of the max heap; `maxMemory` already fits an Int per JVM spec, but keep Long. */
        internal fun defaultBudgetBytes(): Long =
            (Runtime.getRuntime().maxMemory() / 16).coerceAtLeast(1)
    }
}

/**
 * Minimal thread-safe, byte-bounded LRU (mirrors `android.util.LruCache` semantics: access order,
 * entries larger than the whole budget are inserted but evicted immediately, `get` refreshes
 * recency). Pure Kotlin so JVM unit tests need no Android runtime.
 *
 * All map mutations, byte accounting, and trimming run under a single lock, so concurrent puts
 * can never double-count bytes or race the eviction path. Byte totals are [Long]: on a ≥32 GiB
 * heap the capped [Int] budget times large avatars can exceed 2 GiB, which would overflow an
 * [Int] total and disable eviction.
 */
internal class ByteBoundedLru<V>(
    private val budgetBytes: Long,
    private val sizeOfBytes: (V) -> Long
) {
    // Access-order LinkedHashMap: reads refresh recency, first entry is the LRU head.
    private val map = LinkedHashMap<String, V>(16, 0.75f, true)

    private var totalBytes = 0L

    @Synchronized
    fun get(key: String): V? {
        // LinkedHashMap in access order refreshes recency on read.
        return map[key]
    }

    @Synchronized
    fun put(key: String, value: V) {
        val size = sizeOfBytes(value)
        val previous = map.put(key, value)
        totalBytes += size - (previous?.let(sizeOfBytes) ?: 0L)
        trim()
    }

    @Synchronized
    fun clear() {
        map.clear()
        totalBytes = 0L
    }

    /** Evicts from the LRU head until the total fits the budget. */
    private fun trim() {
        val iterator = map.entries.iterator()
        while (totalBytes > budgetBytes && iterator.hasNext()) {
            val entry = iterator.next()
            iterator.remove()
            totalBytes -= sizeOfBytes(entry.value)
        }
    }
}
