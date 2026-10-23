package dev.cupthread.feedback

import dev.cupthread.feedback.internal.WebUrlPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class WebUrlPolicyTest {

    @Test
    fun isAllowedWebUrlAcceptsValidHttpAndHttpsUrls() {
        val allowedUrls = listOf(
            "https://example.com",
            "http://example.com",
            "https://sub.domain.org/path?key=value#hash",
            "http://localhost:8080/api",
            "HTTPS://EXAMPLE.COM/UPPERCASE"
        )

        for (urlString in allowedUrls) {
            assertTrue("Expected $urlString to be allowed", WebUrlPolicy.isAllowedWebUrl(urlString))
            assertTrue("Expected URI($urlString) to be allowed", WebUrlPolicy.isAllowedWebUrl(URI(urlString)))
        }
    }

    @Test
    fun isAllowedWebUrlRejectsDisallowedSchemes() {
        val disallowedUrls = listOf(
            "tel:1234567890",
            "tel://1234567890",
            "javascript:alert(1)",
            "shortcuts://run-shortcut",
            "myapp://open-view",
            "mailto:user@example.com",
            "file:///etc/passwd",
            "data:text/html,<b>hi</b>",
            "ftp://ftp.example.com",
            "sms:123456",
            "intent://scan/#Intent;scheme=zxing;end"
        )

        for (urlString in disallowedUrls) {
            assertFalse("Expected $urlString to be rejected", WebUrlPolicy.isAllowedWebUrl(urlString))
        }
    }

    @Test
    fun isAllowedWebUrlRejectsMissingOrEmptyHost() {
        val invalidUrls = listOf(
            "https://",
            "http://",
            "http:///only-path",
            "//example.com",
            "https:///path"
        )

        for (urlString in invalidUrls) {
            assertFalse("Expected $urlString to be rejected", WebUrlPolicy.isAllowedWebUrl(urlString))
        }
    }

    @Test
    fun isAllowedWebUrlRejectsEmbeddedUserInfo() {
        val credentialUrls = listOf(
            "https://user:pass@example.com",
            "https://admin@example.com",
            "http://user:secret@example.com/path",
            "https://trusted.com@evil.com",
            "https://:pass@example.com",
            "https://@example.com"
        )

        for (urlString in credentialUrls) {
            assertFalse("Expected $urlString to be rejected due to userinfo", WebUrlPolicy.isAllowedWebUrl(urlString))
        }
    }

    @Test
    fun normalizeWebsiteUrlPrefixesHttpsForBareHost() {
        assertEquals("https://example.com", WebUrlPolicy.normalizeWebsiteUrl("example.com"))
        assertEquals("https://example.com/profile", WebUrlPolicy.normalizeWebsiteUrl("example.com/profile"))
        assertEquals("https://sub.example.co.uk", WebUrlPolicy.normalizeWebsiteUrl("sub.example.co.uk"))
        assertEquals("https://example.com", WebUrlPolicy.normalizeWebsiteUrl("   example.com   "))
        assertEquals("https://example.com:8080", WebUrlPolicy.normalizeWebsiteUrl("example.com:8080"))
    }

    @Test
    fun normalizeWebsiteUrlPreservesExistingHttpAndHttps() {
        assertEquals("https://example.com", WebUrlPolicy.normalizeWebsiteUrl("https://example.com"))
        assertEquals("http://example.com/blog", WebUrlPolicy.normalizeWebsiteUrl("http://example.com/blog"))
        assertEquals("https://example.com:8443", WebUrlPolicy.normalizeWebsiteUrl("https://example.com:8443"))
        assertEquals("HTTPS://EXAMPLE.COM/UPPERCASE", WebUrlPolicy.normalizeWebsiteUrl("HTTPS://EXAMPLE.COM/UPPERCASE"))
    }

    @Test
    fun normalizeWebsiteUrlNormalizesProtocolRelativeUrls() {
        assertEquals("https://example.com", WebUrlPolicy.normalizeWebsiteUrl("//example.com"))
        assertEquals("https://example.com/profile", WebUrlPolicy.normalizeWebsiteUrl("//example.com/profile"))
        assertEquals("https://example.com/profile", WebUrlPolicy.normalizeWebsiteUrl("///example.com/profile"))
        assertEquals("https://github.com/cupthread", WebUrlPolicy.normalizeWebsiteUrl("//github.com/cupthread"))
        assertEquals("https://example.com", WebUrlPolicy.normalizeWebsiteUrl("   //example.com   "))
    }

    @Test
    fun normalizeWebsiteUrlRejectsDisallowedSchemes() {
        val disallowed = listOf(
            "tel:1234567890",
            "javascript:alert(1)",
            "shortcuts://run-my-shortcut",
            "myapp://deep-link",
            "mailto:test@example.com",
            "file:///etc/shadow",
            "file:/etc/shadow",
            "data:text/plain,hello",
            "ftp://ftp.test.org",
            "intent://scan/#Intent;end"
        )

        for (item in disallowed) {
            assertNull("Expected $item to normalize to null", WebUrlPolicy.normalizeWebsiteUrl(item))
        }
    }

    @Test
    fun normalizeWebsiteUrlRejectsEmbeddedUserInfo() {
        val userInfoUrls = listOf(
            "https://user:pass@example.com",
            "https://apple.com@attacker.com",
            "http://user:secret@example.com/path",
            "//user:pass@example.com",
            "//trusted.com@evil.com",
            "user:pass@example.com",
            "@example.com"
        )

        for (item in userInfoUrls) {
            assertNull("Expected $item to normalize to null due to userinfo", WebUrlPolicy.normalizeWebsiteUrl(item))
        }
    }

    @Test
    fun normalizeWebsiteUrlRejectsInvalidOrEmptyStrings() {
        assertNull(WebUrlPolicy.normalizeWebsiteUrl(null))
        assertNull(WebUrlPolicy.normalizeWebsiteUrl(""))
        assertNull(WebUrlPolicy.normalizeWebsiteUrl("   "))
        assertNull(WebUrlPolicy.normalizeWebsiteUrl("https://"))
        assertNull(WebUrlPolicy.normalizeWebsiteUrl("http://"))
        assertNull(WebUrlPolicy.normalizeWebsiteUrl("//"))
        assertNull(WebUrlPolicy.normalizeWebsiteUrl("///"))
    }

    @Test
    fun displayWebsiteStripsScheme() {
        assertEquals("example.com", WebUrlPolicy.displayWebsite("https://example.com"))
        assertEquals("example.com/profile", WebUrlPolicy.displayWebsite("http://example.com/profile"))
        assertEquals("EXAMPLE.COM/PATH", WebUrlPolicy.displayWebsite("HTTPS://EXAMPLE.COM/PATH"))
        assertEquals("example.com", WebUrlPolicy.displayWebsite("example.com"))
    }

    @Test
    fun secureImageUrlOnlyAllowsHttpsWithHost() {
        assertEquals("https://example.com/avatar.png", WebUrlPolicy.normalizeSecureImageUrl("https://example.com/avatar.png"))
        assertNull(WebUrlPolicy.normalizeSecureImageUrl("http://example.com/avatar.png"))
        assertNull(WebUrlPolicy.normalizeSecureImageUrl("file:///avatar.png"))
        assertNull(WebUrlPolicy.normalizeSecureImageUrl("https://user:pass@example.com/avatar.png"))
        assertNull(WebUrlPolicy.normalizeSecureImageUrl("https://"))
        assertNull(WebUrlPolicy.normalizeSecureImageUrl(""))
        assertNull(WebUrlPolicy.normalizeSecureImageUrl(null))
    }
}
