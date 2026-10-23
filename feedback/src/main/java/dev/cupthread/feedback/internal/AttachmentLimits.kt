package dev.cupthread.feedback.internal

import dev.cupthread.feedback.FeedbackException
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Enforcement and validation rules for user feedback attachments.
 *
 * Aligns client-side constraints with the CupThread backend and Apple SDK:
 * - Default per-attachment upload budget: 20 MB (20,000,000 bytes).
 * - Maximum attached files per submission: 5.
 * - Accepted image upload formats: PNG, JPEG, WebP, GIF (SVG rejected).
 */
internal object AttachmentLimits {

    /** Default per-attachment size budget when not configured by console (20 MB). */
    const val DEFAULT_MAX_ATTACHMENT_BYTES: Long = 20_000_000L

    /** Maximum allowed attachments per feedback submission. */
    const val MAX_ATTACHMENT_COUNT: Int = 5

    /** Set of lowercase MIME types accepted for image uploads on `/api/v1/uploads/images`. */
    val SUPPORTED_IMAGE_MIME_TYPES: Set<String> = setOf(
        "image/png",
        "image/jpeg",
        "image/jpg",
        "image/webp",
        "image/gif"
    )

    /**
     * Checks whether the declared [mimeType] is an accepted image format (PNG, JPEG, WebP, GIF).
     */
    fun isSupportedImageMime(mimeType: String): Boolean =
        SUPPORTED_IMAGE_MIME_TYPES.contains(mimeType.lowercase(Locale.ROOT).trim())

    /**
     * Checks whether [filename] or [mimeType] represents an SVG file.
     */
    fun isSvg(filename: String?, mimeType: String?): Boolean {
        if (mimeType?.trim()?.equals("image/svg+xml", ignoreCase = true) == true) return true
        if (filename?.trim()?.endsWith(".svg", ignoreCase = true) == true) return true
        return false
    }

    /**
     * Sniffs the image format from [data] magic bytes, returning the canonical MIME type or `null`
     * if the header does not match known image signatures.
     */
    fun sniffImageMimeType(data: ByteArray): String? {
        if (data.size < 3) return null
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (data.size >= 8 &&
            data[0] == 0x89.toByte() && data[1] == 0x50.toByte() &&
            data[2] == 0x4E.toByte() && data[3] == 0x47.toByte() &&
            data[4] == 0x0D.toByte() && data[5] == 0x0A.toByte() &&
            data[6] == 0x1A.toByte() && data[7] == 0x0A.toByte()
        ) {
            return "image/png"
        }
        // JPEG: FF D8 FF
        if (data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() && data[2] == 0xFF.toByte()) {
            return "image/jpeg"
        }
        // GIF: GIF87a or GIF89a
        if (data.size >= 6 &&
            data[0] == 'G'.code.toByte() && data[1] == 'I'.code.toByte() && data[2] == 'F'.code.toByte() &&
            data[3] == '8'.code.toByte() && (data[4] == '7'.code.toByte() || data[4] == '9'.code.toByte()) &&
            data[5] == 'a'.code.toByte()
        ) {
            return "image/gif"
        }
        // WebP: RIFF....WEBP
        if (data.size >= 12 &&
            data[0] == 'R'.code.toByte() && data[1] == 'I'.code.toByte() &&
            data[2] == 'F'.code.toByte() && data[3] == 'F'.code.toByte() &&
            data[8] == 'W'.code.toByte() && data[9] == 'E'.code.toByte() &&
            data[10] == 'B'.code.toByte() && data[11] == 'P'.code.toByte()
        ) {
            return "image/webp"
        }
        return null
    }

    /**
     * Checks if [data] begins with XML or SVG tags.
     */
    fun hasSvgContent(data: ByteArray): Boolean {
        if (data.isEmpty() || sniffImageMimeType(data) != null) return false
        val prefix = String(data.take(256).toByteArray(), Charsets.UTF_8).trimStart()
        return prefix.startsWith("<?xml", ignoreCase = true) ||
            prefix.startsWith("<svg", ignoreCase = true) ||
            prefix.contains("<svg", ignoreCase = true)
    }

    /**
     * Validates image attachment metadata and content before upload.
     *
     * @throws FeedbackException.UnsupportedMediaType if the image format is SVG, unsupported,
     * or if sniffed magic bytes conflict with the declared format.
     */
    fun validateImageAttachment(data: ByteArray, filename: String, mimeType: String) {
        if (isSvg(filename, mimeType) || hasSvgContent(data)) {
            throw FeedbackException.UnsupportedMediaType(
                "SVG images are not supported for feedback attachments. Please upload a PNG, JPEG, WebP, or GIF image."
            )
        }
        val cleanMime = mimeType.lowercase(Locale.ROOT).trim()
        if (!isSupportedImageMime(cleanMime)) {
            throw FeedbackException.UnsupportedMediaType(
                "Unsupported image media type ($mimeType). Please upload a PNG, JPEG, WebP, or GIF image."
            )
        }
        val sniffed = sniffImageMimeType(data)
            ?: throw FeedbackException.UnsupportedMediaType(
                "Unsupported or unrecognized image content for ($mimeType)."
            )
        val normalizedDeclared = if (cleanMime == "image/jpg") "image/jpeg" else cleanMime
        if (sniffed != normalizedDeclared) {
            throw FeedbackException.UnsupportedMediaType(
                "Image content ($sniffed) does not match declared MIME type ($mimeType)."
            )
        }
    }

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
