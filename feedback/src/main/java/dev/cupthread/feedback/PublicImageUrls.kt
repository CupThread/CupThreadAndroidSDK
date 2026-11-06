package dev.cupthread.feedback

import dev.cupthread.feedback.internal.WebUrlPolicy

/**
 * Helpers for the public image delivery contract (`GET /api/v1/files/{key}`).
 *
 * Public image URLs produced by the CupThread API — app icons, avatars, and changelog artwork —
 * are absolute `https:` URLs under `/api/v1/files/`. The endpoint accepts an optional `width`
 * query parameter with a fixed set of documented values; a hinted response is a WebP thumbnail
 * (or the original image when no smaller rendition exists) with a five-minute cache lifetime,
 * while an unhinted request returns the original image under the normal cache policy.
 *
 * Treat delivered URLs as opaque: preserve their path, query string, and fragment verbatim — never
 * parse them into host/path components and rebuild them, and never infer the image format from the
 * URL extension (the response `Content-Type` is authoritative).
 *
 * ### Example Usage
 * ```kotlin
 * val url = featureRequest.requesterAvatarUrl
 * imageLoader.load(PublicImageUrls.withWidth(url, PublicImageUrls.WIDTHS.first())) // 16 px hint
 * ```
 */
object PublicImageUrls {

    /**
     * Query parameter carrying the thumbnail width hint.
     */
    const val WIDTH_PARAM: String = "width"

    /**
     * Documented width hints accepted by `GET /api/v1/files/{key}`, in ascending order.
     *
     * Any other value is not part of the contract; [withWidth] falls back to the original URL
     * rather than sending an undocumented hint.
     */
    val WIDTHS: Set<Int> = java.util.Collections.unmodifiableSet(linkedSetOf(16, 32, 36, 56, 64, 72, 80, 112, 128, 160))

    private val ASCENDING_WIDTHS: List<Int> = WIDTHS.sorted()

    /**
     * Returns [url] with a `width` hint when it points at a CupThread public image file, or the
     * URL unchanged when it does not.
     *
     * - `null` or blank input returns `null`.
     * - URLs that are not valid secure (`https:`) web URLs — malformed, `http:`, other schemes,
     *   or carrying embedded credentials — return `null`.
     * - URLs outside the public file path (`/api/v1/files/`) are returned unchanged: the hint is
     *   only defined there, and other hosts must not receive unknown query parameters.
     * - A [width] outside [WIDTHS] returns the original-image URL unchanged, preserving the
     *   original-image fallback for unsupported hints.
     * - Existing query parameters are preserved in order; an existing `width` parameter is
     *   replaced instead of duplicated, and the fragment is preserved verbatim.
     *
     * @param url Public image URL, or `null`.
     * @param width Thumbnail width hint in pixels; see [WIDTHS].
     * @return Hinted URL, original URL, or `null` when [url] is missing or unsafe.
     */
    @JvmStatic
    fun withWidth(url: String?, width: Int): String? {
        val normalized = WebUrlPolicy.normalizeSecureImageUrl(url) ?: return null
        if (!isPublicFileUrl(normalized) || width !in WIDTHS) return normalized

        val hashIndex = normalized.indexOf('#')
        val fragment = if (hashIndex >= 0) normalized.substring(hashIndex) else ""
        val withoutFragment = if (hashIndex >= 0) normalized.substring(0, hashIndex) else normalized
        val queryIndex = withoutFragment.indexOf('?')
        val base = if (queryIndex >= 0) withoutFragment.substring(0, queryIndex) else withoutFragment
        val query = if (queryIndex >= 0) withoutFragment.substring(queryIndex + 1) else ""

        val kept = query.split('&').filter { it.isNotEmpty() && !isWidthParam(it) }
        val hintedQuery = (kept + "$WIDTH_PARAM=$width").joinToString("&")
        return "$base?$hintedQuery$fragment"
    }

    /**
     * Returns the smallest documented hint that covers [pixels], or the largest one when [pixels]
     * exceeds every documented width.
     */
    @JvmStatic
    fun widthForPixels(pixels: Int): Int {
        return ASCENDING_WIDTHS.firstOrNull { it >= pixels } ?: ASCENDING_WIDTHS.last()
    }

    private fun isPublicFileUrl(url: String): Boolean {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return false
        val authorityStart = schemeEnd + 3
        val pathStart = url.indexOf('/', authorityStart).let { if (it < 0) url.length else it }
        val pathEnd = url.indexOfAny(charArrayOf('?', '#'), pathStart).let { if (it < 0) url.length else it }
        return url.substring(pathStart, pathEnd).startsWith("/api/v1/files/")
    }

    private fun isWidthParam(segment: String): Boolean {
        val nameEnd = segment.indexOf('=')
        val name = if (nameEnd >= 0) segment.substring(0, nameEnd) else segment
        return name == WIDTH_PARAM
    }
}
