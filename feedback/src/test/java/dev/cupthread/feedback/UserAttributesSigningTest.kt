package dev.cupthread.feedback

import dev.cupthread.feedback.internal.canonicalMrr
import dev.cupthread.feedback.internal.hmacSha256Hex
import dev.cupthread.feedback.internal.userAttributesCanonicalString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserAttributesSigningTest {
    @Test
    fun canonicalMrrFormatsTwoFractionDigitsThenStrips() {
        // toFixed(2) semantics followed by trailing-zero / trailing-dot stripping.
        assertEquals("1200", canonicalMrr(1200.0))
        assertEquals("99.5", canonicalMrr(99.5))
        assertEquals("9", canonicalMrr(9.0))
        assertEquals("0", canonicalMrr(0.0))
        assertEquals("9.99", canonicalMrr(9.99))
        assertEquals("1000000", canonicalMrr(1_000_000.0))
    }

    @Test
    fun canonicalMrrRoundsHalfEvenOnExactDoubleValue() {
        // 9.999 → 10.00 → "10" (half-even of 9.99|9 rounds up).
        assertEquals("10", canonicalMrr(9.999))
        // 1.005 and 1.015 sit just below their decimal ties in binary, so both round down
        // exactly like an IEEE 754 double formatter ("toFixed(2) semantics").
        assertEquals("1", canonicalMrr(1.005))
        assertEquals("1.01", canonicalMrr(1.015))
        // 0.125 is exactly representable: a tie resolved to even.
        assertEquals("0.12", canonicalMrr(0.125))
    }

    @Test
    fun canonicalStringJoinsFieldsWithUnsetForAbsentValues() {
        val canonical = userAttributesCanonicalString(
            appKey = "app_testkey123456",
            userToken = "01234567-89ab-cdef-0123-456789abcdef",
            isPaying = true,
            plan = "pro",
            mrr = 9.99,
            currency = "USD",
            timestampSeconds = 1_761_900_000
        )
        assertEquals(
            "cpt-user-attrs-v1\n" +
                "app_testkey123456\n" +
                "01234567-89ab-cdef-0123-456789abcdef\n" +
                "true\n" +
                "pro\n" +
                "9.99\n" +
                "USD\n" +
                "1761900000",
            canonical
        )
    }

    @Test
    fun canonicalStringSignsAbsentAttributesAsUnset() {
        val canonical = userAttributesCanonicalString(
            appKey = "app_testkey123456",
            userToken = "tok-1",
            isPaying = false,
            plan = null,
            mrr = null,
            currency = null,
            timestampSeconds = 1_700_000_001
        )
        assertEquals(
            "cpt-user-attrs-v1\napp_testkey123456\ntok-1\nfalse\nunset\nunset\nunset\n1700000001",
            canonical
        )
    }

    @Test
    fun canonicalStringSignsMrrCanonicalNumber() {
        val canonical = userAttributesCanonicalString(
            appKey = "app_testkey123456",
            userToken = "01234567-89ab-cdef-0123-456789abcdef",
            isPaying = null,
            plan = null,
            mrr = 1200.0,
            currency = "EUR",
            timestampSeconds = 1_700_000_000
        )
        assertTrue(canonical.endsWith("\nunset\nunset\n1200\nEUR\n1700000000"))
    }

    /** Golden vectors cross-checked against Python `hmac.new(..., sha256).hexdigest()`. */
    @Test
    fun hmacSha256HexMatchesIndependentImplementation() {
        assertEquals(
            "d0b19545cec4e9a384fda1314705b0a03e1de2c2fcfed2948229cb24cc716cf9",
            hmacSha256Hex(
                "cpt_signing_secret_test",
                "cpt-user-attrs-v1\napp_testkey123456\n01234567-89ab-cdef-0123-456789abcdef\n" +
                    "true\npro\n9.99\nUSD\n1761900000"
            )
        )
        assertEquals(
            "3cdbd481953d589114d704e6823c7247b86a8f83298522a26a828841c049225d",
            hmacSha256Hex(
                "cpt_signing_secret_test",
                "cpt-user-attrs-v1\napp_testkey123456\n01234567-89ab-cdef-0123-456789abcdef\n" +
                    "unset\nunset\n1200\nEUR\n1700000000"
            )
        )
        assertEquals(
            "c464d295bd582aff5f8c5cbc1430fb54cde1f2f4070d3cdd591bd5d4c2a412b2",
            hmacSha256Hex(
                "another-secret",
                "cpt-user-attrs-v1\napp_testkey123456\ntok-1\nfalse\nunset\nunset\nunset\n1700000001"
            )
        )
    }

    @Test
    fun signatureIsLowercaseHexOf64Chars() {
        val signature = hmacSha256Hex("secret", "message")
        assertEquals(64, signature.length)
        assertEquals(signature, signature.lowercase())
    }
}
