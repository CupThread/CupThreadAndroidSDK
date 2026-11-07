package dev.cupthread.feedback

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEditable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import dev.cupthread.feedback.internal.HttpRequest
import dev.cupthread.feedback.internal.HttpResponse
import dev.cupthread.feedback.internal.HttpTransport
import dev.cupthread.feedback.internal.SdkAppearanceCache
import dev.cupthread.feedback.ui.FeatureRequestComposeSheetBody
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression coverage for issue #191: a rate-limited feature-request submission used to
 * render the raw 429 response body ("The feedback request failed (429): {\"error\":…}") in
 * the compose sheet's error text, because the sheet caught only `VerificationRequired`
 * before the generic `Exception` fallthrough and `submitFeatureRequest` was the only
 * mutation path that did not map HTTP 429 to the typed `FeedbackException.RateLimited`.
 *
 * The client now throws the typed exception (uniform with every other mutation) and the
 * sheet answers with the localized submit-rate-limited text. The form body is rendered
 * directly — the modal window Robolectric cannot query across is not part of this
 * regression (same harness as [FeedbackComposerSendMoreTest]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeatureRequestComposeSheetRateLimitTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = Collections.synchronizedList(mutableListOf<HttpRequest>())
    private var submitted = false

    private val client = FeedbackClient(
        config = FeedbackClientConfig(baseUrl = "https://api.example.com", appKey = APP_KEY),
        transport = HttpTransport { request ->
            calls += request
            when {
                request.method == "POST" && request.url.endsWith("/api/v1/feature-requests") ->
                    HttpResponse(429, """{"error":"Too many requests"}""")
                else -> HttpResponse(404, """{"error":"not_found"}""")
            }
        }
    )

    @Before
    fun resetState() {
        SdkAppearanceCache.clear()
        submitted = false
        synchronized(calls) { calls.clear() }
    }

    @Test
    fun rateLimitedSubmitShowsLocalizedTextInsteadOfRawBody() {
        setContent()
        titleField().performTextInput("Scheduled posts")
        descriptionField().performTextInput("Let authors queue a request for a future date")
        compose.waitForIdle()
        clickSubmit()

        awaitNode(SUBMIT_RATE_LIMITED)
        // Neither the raw UnexpectedStatus message nor the parsed server message may leak
        // into the sheet's error text.
        compose.onNodeWithText("failed (429)", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Too many requests").assertDoesNotExist()

        // A rejected submission is not a submission: the success callback never fired, and
        // the finally block released the in-flight flag so the button is usable again.
        assertFalse(submitted)
        assertEquals(1, submitCalls())
        compose.onNodeWithText("Submit").assertExists()
    }

    private fun setContent() {
        compose.setContent {
            FeatureRequestComposeSheetBody(
                client = client,
                userToken = USER_TOKEN,
                onSubmitted = { submitted = true },
                // The production sheet host applies no scroll, but the Robolectric viewport
                // is smaller than the form; the wrapper keeps the submit button reachable
                // for the injected taps without changing the body under test.
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            )
        }
        compose.waitForIdle()
    }

    private fun submitCalls(): Int = synchronized(calls) {
        calls.count { it.method == "POST" && it.url.endsWith("/api/v1/feature-requests") }
    }

    // Field order in FeatureRequestComposeSheetBody: title, description, name.
    private fun titleField() = compose.onAllNodes(isEditable())[0]
    private fun descriptionField() = compose.onAllNodes(isEditable())[1]

    private fun clickSubmit() {
        // The submit button may sit below the fold in the small Robolectric window; scroll
        // it into view so the injected tap lands on real layout bounds.
        runCatching {
            compose.onNode(hasScrollAction()).performScrollToNode(
                hasText("Submit") and hasClickAction()
            )
        }
        compose.waitForIdle()
        compose.onNode(hasText("Submit") and hasClickAction()).performClick()
    }

    private fun dumpTree(): String = buildString {
        fun walk(node: SemanticsNode, depth: Int) {
            val text = node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString("|")
            append("  ".repeat(depth) + "<node>text=[$text] bounds=" + node.boundsInRoot + "\n")
            node.children.forEach { walk(it, depth + 1) }
        }
        walk(compose.onRoot().fetchSemanticsNode(), 0)
    }

    /**
     * Pumps the Robolectric main looper and the Compose frame clock until [condition] holds
     * (same harness as [FeedbackComposerSendMoreTest]).
     */
    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + WAIT_TIMEOUT_MS * 1_000_000L
        while (!condition()) {
            assertTrue(
                "timed out after ${WAIT_TIMEOUT_MS}ms; submit calls=${submitCalls()}; tree:\n${dumpTree()}",
                System.nanoTime() < deadline
            )
            compose.mainClock.advanceTimeBy(FRAME_MS)
            compose.waitForIdle()
        }
    }

    private fun awaitNode(text: String) = awaitCondition {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private companion object {
        const val APP_KEY = "app_sheetratelimit1234"
        const val USER_TOKEN = "token-sheetratelimit-0000"
        const val SUBMIT_RATE_LIMITED = "You're submitting too fast. Please try again shortly."
        const val WAIT_TIMEOUT_MS = 30_000L
        const val FRAME_MS = 64L
    }
}
