package dev.cupthread.feedback

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import dev.cupthread.feedback.internal.HttpRequest
import dev.cupthread.feedback.internal.HttpResponse
import dev.cupthread.feedback.internal.HttpTransport
import dev.cupthread.feedback.internal.SdkAppearanceCache
import dev.cupthread.feedback.ui.FeatureRequestsScreen
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression coverage for issue #170: `showSnackbar` suspends until dismissal, so the
 * rate-limited catch blocks that called it synchronously kept the owning coroutine's
 * in-flight state (`loadingMore`, `votingIds`) pinned for the whole snackbar duration —
 * the paging footer's Retry tap was swallowed and a rate-limited VotePill could not be
 * tapped again until the snackbar went away.
 *
 * The screens now fire the snackbar from a separate coroutine, so these tests assert the
 * in-flight state releases the moment the failed operation settles: the Retry tap must
 * reach the transport while the snackbar is still on screen, and the vote pill must accept
 * a second tap right after the rate-limited one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RateLimitRecoveryTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = Collections.synchronizedList(mutableListOf<HttpRequest>())
    private val failPage2 = AtomicBoolean(true)
    private val pagingEnabled = AtomicBoolean(true)

    private val client = FeedbackClient(
        config = FeedbackClientConfig(baseUrl = "https://api.example.com", appKey = APP_KEY),
        transport = HttpTransport { request ->
            calls += request
            when {
                request.url.contains("/api/v1/public/config/") ->
                    HttpResponse(200, configJson())
                request.url.contains("/api/v1/public/versions/") ->
                    HttpResponse(200, """{"versions":[]}""")
                request.method == "POST" && request.url.contains("/vote") ->
                    // No `message` field, so the vote snackbar falls back to the localized
                    // rate-limited text the screen owns.
                    HttpResponse(429, "")
                request.url.contains("/api/v1/feature-requests") && request.url.contains("cursor=") ->
                    if (failPage2.get()) {
                        HttpResponse(429, "")
                    } else {
                        HttpResponse(200, PAGE2_JSON)
                    }
                request.url.contains("/api/v1/feature-requests") ->
                    if (pagingEnabled.get()) HttpResponse(200, PAGE1_JSON)
                    else HttpResponse(200, VOTE_PAGE_JSON)
                else -> HttpResponse(404, """{"error":"not_found"}""")
            }
        }
    )

    @Before
    fun resetState() {
        SdkAppearanceCache.clear()
        failPage2.set(true)
        pagingEnabled.set(true)
        synchronized(calls) { calls.clear() }
    }

    @Test
    fun retryAfterRateLimitedPageWorksWhileSnackbarShowing() {
        compose.setContent { FeatureRequestsScreen(client, USER_TOKEN) }
        awaitNode("Alpha request")

        // The short first page sits above the scroll threshold, so paging continues on its
        // own and the second page answers 429.
        awaitCondition { cursorPageCalls() == 1 }
        // The footer only offers the failed-state Retry button once `loadingMore` is
        // released; the snackbar it fired is still on screen at this point.
        awaitNode(LOAD_MORE_FAILED)
        awaitNode(SEARCH_RATE_LIMITED)

        failPage2.set(false)
        compose.onNodeWithText(LOAD_MORE_FAILED).performClick()

        // The Retry tap must start the page fetch immediately — with the snackbar still
        // showing — instead of being swallowed by the guard pinned by the suspended snackbar.
        awaitCondition { cursorPageCalls() == 2 }
        awaitNode("Gamma request")
        awaitNodeGone(LOAD_MORE_FAILED)
    }

    @Test
    fun votePillRecoversImmediatelyAfterRateLimitedVote() {
        // Paging-free page, so no paging snackbar competes with the vote snackbar.
        pagingEnabled.set(false)
        compose.setContent { FeatureRequestsScreen(client, USER_TOKEN) }
        awaitNode("Alpha request")

        compose.onNodeWithContentDescription(VOTE_DESC).performClick()
        awaitCondition { voteCalls() == 1 }
        // The instant stub settles the request within a frame, so the optimistic count is
        // never rendered — the snackbar surfacing is the observable that the failure's
        // revert-and-release slice completed.
        awaitNode(VOTE_RATE_LIMITED)

        // A second tap must fire a new vote request right away, not bounce off the
        // in-flight guard that the suspended snackbar used to pin.
        compose.onNodeWithContentDescription(VOTE_DESC).performClick()
        awaitCondition { voteCalls() == 2 }
    }

    /** Feature-request page fetches that continue a keyset (URL carries `cursor=`). */
    private fun cursorPageCalls(): Int = synchronized(calls) {
        calls.count { it.url.contains("/api/v1/feature-requests") && it.url.contains("cursor=") }
    }

    private fun voteCalls(): Int = synchronized(calls) {
        calls.count { it.method == "POST" && it.url.contains("/vote") }
    }

    private fun dumpTree(): String = compose.onRoot().fetchSemanticsNode().let { root ->
        buildString {
            fun walk(node: androidx.compose.ui.semantics.SemanticsNode, depth: Int) {
                append("  ".repeat(depth) + "<node>" +
                    node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }
                        .joinToString("|") + "\n")
                node.children.forEach { walk(it, depth + 1) }
            }
            walk(root, 0)
        }
    }

    /**
     * Pumps the Robolectric main looper and the Compose frame clock until [condition] holds.
     *
     * [androidx.compose.ui.test.junit4.ComposeContentTestRule.waitUntil] alone does not resume
     * composition-scoped coroutines under Robolectric, so the frame clock is advanced and the
     * looper idled explicitly on every iteration (same harness as [RefreshFailurePreservationTest]).
     *
     * The pump advances virtual time in [FRAME_MS] steps per iteration: a queued snackbar
     * (its Short duration runs on the virtual clock) needs ~4 s of advancement, and at a
     * fine-grained 16 ms step that translates into enough iterations that CI's loaded
     * 2-core runners could burn the wall-clock deadline before the state appeared. The
     * larger step keeps the same coverage in a quarter of the iterations, and the generous
     * [WAIT_TIMEOUT_MS] absorbs a load spike; a genuinely broken assertion still fails,
     * visibly, once the deadline passes.
     */
    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + WAIT_TIMEOUT_MS * 1_000_000L
        while (!condition()) {
            assertTrue(
                "timed out after ${WAIT_TIMEOUT_MS}ms; cursorCalls=${cursorPageCalls()} " +
                    "voteCalls=${voteCalls()}; tree:\n${dumpTree()}",
                System.nanoTime() < deadline
            )
            compose.mainClock.advanceTimeBy(FRAME_MS)
            compose.waitForIdle()
        }
    }

    private fun awaitNode(text: String) = awaitCondition {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun awaitNodeGone(text: String) = awaitCondition {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
    }

    private fun configJson(): String = """
        {
          "appId": "app-1",
          "appKey": "$APP_KEY",
          "slug": "rate-limit-recovery",
          "name": "Rate Limit Recovery",
          "sdk": {
            "features": {
              "feedback": true,
              "featureRequests": true,
              "roadmap": true,
              "changelog": true
            }
          }
        }
    """.trimIndent()

    private companion object {
        const val APP_KEY = "app_ratelimittest1234"
        const val USER_TOKEN = "token-ratelimit-0000"
        const val LOAD_MORE_FAILED = "Couldn't load more"
        const val SEARCH_RATE_LIMITED = "Too many searches. Please wait a moment and try again."
        const val VOTE_RATE_LIMITED = "You're voting too fast. Please try again shortly."
        const val VOTE_DESC = "Vote, 3"
        const val WAIT_TIMEOUT_MS = 30_000L
        const val FRAME_MS = 64L
        val PAGE1_JSON = """
            {"requests":[
                {"id":"fr-1","appId":"app-1","title":"Alpha request","description":"first",
                 "status":"open","voteCount":3,"hasVoted":false,"isOwnRequest":false}
             ],"total":2,"hasMore":true,"nextCursor":"cursor-2"}
        """.trimIndent()
        val PAGE2_JSON = """
            {"requests":[
                {"id":"fr-3","appId":"app-1","title":"Gamma request","description":"third",
                 "status":"open","voteCount":1,"hasVoted":false,"isOwnRequest":false}
             ],"total":2,"hasMore":false,"nextCursor":null}
        """.trimIndent()
        // Paging-free first page for the vote test, so no paging snackbar competes with the
        // vote snackbar (SnackbarHostState serializes concurrent snackbars).
        val VOTE_PAGE_JSON = """
            {"requests":[
                {"id":"fr-1","appId":"app-1","title":"Alpha request","description":"first",
                 "status":"open","voteCount":3,"hasVoted":false,"isOwnRequest":false}
             ],"total":1,"hasMore":false,"nextCursor":null}
        """.trimIndent()
    }
}
