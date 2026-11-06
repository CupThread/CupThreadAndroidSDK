package dev.cupthread.feedback

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import dev.cupthread.feedback.internal.HttpRequest
import dev.cupthread.feedback.internal.HttpResponse
import dev.cupthread.feedback.internal.HttpTransport
import dev.cupthread.feedback.internal.SdkAppearanceCache
import dev.cupthread.feedback.ui.FeatureRequestsScreen
import dev.cupthread.feedback.ui.RoadmapBoardScreen
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression coverage for issue #142: the bundled paging screens must always issue requests with
 * the [UserTokenStore] token the host passed in most recently.
 *
 * Tokens are mutable by design ([UserTokenStore.rotateToken], [UserTokenStore.clearToken] after
 * [FeedbackClient.eraseEndUser]), so a pager that captured the token by value would keep querying
 * the erased identity for the rest of the session. These tests render the real screens, flip the
 * token through Compose state, and inspect the `X-User-Token` header the SDK actually sent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UserTokenRotationTest {

    @get:Rule
    val compose = createComposeRule()

    private val appKey = "app_tokentest1234"
    private val token = mutableStateOf(ALPHA)
    private val calls = Collections.synchronizedList(mutableListOf<HttpRequest>())

    private val client = FeedbackClient(
        config = FeedbackClientConfig(baseUrl = "https://api.example.com", appKey = appKey),
        transport = HttpTransport { request ->
            calls += request
            when {
                request.url.contains("/api/v1/public/config/") ->
                    HttpResponse(200, configJson())
                request.url.contains("/api/v1/public/versions/") ->
                    HttpResponse(200, """{"versions":[]}""")
                request.url.contains("/api/v1/public/columns/") ->
                    HttpResponse(200, """{"columns":[]}""")
                request.url.contains("/api/v1/feature-requests") -> HttpResponse(200, PAGE_JSON)
                else -> HttpResponse(404, """{"error":"not_found"}""")
            }
        }
    )

    @Before
    fun resetAppearanceCache() {
        SdkAppearanceCache.clear()
    }

    @Test
    fun featureRequestsScreenRefetchesFirstPageWithRotatedToken() {
        compose.setContent { FeatureRequestsScreen(client, token.value) }

        val initial = awaitPageRequests(1)
        assertEquals(listOf(ALPHA), initial.map { it.userToken() })

        compose.runOnIdle { token.value = BETA }

        val afterRotation = awaitNewPageRequest(since = initial.size)
        assertEquals(BETA, afterRotation.userToken())
        assertTrue(
            "no request may reuse the rotated-away token",
            pageRequests().drop(initial.size).all { it.userToken() == BETA }
        )
    }

    @Test
    fun roadmapBoardScreenRefetchesWithRotatedToken() {
        compose.setContent { RoadmapBoardScreen(client, token.value) }

        val initial = awaitPageRequests(1)
        assertEquals(listOf(ALPHA), initial.map { it.userToken() })

        compose.runOnIdle { token.value = BETA }

        val afterRotation = awaitNewPageRequest(since = initial.size)
        assertEquals(BETA, afterRotation.userToken())
    }

    private fun pageRequests(): List<HttpRequest> = synchronized(calls) {
        calls.filter { it.url.contains("/api/v1/feature-requests") }
    }

    private fun awaitPageRequests(count: Int): List<HttpRequest> {
        awaitCondition { pageRequests().size >= count }
        return pageRequests()
    }

    private fun awaitNewPageRequest(since: Int): HttpRequest {
        awaitCondition { pageRequests().size > since }
        return pageRequests().last()
    }

    /**
     * Pumps the Robolectric main looper and the Compose frame clock until [condition] holds.
     *
     * [androidx.compose.ui.test.junit4.ComposeContentTestRule.waitUntil] alone does not resume
     * composition-scoped coroutines under Robolectric, so the frame clock is advanced and the
     * looper idled explicitly on every iteration.
     */
    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + WAIT_TIMEOUT_MS * 1_000_000L
        while (!condition()) {
            assertTrue(
                "timed out after ${WAIT_TIMEOUT_MS}ms; requests=" +
                    calls.map { it.url.substringAfter("/api/v1/") + " " + it.userToken() },
                System.nanoTime() < deadline
            )
            compose.mainClock.advanceTimeBy(FRAME_MS)
            compose.waitForIdle()
        }
    }

    private fun HttpRequest.userToken(): String? = headers.entries
        .firstOrNull { it.key.equals("X-User-Token", ignoreCase = true) }
        ?.value

    private fun configJson(): String = """
        {
          "appId": "app-1",
          "appKey": "$appKey",
          "slug": "token-rotation",
          "name": "Token Rotation",
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
        const val ALPHA = "token-alpha-0000"
        const val BETA = "token-beta-1111"
        const val WAIT_TIMEOUT_MS = 10_000L
        const val FRAME_MS = 16L
        const val PAGE_JSON = """{"requests":[],"total":0,"hasMore":false,"nextCursor":null}"""
    }
}
