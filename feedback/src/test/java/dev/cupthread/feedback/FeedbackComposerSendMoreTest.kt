package dev.cupthread.feedback

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEditable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import dev.cupthread.feedback.internal.HttpRequest
import dev.cupthread.feedback.internal.HttpResponse
import dev.cupthread.feedback.internal.HttpTransport
import dev.cupthread.feedback.ui.FeedbackFormBody
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression coverage for issue #169: tapping "Send More Feedback" must start a fresh report.
 *
 * The sheet presentation ([FeedbackComposerSheet] renders [FeedbackFormBody] directly) keeps the
 * form composable in composition while the confirmation is showing, so without an explicit reset
 * the submitted draft — including the server-finalized attachment uploadIds — survived into the
 * next submission and the server answered 409 `attachment_already_finalized`.
 *
 * These tests render [FeedbackFormBody] itself (the exact state machine the sheet hosts, minus
 * the modal window that Robolectric cannot query across) with a stub transport answering 201.
 * Session-added attachments ride the same reset — the fresh seed has empty attachments — but the
 * photo-picker result cannot be dispatched from a Robolectric environment, so the assertions
 * observe the reset through the text fields, the submit button state, and the captured
 * `POST /api/v1/feedback` bodies.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeedbackComposerSendMoreTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = Collections.synchronizedList(mutableListOf<HttpRequest>())

    private val client = FeedbackClient(
        config = FeedbackClientConfig(
            baseUrl = "https://api.example.com",
            appKey = "app_sendmore1234",
            defaultPlatform = FeedbackPlatform.ANDROID
        ),
        transport = HttpTransport { request ->
            calls += request
            if (request.method == "POST" && request.url.contains("/api/v1/feedback")) {
                HttpResponse(201, """{"submissionId":"s-1","forwardedToGithub":false}""")
            } else {
                HttpResponse(404, """{"error":"not_found"}""")
            }
        }
    )

    private fun submitRequests() =
        calls.filter { it.method == "POST" && it.url.contains("/api/v1/feedback") }

    private fun submitBody(index: Int) =
        submitRequests()[index].body?.toString(Charsets.UTF_8).orEmpty()

    // Field order in FeedbackFormBody: title, description, name, email.
    private fun titleField() = compose.onAllNodes(isEditable())[0]
    private fun descriptionField() = compose.onAllNodes(isEditable())[1]

    // "Send Feedback" is both the sheet header text and the submit button label; only the
    // button carries a click action.
    private fun sendButton() = compose.onNode(hasText("Send Feedback") and hasClickAction())

    private fun setContent(initialDraft: FeedbackDraft? = null) {
        // Mirrors what FeedbackComposerSheet/FeedbackComposerContent do: own the consumed
        // uploadIds so they outlive the form and can be filtered out of the restored seed.
        val submittedAttachments = mutableStateOf(emptyList<FeedbackAttachment>())
        compose.setContent {
            FeedbackFormBody(
                client = client,
                userToken = null,
                initialDraft = initialDraft,
                onSubmit = {},
                onPickAttachment = null,
                onCloseSheet = {},
                maxAttachmentBytes = 1_000_000L,
                submittedAttachments = submittedAttachments.value,
                onAttachmentsSubmitted = {
                    submittedAttachments.value = submittedAttachments.value + it
                },
                // Same modifier chain FeedbackComposerSheet applies, so the form lays out
                // exactly as it does inside the modal bottom sheet.
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            )
        }
        compose.waitForIdle()
    }

    private fun submitWithTitleAndDescription(title: String, description: String) {
        titleField().performTextInput(title)
        descriptionField().performTextInput(description)
        compose.waitForIdle()
        clickSubmit()
    }

    /** Scrolls the submit button into view and taps it. */
    private fun clickSubmit() {
        // The submit button sits below the fold in the small Robolectric window; scroll the
        // form (as a user would) so the button gains real layout bounds — otherwise the
        // injected touch lands at (0, 0).
        runCatching {
            compose.onNode(hasScrollAction()).performScrollToNode(
                hasText("Send Feedback") and hasClickAction()
            )
        }
        compose.waitForIdle()
        awaitCondition { sendButton().fetchSemanticsNode().boundsInRoot.height > 0 }
        sendButton().performClick()
    }

    /** Scrolls the attachment chip into view and taps its remove button. */
    private fun deleteAttachment(filename: String) {
        // Same below-the-fold constraint as [clickSubmit]: the chip must gain real layout
        // bounds before the injected tap, so the form is scrolled to it first.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(filename))
        compose.waitForIdle()
        awaitCondition {
            compose.onAllNodesWithContentDescription("Remove attachment")
                .fetchSemanticsNodes()
                .any { it.boundsInRoot.height > 0 }
        }
        compose.onAllNodesWithContentDescription("Remove attachment")[1].performClick()
        compose.waitForIdle()
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
                "timed out after ${WAIT_TIMEOUT_MS}ms; submit calls=${submitRequests().size}; tree:\n${dumpTree()}",
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

    @Test
    fun sendMoreStartsAFreshDraft() {
        setContent()

        submitWithTitleAndDescription("Broken widget", "It stops working after every reboot")
        awaitNode("Send More Feedback")

        compose.onNodeWithText("Send More Feedback").performClick()

        // "Send more" must come back to a blank form: no confirmation screen, no typed
        // content, and the submit button disabled again until the user types anew.
        awaitNodeGone("Send More Feedback")
        awaitCondition {
            compose.onAllNodesWithText("Broken widget").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("It stops working after every reboot").assertDoesNotExist()
        sendButton().assertIsNotEnabled()
    }

    @Test
    fun sendMoreRestoresHostSuppliedInitialDraft() {
        setContent(
            FeedbackDraft(
                title = "Seeded title",
                description = "Seeded description text",
                platform = FeedbackPlatform.ANDROID
            )
        )

        clickSubmit()
        awaitNode("Send More Feedback")
        compose.onNodeWithText("Send More Feedback").performClick()

        // A host-provided initialDraft is the composer's starting point, so "Send more"
        // resets to that same draft, not to the auto-filled one.
        awaitNodeGone("Send More Feedback")
        compose.onNodeWithText("Seeded title").assertExists()
        compose.onNodeWithText("Seeded description text").assertExists()
        sendButton().assertIsEnabled()
    }

    @Test
    fun secondSubmissionAfterSendMoreCarriesOnlyFreshContent() {
        setContent()

        submitWithTitleAndDescription("First report", "First description body")
        awaitNode("Send More Feedback")
        compose.onNodeWithText("Send More Feedback").performClick()
        awaitNodeGone("Send More Feedback")

        submitWithTitleAndDescription("Second report", "Second description body")
        awaitCondition { submitRequests().size == 2 }

        val first = submitBody(0)
        val second = submitBody(1)
        assertTrue(first.contains("First report"))
        assertTrue(first.contains("First description body"))
        assertTrue(second.contains("Second report"))
        assertTrue(second.contains("Second description body"))
        assertFalse("second submission must not repeat first submission content", second.contains("First report"))
        assertFalse(second.contains("First description body"))
    }

    @Test
    fun sendMoreDropsConsumedUploadIdsFromRestoredSeed() {
        // The photo-picker cannot be driven from Robolectric, so the attachments ride the
        // host-supplied initialDraft — the same path a host uses for pre-uploaded diagnostics.
        setContent(
            FeedbackDraft(
                title = "Seeded title",
                description = "Seeded description text",
                platform = FeedbackPlatform.ANDROID,
                attachments = listOf(
                    FeedbackAttachment(
                        kind = FeedbackAttachment.Kind.IMAGE,
                        filename = "first.png",
                        uploadId = "upload-first"
                    ),
                    FeedbackAttachment(
                        kind = FeedbackAttachment.Kind.IMAGE,
                        filename = "second.png",
                        uploadId = "upload-second"
                    )
                )
            )
        )

        // Delete the second chip so only upload-first goes out with the first report.
        deleteAttachment("second.png")
        clickSubmit()
        awaitCondition { submitRequests().size == 1 }

        val first = submitBody(0)
        assertTrue("first submission must carry its own uploadId", first.contains("upload-first"))
        assertFalse("deleted attachment must not be submitted", first.contains("upload-second"))

        awaitNode("Send More Feedback")
        compose.onNodeWithText("Send More Feedback").performClick()

        // The restored seed keeps the unconsumed attachment but drops the finalized
        // upload-first, so the second report cannot trip 409 attachment_already_finalized.
        awaitNodeGone("Send More Feedback")
        compose.onNodeWithText("first.png").assertDoesNotExist()
        compose.onNodeWithText("second.png").assertExists()
        clickSubmit()
        awaitCondition { submitRequests().size == 2 }

        val second = submitBody(1)
        assertTrue("second submission must carry the unconsumed uploadId", second.contains("upload-second"))
        assertFalse(
            "second submission must not reuse the finalized uploadId",
            second.contains("upload-first")
        )
    }

    private companion object {
        const val WAIT_TIMEOUT_MS = 10_000L
        const val FRAME_MS = 16L
    }
}
