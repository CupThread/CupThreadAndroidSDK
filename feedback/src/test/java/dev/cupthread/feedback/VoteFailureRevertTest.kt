package dev.cupthread.feedback

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDialog
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
    private val voteSucceeds = AtomicBoolean(false)
    private val turnstileRejected = AtomicBoolean(false)
    private val authRejected = AtomicBoolean(false)
    private val token = mutableStateOf(USER_TOKEN)
    private val screenVisible = mutableStateOf(true)
    private var voteGate = CountDownLatch(1)

    private val transport = HttpTransport { request ->
        calls += request
        when {
            request.url.contains("/api/v1/public/config/") ->
                HttpResponse(200, configJson())
            request.url.contains("/api/v1/public/versions/") ->
                HttpResponse(200, """{"versions":[]}""")
            request.url.contains("/api/v1/public/columns/") ->
                HttpResponse(200, COLUMNS_JSON)
            (request.method == "POST" || request.method == "DELETE") && request.url.contains("/vote") -> {
                // Hold the vote in flight until the test releases the gate, so a reload
                // can complete while the request is still pending.
                if (holdVote.get()) voteGate.await(HOLD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                when {
                    ioFailure.get() -> throw IOException("transport lost")
                    // Typed server rejections (issue #184): the turnstile gate and the
                    // anonymous-voting-disabled answer must reach the UI as their own
                    // FeedbackException subclasses, not as silent generic failures.
                    turnstileRejected.get() -> HttpResponse(403, """{"error":"turnstile_required"}""")
                    authRejected.get() -> HttpResponse(401, """{"error":"authentication_required"}""")
                    voteSucceeds.get() ->
                        // A reconciled server answer: upvote -> voted, unvote -> not voted.
                        HttpResponse(
                            200,
                            """{"voted":${request.method == "POST"},"voteCount":${if (request.method == "POST") 11 else 10}}"""
                        )
                    else -> HttpResponse(429, "")
                }
            }
            request.method == "GET" && request.url.contains("/api/v1/feature-requests") ->
                if (refreshed.get()) HttpResponse(200, REFRESHED_JSON) else HttpResponse(200, PAGE1_JSON)
            else -> HttpResponse(404, """{"error":"not_found"}""")
        }
    }

    private val client = FeedbackClient(
        config = FeedbackClientConfig(baseUrl = "https://api.example.com", appKey = APP_KEY),
        transport = transport
    )

    // The same client with a turnstile token provider installed: the host now renders the
    // verification challenge, so the rejection keeps the actionable "complete the step" ask.
    private val clientWithTurnstileProvider = FeedbackClient(
        config = FeedbackClientConfig(
            baseUrl = "https://api.example.com",
            appKey = APP_KEY,
            turnstileTokenProvider = TurnstileTokenProvider { null }
        ),
        transport = transport
    )

    // A client whose host-side turnstile bridge times out on its own: the provider wraps
    // its token await in an inner withTimeout that always elapses, so the vote handler
    // sees a TimeoutCancellationException while its own job is still alive.
    private val clientWithTimingOutTurnstileProvider = FeedbackClient(
        config = FeedbackClientConfig(
            baseUrl = "https://api.example.com",
            appKey = APP_KEY,
            turnstileTokenProvider = TurnstileTokenProvider {
                withTimeout(1) { awaitCancellation() }
            }
        ),
        transport = transport
    )

    @Before
    fun resetState() {
        SdkAppearanceCache.clear()
        holdVote.set(false)
        ioFailure.set(false)
        refreshed.set(false)
        voteSucceeds.set(false)
        turnstileRejected.set(false)
        authRejected.set(false)
        token.value = USER_TOKEN
        screenVisible.value = true
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

    @Test
    fun turnstileRejectedVoteShowsFeedbackAndReverts() {
        compose.setContent { FeatureRequestsScreen(client, token.value) }
        awaitNode("Alpha request")
        awaitVotePill("Vote, 10")

        // HTTP 403 turnstile_required (issue #184): the vote gate rejected the tap. The
        // generic catch used to revert silently — the user could not tell the vote apart
        // from "recorded" or "rate limited". Without a token provider there is no
        // verification step this app could offer, so the message says it is unavailable
        // instead of pointing at a step that does not exist.
        turnstileRejected.set(true)
        compose.onNodeWithContentDescription("Vote, 10").performClick()

        awaitNode(VOTE_VERIFICATION_UNAVAILABLE)

        // The optimistic flip is gone: the row shows its pre-tap values again.
        compose.onNodeWithContentDescription("Vote, 10").assertExists()
        compose.onNodeWithContentDescription("Vote, 11").assertDoesNotExist()
        compose.onNodeWithContentDescription("Remove vote, 11").assertDoesNotExist()
    }

    @Test
    fun turnstileRejectedVoteWithProviderAsksToCompleteVerification() {
        compose.setContent { FeatureRequestsScreen(clientWithTurnstileProvider, token.value) }
        awaitNode("Alpha request")
        awaitVotePill("Vote, 10")

        // With a provider installed the host renders the challenge, so the actionable
        // "complete the step" ask survives (the client retries once with a fresh token
        // before surfacing this — the stub gate still rejects it).
        turnstileRejected.set(true)
        compose.onNodeWithContentDescription("Vote, 10").performClick()

        awaitNode(VOTE_VERIFICATION_REQUIRED)

        compose.onNodeWithContentDescription("Vote, 10").assertExists()
        compose.onNodeWithContentDescription("Vote, 11").assertDoesNotExist()
        compose.onNodeWithContentDescription("Remove vote, 11").assertDoesNotExist()
    }

    @Test
    fun turnstileProviderTimeoutRevertsLikeAFailureInsteadOfStayingOptimistic() {
        compose.setContent { FeatureRequestsScreen(clientWithTimingOutTurnstileProvider, token.value) }
        awaitNode("Alpha request")
        awaitVotePill("Vote, 10")

        // The host's turnstile bridge times out on its own (an inner withTimeout inside
        // the provider): the cancellation is provider-local, the screen stays mounted,
        // and no vote request is ever sent. The row must revert like a generic failure —
        // keeping the optimistic flip on screen would make the next tap try to DELETE a
        // vote that was never recorded (CodeRabbit review of the #190 rethrow).
        compose.onNodeWithContentDescription("Vote, 10").performClick()

        awaitCondition { pillEnabled("Vote, 10") }
        awaitCondition { voteCalls() == 0 }
        compose.onNodeWithContentDescription("Vote, 10").assertExists()
        compose.onNodeWithContentDescription("Remove vote, 11").assertDoesNotExist()
        compose.onNodeWithContentDescription("Vote, 11").assertDoesNotExist()

        // Silent, exactly like the generic failure branch: no snackbar either.
        assertNoVoteFailureFeedbackWhilePumping()
    }

    @Test
    fun authenticationRequiredVoteShowsFeedbackAndReverts() {
        compose.setContent { FeatureRequestsScreen(client, token.value) }
        awaitNode("Alpha request")
        awaitVotePill("Vote, 10")

        // HTTP 401 (issue #184): the app disabled anonymous voting.
        authRejected.set(true)
        compose.onNodeWithContentDescription("Vote, 10").performClick()

        awaitNode(VOTE_SIGN_IN_REQUIRED)

        compose.onNodeWithContentDescription("Vote, 10").assertExists()
        compose.onNodeWithContentDescription("Vote, 11").assertDoesNotExist()
        compose.onNodeWithContentDescription("Remove vote, 11").assertDoesNotExist()
    }

    @Test
    fun rejectedSheetVoteShowsFeedbackAndReverts() {
        compose.setContent { FeatureRequestsScreen(client, token.value) }
        awaitNode("Alpha request")
        compose.onNodeWithText("Alpha request").performClick()
        awaitCondition { "Vote, 10" in dialogDescriptions() }

        // The detail-sheet vote path hits the same turnstile gate: it must surface the
        // same feedback and revert the sheet's copy instead of failing silently. The
        // no-provider message branch applies here too.
        turnstileRejected.set(true)
        tapDescriptionInDialog("Vote, 10")

        awaitNode(VOTE_VERIFICATION_UNAVAILABLE)
        // With the sheet open both the list row and the sheet row carry "Vote, 10"
        // (onNodeWithContentDescription would be ambiguous), so the revert is asserted
        // through the dialog descriptions: the sheet is back to its tap-time values and
        // the optimistic flip is gone.
        awaitCondition { "Vote, 10" in dialogDescriptions() && "Remove vote, 11" !in dialogDescriptions() }
    }

    @Test
    fun repeatedIdenticalFailuresDoNotQueueDuplicateSnackbars() {
        compose.setContent { FeatureRequestsScreen(client, token.value) }
        awaitNode("Alpha request")
        awaitVotePill("Vote, 10")

        // Failure 1 (429) shows the rate-limit snackbar; failures 2 and 3 (403) land while
        // that snackbar is still up, so their verification message is only pending behind
        // it. The pending coalescing must queue that message once: it surfaces after the
        // rate-limit snackbar dismisses and never re-appears afterwards (a queued duplicate
        // would show the pill a second time at roughly double the Short duration).
        compose.onNodeWithContentDescription("Vote, 10").performClick()
        awaitNode(VOTE_RATE_LIMITED)
        awaitVotePill("Vote, 10")

        turnstileRejected.set(true)
        compose.onNodeWithContentDescription("Vote, 10").performClick()
        awaitVotePill("Vote, 10")
        compose.onNodeWithContentDescription("Vote, 10").performClick()

        // Pump past the rate-limit snackbar's Short duration (~4 s): the verification
        // message becomes the displayed snackbar.
        awaitCondition { snackbarGone(VOTE_RATE_LIMITED) }
        awaitNode(VOTE_VERIFICATION_UNAVAILABLE)

        // Pump past the verification snackbar's own duration: with the coalescing, nothing
        // is queued behind it, so the message is gone for good. A queued duplicate would
        // surface within a frame of the first dismissal and stay up for another Short
        // duration, so the absence is re-asserted on every pump across that whole window.
        awaitCondition { snackbarGone(VOTE_VERIFICATION_UNAVAILABLE) }
        repeat(SNACKBAR_RESHOW_PUMPS) {
            compose.mainClock.advanceTimeBy(FRAME_MS)
            compose.waitForIdle()
            compose.onNodeWithText(VOTE_VERIFICATION_UNAVAILABLE).assertDoesNotExist()
        }
    }


    @Test
    fun staleRefreshDuringFailedUnvoteStillRestoresUpvote() {
        compose.setContent { FeatureRequestsScreen(client, token.value) }
        awaitNode("Alpha request")
        awaitVotePill("Vote, 10")

        // A successful upvote (10 -> 11) reconciles the row to "Remove vote, 11".
        voteSucceeds.set(true)
        holdVote.set(true)
        compose.onNodeWithContentDescription("Vote, 10").performClick()
        awaitCondition { voteCalls() == 1 }
        voteGate.countDown()
        awaitCondition { pillEnabled("Remove vote, 11") }

        // The unvote is held in flight; meanwhile a stale reload lands — a snapshot taken
        // BEFORE the upvote was applied (still "Vote, 10"). It replaces the optimistic
        // instance with a refetched row carrying the pre-revert vote values.
        voteSucceeds.set(false)
        holdVote.set(true)
        voteGate = CountDownLatch(1)
        compose.onNodeWithContentDescription("Remove vote, 11").performClick()
        awaitCondition { voteCalls() == 2 }
        token.value = USER_TOKEN_2
        awaitCondition { pageCalls() == 2 }

        // The unvote fails: without reconciliation the stale refetch (matching the
        // optimistic values on a different instance) would bypass the rollback and the
        // successful upvote would stay hidden.
        ioFailure.set(true)
        voteGate.countDown()
        awaitCondition { pillEnabled("Remove vote, 11") }

        compose.onNodeWithContentDescription("Remove vote, 11").assertExists()
        compose.onNodeWithContentDescription("Vote, 10").assertDoesNotExist()
        compose.onNodeWithContentDescription("Vote, 11").assertDoesNotExist()
    }

    @Test
    fun failedSheetVoteRevertsListToTheRowItOptimisticallyChanged() {
        compose.setContent { FeatureRequestsScreen(client, token.value) }
        awaitNode("Alpha request")
        compose.onNodeWithText("Alpha request").performClick()
        awaitCondition { "Vote, 10" in dialogDescriptions() }

        // A reload lands while the sheet is open: the list row is refetched to count 12,
        // but the sheet keeps showing the instance it was opened on (still count 10).
        refreshed.set(true)
        token.value = USER_TOKEN_2
        awaitVotePill("Vote, 12")
        awaitCondition { "Vote, 10" in dialogDescriptions() }

        // Voting from the sheet optimistically flips both views onto the refetched
        // baseline (13): the list's refetched row and the sheet's own copy agree. The 429
        // must restore the list row it actually changed — the refetched one — not the
        // sheet's stale snapshot.
        holdVote.set(true)
        voteGate = CountDownLatch(1)
        tapDescriptionInDialog("Vote, 10")
        awaitCondition { voteCalls() == 1 }

        voteGate.countDown()
        awaitNode(VOTE_RATE_LIMITED)

        // The optimistic 11 is gone from both views, and both agree on the refetched
        // baseline (12): the failure paths restore the same instance the optimistic flip
        // was built on (issue #182). Both windows carry the pill now, so the existence
        // check goes through the multi-node-safe helper.
        awaitVotePill("Vote, 12")
        compose.onNodeWithContentDescription("Remove vote, 11").assertDoesNotExist()
        awaitCondition { "Vote, 12" in dialogDescriptions() && "Remove vote, 11" !in dialogDescriptions() }
    }

    @Test
    fun sheetVoteOptimisticCountUsesFresherListRow() {
        compose.setContent { FeatureRequestsScreen(client, token.value) }
        awaitNode("Alpha request")
        compose.onNodeWithText("Alpha request").performClick()
        awaitCondition { "Vote, 10" in dialogDescriptions() }

        // A reload lands while the sheet is open: the list row is refetched to count 12,
        // but the sheet keeps showing the instance it was opened on (still count 10).
        refreshed.set(true)
        token.value = USER_TOKEN_2
        awaitVotePill("Vote, 12")
        awaitCondition { "Vote, 10" in dialogDescriptions() }

        // Voting from the sheet must build the optimistic flip on the refetched baseline:
        // both views show 13 (12 + 1). Deriving the list row's optimistic values from the
        // sheet's stale snapshot rendered 11 — a vote that made the count go down and
        // swallowed the other user's vote for the whole optimistic window (issue #182).
        holdVote.set(true)
        voteGate = CountDownLatch(1)
        tapDescriptionInDialog("Vote, 10")
        awaitCondition { voteCalls() == 1 }

        awaitVotePill("Remove vote, 13")
        compose.onNodeWithContentDescription("Remove vote, 11").assertDoesNotExist()
        // The sheet's own copy agrees with the list during the optimistic window.
        awaitCondition { "Remove vote, 13" in dialogDescriptions() && "Remove vote, 11" !in dialogDescriptions() }

        // The 429 revert restores the refetched baseline on both views (the #179
        // contract, with the revert target now the instance the flip was built on).
        // The revert is asserted through the row state; the snackbar surface for 429s
        // is already covered by the rate-limited cases above.
        voteGate.countDown()

        awaitVotePill("Vote, 12")
        compose.onNodeWithContentDescription("Remove vote, 13").assertDoesNotExist()
        awaitCondition { "Vote, 12" in dialogDescriptions() && "Remove vote, 13" !in dialogDescriptions() }
    }

    @Test
    fun dismissedSheetStaysClosedWhenItsVoteSucceeds() {
        compose.setContent { FeatureRequestsScreen(client, token.value) }
        awaitNode("Alpha request")
        compose.onNodeWithText("Alpha request").performClick()
        awaitCondition { "Vote, 10" in dialogDescriptions() }

        // Vote from the sheet, then dismiss the sheet while the vote is in flight.
        voteSucceeds.set(true)
        holdVote.set(true)
        voteGate = CountDownLatch(1)
        tapDescriptionInDialog("Vote, 10")
        awaitCondition { voteCalls() == 1 }
        awaitCondition { "Remove vote, 11" in dialogDescriptions() }

        tapDialogScrim()
        awaitCondition { dialogDescriptions().isEmpty() }

        // The vote succeeds after dismissal: the sheet must not be resurrected; the list
        // row still reconciles to the returned state.
        voteGate.countDown()
        awaitCondition { pillEnabled("Remove vote, 11") }

        compose.onNodeWithContentDescription("Remove vote, 11").assertExists()
        assertTrue("detail sheet re-appeared after its vote succeeded", dialogDescriptions().isEmpty())
    }

    @Test
    fun cancelledListVoteShowsNoFailureFeedbackAndFreshScreenWorks() {
        compose.setContent {
            if (screenVisible.value) FeatureRequestsScreen(client, token.value)
        }
        awaitNode("Alpha request")
        awaitVotePill("Vote, 10")

        // Hold the vote in flight, then take the whole screen out of composition: the
        // screen-scoped coroutine (rememberCoroutineScope) dies while the vote is still
        // pending in the stub transport (issue #190).
        holdVote.set(true)
        compose.onNodeWithContentDescription("Vote, 10").performClick()
        awaitCondition { voteCalls() == 1 }
        setScreenVisible(false)

        // The stub only unblocks now: the response resumes into an already cancelled
        // coroutine, so the vote must end as a cancellation — never as a vote failure.
        // The recovery path used to catch the CancellationException in the generic
        // failure branch and revert against the dead tree; pump the frames so that
        // resumption is forced to run while nothing is mounted.
        voteGate.countDown()
        holdVote.set(false)
        repeat(SETTLE_PUMPS) {
            compose.mainClock.advanceTimeBy(FRAME_MS)
            compose.waitForIdle()
        }

        // A fresh mount starts clean (remember slots are re-created): no failure
        // feedback may bleed in from the cancelled vote (not even queued behind another
        // snackbar), and the screen must be fully functional again — the pill votes
        // through and reconciles.
        voteSucceeds.set(true)
        setScreenVisible(true)
        awaitNode("Alpha request")
        awaitVotePill("Vote, 10")
        assertNoVoteFailureFeedbackWhilePumping()

        compose.onNodeWithContentDescription("Vote, 10").performClick()
        awaitCondition { pillEnabled("Remove vote, 11") }
        // The cancelled vote burned exactly one call; the fresh screen's vote is the
        // second and last one (a cancellation-triggered retry would show up here).
        awaitCondition { voteCalls() == 2 }
    }

    @Test
    fun cancelledSheetVoteShowsNoFailureFeedbackAndFreshScreenWorks() {
        compose.setContent {
            if (screenVisible.value) FeatureRequestsScreen(client, token.value)
        }
        awaitNode("Alpha request")
        compose.onNodeWithText("Alpha request").performClick()
        awaitCondition { "Vote, 10" in dialogDescriptions() }

        // The sheet's vote runs in the same screen-scoped coroutine, so unmounting the
        // screen while the vote is in flight cancels it too (issue #190).
        holdVote.set(true)
        voteGate = CountDownLatch(1)
        tapDescriptionInDialog("Vote, 10")
        awaitCondition { voteCalls() == 1 }
        setScreenVisible(false)

        voteGate.countDown()
        holdVote.set(false)
        repeat(SETTLE_PUMPS) {
            compose.mainClock.advanceTimeBy(FRAME_MS)
            compose.waitForIdle()
        }

        // Same contract as the list-pill cancellation: the fresh screen carries no
        // failure feedback from the cancelled sheet vote and keeps working.
        voteSucceeds.set(true)
        setScreenVisible(true)
        awaitNode("Alpha request")
        awaitVotePill("Vote, 10")
        assertNoVoteFailureFeedbackWhilePumping()

        compose.onNodeWithContentDescription("Vote, 10").performClick()
        awaitCondition { pillEnabled("Remove vote, 11") }
        awaitCondition { voteCalls() == 2 }
    }

    @Test
    fun cancelledRoadmapSheetVoteShowsNoFailureFeedbackAndFreshScreenWorks() {
        compose.setContent {
            if (screenVisible.value) RoadmapBoardScreen(client, token.value)
        }
        awaitNode("Alpha request")
        compose.onNodeWithText("Alpha request").performClick()
        awaitCondition { "Vote, 10" in dialogDescriptions() }

        // The roadmap board's sheet vote ladder is the third copy of the same pattern
        // (issue #190): cancelling it by unmounting the board must behave identically.
        holdVote.set(true)
        voteGate = CountDownLatch(1)
        tapDescriptionInDialog("Vote, 10")
        awaitCondition { voteCalls() == 1 }
        setScreenVisible(false)

        voteGate.countDown()
        holdVote.set(false)
        repeat(SETTLE_PUMPS) {
            compose.mainClock.advanceTimeBy(FRAME_MS)
            compose.waitForIdle()
        }

        voteSucceeds.set(true)
        setScreenVisible(true)
        awaitNode("Alpha request")
        assertNoVoteFailureFeedbackWhilePumping()

        // The fresh board's sheet still votes through and reconciles.
        compose.onNodeWithText("Alpha request").performClick()
        awaitCondition { "Vote, 10" in dialogDescriptions() }
        tapDescriptionInDialog("Vote, 10")
        awaitCondition { voteCalls() == 2 && "Remove vote, 11" in dialogDescriptions() }
    }

    /**
     * Pumps a full Short-snackbar window while asserting none of the vote failure
     * feedback messages is visible: a cancelled vote must never surface the rate-limited,
     * verification, or sign-in asks — not even queued behind another snackbar (issue #190).
     */
    private fun assertNoVoteFailureFeedbackWhilePumping() {
        repeat(SNACKBAR_RESHOW_PUMPS) {
            compose.mainClock.advanceTimeBy(FRAME_MS)
            compose.waitForIdle()
            VOTE_FAILURE_FEEDBACK_TEXTS.forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        }
    }

    /**
     * Flips the screen's presence in the composition and pumps frames until the change
     * is applied: leaving composition cancels the screen-scoped coroutine (and any vote
     * it holds in flight), re-entering builds the screen from fresh remember slots.
     */
    private fun setScreenVisible(visible: Boolean) {
        compose.runOnIdle { screenVisible.value = visible }
        repeat(SETTLE_PUMPS) {
            compose.mainClock.advanceTimeBy(FRAME_MS)
            compose.waitForIdle()
        }
    }

    /** GET feature-request page fetches (first pages only; cursor pages are not used here). */
    private fun pageCalls(): Int = synchronized(calls) {
        calls.count { it.method == "GET" && it.url.contains("/api/v1/feature-requests") }
    }

    private fun voteCalls(): Int = synchronized(calls) {
        calls.count { (it.method == "POST" || it.method == "DELETE") && it.url.contains("/vote") }
    }

    /** True when the vote pill with [desc] is rendered and no longer in flight (disabled). */
    private fun pillEnabled(desc: String): Boolean = runCatching {
        compose.onNodeWithContentDescription(desc).assertIsEnabled()
    }.isSuccess

    /**
     * Content descriptions currently visible inside the detail sheet.
     *
     * [androidx.compose.material3.ModalBottomSheet] hosts its content in a separate dialog
     * window that the compose test rule's `onNode*` matchers do not reach, so the dialog's
     * semantics owner is scanned explicitly (same approach as [ChangelogOverlayRecoveryTest]).
     * Dismissed dialogs are skipped (`isShowing`) so stale trees cannot satisfy absence checks.
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
     * Taps the sheet node carrying [desc] by injecting a touch pair into the dialog window:
     * the detail sheet lives outside the compose test rule's reachable tree, so interactions
     * inside it must be dispatched on the dialog's decor view directly.
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

    /**
     * Dismisses the sheet with the dialog window's back handling (material3's
     * `dismissOnBackPress`): the sheet fills the small test viewport, leaving no reachable
     * scrim to tap.
     */
    private fun tapDialogScrim() {
        val dialog = ShadowDialog.getLatestDialog()
        val decor = dialog?.window?.decorView
            ?: throw AssertionError("no dialog showing; cannot press back")
        val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
        val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)
        decor.dispatchKeyEvent(down)
        decor.dispatchKeyEvent(up)
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
     * The compose semantics owner of [view]'s view subtree, if any.
     *
     * The view that hosts sheet content (the internal compose view) exposes its
     * `getSemanticsOwner()` accessor only reflectively, so it is resolved as such.
     */
    private fun semanticsOwnerOf(view: View): SemanticsOwner? = runCatching {
        view.javaClass.methods
            .firstOrNull { it.name == "getSemanticsOwner" && it.parameterCount == 0 }
            ?.invoke(view) as? SemanticsOwner
    }.getOrNull()

    private fun dumpTree(): String {
        val descriptions = dialogDescriptions()
        val roots = mutableListOf<androidx.compose.ui.semantics.SemanticsNode>()
        runCatching { compose.onRoot().fetchSemanticsNode() }.getOrNull()?.let { roots += it }
        ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView?.let { decor ->
            collectDialogRoots(decor, roots)
        }
        return buildString {
            roots.forEachIndexed { index, root ->
                append("<window#$index>")
                fun walk(node: androidx.compose.ui.semantics.SemanticsNode, depth: Int) {
                    append(
                        "\n" + "  ".repeat(depth) + "<node>" +
                            node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }
                                .joinToString("|") +
                            "/" + (node.config
                            .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)
                            ?.joinToString("|") ?: "")
                    )
                    node.children.forEach { walk(it, depth + 1) }
                }
                walk(root, 0)
            }
            if (descriptions.isNotEmpty()) append("\n<dialog descs: $descriptions>")
        }
    }

    private fun collectDialogRoots(view: View, into: MutableList<androidx.compose.ui.semantics.SemanticsNode>) {
        dialogSemanticsRoot(view)?.let { (_, owner) -> owner.rootSemanticsNode?.let { into += it } }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) collectDialogRoots(view.getChildAt(i), into)
        }
    }

    /**
     * Pumps the Robolectric main looper and the Compose frame clock until [condition] holds.
     *
     * [androidx.compose.ui.test.junit4.ComposeContentTestRule.waitUntil] alone does not resume
     * composition-scoped coroutines under Robolectric, so the frame clock is advanced and the
     * looper idled explicitly on every iteration (same harness as [RateLimitRecoveryTest]).
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

    /** A poll condition, not an awaiter: true once no node carries [text] any more. */
    private fun snackbarGone(text: String) =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()

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
        const val VOTE_VERIFICATION_REQUIRED = "Please complete the human verification step and try again."
        // Matches cupthread_features_vote_verification_unavailable: the no-provider branch of
        // the turnstile rejection message, which must not send the user to a step the app
        // cannot offer (issue #184 review).
        const val VOTE_VERIFICATION_UNAVAILABLE =
            "Voting requires human verification that isn\u2019t available in this app right now."
        const val VOTE_SIGN_IN_REQUIRED = "Please sign in to vote."
        // Every feedback message a vote failure branch can surface; a cancelled vote
        // must show none of them (issue #190).
        val VOTE_FAILURE_FEEDBACK_TEXTS = listOf(
            VOTE_RATE_LIMITED,
            VOTE_VERIFICATION_UNAVAILABLE,
            VOTE_SIGN_IN_REQUIRED
        )
        const val WAIT_TIMEOUT_MS = 30_000L
        const val HOLD_TIMEOUT_MS = 10_000L
        const val FRAME_MS = 64L
        // A short settle window for the cancelled vote's final resumption: the response
        // resumes into the dead scope on these pumped frames, before the fresh mount.
        const val SETTLE_PUMPS = 8
        // 100 pumps x 64 ms ≈ 6.4 s of virtual time: longer than one Short snackbar, so a
        // duplicate queued behind the dismissed one cannot hide inside the window.
        const val SNACKBAR_RESHOW_PUMPS = 100
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
