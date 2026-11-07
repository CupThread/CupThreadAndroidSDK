package dev.cupthread.feedback

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import dev.cupthread.feedback.internal.HttpRequest
import dev.cupthread.feedback.internal.HttpResponse
import dev.cupthread.feedback.internal.HttpTransport
import dev.cupthread.feedback.internal.SdkAppearanceCache
import dev.cupthread.feedback.ui.FeatureRequestsScreen
import dev.cupthread.feedback.ui.RoadmapBoardScreen
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
import org.robolectric.shadows.ShadowDialog

/**
 * Feedback-parity guardrail for issue #192: the three vote handlers (list pill, detail
 * sheet on [FeatureRequestsScreen], detail sheet on [RoadmapBoardScreen]) must surface the
 * identical feedback message for every typed rejection, so a new rejection wired into one
 * copy but not the others cannot pass CI silently.
 *
 * [VoteFailureRevertTest] already pins the list pill for all three rejection types and the
 * FeatureRequestsScreen sheet for the turnstile rejection; this file covers the remaining
 * cells of the 3 surfaces x 3 rejections matrix. With the orchestration extracted into
 * [dev.cupthread.feedback.ui.applyOptimisticVote] the exception-to-message mapping exists
 * once, so these cases primarily pin that the sheet call sites pass the same localized
 * strings as the list pill.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoteFeedbackParityTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = Collections.synchronizedList(mutableListOf<HttpRequest>())
    private val holdVote = AtomicBoolean(false)
    private val rateLimited = AtomicBoolean(false)
    private val turnstileRejected = AtomicBoolean(false)
    private val authRejected = AtomicBoolean(false)
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
                request.url.contains("/api/v1/public/columns/") ->
                    HttpResponse(200, COLUMNS_JSON)
                (request.method == "POST" || request.method == "DELETE") && request.url.contains("/vote") -> {
                    // Hold the vote in flight until the test releases the gate, so the
                    // optimistic flip is observable before the rejection resolves.
                    if (holdVote.get()) voteGate.await(HOLD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    when {
                        rateLimited.get() -> HttpResponse(429, "")
                        turnstileRejected.get() -> HttpResponse(403, """{"error":"turnstile_required"}""")
                        authRejected.get() -> HttpResponse(401, """{"error":"authentication_required"}""")
                        else -> HttpResponse(404, """{"error":"not_found"}""")
                    }
                }
                request.method == "GET" && request.url.contains("/api/v1/feature-requests") ->
                    HttpResponse(200, PAGE1_JSON)
                else -> HttpResponse(404, """{"error":"not_found"}""")
            }
        }
    )

    @Before
    fun resetState() {
        SdkAppearanceCache.clear()
        holdVote.set(false)
        rateLimited.set(false)
        turnstileRejected.set(false)
        authRejected.set(false)
        voteGate = CountDownLatch(1)
        synchronized(calls) { calls.clear() }
    }

    @Test
    fun rateLimitedFeaturesSheetVoteMatchesTheListPillMessage() {
        compose.setContent { FeatureRequestsScreen(client, USER_TOKEN) }
        openSheetAndVoteWithRejection { rateLimited.set(true) }

        awaitNode(VOTE_RATE_LIMITED)
        awaitCondition { "Vote, 10" in dialogDescriptions() && "Remove vote, 11" !in dialogDescriptions() }
    }

    @Test
    fun authenticationRequiredFeaturesSheetVoteMatchesTheListPillMessage() {
        compose.setContent { FeatureRequestsScreen(client, USER_TOKEN) }
        openSheetAndVoteWithRejection { authRejected.set(true) }

        awaitNode(VOTE_SIGN_IN_REQUIRED)
        awaitCondition { "Vote, 10" in dialogDescriptions() && "Remove vote, 11" !in dialogDescriptions() }
    }

    @Test
    fun rateLimitedRoadmapSheetVoteMatchesTheListPillMessage() {
        compose.setContent { RoadmapBoardScreen(client, USER_TOKEN) }
        openSheetAndVoteWithRejection { rateLimited.set(true) }

        awaitNode(VOTE_RATE_LIMITED)
        awaitCondition { "Vote, 10" in dialogDescriptions() && "Remove vote, 11" !in dialogDescriptions() }
    }

    @Test
    fun turnstileRejectedRoadmapSheetVoteMatchesTheListPillMessage() {
        compose.setContent { RoadmapBoardScreen(client, USER_TOKEN) }
        openSheetAndVoteWithRejection { turnstileRejected.set(true) }

        awaitNode(VOTE_VERIFICATION_UNAVAILABLE)
        awaitCondition { "Vote, 10" in dialogDescriptions() && "Remove vote, 11" !in dialogDescriptions() }
    }

    @Test
    fun authenticationRequiredRoadmapSheetVoteMatchesTheListPillMessage() {
        compose.setContent { RoadmapBoardScreen(client, USER_TOKEN) }
        openSheetAndVoteWithRejection { authRejected.set(true) }

        awaitNode(VOTE_SIGN_IN_REQUIRED)
        awaitCondition { "Vote, 10" in dialogDescriptions() && "Remove vote, 11" !in dialogDescriptions() }
    }

    /**
     * Opens the detail sheet on "Alpha request", then votes with the rejection armed.
     *
     * The vote request is held in flight until the optimistic flip ("Remove vote, 11") is
     * observed, so the post-rejection rollback checks below cannot pass without an update
     * actually having been applied — the final state alone proves nothing about the
     * transition.
     */
    private fun openSheetAndVoteWithRejection(armRejection: () -> Unit) {
        awaitNode("Alpha request")
        compose.onAllNodesWithText("Alpha request")[0].performClick()
        awaitCondition { "Vote, 10" in dialogDescriptions() }

        holdVote.set(true)
        armRejection()
        tapDescriptionInDialog("Vote, 10")
        awaitCondition { "Remove vote, 11" in dialogDescriptions() }
        voteGate.countDown()
    }

    /**
     * Pumps the Robolectric main looper and the Compose frame clock until [condition] holds
     * (same harness as [VoteFailureRevertTest]: `waitUntil` alone does not resume
     * composition-scoped coroutines under Robolectric).
     */
    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + WAIT_TIMEOUT_MS * 1_000_000L
        while (!condition()) {
            assertTrue(
                "timed out after ${WAIT_TIMEOUT_MS}ms; voteCalls=${voteCalls()}; " +
                    "dialog descs: ${dialogDescriptions()}",
                System.nanoTime() < deadline
            )
            compose.mainClock.advanceTimeBy(FRAME_MS)
            compose.waitForIdle()
        }
    }

    private fun awaitNode(text: String) = awaitCondition {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun voteCalls(): Int = synchronized(calls) {
        calls.count { (it.method == "POST" || it.method == "DELETE") && it.url.contains("/vote") }
    }

    /**
     * Content descriptions currently visible inside the detail sheet.
     *
     * [androidx.compose.material3.ModalBottomSheet] hosts its content in a separate dialog
     * window that the compose test rule's `onNode*` matchers do not reach, so the dialog's
     * semantics owner is scanned explicitly (same approach as [VoteFailureRevertTest]).
     */
    private fun dialogDescriptions(): Set<String> {
        val dialog = ShadowDialog.getLatestDialog() ?: return emptySet()
        if (!dialog.isShowing) return emptySet()
        val descriptions = mutableSetOf<String>()
        dialog.window?.decorView?.let { collectDialogDescriptions(it, descriptions) }
        return descriptions
    }

    private fun collectDialogDescriptions(view: View, into: MutableSet<String>) {
        semanticsOwnerOf(view)?.rootSemanticsNode?.let { root ->
            fun walk(node: SemanticsNode) {
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.forEach { into += it }
                node.children.forEach { walk(it) }
            }
            walk(root)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) collectDialogDescriptions(view.getChildAt(i), into)
        }
    }

    /**
     * Taps the sheet node carrying [desc] by injecting a touch pair into the dialog window
     * (same approach as [VoteFailureRevertTest]).
     */
    private fun tapDescriptionInDialog(desc: String) {
        val dialog = ShadowDialog.getLatestDialog()
        val decor = dialog?.window?.decorView
            ?: throw AssertionError("no dialog showing; cannot tap $desc")
        val (composeView, owner) = dialogSemanticsRoot(decor)
            ?: throw AssertionError("no compose semantics inside the dialog")
        val root = owner.rootSemanticsNode
            ?: throw AssertionError("empty semantics tree inside the dialog")
        val matches = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            if (node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(desc) == true) {
                matches += node
            }
            node.children.forEach { walk(it) }
        }
        walk(root)
        // Prefer the innermost match: the pill's own node bounds the tap area exactly.
        val target = matches.lastOrNull()
            ?: throw AssertionError("$desc not found in the dialog tree")
        val location = IntArray(2)
        composeView.getLocationInWindow(location)
        val bounds = target.boundsInRoot
        val x = (location[0] + bounds.left + bounds.width / 2).toFloat()
        val y = (location[1] + bounds.top + bounds.height / 2).toFloat()
        dispatchTap(decor, x, y)
    }

    private fun dispatchTap(decor: View, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        decor.dispatchTouchEvent(down)
        down.recycle()
        val up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0)
        decor.dispatchTouchEvent(up)
        up.recycle()
    }

    /** First view under [view] that owns a compose semantics tree, with that tree's owner. */
    private fun dialogSemanticsRoot(view: View): Pair<View, SemanticsOwner>? {
        semanticsOwnerOf(view)?.let { return view to it }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                dialogSemanticsRoot(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    /**
     * The compose semantics owner of [view]'s view subtree, if any; the view that hosts
     * sheet content exposes its `getSemanticsOwner()` accessor only reflectively.
     */
    private fun semanticsOwnerOf(view: View): SemanticsOwner? = runCatching {
        view.javaClass.methods
            .firstOrNull { it.name == "getSemanticsOwner" && it.parameterCount == 0 }
            ?.invoke(view) as? SemanticsOwner
    }.getOrNull()

    private fun configJson(): String = """
        {
          "appId": "app-1",
          "appKey": "$APP_KEY",
          "slug": "vote-feedback-parity",
          "name": "Vote Feedback Parity",
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
        const val APP_KEY = "app_voteparitytest1234"
        const val USER_TOKEN = "token-voteparity-0000"
        // The exact strings the list pill surfaces (pinned by VoteFailureRevertTest); the
        // sheet paths must show the same ones. The turnstile constant is the no-provider
        // branch (cupthread_features_vote_verification_unavailable).
        const val VOTE_RATE_LIMITED = "You're voting too fast. Please try again shortly."
        const val VOTE_VERIFICATION_UNAVAILABLE =
            "Voting requires human verification that isn\u2019t available in this app right now."
        const val VOTE_SIGN_IN_REQUIRED = "Please sign in to vote."
        const val WAIT_TIMEOUT_MS = 30_000L
        const val HOLD_TIMEOUT_MS = 10_000L
        const val FRAME_MS = 64L
        val PAGE1_JSON = """
            {"requests":[
                {"id":"fr-1","appId":"app-1","title":"Alpha request","description":"first",
                 "status":"open","voteCount":10,"hasVoted":false,"isOwnRequest":false,
                 "columnId":"col-1"}
             ],"total":1,"hasMore":false,"nextCursor":null}
        """.trimIndent()
        // The single roadmap column the board groups the page-1 request into.
        val COLUMNS_JSON = """
            {"columns":[
                {"id":"col-1","appId":"app-1","name":"Planned","slug":"planned","position":0,
                 "isVisible":true,"isSystem":true,"kind":"normal","color":null,
                 "createdAt":"","updatedAt":""}
             ]}
        """.trimIndent()
    }
}
