package dev.cupthread.feedback

import dev.cupthread.feedback.internal.HttpRequest
import dev.cupthread.feedback.internal.HttpResponse
import dev.cupthread.feedback.internal.HttpTransport
import java.lang.reflect.Modifier
import kotlin.coroutines.Continuation
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
 * The second test pins the deliberate counter-contract: endpoints whose KDoc keeps HTTP 429 under
 * `UnexpectedStatus` (no `RateLimited` `@throws`) must surface it raw, so the distinction between
 * "forgot the surface" and "contract says raw" is an explicit, test-enforced decision instead of a
 * silent gap.
 *
 * The third test closes the one gap the row tables cannot: it asserts via reflection that every
 * public suspend method of [FeedbackClient] is classified by one of the tables, so a newly added
 * endpoint (or a renamed/removed one) fails CI until its 429 contract is consciously recorded here.
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
        "fetchCommentsPage" to { client -> client.fetchCommentsPage("fr-guard") },
        "fetchChangelog" to { client -> client.fetchChangelog() },
        "fetchChangelogPage" to { client -> client.fetchChangelogPage() },
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
     * Endpoints whose KDoc deliberately keeps HTTP 429 under [FeedbackException.UnexpectedStatus]
     * (no `RateLimited` `@throws`): the two token-link mutations, the anonymous config fetch, and
     * the `/me` end-user erase/link mutations. Pinning the counter-contract here means the typed
     * table above cannot silently lose a surface, and these endpoints cannot silently gain one —
     * either change must update this test consciously.
     */
    private fun rawStatusEndpoints(): List<Pair<String, suspend (FeedbackClient) -> Unit>> = listOf(
        "unsubscribeFromChangelog" to { client -> client.unsubscribeFromChangelog("sub_token_guard") },
        "confirmChangelogSubscription" to { client -> client.confirmChangelogSubscription("conf_token_guard") },
        "fetchAppConfig" to { client -> client.fetchAppConfig() },
        "eraseEndUser" to { client -> client.eraseEndUser(userToken = "tok_guard") },
        "linkEndUser" to { client -> client.linkEndUser(sessionToken = "sess_guard", userToken = "tok_guard") }
    )

    @Test
    fun changelogUnsubscribeAndConfirmKeepTheirDocumentedRaw429() = runTest {
        for ((endpoint, action) in rawStatusEndpoints()) {
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

    /**
     * Public endpoints that carry no transport call of their own and are covered through what they
     * delegate to: [FeedbackClient.toggleVote] calls [FeedbackClient.vote] (typed row above), and
     * [FeedbackClient.prepareChangelogOverlay] calls [FeedbackClient.fetchAppConfig] (raw row
     * above) plus [FeedbackClient.fetchChangelog] (typed row above). Non-suspend locals like
     * `hasSeenChangelog`/`markChangelogSeen` never reach the transport and need no entry here.
     */
    private fun delegatingEndpointNames(): Set<String> = setOf(
        "toggleVote",
        "prepareChangelogOverlay"
    )

    /**
     * Completeness guard for the hand-maintained tables above: every public suspend method of
     * [FeedbackClient] must be classified here — typed 429 table, documented-raw list, or the
     * delegating list. A newly added endpoint fails this test until its 429 contract is
     * consciously decided, so the tables cannot silently stop tracking the API surface (the rot
     * flagged by the PR #210 review: a documented endpoint missing from the table passes every
     * row-driven check). Renaming or removing an endpoint fails the reverse direction, keeping
     * the tables from accumulating stale entries.
     */
    @Test
    fun everyPublicEndpointIsClassifiedByThisContractTest() {
        val publicSuspendMethods = FeedbackClient::class.java.declaredMethods
            .filter { !it.isSynthetic && Modifier.isPublic(it.modifiers) }
            .filter { it.parameterTypes.isNotEmpty() && it.parameterTypes.last() == Continuation::class.java }
            .map { it.name.substringBefore('$') } // strips the internal-fun `$module` mangling, if any
            .toSet()
        val classified = (
            rateLimitedEndpoints().map { it.first.substringBefore('(') } +
                rawStatusEndpoints().map { it.first } +
                delegatingEndpointNames()
            ).toSet()
        assertEquals(
            "unclassified FeedbackClient endpoints — decide their 429 contract and add them to the matching table above",
            emptySet<String>(),
            publicSuspendMethods - classified
        )
        assertEquals(
            "stale classification entries that name no public suspend method of FeedbackClient",
            emptySet<String>(),
            classified - publicSuspendMethods
        )
    }

    /** The 8-byte PNG signature is enough to pass the composer's image validation (see FeedbackClientTest). */
    private fun pngBytes(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
        0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte()
    )
}
