package dev.cupthread.feedback

import dev.cupthread.feedback.internal.REQUEST_ID_HEADER
import dev.cupthread.feedback.internal.REQUEST_ID_PATTERN
import dev.cupthread.feedback.internal.HttpRequest
import dev.cupthread.feedback.internal.HttpResponse
import dev.cupthread.feedback.internal.HttpTransport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for the X-Request-Id correlation contract (#12): the SDK sends the header on every
 * request (generated UUID by default, caller-supplied via [FeedbackClientConfig.requestIdProvider])
 * and attaches the server's echoed value to typed errors as [FeedbackException.requestId].
 */
class RequestIdTest {
    private val appKey = "app_testkey123456"

    private val configJson = """
        {
          "appId":"app-1","appKey":"$appKey","slug":"demo-app","name":"Demo App",
          "storeUrl":null,"storeKind":null,"iconUrl":null,"allowPublic":true,
          "allowedPlatforms":["android"],"maxAttachmentBytes":20000000
        }
    """.trimIndent()

    private fun client(
        config: FeedbackClientConfig = FeedbackClientConfig(baseUrl = "https://api.example.com", appKey = appKey),
        handler: (HttpRequest) -> HttpResponse
    ): FeedbackClient = FeedbackClient(config = config, transport = HttpTransport { handler(it) })

    @Test
    fun defaultConfigSendsFreshRequestIdOnEveryRequest() = runTest {
        val sentIds = mutableListOf<String>()
        repeat(2) {
            client { request ->
                request.headers[REQUEST_ID_HEADER]?.let { sentIds += it }
                HttpResponse(200, configJson)
            }.fetchAppConfig()
        }
        assertEquals(2, sentIds.size)
        sentIds.forEach { id -> assertTrue(REQUEST_ID_PATTERN.matches(id)) }
        assertFalse("each request must mint its own id", sentIds[0] == sentIds[1])
    }

    @Test
    fun configuredProviderValueIsSentOnEveryRequest() = runTest {
        val providerValue = "corr-id-1234567890"
        val config = FeedbackClientConfig(
            baseUrl = "https://api.example.com",
            appKey = appKey,
            requestIdProvider = { providerValue }
        )
        val sentIds = mutableListOf<String>()
        repeat(2) {
            client(config) { request ->
                request.headers[REQUEST_ID_HEADER]?.let { sentIds += it }
                HttpResponse(200, configJson)
            }.fetchAppConfig()
        }
        assertEquals(listOf(providerValue, providerValue), sentIds)
    }

    @Test
    fun providerReturningBlankOrNullOrInvalidSkipsRequestIdHeader() = runTest {
        for (declined in listOf<String?>(null, "", "   ", "short", "bad value!", "bad/slash")) {
            val config = FeedbackClientConfig(
                baseUrl = "https://api.example.com",
                appKey = appKey,
                requestIdProvider = { declined }
            )
            client(config) { request ->
                assertNull("header must be skipped for provider value '$declined'", request.headers[REQUEST_ID_HEADER])
                HttpResponse(200, configJson)
            }.fetchAppConfig()
        }
    }

    @Test
    fun providerWhitespacePaddedValueIsNotTrimmedIntoValidity() = runTest {
        val config = FeedbackClientConfig(
            baseUrl = "https://api.example.com",
            appKey = appKey,
            requestIdProvider = { "  corr-id-1234567890  " }
        )
        client(config) { request ->
            assertNull("padded value must be treated as invalid, not trimmed", request.headers[REQUEST_ID_HEADER])
            HttpResponse(200, configJson)
        }.fetchAppConfig()
    }

    @Test
    fun lowercaseEchoedRequestIdHeaderIsAttachedToError() = runTest {
        try {
            client {
                HttpResponse(404, """{"error":"App not found"}""", mapOf("x-request-id" to "srv-req-lowercase"))
            }.fetchAppConfig()
            fail("expected UnexpectedStatus")
        } catch (error: FeedbackException.UnexpectedStatus) {
            assertEquals("srv-req-lowercase", error.requestId)
        }
    }

    @Test
    fun requestIdPatternMatchesServerContract() {
        assertTrue(REQUEST_ID_PATTERN.matches("0123456789abcdef-uuid_like.Value"))
        assertTrue(REQUEST_ID_PATTERN.matches("abcd1234"))
        assertTrue(REQUEST_ID_PATTERN.matches("A.Z-_9xy"))
        assertFalse(REQUEST_ID_PATTERN.matches("seven7"))
        assertFalse(REQUEST_ID_PATTERN.matches("with space123"))
        assertFalse(REQUEST_ID_PATTERN.matches("slash/rejected"))
        assertFalse(REQUEST_ID_PATTERN.matches("a".repeat(65)))
    }

    @Test
    fun serverEchoedRequestIdIsAttachedToUnexpectedStatus() = runTest {
        try {
            client {
                HttpResponse(404, """{"error":"App not found"}""", mapOf(REQUEST_ID_HEADER to "srv-req-12345678"))
            }.fetchAppConfig()
            fail("expected UnexpectedStatus")
        } catch (error: FeedbackException.UnexpectedStatus) {
            assertEquals(404, error.code)
            assertEquals("srv-req-12345678", error.requestId)
        }
    }

    @Test
    fun serverEchoedRequestIdIsAttachedToRateLimited() = runTest {
        try {
            client {
                HttpResponse(429, """{"error":"Too many requests"}""", mapOf(REQUEST_ID_HEADER to "srv-req-abcdef42"))
            }.fetchFeatureRequest("fr_1")
            fail("expected RateLimited")
        } catch (error: FeedbackException.RateLimited) {
            assertEquals("Too many requests", error.serverMessage)
            assertEquals("srv-req-abcdef42", error.requestId)
        }
    }

    @Test
    fun serverEchoedRequestIdIsAttachedToAuthenticationRequired() = runTest {
        try {
            client {
                HttpResponse(401, """{"error":"authentication_required"}""", mapOf(REQUEST_ID_HEADER to "srv-req-000abc12"))
            }.fetchFeatureRequests(userToken = "tok")
            fail("expected AuthenticationRequired")
        } catch (error: FeedbackException.AuthenticationRequired) {
            assertEquals("srv-req-000abc12", error.requestId)
        }
    }

    @Test
    fun missingServerEchoLeavesErrorRequestIdNull() = runTest {
        try {
            client { HttpResponse(404, """{"error":"App not found"}""") }.fetchAppConfig()
            fail("expected UnexpectedStatus")
        } catch (error: FeedbackException.UnexpectedStatus) {
            assertNull(error.requestId)
        }
    }

    @Test
    fun baseExceptionRequestIdDefaultsToNull() {
        assertNull((FeedbackException.InvalidResponse(IllegalStateException("boom")) as FeedbackException).requestId)
    }
}
