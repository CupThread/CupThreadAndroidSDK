package dev.cupthread.feedback.internal

import java.net.URI
import java.util.Locale

/**
 * URL validation and normalization policy for user-supplied or remote web content.
 *
 * Enforces security constraints matching the CupThread Apple SDK policy:
 * - Only `http` and `https` schemes are allowed for web links (`ACTION_VIEW`).
 * - Only `https` is allowed for remote images and avatars.
 * - Schemes are checked case-insensitively.
 * - Disallowed schemes (e.g., `javascript:`, `intent:`, `file:`, `tel:`, `mailto:`, custom schemes) are strictly rejected.
 * - Embedded credentials (`userInfo`, `user:pass@host`) are rejected.
 * - Host must be non-empty and non-whitespace.
 * - Scheme-less or protocol-relative strings (e.g. `example.com`, `//example.com`) are normalized by prefixing `https://`.
 */
internal object WebUrlPolicy {

    private val SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*$")
    private val SCHEME_PREFIX_REGEX = Regex("^https?://", RegexOption.IGNORE_CASE)

    /**
     * Checks whether a [URI] is an allowed web URL:
     * - Must have `http` or `https` scheme (case-insensitive).
     * - Must have a non-empty, non-whitespace host.
     * - Must not contain embedded user credentials (`userInfo`, `rawUserInfo`, or `@` in authority).
     */
    fun isAllowedWebUrl(uri: URI): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.trim()
        if (host.isNullOrEmpty()) return false
        if (uri.userInfo != null || uri.rawUserInfo != null) return false
        val authority = uri.rawAuthority ?: uri.authority
        if (authority != null && authority.contains('@')) return false
        return true
    }

    /**
     * Checks whether a string represents an allowed web URL.
     */
    fun isAllowedWebUrl(urlString: String?): Boolean {
        if (urlString.isNullOrBlank()) return false
        val uri = try {
            URI(urlString.trim())
        } catch (_: Exception) {
            return false
        }
        return isAllowedWebUrl(uri)
    }

    /**
     * Checks whether an [android.net.Uri] is an allowed web URL.
     */
    fun isAllowedWebUrl(uri: android.net.Uri): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.trim()
        if (host.isNullOrEmpty()) return false
        if (uri.userInfo != null) return false
        val authority = uri.encodedAuthority ?: uri.authority
        if (authority != null && authority.contains('@')) return false
        return true
    }

    /**
     * Checks whether a [URI] is an allowed secure remote image URL:
     * - Must have `https` scheme (case-insensitive).
     * - Must have a non-empty, non-whitespace host.
     * - Must not contain embedded credentials.
     */
    fun isAllowedSecureImageUrl(uri: URI): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        if (scheme != "https") return false
        val host = uri.host?.trim()
        if (host.isNullOrEmpty()) return false
        if (uri.userInfo != null || uri.rawUserInfo != null) return false
        val authority = uri.rawAuthority ?: uri.authority
        if (authority != null && authority.contains('@')) return false
        return true
    }

    /**
     * Checks whether a string represents an allowed secure remote image URL.
     */
    fun isAllowedSecureImageUrl(urlString: String?): Boolean {
        if (urlString.isNullOrBlank()) return false
        val uri = try {
            URI(urlString.trim())
        } catch (_: Exception) {
            return false
        }
        return isAllowedSecureImageUrl(uri)
    }

    /**
     * Checks whether an [android.net.Uri] is an allowed secure remote image URL.
     */
    fun isAllowedSecureImageUrl(uri: android.net.Uri): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        if (scheme != "https") return false
        val host = uri.host?.trim()
        if (host.isNullOrEmpty()) return false
        if (uri.userInfo != null) return false
        val authority = uri.encodedAuthority ?: uri.authority
        if (authority != null && authority.contains('@')) return false
        return true
    }

    /**
     * Normalizes and validates a secure remote image URL string (e.g. avatar URL):
     * - Requires `https` scheme and non-empty host without embedded credentials.
     * - Returns normalized URL string, or `null` if disallowed or invalid.
     */
    fun normalizeSecureImageUrl(string: String?): String? {
        if (string.isNullOrBlank()) return null
        val trimmed = string.trim()
        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return null
        }
        return if (isAllowedSecureImageUrl(uri)) trimmed else null
    }

    /**
     * Normalizes and validates a website URL for user profiles:
     * - If already an allowed `http` or `https` URL with a valid host and without embedded credentials, returns it.
     * - If it contains a disallowed scheme (such as `tel:`, `javascript:`, `shortcuts://`, `intent://`, or custom schemes), returns `null`.
     * - If it is scheme-less or protocol-relative (e.g. `example.com` or `//example.com`), prefixes `https://` and validates.
     */
    fun normalizeWebsiteUrl(string: String?): String? {
        if (string.isNullOrBlank()) return null
        val trimmed = string.trim()

        // 1. Explicit hierarchical scheme with ://
        if (trimmed.contains("://")) {
            val scheme = trimmed.substringBefore("://").lowercase(Locale.ROOT)
            if (scheme != "http" && scheme != "https") return null
            val uri = try {
                URI(trimmed)
            } catch (_: Exception) {
                return null
            }
            return if (isAllowedWebUrl(uri)) trimmed else null
        }

        // 2. Disallowed non-hierarchical scheme or URI scheme without authority (e.g. javascript:, tel:, mailto:, file:)
        val colonIndex = trimmed.indexOf(':')
        if (colonIndex != -1) {
            val slashIndex = trimmed.indexOf('/')
            val questionIndex = trimmed.indexOf('?')
            val hashIndex = trimmed.indexOf('#')
            val firstDelimiter = listOf(slashIndex, questionIndex, hashIndex).filter { it != -1 }.minOrNull()
            if (firstDelimiter == null || colonIndex < firstDelimiter) {
                val prefix = trimmed.substring(0, colonIndex)
                if (!prefix.contains('.') && prefix.matches(SCHEME_REGEX)) {
                    val lower = prefix.lowercase(Locale.ROOT)
                    if (lower != "http" && lower != "https") {
                        return null
                    }
                }
            }
        }

        // 3. Scheme-less or protocol-relative (e.g. "example.com", "//example.com", "example.com:8080")
        val stripped = trimmed.trimStart('/')
        if (stripped.isEmpty()) return null

        val candidate = "https://$stripped"
        val uri = try {
            URI(candidate)
        } catch (_: Exception) {
            return null
        }
        return if (isAllowedWebUrl(uri)) candidate else null
    }

    /**
     * Convenience method to normalize a website string to an [android.net.Uri] for use with Android intents.
     */
    fun normalizeWebsiteUri(string: String?): android.net.Uri? {
        val normalized = normalizeWebsiteUrl(string) ?: return null
        return try {
            android.net.Uri.parse(normalized).normalizeScheme()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Strips leading `http://` or `https://` scheme for concise UI presentation.
     */
    fun displayWebsite(url: String): String {
        return url.replaceFirst(SCHEME_PREFIX_REGEX, "")
    }
}
