package dev.cupthread.feedback

import dev.cupthread.feedback.internal.AttachmentLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentLimitsTest {

    @Test
    fun defaultConstantsMatchSpecification() {
        assertEquals(20_000_000L, AttachmentLimits.DEFAULT_MAX_ATTACHMENT_BYTES)
        assertEquals(5, AttachmentLimits.MAX_ATTACHMENT_COUNT)
    }

    @Test
    fun canAddAttachmentEnforcesMaxFive() {
        assertTrue(AttachmentLimits.canAddAttachment(0))
        assertTrue(AttachmentLimits.canAddAttachment(1))
        assertTrue(AttachmentLimits.canAddAttachment(4))
        assertFalse(AttachmentLimits.canAddAttachment(5))
        assertFalse(AttachmentLimits.canAddAttachment(6))
        assertFalse(AttachmentLimits.canAddAttachment(100))
    }

    @Test
    fun isWithinSizeLimitUnderDefaultLimit() {
        assertTrue(AttachmentLimits.isWithinSizeLimit(0))
        assertTrue(AttachmentLimits.isWithinSizeLimit(1024))
        assertTrue(AttachmentLimits.isWithinSizeLimit(19_999_999L))
        assertTrue(AttachmentLimits.isWithinSizeLimit(20_000_000L))
        assertFalse(AttachmentLimits.isWithinSizeLimit(20_000_001L))
        assertFalse(AttachmentLimits.isWithinSizeLimit(25_000_000L))
        assertFalse(AttachmentLimits.isWithinSizeLimit(-1L))
    }

    @Test
    fun isWithinSizeLimitHonorsCustomLimit() {
        val customLimit = 10_000_000L
        assertTrue(AttachmentLimits.isWithinSizeLimit(0, customLimit))
        assertTrue(AttachmentLimits.isWithinSizeLimit(5_000_000L, customLimit))
        assertTrue(AttachmentLimits.isWithinSizeLimit(10_000_000L, customLimit))
        assertFalse(AttachmentLimits.isWithinSizeLimit(10_000_001L, customLimit))
        assertFalse(AttachmentLimits.isWithinSizeLimit(20_000_000L, customLimit))
    }

    @Test
    fun isWithinSizeLimitFallsBackToDefaultWhenLimitZeroOrNegative() {
        assertTrue(AttachmentLimits.isWithinSizeLimit(20_000_000L, 0L))
        assertFalse(AttachmentLimits.isWithinSizeLimit(20_000_001L, 0L))
        assertTrue(AttachmentLimits.isWithinSizeLimit(20_000_000L, -100L))
        assertFalse(AttachmentLimits.isWithinSizeLimit(20_000_001L, -100L))
    }

    @Test
    fun validateAttachmentSizeSucceedsUnderLimit() {
        AttachmentLimits.validateAttachmentSize(1024, 2048)
        AttachmentLimits.validateAttachmentSize(2048, 2048)
        AttachmentLimits.validateAttachmentSize(20_000_000L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun validateAttachmentSizeThrowsWhenOverDefault() {
        AttachmentLimits.validateAttachmentSize(25_000_000L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun validateAttachmentSizeThrowsWhenOverCustomLimit() {
        AttachmentLimits.validateAttachmentSize(2049, 2048)
    }

    @Test
    fun formatFileSizeFormatsBytesCorrectly() {
        assertEquals("0 B", AttachmentLimits.formatFileSize(-5))
        assertEquals("0 B", AttachmentLimits.formatFileSize(0))
        assertEquals("500 B", AttachmentLimits.formatFileSize(500))
        assertEquals("999 B", AttachmentLimits.formatFileSize(999))
        assertEquals("1 KB", AttachmentLimits.formatFileSize(1000))
        assertEquals("1.5 KB", AttachmentLimits.formatFileSize(1500))
        assertEquals("2 KB", AttachmentLimits.formatFileSize(2000))
        assertEquals("1 MB", AttachmentLimits.formatFileSize(1_000_000))
        assertEquals("2.5 MB", AttachmentLimits.formatFileSize(2_500_000))
        assertEquals("20 MB", AttachmentLimits.formatFileSize(20_000_000))
        assertEquals("25 MB", AttachmentLimits.formatFileSize(25_000_000))
        assertEquals("1 GB", AttachmentLimits.formatFileSize(1_000_000_000))
        assertEquals("1.5 GB", AttachmentLimits.formatFileSize(1_500_000_000))
        // Distinguishable near boundary
        assertEquals("20.0 MB (20000001 B)", AttachmentLimits.formatFileSize(20_000_001L))
    }

    @Test
    fun oversizedErrorFormattingMatchesExpectedMessage() {
        val formattedSize = AttachmentLimits.formatFileSize(25_000_000L)
        val formattedLimit = AttachmentLimits.formatFileSize(20_000_000L)
        val message = "Attachment ($formattedSize) exceeds the maximum allowed size of $formattedLimit."
        assertEquals("Attachment (25 MB) exceeds the maximum allowed size of 20 MB.", message)
    }

    @Test
    fun feedbackDraftAttachmentCountBoundaryChecks() {
        val dummyAttachment = FeedbackAttachment(
            kind = FeedbackAttachment.Kind.IMAGE,
            key = "key",
            url = "https://cdn.example.com/img.png"
        )
        val validDraft = FeedbackDraft(
            title = "Valid title",
            description = "Valid description",
            platform = FeedbackPlatform.ANDROID,
            attachments = List(5) { dummyAttachment }
        )
        // 5 attachments allows submission, but prevents starting a 6th attachment pick/upload
        val canSubmitWith5 = validDraft.title.isNotBlank() &&
            validDraft.description.isNotBlank() &&
            validDraft.attachments.size <= AttachmentLimits.MAX_ATTACHMENT_COUNT
        assertTrue(canSubmitWith5)
        assertFalse(AttachmentLimits.canAddAttachment(validDraft.attachments.size))

        // 6 attachments disables submission
        val invalidDraft = validDraft.copy(attachments = List(6) { dummyAttachment })
        val canSubmitWith6 = invalidDraft.title.isNotBlank() &&
            invalidDraft.description.isNotBlank() &&
            invalidDraft.attachments.size <= AttachmentLimits.MAX_ATTACHMENT_COUNT
        assertFalse(canSubmitWith6)
        assertFalse(AttachmentLimits.canAddAttachment(invalidDraft.attachments.size))

        // 4 attachments allows both submission and adding another attachment
        val underLimitDraft = validDraft.copy(attachments = List(4) { dummyAttachment })
        val canSubmitWith4 = underLimitDraft.title.isNotBlank() &&
            underLimitDraft.description.isNotBlank() &&
            underLimitDraft.attachments.size <= AttachmentLimits.MAX_ATTACHMENT_COUNT
        assertTrue(canSubmitWith4)
        assertTrue(AttachmentLimits.canAddAttachment(underLimitDraft.attachments.size))
    }

    @Test
    fun draftAttachmentDeletionWorksWithNullKeyAttachments() {
        val attachment1 = FeedbackAttachment(
            kind = FeedbackAttachment.Kind.IMAGE,
            uploadId = "upl_1",
            filename = "img1.png"
        )
        val attachment2 = FeedbackAttachment(
            kind = FeedbackAttachment.Kind.IMAGE,
            uploadId = "upl_2",
            filename = "img2.png"
        )
        val draft = FeedbackDraft(
            title = "Title",
            description = "Desc",
            platform = FeedbackPlatform.ANDROID,
            attachments = listOf(attachment1, attachment2)
        )

        val afterDelete = draft.copy(
            attachments = draft.attachments.filter { it != attachment1 }
        )
        assertEquals(1, afterDelete.attachments.size)
        assertEquals(attachment2, afterDelete.attachments.first())
    }

    @Test
    fun isSupportedImageMimeValidatesAllowedImageTypes() {
        assertTrue(AttachmentLimits.isSupportedImageMime("image/png"))
        assertTrue(AttachmentLimits.isSupportedImageMime("image/jpeg"))
        assertTrue(AttachmentLimits.isSupportedImageMime("image/jpg"))
        assertTrue(AttachmentLimits.isSupportedImageMime("image/webp"))
        assertTrue(AttachmentLimits.isSupportedImageMime("image/gif"))
        assertTrue(AttachmentLimits.isSupportedImageMime("IMAGE/PNG"))

        assertFalse(AttachmentLimits.isSupportedImageMime("image/svg+xml"))
        assertFalse(AttachmentLimits.isSupportedImageMime("image/bmp"))
        assertFalse(AttachmentLimits.isSupportedImageMime("application/pdf"))
        assertFalse(AttachmentLimits.isSupportedImageMime("text/plain"))
    }

    @Test
    fun isSvgIdentifiesSvgFilenamesAndMimes() {
        assertTrue(AttachmentLimits.isSvg("icon.svg", "image/png"))
        assertTrue(AttachmentLimits.isSvg("icon.SVG", "image/png"))
        assertTrue(AttachmentLimits.isSvg("photo.png", "image/svg+xml"))
        assertTrue(AttachmentLimits.isSvg("chart.svg", "image/svg+xml"))

        assertFalse(AttachmentLimits.isSvg("photo.png", "image/png"))
        assertFalse(AttachmentLimits.isSvg("photo.jpg", "image/jpeg"))
        assertFalse(AttachmentLimits.isSvg("logo.webp", "image/webp"))
        assertFalse(AttachmentLimits.isSvg("anim.gif", "image/gif"))
        assertFalse(AttachmentLimits.isSvg(null, null))
    }

    @Test
    fun sniffImageMimeTypeDetectsMagicBytes() {
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte())
        assertEquals("image/png", AttachmentLimits.sniffImageMimeType(pngBytes))

        val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        assertEquals("image/jpeg", AttachmentLimits.sniffImageMimeType(jpegBytes))

        val gifBytes = "GIF89aSomeImageData".toByteArray()
        assertEquals("image/gif", AttachmentLimits.sniffImageMimeType(gifBytes))

        val webpBytes = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.US_ASCII)
        assertEquals("image/webp", AttachmentLimits.sniffImageMimeType(webpBytes))

        val randomBytes = byteArrayOf(1, 2, 3, 4, 5)
        assertEquals(null, AttachmentLimits.sniffImageMimeType(randomBytes))
    }

    @Test
    fun hasSvgContentDetectsXmlAndSvgTags() {
        val svgXml = "<?xml version=\"1.0\"?><svg width=\"100\"></svg>".toByteArray()
        assertTrue(AttachmentLimits.hasSvgContent(svgXml))

        val rawSvg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><circle/></svg>".toByteArray()
        assertTrue(AttachmentLimits.hasSvgContent(rawSvg))

        val binaryData = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte())
        assertFalse(AttachmentLimits.hasSvgContent(binaryData))

        val pngWithSvgPayload = binaryData + "<svg xmlns='...'>".toByteArray()
        assertFalse(AttachmentLimits.hasSvgContent(pngWithSvgPayload))
    }

    @Test
    fun validateImageAttachmentAcceptsValidImages() {
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte())
        AttachmentLimits.validateImageAttachment(pngBytes, "screenshot.png", "image/png")

        val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        AttachmentLimits.validateImageAttachment(jpegBytes, "photo.jpg", "image/jpeg")

        val pngWithSvgComment = pngBytes + "<svg>embedded metadata</svg>".toByteArray()
        AttachmentLimits.validateImageAttachment(pngWithSvgComment, "screenshot.png", "image/png")
    }

    @Test(expected = FeedbackException.UnsupportedMediaType::class)
    fun validateImageAttachmentRejectsSvgFilename() {
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte())
        AttachmentLimits.validateImageAttachment(pngBytes, "vector.svg", "image/png")
    }

    @Test(expected = FeedbackException.UnsupportedMediaType::class)
    fun validateImageAttachmentRejectsSvgMime() {
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte())
        AttachmentLimits.validateImageAttachment(pngBytes, "vector.png", "image/svg+xml")
    }

    @Test(expected = FeedbackException.UnsupportedMediaType::class)
    fun validateImageAttachmentRejectsSvgContentDisguisedAsPng() {
        val svgBytes = "<svg width=\"100\" height=\"100\"></svg>".toByteArray()
        AttachmentLimits.validateImageAttachment(svgBytes, "vector.png", "image/png")
    }

    @Test(expected = FeedbackException.UnsupportedMediaType::class)
    fun validateImageAttachmentRejectsMagicByteMismatch() {
        val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        AttachmentLimits.validateImageAttachment(jpegBytes, "screenshot.png", "image/png")
    }

    @Test(expected = FeedbackException.UnsupportedMediaType::class)
    fun validateImageAttachmentRejectsArbitraryBytesWithPngMime() {
        val arbitraryBytes = byteArrayOf(1, 2, 3, 4, 5)
        AttachmentLimits.validateImageAttachment(arbitraryBytes, "screenshot.png", "image/png")
    }

    @Test(expected = FeedbackException.UnsupportedMediaType::class)
    fun validateImageAttachmentRejectsUnsupportedDeclaredMime() {
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte())
        AttachmentLimits.validateImageAttachment(pngBytes, "doc.pdf", "application/pdf")
    }
}
