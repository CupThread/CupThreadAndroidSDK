package dev.cupthread.feedback.internal

import java.util.Locale
import kotlin.math.roundToLong

/**
 * Enforcement and validation rules for user feedback attachments.
 *
 * Aligns client-side constraints with the CupThread backend and Apple SDK:
 * - Default per-attachment upload budget: 20 MB (20,000,000 bytes).
 * - Maximum attached files per submission: 5.
 */
internal object AttachmentLimits {

    /** Default per-attachment size budget when not configured by console (20 MB). */
    const val DEFAULT_MAX_ATTACHMENT_BYTES: Long = 20_000_000L

    /** Maximum allowed attachments per feedback submission. */
    const val MAX_ATTACHMENT_COUNT: Int = 5

    /**
     * Checks whether an additional attachment can be added given the [currentCount].
     */
    fun canAddAttachment(currentCount: Int): Boolean = currentCount < MAX_ATTACHMENT_COUNT

    /**
     * Checks whether raw byte [size] is within the specified [limit].
     */
    fun isWithinSizeLimit(size: Long, limit: Long = DEFAULT_MAX_ATTACHMENT_BYTES): Boolean {
        if (size < 0) return false
        val effectiveLimit = if (limit > 0) limit else DEFAULT_MAX_ATTACHMENT_BYTES
        return size <= effectiveLimit
    }

    /**
     * Validates attachment size and throws [IllegalArgumentException] if [size] exceeds [limit].
     */
    fun validateAttachmentSize(size: Long, limit: Long = DEFAULT_MAX_ATTACHMENT_BYTES) {
        require(isWithinSizeLimit(size, limit)) {
            "Attachment size ($size bytes) exceeds limit ($limit bytes)"
        }
    }

    /**
     * Formats raw bytes into a human-readable file size string (e.g. "20 MB", "1.5 MB", "500 KB").
     */
    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        if (bytes < 1000) return "$bytes B"
        val kb = bytes / 1000.0
        if (kb < 1000) {
            val rounded = (kb * 10).roundToLong()
            return if (rounded % 10 == 0L) {
                "${rounded / 10} KB"
            } else {
                String.format(Locale.ROOT, "%.1f KB", kb)
            }
        }
        val mb = bytes / 1_000_000.0
        if (mb < 1000) {
            val rounded = (mb * 10).roundToLong()
            return if (rounded % 10 == 0L) {
                "${rounded / 10} MB"
            } else {
                String.format(Locale.ROOT, "%.1f MB", mb)
            }
        }
        val gb = bytes / 1_000_000_000.0
        val rounded = (gb * 10).roundToLong()
        return if (rounded % 10 == 0L) {
            "${rounded / 10} GB"
        } else {
            String.format(Locale.ROOT, "%.1f GB", gb)
        }
    }
}
