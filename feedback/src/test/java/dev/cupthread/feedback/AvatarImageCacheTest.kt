package dev.cupthread.feedback

import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.ImageBitmap
import dev.cupthread.feedback.internal.AvatarImageCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the byte-bounded LRU behavior of [AvatarImageCache] (issue #141): eviction respects
 * the byte budget (not entry count), follows least-recently-used order, and treats each hinted
 * width of one avatar as a separate entry under a shared budget.
 */
class AvatarImageCacheTest {

    private class FakeBitmap(val bytes: Int) : ImageBitmap {
        override val width: Int = 1
        override val height: Int = 1
        override val hasAlpha: Boolean = false
        override val colorSpace: ColorSpace = ColorSpaces.Srgb
        override val config: androidx.compose.ui.graphics.ImageBitmapConfig =
            androidx.compose.ui.graphics.ImageBitmapConfig.Argb8888
        override fun prepareToDraw() {}
        override fun readPixels(
            buffer: IntArray,
            startX: Int,
            startY: Int,
            width: Int,
            height: Int,
            bufferOffset: Int,
            stride: Int
        ) {}
    }

    private fun cache(budget: Int) = AvatarImageCache(
        budgetBytes = budget,
        sizeOfBytes = { (it as FakeBitmap).bytes }
    )

    @Test
    fun getReturnsPutEntry() {
        val cache = cache(budget = 100)
        val bitmap = FakeBitmap(10)
        cache.put("a", bitmap)
        assertEquals(bitmap, cache.get("a"))
    }

    @Test
    fun evictsLeastRecentlyUsedFirstWhenBudgetExceeded() {
        val cache = cache(budget = 25)
        val a = FakeBitmap(10)
        val b = FakeBitmap(10)
        val c = FakeBitmap(10)
        cache.put("a", a)
        cache.put("b", b)
        cache.get("a") // touch "a" so "b" becomes the LRU entry
        cache.put("c", c)

        assertEquals(a, cache.get("a"))
        assertNull("The untouched middle entry must be evicted first", cache.get("b"))
        assertEquals(c, cache.get("c"))
    }

    @Test
    fun oversizedSingleEntryIsEvictedRatherThanPinningTheCache() {
        val cache = cache(budget = 20)
        val huge = FakeBitmap(100)
        val small = FakeBitmap(10)
        cache.put("huge", huge)
        cache.put("small", small)

        // The 100-byte entry cannot fit a 20-byte budget, so it is not retained; the small
        // entry must still be served and must not have been dropped by the oversized one.
        assertNull(cache.get("huge"))
        assertEquals(small, cache.get("small"))
    }

    @Test
    fun twoWidthsOfOneAvatarCountSeparatelyUnderSharedBudget() {
        val cache = cache(budget = 18)
        val w16 = FakeBitmap(9)
        val w32 = FakeBitmap(9)
        val w160 = FakeBitmap(9)

        cache.put("https://cdn.example/api/v1/files/k?width=16", w16)
        cache.put("https://cdn.example/api/v1/files/k?width=32", w32)
        cache.put("https://cdn.example/api/v1/files/k?width=160", w160)

        // Two entries fit the 18-byte budget; the third evicts the oldest one.
        assertNotNull(cache.get("https://cdn.example/api/v1/files/k?width=32"))
        assertNotNull(cache.get("https://cdn.example/api/v1/files/k?width=160"))
        assertNull(cache.get("https://cdn.example/api/v1/files/k?width=16"))
    }

    @Test
    fun clearDropsEverything() {
        val cache = cache(budget = 100)
        cache.put("a", FakeBitmap(10))
        cache.clear()
        assertNull(cache.get("a"))
    }

    @Test
    fun defaultBudgetIsPositiveAndCapped() {
        assertTrue(AvatarImageCache.defaultBudgetBytes() > 0)
        assertTrue(AvatarImageCache.defaultBudgetBytes() <= Int.MAX_VALUE)
    }
}
