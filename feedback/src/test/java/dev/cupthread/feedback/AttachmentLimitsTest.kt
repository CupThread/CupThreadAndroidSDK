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
        assertTrue(validDraft.attachments.size <= AttachmentLimits.MAX_ATTACHMENT_COUNT)

        val invalidDraft = validDraft.copy(attachments = List(6) { dummyAttachment })
        assertFalse(invalidDraft.attachments.size <= AttachmentLimits.MAX_ATTACHMENT_COUNT)
    }
}
