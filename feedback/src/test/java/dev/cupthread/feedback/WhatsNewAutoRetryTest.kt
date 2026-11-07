package dev.cupthread.feedback

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import dev.cupthread.feedback.internal.HttpRequest
import dev.cupthread.feedback.internal.HttpResponse
import dev.cupthread.feedback.internal.HttpTransport
import dev.cupthread.feedback.internal.SdkAppearanceCache
import dev.cupthread.feedback.ui.WhatsNewScreen
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression coverage for issue #172: a failed page load must stop [WhatsNewScreen]'s
 * auto-pagination — the same contract `FeatureRequestsScreen` and `RoadmapBoardScreen`
 * already enforce. While `loadMoreFailed` is set, scroll events near the end of the list
 * must not re-fire page requests (the footer's explicit Retry is the only way back), and
 * a successful Retry must resume automatic paging.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WhatsNewAutoRetryTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = Collections.synchronizedList(mutableListOf<HttpRequest>())

    /** `false` makes the second page answer 500 so its load attempt fails. */
    private val page2Ok = AtomicBoolean(false)

    private val client = FeedbackClient(
        config = FeedbackClientConfig(baseUrl = "https://api.example.com", appKey = APP_KEY),
        transport = HttpTransport { request ->
            calls += request
            when {
                request.url.contains("/api/v1/public/config/") ->
                    HttpResponse(200, configJson())
                request.url.contains("/api/v1/public/apps/$APP_KEY/changelog") -> when {
                    !request.url.contains("cursor=") ->
                        HttpResponse(200, pageJson(start = 1, count = 4, hasMore = true, cursor = "cursor-1"))
                    request.url.contains("cursor=cursor-1") ->
                        if (page2Ok.get()) {
                            HttpResponse(200, pageJson(start = 5, count = 4, hasMore = true, cursor = "cursor-2"))
                        } else {
                            HttpResponse(500, """{"error":"internal_error"}""")
                        }
                    request.url.contains("cursor=cursor-2") ->
                        HttpResponse(200, pageJson(start = 9, count = 2, hasMore = false, cursor = null))
                    else -> HttpResponse(404, """{"error":"not_found"}""")
                }
                else -> HttpResponse(404, """{"error":"not_found"}""")
            }
        }
    )

    @Before
    fun resetState() {
        SdkAppearanceCache.clear()
        page2Ok.set(false)
        synchronized(calls) { calls.clear() }
    }

    @Test
    fun failedPageDoesNotAutoRetryOnScroll() {
        compose.setContent { WhatsNewScreen(client, userToken = USER_TOKEN) }
        awaitNode(FIRST_ENTRY)

        // Drive the list to its end: the auto-load fires, page 2 fails, and the failed
        // footer takes over.
        scrollListTo("Get Update Emails")
        awaitNode(FAILED_FOOTER)
        awaitCondition { cursorRequests("cursor-1") == 1 }

        // Pump further scroll events in both directions while the failure persists: none of
        // them may issue another page request (issue #172).
        repeat(2) {
            scrollListTo(FIRST_ENTRY)
            scrollListTo("Get Update Emails")
        }
        compose.waitForIdle()
        compose.waitForIdle()
        assertEquals(
            "scroll events must not auto-retry a failed page; cursor-1 calls=${cursorRequests("cursor-1")}",
            1,
            cursorRequests("cursor-1")
        )

        // The explicit footer Retry is the only recovery path: it must issue exactly one
        // more request for the failed page and render its entries.
        page2Ok.set(true)
        compose.onNodeWithText(FAILED_FOOTER).performClick()
        awaitCondition { cursorRequests("cursor-1") == 2 }
        scrollListTo(LAST_PAGE2_ENTRY)
        awaitNode(LAST_PAGE2_ENTRY)
        compose.onNodeWithText(FAILED_FOOTER).assertDoesNotExist()
    }

    @Test
    fun retryResumesAutoPaging() {
        compose.setContent { WhatsNewScreen(client, userToken = USER_TOKEN) }
        awaitNode(FIRST_ENTRY)
        scrollListTo("Get Update Emails")
        awaitNode(FAILED_FOOTER)
        awaitCondition { cursorRequests("cursor-1") == 1 }

        page2Ok.set(true)
        compose.onNodeWithText(FAILED_FOOTER).performClick()
        awaitCondition { cursorRequests("cursor-1") == 2 }

        // With the guard released, scrolling near the new end auto-loads page 3 again —
        // no second Retry tap required.
        scrollListTo("Get Update Emails")
        awaitCondition { cursorRequests("cursor-2") >= 1 }
        awaitNode("Release Nine")
        compose.onNodeWithText(FAILED_FOOTER).assertDoesNotExist()
        compose.onNodeWithText("Load more").assertDoesNotExist()
    }

    /** Changelog page requests that fetch the page after [cursor]. */
    private fun cursorRequests(cursor: String): Int = synchronized(calls) {
        calls.count { it.url.contains("/changelog") && it.url.contains("cursor=$cursor") }
    }

    /**
     * Scrolls the screen's list until the node matching [text] is displayed.
     *
     * The [androidx.compose.foundation.lazy.LazyColumn] is the only scrollable node in this
     * screen's tree, so the scroll action is looked up on it directly and the target is
     * matched by text.
     */
    private fun scrollListTo(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
    }

    /**
     * Pumps the Robolectric main looper and the Compose frame clock until [condition] holds.
     *
     * [androidx.compose.ui.test.junit4.ComposeContentTestRule.waitUntil] alone does not resume
     * composition-scoped coroutines under Robolectric, so the frame clock is advanced and the
     * looper idled explicitly on every iteration (same harness as [RefreshFailurePreservationTest]).
     */
    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + WAIT_TIMEOUT_MS * 1_000_000L
        while (!condition()) {
            assertTrue(
                "timed out after ${WAIT_TIMEOUT_MS}ms; " +
                    "cursor-1 calls=${cursorRequests("cursor-1")}, cursor-2 calls=${cursorRequests("cursor-2")}\n" +
                    "urls=${synchronized(calls) { calls.map { it.url } }}\ntree:\n${dumpTree()}",
                System.nanoTime() < deadline
            )
            compose.mainClock.advanceTimeBy(FRAME_MS)
            compose.waitForIdle()
        }
    }

    private fun dumpTree(): String = buildString {
        fun walk(node: SemanticsNode, depth: Int) {
            val text = node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString("|")
            append("  ".repeat(depth) + "<node>" + text + "\n")
            node.children.forEach { walk(it, depth + 1) }
        }
        runCatching { walk(compose.onRoot().fetchSemanticsNode(), 0) }
    }

    private fun awaitNode(text: String) = awaitCondition {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun configJson(): String = """
        {
          "appId": "app-1",
          "appKey": "$APP_KEY",
          "slug": "whatsnew-auto-retry",
          "name": "WhatsNew Auto Retry",
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

    /** A changelog page of [count] entries titled "Release [start]".."Release [start+count-1]". */
    private fun pageJson(start: Int, count: Int, hasMore: Boolean, cursor: String?): String {
        val entries = (start until start + count).joinToString(",") { n ->
            """
            {
              "id": "entry-$n",
              "title": "Release ${numberWord(n)}",
              "body": "Body of release $n with enough text to give the card a realistic height.",
              "versionLabel": "1.$n.0",
              "publishedAt": "2026-01-${n.toString().padStart(2, '0')}T00:00:00Z",
              "linkedRequests": []
            }
            """.trimIndent()
        }
        return """
            {
              "entries": [$entries],
              "hasMore": $hasMore,
              "nextCursor": ${cursor?.let { "\"$it\"" } ?: "null"}
            }
        """.trimIndent()
    }

    private fun numberWord(n: Int): String = when (n) {
        1 -> "One"; 2 -> "Two"; 3 -> "Three"; 4 -> "Four"; 5 -> "Five"; 6 -> "Six"
        7 -> "Seven"; 8 -> "Eight"; 9 -> "Nine"; else -> "Ten"
    }

    private companion object {
        const val APP_KEY = "app_whatsnewretry12"
        const val USER_TOKEN = "token-whatsnew-retry"

        // parseListChangelog re-sorts each page newest-first, so the first rendered card of
        // page 1 (publishedAt Jan 01-04) is "Release Four".
        const val FIRST_ENTRY = "Release Four"

        // Newest entry of page 2 (publishedAt Jan 05-08), rendered right after page 1's block.
        const val LAST_PAGE2_ENTRY = "Release Eight"
        const val FAILED_FOOTER = "Couldn't load more"
        const val WAIT_TIMEOUT_MS = 10_000L
        const val FRAME_MS = 16L
    }
}
