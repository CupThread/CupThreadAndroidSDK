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
     *
     * Preserves sufficient precision so that near-boundary sizes (e.g. 20,000,001 bytes)
     * are distinguishable from exact threshold limits (e.g. 20 MB).
     */
    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        if (bytes < 1000) return "$bytes B"
        val kb = bytes / 1000.0
        if (kb < 1000) {
            val rounded = (kb * 10).roundToLong()
            return when {
                bytes % 1000L == 0L -> "${bytes / 1000L} KB"
                rounded % 10 != 0L -> String.format(Locale.ROOT, "%.1f KB", kb)
                (kb * 100).roundToLong() % 100 != 0L -> String.format(Locale.ROOT, "%.2f KB", kb)
                else -> String.format(Locale.ROOT, "%.1f KB (%d B)", kb, bytes)
            }
        }
        val mb = bytes / 1_000_000.0
        if (mb < 1000) {
            val rounded = (mb * 10).roundToLong()
            return when {
                bytes % 1_000_000L == 0L -> "${bytes / 1_000_000L} MB"
                rounded % 10 != 0L -> String.format(Locale.ROOT, "%.1f MB", mb)
                (mb * 100).roundToLong() % 100 != 0L -> String.format(Locale.ROOT, "%.2f MB", mb)
                else -> String.format(Locale.ROOT, "%.1f MB (%d B)", mb, bytes)
            }
        }
        val gb = bytes / 1_000_000_000.0
        val rounded = (gb * 10).roundToLong()
        return when {
            bytes % 1_000_000_000L == 0L -> "${bytes / 1_000_000_000L} GB"
            rounded % 10 != 0L -> String.format(Locale.ROOT, "%.1f GB", gb)
            (gb * 100).roundToLong() % 100 != 0L -> String.format(Locale.ROOT, "%.2f GB", gb)
            else -> String.format(Locale.ROOT, "%.1f GB (%d B)", gb, bytes)
        }
    }
}
