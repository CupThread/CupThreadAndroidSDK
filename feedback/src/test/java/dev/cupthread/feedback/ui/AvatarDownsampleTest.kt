package dev.cupthread.feedback.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [computeAvatarSampleSize] (issues #141, #220).
 *
 * Verifies that avatar bitmap downsampling:
 * - Safely returns 1 when [requestedPixels] is non-positive (0 or negative) without entering
 *   an infinite loop or throwing an [ArithmeticException] from integer overflow.
 * - Safely returns 1 when image dimensions [outWidth] or [outHeight] are non-positive.
 * - Computes the correct power-of-two [android.graphics.BitmapFactory.Options.inSampleSize]
 *   ensuring both decoded dimensions remain >= requestedPixels.
 * - Safely handles extreme boundary values without integer overflow.
 */
class AvatarDownsampleTest {

    @Test
    fun zeroRequestedPixelsReturnsOneWithoutHangingOrOverflow() {
        // Issue #220: requestedPixels = 0 previously looped infinitely until sampleSize overflowed to 0
        val sampleSize = computeAvatarSampleSize(outWidth = 256, outHeight = 256, requestedPixels = 0)
        assertEquals(1, sampleSize)
    }

    @Test
    fun negativeRequestedPixelsReturnsOneImmediately() {
        val sampleSize = computeAvatarSampleSize(outWidth = 256, outHeight = 256, requestedPixels = -10)
        assertEquals(1, sampleSize)
    }

    @Test
    fun nonPositiveDimensionsReturnOneSafely() {
        assertEquals(1, computeAvatarSampleSize(outWidth = 0, outHeight = 256, requestedPixels = 48))
        assertEquals(1, computeAvatarSampleSize(outWidth = 256, outHeight = 0, requestedPixels = 48))
        assertEquals(1, computeAvatarSampleSize(outWidth = -1, outHeight = 256, requestedPixels = 48))
        assertEquals(1, computeAvatarSampleSize(outWidth = 256, outHeight = -10, requestedPixels = 48))
        assertEquals(1, computeAvatarSampleSize(outWidth = 0, outHeight = 0, requestedPixels = 0))
    }

    @Test
    fun squareImageDownsamplesToLargestPowerOfTwoMeetingRequestedPixels() {
        // 200x200 downsampled for 48px:
        // sampleSize 1 -> 200 / 2 = 100 >= 48
        // sampleSize 2 -> 200 / 4 = 50 >= 48
        // sampleSize 4 -> 200 / 8 = 25 < 48 -> stops at sampleSize = 4
        assertEquals(4, computeAvatarSampleSize(outWidth = 200, outHeight = 200, requestedPixels = 48))

        // 512x512 downsampled for 64px:
        // sampleSize 1 -> 512/2 = 256
        // sampleSize 2 -> 512/4 = 128
        // sampleSize 4 -> 512/8 = 64 >= 64
        // sampleSize 8 -> 512/16 = 32 < 64 -> stops at sampleSize = 8
        assertEquals(8, computeAvatarSampleSize(outWidth = 512, outHeight = 512, requestedPixels = 64))
    }

    @Test
    fun dimensionsEqualToOrSmallerThanRequestedPixelsReturnOne() {
        // Image is 48x48, requested 48px -> cannot downsample without dipping below 48
        assertEquals(1, computeAvatarSampleSize(outWidth = 48, outHeight = 48, requestedPixels = 48))

        // Image is smaller than requested
        assertEquals(1, computeAvatarSampleSize(outWidth = 32, outHeight = 32, requestedPixels = 48))
    }

    @Test
    fun asymmetricDimensionsConstrainedBySmallerDimension() {
        // Width is 500 (could be downsampled by 4), but height is 50 (50 / 2 = 25 < 48)
        assertEquals(1, computeAvatarSampleSize(outWidth = 500, outHeight = 50, requestedPixels = 48))

        // Width is 400 (400/8=50>=48), height is 200 (200/4=50>=48, 200/8=25<48) -> constrained by height to 4
        assertEquals(4, computeAvatarSampleSize(outWidth = 400, outHeight = 200, requestedPixels = 48))
    }

    @Test
    fun extremeDimensionsDoNotOverflowSignedInteger() {
        // Int.MAX_VALUE with requestedPixels = 1
        val sampleSize = computeAvatarSampleSize(
            outWidth = Int.MAX_VALUE,
            outHeight = Int.MAX_VALUE,
            requestedPixels = 1
        )
        // Must be a positive power of two <= Int.MAX_VALUE / 2
        assertEquals(1073741824, sampleSize)
    }
}
