package dev.cupthread.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the public image delivery contract (#59): `GET /api/v1/files/{key}` accepts an
 * optional documented `width` hint, and public image URLs must stay opaque.
 */
class PublicImageUrlsTest {

    private val iconUrl = "https://api.cupthread.com/api/v1/files/images/app-icon.png"

    @Test
    fun widthSetMatchesDocumentedValues() {
        assertEquals(
            listOf(16, 32, 36, 56, 64, 72, 80, 112, 128, 160),
            PublicImageUrls.WIDTHS.toList()
        )
    }

    @Test
    fun withWidthAppendsEveryDocumentedWidth() {
        for (width in PublicImageUrls.WIDTHS) {
            assertEquals("$iconUrl?width=$width", PublicImageUrls.withWidth(iconUrl, width))
        }
    }

    @Test
    fun withWidthFallsBackToOriginalImageForUndocumentedWidths() {
        for (width in listOf(0, -8, 17, 48, 100, 161, 1024, Int.MAX_VALUE)) {
            assertEquals(iconUrl, PublicImageUrls.withWidth(iconUrl, width))
        }
    }

    @Test
    fun withWidthPreservesExistingQueryParametersAndReplacesWidth() {
        assertEquals(
            "$iconUrl?v=2&sig=a%2Fb&width=128",
            PublicImageUrls.withWidth("$iconUrl?v=2&width=64&sig=a%2Fb", 128)
        )
    }

    @Test
    fun withWidthPreservesFragment() {
        assertEquals(
            "$iconUrl?v=2&width=32#details",
            PublicImageUrls.withWidth("$iconUrl?v=2#details", 32)
        )
    }

    @Test
    fun withWidthPreservesEncodedPathAndParameterNamesCaseSensitively() {
        assertEquals(
            "https://api.cupthread.com/api/v1/files/images/a%20b.png?Width=64&width=16",
            PublicImageUrls.withWidth(
                "https://api.cupthread.com/api/v1/files/images/a%20b.png?Width=64",
                16
            )
        )
    }

    @Test
    fun withWidthLeavesNonFileUrlsUntouched() {
        for (url in listOf(
            "https://images.cupthread.com/avatars/u_1.png",
            "https://api.cupthread.com/api/v1/uploads/images/abc",
            "https://api.cupthread.com/other/files/x.png"
        )) {
            assertEquals(url, PublicImageUrls.withWidth(url, 64))
        }
    }

    @Test
    fun withWidthReturnsNullForMissingOrUnsafeUrls() {
        for (url in listOf(
            null,
            "",
            "   ",
            "http://api.cupthread.com/api/v1/files/images/app-icon.png",
            "ftp://api.cupthread.com/api/v1/files/images/app-icon.png",
            "javascript:alert(1)",
            "not a url",
            "https://user:pass@api.cupthread.com/api/v1/files/images/app-icon.png",
            "/api/v1/files/images/app-icon.png"
        )) {
            assertNull(url, PublicImageUrls.withWidth(url, 64))
        }
    }

    @Test
    fun widthForPixelsRoundsUpToNearestDocumentedWidth() {
        val expected = mapOf(
            0 to 16,
            1 to 16,
            16 to 16,
            17 to 32,
            35 to 36,
            55 to 56,
            56 to 56,
            100 to 112,
            128 to 128,
            160 to 160,
            161 to 160,
            4000 to 160,
            Int.MAX_VALUE to 160
        )
        for ((pixels, width) in expected) {
            assertEquals("pixels=$pixels", width, PublicImageUrls.widthForPixels(pixels))
        }
    }
}
