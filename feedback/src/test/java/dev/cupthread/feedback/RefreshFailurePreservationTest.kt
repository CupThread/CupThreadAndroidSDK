package dev.cupthread.feedback

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import dev.cupthread.feedback.internal.HttpRequest
import dev.cupthread.feedback.internal.HttpResponse
import dev.cupthread.feedback.internal.HttpTransport
import dev.cupthread.feedback.internal.SdkAppearanceCache
import dev.cupthread.feedback.ui.FeatureRequestsScreen
import dev.cupthread.feedback.ui.RoadmapBoardScreen
import java.io.IOException
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
 * Regression coverage for issue #163: a failed refresh of already-rendered content must keep the
 * rendered rows on screen — the full-screen "Couldn't load" state is reserved for a failed
 * *initial* load — and the inline retry banner must re-run the load from the first page.
 *
 * Reloads are triggered through the `userToken` state the screens already observe (issue #142:
 * a token rotation restarts the search collector and re-runs the first-page load), so the test
 * exercises the real `load()` path without gestures.
 *
 * Also pins the refresh-failure half of the snackbar-coalescing invariant (issue #203): the
 * generic-failure branch of `load()` routes through the same coalescing helper as the 429
 * branch ([showCoalescedFeedbackSnackbar]), so repeated failed refreshes while offline keep
 * the message on at most the banner plus one snackbar. The guard's arms themselves are
 * pinned at the coroutine level in [SnackbarCoalescingTest]: the pre-fix duplicate (a
 * queued identical snackbar) starves on the ui dispatcher's trampoline under Robolectric
 * and is not renderable here, so these tests additionally pin the Main.immediate
 * publication — a plain composition-scope launch would starve and the post-dismissal
 * re-surfacing failure would time out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RefreshFailurePreservationTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = Collections.synchronizedList(mutableListOf<HttpRequest>())
    private val failFeatureRequests = AtomicBoolean(false)
    private val userToken = mutableStateOf(TOKEN_ONE)

    private val client = FeedbackClient(
        config = FeedbackClientConfig(baseUrl = "https://api.example.com", appKey = APP_KEY),
        transport = HttpTransport { request ->
            calls += request
            when {
                request.url.contains("/api/v1/public/config/") ->
                    HttpResponse(200, configJson())
                request.url.contains("/api/v1/public/versions/") ->
                    HttpResponse(200, """{"versions":[]}""")
                request.url.contains("/api/v1/public/columns/") ->
                    HttpResponse(200, COLUMNS_JSON)
                request.url.contains("/api/v1/feature-requests") ->
                    if (failFeatureRequests.get()) throw IOException("stub network down")
                    else HttpResponse(200, PAGE_JSON)
                else -> HttpResponse(404, """{"error":"not_found"}""")
            }
        }
    )

    @Before
    fun resetState() {
        SdkAppearanceCache.clear()
        failFeatureRequests.set(false)
        userToken.value = TOKEN_ONE
        synchronized(calls) { calls.clear() }
    }

    @Test
    fun failedRefreshKeepsRenderedRows() {
        compose.setContent { FeatureRequestsScreen(client, userToken.value) }
        awaitNode("Alpha request")

        failFeatureRequests.set(true)
        compose.runOnIdle { userToken.value = TOKEN_TWO }

        // The failure keeps the rows mounted and surfaces the retry banner instead of the
        // full-screen error state.
        awaitNode(RETRY)
        compose.onNodeWithText("Alpha request").assertExists()
        compose.onNodeWithText("Beta request").assertExists()
        compose.onNodeWithText(COULD_NOT_LOAD).assertDoesNotExist()
    }

    @Test
    fun failedInitialLoadShowsErrorState() {
        failFeatureRequests.set(true)
        compose.setContent { FeatureRequestsScreen(client, userToken.value) }

        // The very first load still owns the full-screen error state with a retry action.
        awaitNode(COULD_NOT_LOAD)
        compose.onNodeWithText(RETRY).assertExists()
        assertTrue(
            "a failed initial load must not render request rows",
            compose.onAllNodesWithText("Alpha request").fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun retryAfterFailedRefreshRestoresPaging() {
        compose.setContent { FeatureRequestsScreen(client, userToken.value) }
        awaitNode("Alpha request")

        failFeatureRequests.set(true)
        compose.runOnIdle { userToken.value = TOKEN_TWO }
        awaitNode(RETRY)

        failFeatureRequests.set(false)
        val firstPageCallsBeforeRetry = firstPageCalls()
        compose.onNodeWithText(RETRY).performClick()

        // The retry re-runs the full load from the first page (fresh paging position) and the
        // banner disappears once the load succeeds.
        awaitCondition { firstPageCalls() > firstPageCallsBeforeRetry }
        awaitNodeGone(RETRY)
        compose.onNodeWithText("Alpha request").assertExists()
        compose.onNodeWithText("Beta request").assertExists()
        compose.onNodeWithText(COULD_NOT_LOAD).assertDoesNotExist()
    }

    @Test
    fun repeatedFailedRefreshesCoalesceIntoOneSnackbar() {
        compose.setContent { FeatureRequestsScreen(client, userToken.value) }
        awaitNode("Alpha request")

        // Refresh #1 fails: the failure surfaces as the retry banner plus one snackbar.
        // The banner and the snackbar share the message copy, and the first occurrence is
        // never coalesced away — so both surfaces show it and the count is exactly 2.
        failFeatureRequests.set(true)
        compose.runOnIdle { userToken.value = TOKEN_TWO }
        awaitNode(RETRY)
        awaitCondition { nodesWithText(SNACKBAR_MESSAGE) == 2 }

        // Refresh #2 fails while that snackbar is still displayed. Before issue #203 the
        // generic-failure branch fired an un-coalesced showSnackbar, queueing an identical
        // duplicate behind the one on display — the same error then re-surfaced for another
        // Short duration per retry tap on a real device. The branch must coalesce exactly
        // like the 429 branch above it: the identical message is suppressed, the banner
        // keeps the copy and the count stays at 2.
        compose.runOnIdle { userToken.value = TOKEN_ONE }
        awaitCondition { featureRequestCalls() >= 3 }
        assertTrue(
            "the refresh-failure snackbar must still be up when the second refresh fails",
            nodesWithText(SNACKBAR_MESSAGE) == 2
        )

        // Pump past the Short duration: the snackbar dismisses while the banner keeps the
        // copy (count drops to 2 -> 1), and no duplicate may ever re-appear — a queued
        // duplicate would surface within a frame of the dismissal and push the count back
        // to 2, so per-pump counting pins one-snackbar-only.
        awaitCondition { nodesWithText(SNACKBAR_MESSAGE) == 1 }
        repeat(SNACKBAR_RESHOW_PUMPS) {
            compose.mainClock.advanceTimeBy(SNACKBAR_FRAME_MS)
            compose.waitForIdle()
            assertTrue(
                "a duplicate snackbar resurfaced after the first one dismissed",
                nodesWithText(SNACKBAR_MESSAGE) == 1
            )
        }

        // A third identical failure after the dismissal must surface again (the helper's
        // pending entry left the set when the snackbar finished), and it must do so
        // reliably — the coalescing helper publishes through Dispatchers.Main.immediate,
        // where a plain composition-scope launch would starve on the Robolectric
        // quiescent-tree trampoline and never display at all. This pins that the
        // generic-failure branch really routes through the helper.
        compose.runOnIdle { userToken.value = TOKEN_TWO }
        awaitCondition { featureRequestCalls() >= 4 }
        awaitCondition { nodesWithText(SNACKBAR_MESSAGE) == 2 }

        // And that re-surfaced snackbar dismisses cleanly back to the banner-only copy.
        awaitCondition { nodesWithText(SNACKBAR_MESSAGE) == 1 }
        repeat(SNACKBAR_RESHOW_PUMPS) {
            compose.mainClock.advanceTimeBy(SNACKBAR_FRAME_MS)
            compose.waitForIdle()
            assertTrue(
                "a duplicate snackbar resurfaced after the re-shown one dismissed",
                nodesWithText(SNACKBAR_MESSAGE) == 1
            )
        }
    }

    @Test
    fun repeatedFailedBoardRefreshesCoalesceIntoOneSnackbar() {
        // The roadmap board's load() carries the same two branches (issue #203): its
        // generic-failure snackbar must coalesce like the feature-requests screen's.
        compose.setContent { RoadmapBoardScreen(client, userToken.value) }
        awaitNode("Alpha request")

        failFeatureRequests.set(true)
        compose.runOnIdle { userToken.value = TOKEN_TWO }
        awaitCondition { nodesWithText(SNACKBAR_MESSAGE) == 2 }

        // Second identical failure while the snackbar is up: coalesced, banner-only copy
        // remains at count 2.
        compose.runOnIdle { userToken.value = TOKEN_ONE }
        awaitCondition { featureRequestCalls() >= 3 }
        assertTrue(
            "the board's refresh-failure snackbar must still be up when the second refresh fails",
            nodesWithText(SNACKBAR_MESSAGE) == 2
        )

        // The snackbar dismisses back to the banner-only copy and no duplicate re-appears.
        awaitCondition { nodesWithText(SNACKBAR_MESSAGE) == 1 }
        repeat(SNACKBAR_RESHOW_PUMPS) {
            compose.mainClock.advanceTimeBy(SNACKBAR_FRAME_MS)
            compose.waitForIdle()
            assertTrue(
                "a duplicate snackbar resurfaced after the first one dismissed",
                nodesWithText(SNACKBAR_MESSAGE) == 1
            )
        }

        // A third identical failure after the dismissal must re-surface reliably through
        // the helper's Main.immediate publication (see the feature-requests twin above).
        compose.runOnIdle { userToken.value = TOKEN_TWO }
        awaitCondition { featureRequestCalls() >= 4 }
        awaitCondition { nodesWithText(SNACKBAR_MESSAGE) == 2 }
        awaitCondition { nodesWithText(SNACKBAR_MESSAGE) == 1 }
        repeat(SNACKBAR_RESHOW_PUMPS) {
            compose.mainClock.advanceTimeBy(SNACKBAR_FRAME_MS)
            compose.waitForIdle()
            assertTrue(
                "a duplicate snackbar resurfaced after the re-shown one dismissed",
                nodesWithText(SNACKBAR_MESSAGE) == 1
            )
        }
    }

    /**
     * Semantics nodes currently showing [text]: the refresh-failure surfaces share one copy
     * (the inline retry banner and the snackbar), so coalescing is asserted by counting the
     * surfaces the message is on instead of by single-node existence.
     */
    private fun nodesWithText(text: String): Int =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    /** All feature-request fetches (initial loads and reloads share the endpoint). */
    private fun featureRequestCalls(): Int = synchronized(calls) {
        calls.count { it.url.contains("/api/v1/feature-requests") }
    }

    /** Feature-request calls that start a fresh first page (no keyset `cursor` in the URL). */
    private fun firstPageCalls(): Int = synchronized(calls) {
        calls.count { it.url.contains("/api/v1/feature-requests") && !it.url.contains("cursor=") }
    }

    private fun dumpTree(): String = buildString {
        fun walk(node: SemanticsNode, depth: Int) {
            val text = node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString("|")
            append("  ".repeat(depth) + "<node>" + text + "\n")
            node.children.forEach { walk(it, depth + 1) }
        }
        walk(compose.onRoot().fetchSemanticsNode(), 0)
    }

    /**
     * Pumps the Robolectric main looper and the Compose frame clock until [condition] holds.
     *
     * [androidx.compose.ui.test.junit4.ComposeContentTestRule.waitUntil] alone does not resume
     * composition-scoped coroutines under Robolectric, so the frame clock is advanced and the
     * looper idled explicitly on every iteration (same harness as [UserTokenRotationTest]).
     */
    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + WAIT_TIMEOUT_MS * 1_000_000L
        while (!condition()) {
            assertTrue(
                "timed out after ${WAIT_TIMEOUT_MS}ms; " +
                    "first-page calls=${firstPageCalls()}; tree:\n${dumpTree()}",
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
          "slug": "refresh-failure",
          "name": "Refresh Failure",
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
        const val APP_KEY = "app_refreshtest1234"
        const val TOKEN_ONE = "token-one-0000"
        const val TOKEN_TWO = "token-two-1111"
        const val RETRY = "Retry"
        const val COULD_NOT_LOAD = "Couldn't load"
        const val WAIT_TIMEOUT_MS = 10_000L
        const val FRAME_MS = 16L
        // The snackbar text for a generic refresh failure: the transport IOException reaches
        // the UI wrapped in FeedbackException.InvalidResponse, whose message is fixed copy.
        const val SNACKBAR_MESSAGE = "The feedback server returned an invalid response."
        // 100 pumps x 64 ms ≈ 6.4 s of virtual time: longer than one Short snackbar, so a
        // duplicate queued behind the dismissed one cannot hide inside the window (same
        // harness as VoteFailureRevertTest; the fine 16 ms awaitCondition step stays for
        // ordinary state polling).
        const val SNACKBAR_FRAME_MS = 64L
        const val SNACKBAR_RESHOW_PUMPS = 100
        val COLUMNS_JSON = """
            {"columns":[
                {"id":"col-1","appId":"app-1","name":"Planned","slug":"planned","position":0,
                 "isVisible":true,"isSystem":true,"kind":"normal","color":null,
                 "createdAt":"","updatedAt":""}
             ]}
        """.trimIndent()
        val PAGE_JSON = """
            {"requests":[
                {"id":"fr-1","appId":"app-1","title":"Alpha request","description":"first",
                 "status":"open","voteCount":1,"hasVoted":false,"isOwnRequest":false,
                 "columnId":"col-1"},
                {"id":"fr-2","appId":"app-1","title":"Beta request","description":"second",
                 "status":"open","voteCount":2,"hasVoted":false,"isOwnRequest":false,
                 "columnId":"col-1"}
             ],"total":2,"hasMore":true,"nextCursor":"cursor-1"}
        """.trimIndent()
    }
}
