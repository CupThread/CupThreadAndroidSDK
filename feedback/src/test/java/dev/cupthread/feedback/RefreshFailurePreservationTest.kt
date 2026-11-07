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
        val PAGE_JSON = """
            {"requests":[
                {"id":"fr-1","appId":"app-1","title":"Alpha request","description":"first",
                 "status":"open","voteCount":1,"hasVoted":false,"isOwnRequest":false},
                {"id":"fr-2","appId":"app-1","title":"Beta request","description":"second",
                 "status":"open","voteCount":2,"hasVoted":false,"isOwnRequest":false}
             ],"total":2,"hasMore":true,"nextCursor":"cursor-1"}
        """.trimIndent()
    }
}
