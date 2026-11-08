package dev.cupthread.feedback

import dev.cupthread.feedback.internal.HttpRequest
import dev.cupthread.feedback.internal.HttpResponse
import dev.cupthread.feedback.internal.HttpTransport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Contract guardrail for the per-endpoint error-surface declarations (issue #197).
 *
 * Each endpoint now declares its typed surfaces as one `Set<ErrorSurface>` at its transport
 * call site, replacing the `mapXxxErrors = true` boolean soup whose single omitted flag shipped
 * a real drift (issue #191: `submitFeatureRequest` forgot the 429 mapping). This test is the
 * mechanical check that failure mode cannot pass CI silently again: it drives an HTTP 429
 * through every endpoint whose documented contract includes `FeedbackException.RateLimited`
 * and asserts the typed surface (server message + `X-Request-Id` echo), naming the endpoint in
 * every failure — the same parity-table shape VoteFeedbackParityTest uses for UI feedback.
 *
 * The second test pins the deliberate counter-contract: `unsubscribeFromChangelog` and
 * `confirmChangelogSubscription` document HTTP 429 under `UnexpectedStatus` (their KDoc has no
 * `RateLimited` `@throws`), so the distinction between "forgot the surface" and "contract says
 * raw" is an explicit, test-enforced decision instead of a silent gap.
 */
class ErrorSurfaceMappingTest {

    private val appKey = "app_testkey123456"

    private fun client(handler: (HttpRequest) -> HttpResponse): FeedbackClient =
        FeedbackClient(
            config = FeedbackClientConfig(baseUrl = "https://api.example.com", appKey = appKey),
            transport = HttpTransport { handler(it) }
        )

    private companion object {
        const val RATE_LIMITED_BODY = """{"error":"Too many requests. Please slow down."}"""
        const val RATE_LIMITED_MESSAGE = "Too many requests. Please slow down."
        const val REQUEST_ID = "req_guard_429"
    }

    /**
     * Every endpoint whose KDoc contract maps HTTP 429 to [FeedbackException.RateLimited], as
     * `endpoint name` to the call exercising it. A 429 response short-circuits each endpoint
     * before any response parsing, so one stub response serves all of them.
     */
    private fun rateLimitedEndpoints(): List<Pair<String, suspend (FeedbackClient) -> Unit>> = listOf(
        "submit" to { client ->
            client.submit(
                FeedbackDraft(title = "Crash", description = "It crashed on launch", platform = FeedbackPlatform.ANDROID),
                userToken = "tok_guard"
            )
        },
        "submitFeatureRequest" to { client ->
            client.submitFeatureRequest(
                FeatureRequestDraft(title = "Dark mode", description = "Ship a dark theme."),
                userToken = "tok_guard"
            )
        },
        "vote" to { client -> client.vote("fr-guard", "tok_guard") },
        "deleteVote" to { client -> client.deleteVote("fr-guard", userToken = "tok_guard") },
        "postComment" to { client ->
            client.postComment("fr-guard", CommentDraft(body = "hi"), userToken = "tok_guard")
        },
        "uploadAttachment(image)" to { client ->
            client.uploadAttachment(data = pngBytes(), filename = "guard.png", mimeType = "image/png")
        },
        "uploadAttachment(upload-session)" to { client ->
            client.uploadAttachment(
                data = byteArrayOf(1),
                filename = "guard.log",
                mimeType = "text/plain",
                userToken = "tok_guard"
            )
        },
        "subscribeToChangelog" to { client ->
            client.subscribeToChangelog("guard@example.com", userToken = "tok_guard")
        },
        "unsubscribeFromWeeklyDigest" to { client ->
            client.unsubscribeFromWeeklyDigest("digest_token_guard")
        },
        "updateUserAttributes" to { client -> client.updateUserAttributes("tok_guard") },
        "fetchColumns" to { client -> client.fetchColumns() },
        "fetchVersions" to { client -> client.fetchVersions() },
        "fetchFeatureRequests" to { client -> client.fetchFeatureRequests(userToken = "tok_guard") },
        "fetchFeatureRequest" to { client -> client.fetchFeatureRequest("fr-guard") },
        "fetchComments" to { client -> client.fetchComments("fr-guard") },
        "fetchChangelog" to { client -> client.fetchChangelog() },
        "fetchUserProfile" to { client -> client.fetchUserProfile("u_guard") }
    )

    @Test
    fun everyRateLimitedContractEndpointSurfacesTypedRateLimitedOnHttp429() = runTest {
        for ((endpoint, action) in rateLimitedEndpoints()) {
            val limited = client {
                HttpResponse(429, RATE_LIMITED_BODY, headers = mapOf("X-Request-Id" to REQUEST_ID))
            }
            try {
                action(limited)
                fail(
                    "$endpoint did not surface FeedbackException.RateLimited on HTTP 429 — " +
                        "its transport call site is missing ErrorSurface.RateLimit (issue #197 regression)"
                )
            } catch (ex: FeedbackException.RateLimited) {
                assertEquals(endpoint, RATE_LIMITED_MESSAGE, ex.serverMessage)
                assertEquals(endpoint, REQUEST_ID, ex.requestId)
            }
        }
    }

    /**
     * The two token-link endpoints deliberately keep HTTP 429 under [FeedbackException.UnexpectedStatus]
     * (their KDoc has no `RateLimited` `@throws`). Pinning the counter-contract here means the
     * endpoints above cannot silently lose their typed surface, and these two cannot silently
     * gain one — either change must update this test consciously.
     */
    @Test
    fun changelogUnsubscribeAndConfirmKeepTheirDocumentedRaw429() = runTest {
        val rawStatusEndpoints: List<Pair<String, suspend (FeedbackClient) -> Unit>> = listOf(
            "unsubscribeFromChangelog" to { client -> client.unsubscribeFromChangelog("sub_token_guard") },
            "confirmChangelogSubscription" to { client -> client.confirmChangelogSubscription("conf_token_guard") }
        )
        for ((endpoint, action) in rawStatusEndpoints) {
            val limited = client { HttpResponse(429, RATE_LIMITED_BODY) }
            try {
                action(limited)
                fail("$endpoint should surface HTTP 429 as FeedbackException.UnexpectedStatus")
            } catch (ex: FeedbackException.RateLimited) {
                fail(
                    "$endpoint now maps 429 to FeedbackException.RateLimited — document the typed " +
                        "surface in its KDoc and move it to the RateLimited table in this test"
                )
            } catch (ex: FeedbackException.UnexpectedStatus) {
                assertEquals(endpoint, 429, ex.code)
            }
        }
    }

    /** The 8-byte PNG signature is enough to pass the composer's image validation (see FeedbackClientTest). */
    private fun pngBytes(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
        0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte()
    )
}
