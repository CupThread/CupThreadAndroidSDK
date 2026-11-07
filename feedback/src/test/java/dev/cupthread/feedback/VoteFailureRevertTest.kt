package dev.cupthread.feedback

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import dev.cupthread.feedback.internal.HttpRequest
import dev.cupthread.feedback.internal.HttpResponse
import dev.cupthread.feedback.internal.HttpTransport
import dev.cupthread.feedback.internal.SdkAppearanceCache
import dev.cupthread.feedback.ui.FeatureRequestsScreen
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression coverage for issue #178: the vote failure paths used to restore the tap-time
 * row snapshot wholesale, so a reload that landed while the vote was in flight (pull-to-
 * refresh, a search/version reload, a token-rotation reload) got clobbered — the revert
 * rolled `voteCount` back to the tap-time value and discarded votes other users cast in
 * the meantime.
 *
 * The failure paths now revert only the row instance the optimistic update created; a row
 * refetched after the tap keeps its fresh server state. The held vote request (a gate inside
 * the stub transport) makes the reload-while-in-flight window deterministic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoteFailureRevertTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = Collections.synchronizedList(mutableListOf<HttpRequest>())
    private val holdVote = AtomicBoolean(false)
    private val ioFailure = AtomicBoolean(false)
    private val refreshed = AtomicBoolean(false)
    private val token = mutableStateOf(USER_TOKEN)
    private var voteGate = CountDownLatch(1)

    private val client = FeedbackClient(
        config = FeedbackClientConfig(baseUrl = "https://api.example.com", appKey = APP_KEY),
        transport = HttpTransport { request ->
            calls += request
            when {
                request.url.contains("/api/v1/public/config/") ->
                    HttpResponse(200, configJson())
                request.url.contains("/api/v1/public/versions/") ->
                    HttpResponse(200, """{"versions":[]}""")
                request.method == "POST" && request.url.contains("/vote") -> {
                    // Hold the vote in flight until the test releases the gate, so a reload
                    // can complete while the request is still pending.
                    if (holdVote.get()) voteGate.await(HOLD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    if (ioFailure.get()) throw IOException("transport lost")
                    HttpResponse(429, "")
                }
                request.method == "GET" && request.url.contains("/api/v1/feature-requests") ->
                    if (refreshed.get()) HttpResponse(200, REFRESHED_JSON) else HttpResponse(200, PAGE1_JSON)
                else -> HttpResponse(404, """{"error":"not_found"}""")
            }
        }
    )

    @Before
    fun resetState() {
        SdkAppearanceCache.clear()
        holdVote.set(false)
        ioFailure.set(false)
        refreshed.set(false)
        token.value = USER_TOKEN
        voteGate = CountDownLatch(1)
        synchronized(calls) { calls.clear() }
    }

    @Test
    fun rateLimitedVoteRevertKeepsRowRefetchedWhileInFlight() {
        compose.setContent { FeatureRequestsScreen(client, token.value) }
        awaitNode("Alpha request")
        awaitVotePill("Vote, 10")

        holdVote.set(true)
        compose.onNodeWithContentDescription("Vote, 10").performClick()
        awaitCondition { voteCalls() == 1 }

        // A reload lands while the vote is still pending; the refreshed row already counts
        // another user's vote (10 -> 12). The token change re-fetches the first page the way
        // a pull-to-refresh or a version-filter change does.
        refreshed.set(true)
        token.value = USER_TOKEN_2
        awaitVotePill("Vote, 12")

        voteGate.countDown()
        // The snackbar only surfaces once the rate-limited catch — and its revert — ran.
        awaitNode(VOTE_RATE_LIMITED)

        // The optimistic change must revert without rolling the row back to the tap-time
        // snapshot: the refetched count survives and the row stays unvoted.
        compose.onNodeWithContentDescription("Vote, 12").assertExists()
        compose.onNodeWithContentDescription("Vote, 10").assertDoesNotExist()
        compose.onNodeWithContentDescription("Remove vote, 12").assertDoesNotExist()
    }

    @Test
    fun transportFailureVoteRevertKeepsRowRefetchedWhileInFlight() {
        compose.setContent { FeatureRequestsScreen(client, token.value) }
        awaitNode("Alpha request")
        awaitVotePill("Vote, 10")

        holdVote.set(true)
        compose.onNodeWithContentDescription("Vote, 10").performClick()
        awaitCondition { voteCalls() == 1 }

        refreshed.set(true)
        token.value = USER_TOKEN_2
        awaitVotePill("Vote, 12")

        ioFailure.set(true)
        voteGate.countDown()
        // The generic failure branch shows no snackbar; the pill unlocking (votingIds is
        // released in `finally`) is the observable that the failure slice completed.
        awaitCondition { pillEnabled("Vote, 12") }

        compose.onNodeWithContentDescription("Vote, 12").assertExists()
        compose.onNodeWithContentDescription("Vote, 10").assertDoesNotExist()
        compose.onNodeWithContentDescription("Remove vote, 12").assertDoesNotExist()
    }

    @Test
    fun rateLimitedVoteStillRevertsWhenNoRefetchHappened() {
        compose.setContent { FeatureRequestsScreen(client, token.value) }
        awaitNode("Alpha request")
        awaitVotePill("Vote, 10")

        holdVote.set(true)
        compose.onNodeWithContentDescription("Vote, 10").performClick()
        // The optimistic flip renders while the vote is held in flight: the pill turns into
        // a voted "Remove vote" pill with the incremented count.
        awaitVotePill("Remove vote, 11")

        voteGate.countDown()
        awaitNode(VOTE_RATE_LIMITED)

        // Without a refetch the tap-time state must come back exactly: count restored and
        // the optimistic ballot dropped.
        compose.onNodeWithContentDescription("Vote, 10").assertExists()
        compose.onNodeWithContentDescription("Vote, 11").assertDoesNotExist()
        compose.onNodeWithContentDescription("Remove vote, 10").assertDoesNotExist()
        compose.onNodeWithContentDescription("Remove vote, 11").assertDoesNotExist()
    }

    /** GET feature-request page fetches (first pages only; cursor pages are not used here). */
    private fun pageCalls(): Int = synchronized(calls) {
        calls.count { it.method == "GET" && it.url.contains("/api/v1/feature-requests") }
    }

    private fun voteCalls(): Int = synchronized(calls) {
        calls.count { it.method == "POST" && it.url.contains("/vote") }
    }

    /** True when the vote pill with [desc] is rendered and no longer in flight (disabled). */
    private fun pillEnabled(desc: String): Boolean = runCatching {
        compose.onNodeWithContentDescription(desc).assertIsEnabled()
    }.isSuccess

    private fun dumpTree(): String = compose.onRoot().fetchSemanticsNode().let { root ->
        buildString {
            fun walk(node: androidx.compose.ui.semantics.SemanticsNode, depth: Int) {
                append("  ".repeat(depth) + "<node>" +
                    node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }
                        .joinToString("|") + "\n")
                node.children.forEach { walk(it, depth + 1) }
            }
            walk(root, depth = 0)
        }
    }

    /**
     * Pumps the Robolectric main looper and the Compose frame clock until [condition] holds.
     *
     * [androidx.compose.ui.test.junit4.ComposeContentTestRule.waitUntil] alone does not resume
     * composition-scoped coroutines under Robolectric, so the frame clock is advanced and the
     * looper idled explicitly on every iteration (same harness as [RateLimitRecoveryTest]).
     */
    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + WAIT_TIMEOUT_MS * 1_000_000L
        while (!condition()) {
            assertTrue(
                "timed out after ${WAIT_TIMEOUT_MS}ms; pageCalls=${pageCalls()} " +
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

    private fun awaitVotePill(desc: String) = awaitCondition {
        compose.onAllNodesWithContentDescription(desc).fetchSemanticsNodes().isNotEmpty()
    }

    private fun configJson(): String = """
        {
          "appId": "app-1",
          "appKey": "$APP_KEY",
          "slug": "vote-failure-revert",
          "name": "Vote Failure Revert",
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
        const val APP_KEY = "app_votereverttest1234"
        const val USER_TOKEN = "token-voterevert-0000"
        const val USER_TOKEN_2 = "token-voterevert-0001"
        const val VOTE_RATE_LIMITED = "You're voting too fast. Please try again shortly."
        const val WAIT_TIMEOUT_MS = 10_000L
        const val HOLD_TIMEOUT_MS = 10_000L
        const val FRAME_MS = 16L
        val PAGE1_JSON = """
            {"requests":[
                {"id":"fr-1","appId":"app-1","title":"Alpha request","description":"first",
                 "status":"open","voteCount":10,"hasVoted":false,"isOwnRequest":false}
             ],"total":1,"hasMore":false,"nextCursor":null}
        """.trimIndent()
        // The reload's answer for the same row while the vote is in flight: another user's
        // vote arrived in the meantime (10 -> 12).
        val REFRESHED_JSON = """
            {"requests":[
                {"id":"fr-1","appId":"app-1","title":"Alpha request","description":"first",
                 "status":"open","voteCount":12,"hasVoted":false,"isOwnRequest":false}
             ],"total":1,"hasMore":false,"nextCursor":null}
        """.trimIndent()
    }
}
