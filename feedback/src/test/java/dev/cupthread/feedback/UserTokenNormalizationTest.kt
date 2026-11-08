package dev.cupthread.feedback

import dev.cupthread.feedback.internal.normalizeUserToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * #212: caller-supplied credentials are normalized once at the transport boundary — trimmed,
 * blank mapped to `null`, CR/LF and other C0 control characters rejected with an
 * `IllegalArgumentException` that names the offending parameter.
 */
class UserTokenNormalizationTest {

    @Test
    fun returnsWellFormedTokenUnchanged() {
        assertEquals("tok_123", normalizeUserToken("tok_123"))
    }

    @Test
    fun trimsSurroundingWhitespaceIncludingLineBreaks() {
        assertEquals("tok_123", normalizeUserToken(" tok_123 "))
        assertEquals("tok_123", normalizeUserToken("tok_123\n"))
        assertEquals("tok_123", normalizeUserToken("\ttok_123\r\n"))
    }

    @Test
    fun mapsNullOrBlankToNull() {
        assertNull(normalizeUserToken(null))
        assertNull(normalizeUserToken(""))
        assertNull(normalizeUserToken("   "))
        assertNull(normalizeUserToken("\r\n\t"))
    }

    @Test
    fun rejectsCrLfBeforeHeaderInjection() {
        try {
            normalizeUserToken("tok\r\nX-Evil: injected")
            fail("Expected IllegalArgumentException for a token carrying CR/LF")
        } catch (ex: IllegalArgumentException) {
            assertTrue(ex.message.orEmpty().contains("userToken"))
            assertTrue(ex.message.orEmpty().contains("control characters"))
        }
    }

    @Test
    fun rejectsInteriorControlCharactersThatSurviveTrim() {
        for (malformed in listOf("tok\u0000x", "tok\u0007x", "tok\u001Bx", "tok\u007Fx")) {
            try {
                normalizeUserToken(malformed)
                fail("Expected IllegalArgumentException for \"$malformed\"")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun messageNamesTheOffendingParameter() {
        try {
            normalizeUserToken("sess\rx", parameterName = "sessionToken")
            fail("Expected IllegalArgumentException for a session token carrying CR")
        } catch (ex: IllegalArgumentException) {
            assertTrue(ex.message.orEmpty().contains("sessionToken"))
        }
    }
}
