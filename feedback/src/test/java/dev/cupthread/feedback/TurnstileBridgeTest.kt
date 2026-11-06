package dev.cupthread.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for the hosted Turnstile challenge page integration (issue #71 / SaaS #1086):
 * - [TurnstileBridgeMessage.parse] message parsing
 * - [AndroidTurnstileBridge] dispatch and legacy fallback
 * - [TurnstileHostedChallenge] URL assembly and portal derivation
 * - [TurnstileChallenge] factory helpers
 */
class TurnstileBridgeTest {

    // region TurnstileBridgeMessage.parse

    @Test
    fun parseSuccessMessageExtractsToken() {
        val json = """{"status":"success","token":"0.4AAAAAA_test_token_12345"}"""
        val message = TurnstileBridgeMessage.parse(json)
        assertTrue(message is TurnstileBridgeMessage.Success)
        assertEquals("0.4AAAAAA_test_token_12345", (message as TurnstileBridgeMessage.Success).token)
    }

    @Test
    fun parseSuccessMessageIgnoresMissingOrBlankToken() {
        assertNull(TurnstileBridgeMessage.parse("""{"status":"success"}"""))
        assertNull(TurnstileBridgeMessage.parse("""{"status":"success","token":""}"""))
        assertNull(TurnstileBridgeMessage.parse("""{"status":"success","token":"   "}"""))
    }

    @Test
    fun parseErrorMessageWithNumericOrStringCode() {
        val stringCode = TurnstileBridgeMessage.parse("""{"status":"error","code":"110200"}""")
        assertTrue(stringCode is TurnstileBridgeMessage.Error)
        assertEquals("110200", (stringCode as TurnstileBridgeMessage.Error).code)

        val intCode = TurnstileBridgeMessage.parse("""{"status":"error","code":110200}""")
        assertTrue(intCode is TurnstileBridgeMessage.Error)
        assertEquals("110200", (intCode as TurnstileBridgeMessage.Error).code)

        val noCode = TurnstileBridgeMessage.parse("""{"status":"error"}""")
        assertTrue(noCode is TurnstileBridgeMessage.Error)
        assertNull((noCode as TurnstileBridgeMessage.Error).code)

        val nullCode = TurnstileBridgeMessage.parse("""{"status":"error","code":null}""")
        assertTrue(nullCode is TurnstileBridgeMessage.Error)
        assertNull((nullCode as TurnstileBridgeMessage.Error).code)
    }

    @Test
    fun parseExpiredMessage() {
        val expired = TurnstileBridgeMessage.parse("""{"status":"expired"}""")
        assertEquals(TurnstileBridgeMessage.Expired, expired)
    }

    @Test
    fun parseRejectsMalformedOrUnrecognizedStatus() {
        assertNull(TurnstileBridgeMessage.parse(null))
        assertNull(TurnstileBridgeMessage.parse(""))
        assertNull(TurnstileBridgeMessage.parse("   "))
        assertNull(TurnstileBridgeMessage.parse("not json"))
        assertNull(TurnstileBridgeMessage.parse("{}"))
        assertNull(TurnstileBridgeMessage.parse("""{"status":"unknown"}"""))
        assertNull(TurnstileBridgeMessage.parse("""{"status":"pending"}"""))
    }

    // endregion

    // region AndroidTurnstileBridge

    @Test
    fun bridgeDispatchesParsedMessages() {
        val received = mutableListOf<TurnstileBridgeMessage>()
        val bridge = AndroidTurnstileBridge { received += it }

        assertEquals("AndroidTurnstileBridge", AndroidTurnstileBridge.BRIDGE_NAME)

        bridge.postMessage("""{"status":"success","token":"0.token_abc"}""")
        assertEquals(listOf(TurnstileBridgeMessage.Success("0.token_abc")), received)

        bridge.postMessage("""{"status":"error","code":"110200"}""")
        assertEquals(
            listOf(
                TurnstileBridgeMessage.Success("0.token_abc"),
                TurnstileBridgeMessage.Error("110200")
            ),
            received
        )

        bridge.postMessage("""{"status":"expired"}""")
        assertEquals(
            listOf(
                TurnstileBridgeMessage.Success("0.token_abc"),
                TurnstileBridgeMessage.Error("110200"),
                TurnstileBridgeMessage.Expired
            ),
            received
        )

        // Invalid JSON or null does not trigger callback
        bridge.postMessage("bad_json")
        bridge.postMessage(null)
        assertEquals(3, received.size)
    }

    @Test
    fun bridgeLegacyOnTokenCallback() {
        val received = mutableListOf<TurnstileBridgeMessage>()
        val bridge = AndroidTurnstileBridge { received += it }

        bridge.onToken("0.legacy_token_xyz")
        assertEquals(listOf(TurnstileBridgeMessage.Success("0.legacy_token_xyz")), received)

        // Blank tokens ignored
        bridge.onToken("")
        bridge.onToken("   ")
        bridge.onToken(null)
        assertEquals(1, received.size)
    }

    // endregion

    // region TurnstileHostedChallenge URL generation & derivation

    @Test
    fun derivePortalBaseUrlTransformsApiPrefixToApp() {
        assertEquals("https://app.cupthread.com", TurnstileHostedChallenge.derivePortalBaseUrl("https://api.cupthread.com"))
        assertEquals("https://app.cupthread.com", TurnstileHostedChallenge.derivePortalBaseUrl("https://api.cupthread.com/"))
        assertEquals("http://app.local.dev:8080", TurnstileHostedChallenge.derivePortalBaseUrl("http://api.local.dev:8080"))
    }

    @Test
    fun derivePortalBaseUrlRetainsCustomHosts() {
        assertEquals("https://custom.portal.com", TurnstileHostedChallenge.derivePortalBaseUrl("https://custom.portal.com"))
        assertEquals("https://cupthread.staging.internal", TurnstileHostedChallenge.derivePortalBaseUrl("https://cupthread.staging.internal"))
    }

    @Test
    fun buildChallengeUrlAssemblesExpectedQueryString() {
        val challenge = TurnstileChallenge.feedback("app_live_123")
        val url = TurnstileHostedChallenge.buildChallengeUrl(
            siteKey = "0x4AAAAAAA_site_key",
            challenge = challenge,
            theme = "dark",
            locale = "zh-CN",
            portalBaseUrl = "https://app.cupthread.com"
        )

        assertTrue(url.startsWith("https://app.cupthread.com/turnstile/verify?"))
        assertTrue(url.contains("siteKey=0x4AAAAAAA_site_key"))
        assertTrue(url.contains("action=feedback"))
        assertTrue(url.contains("cdata=app_live_123"))
        assertTrue(url.contains("theme=dark"))
        assertTrue(url.contains("locale=zh-CN"))
    }

    @Test
    fun buildChallengeUrlOmitsOptionalParamsWhenNull() {
        val challenge = TurnstileChallenge.featureRequest("app_test_456")
        val url = TurnstileHostedChallenge.buildChallengeUrl(
            siteKey = "0x4AAAAAAA_site_key",
            challenge = challenge
        )

        assertEquals(
            "https://app.cupthread.com/turnstile/verify?siteKey=0x4AAAAAAA_site_key&action=feature-request&cdata=app_test_456",
            url
        )
    }

    @Test
    fun buildChallengeUrlValidatesArguments() {
        val challenge = TurnstileChallenge.feedback("app-1")
        try {
            TurnstileHostedChallenge.buildChallengeUrl("", challenge)
            fail("Expected IllegalArgumentException for blank siteKey")
        } catch (_: IllegalArgumentException) {}

        try {
            TurnstileHostedChallenge.buildChallengeUrl("0x...", TurnstileChallenge("", "app-1"))
            fail("Expected IllegalArgumentException for blank action")
        } catch (_: IllegalArgumentException) {}

        try {
            TurnstileHostedChallenge.buildChallengeUrl("0x...", TurnstileChallenge("feedback", "   "))
            fail("Expected IllegalArgumentException for blank cdata")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun challengeHostedUrlHelperMethod() {
        val challenge = TurnstileChallenge.vote("app_vote_789")
        val url = challenge.hostedUrl("0x4_site_key", theme = "auto", locale = "en")

        assertTrue(url.startsWith("https://app.cupthread.com/turnstile/verify?"))
        assertTrue(url.contains("action=vote"))
        assertTrue(url.contains("cdata=app_vote_789"))
        assertTrue(url.contains("theme=auto"))
        assertTrue(url.contains("locale=en"))
    }

    // endregion

    // region TurnstileChallenge action factory helpers

    @Test
    fun challengeFactoryMethodsSetCorrectBindings() {
        val appKey = "app_binding_test"

        val feedback = TurnstileChallenge.feedback(appKey)
        assertEquals(TurnstileAction.FEEDBACK, feedback.action)
        assertEquals(appKey, feedback.cdata)

        val featureRequest = TurnstileChallenge.featureRequest(appKey)
        assertEquals(TurnstileAction.FEATURE_REQUEST, featureRequest.action)
        assertEquals(appKey, featureRequest.cdata)

        val vote = TurnstileChallenge.vote(appKey)
        assertEquals(TurnstileAction.VOTE, vote.action)
        assertEquals(appKey, vote.cdata)

        val shipNotify = TurnstileChallenge.shipNotify(appKey)
        assertEquals(TurnstileAction.SHIP_NOTIFY, shipNotify.action)
        assertEquals(appKey, shipNotify.cdata)
    }

    // endregion
}
