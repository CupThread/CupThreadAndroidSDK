package dev.cupthread.feedback

import androidx.compose.material3.SnackbarHostState
import dev.cupthread.feedback.ui.showCoalescedFeedbackSnackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Coroutine-level regression coverage for the shared feedback snackbar coalescing helper
 * (issue #203): identical repeated failures must never queue duplicate snackbars behind
 * the one on display, and a finished snackbar must release its pending entry so a later
 * identical failure can surface again.
 *
 * The helper's semantics are pinned here rather than only through the screens' Robolectric
 * tests because the failure the guard prevents is not renderable there: the pre-fix raw
 * launch starves on the ui dispatcher's frame-driven trampoline once the tree goes
 * quiescent (the documented CI-flake shape), so a queued duplicate never surfaces under
 * Robolectric. `Dispatchers.setMain` bridges the helper's `Main.immediate` publication
 * onto the test scheduler, making every arm of the guard deterministic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SnackbarCoalescingTest {

    private companion object {
        const val RATE_LIMITED = "You're doing that too often."
        const val LOAD_FAILED = "The feedback server returned an invalid response."
    }

    /** One scheduler shared by `Main.immediate` and [runTest], so both advance together. */
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(mainDispatcher)
    }

    /** Runs after [runTest] has fully joined and cancelled its background scope. */
    @After
    fun removeMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun displayedMessageSuppressesIdenticalRequestsAndPendingSetQueuesDistinctOnce() =
        runTest(mainDispatcher) {
            val host = SnackbarHostState()
            val pending = mutableSetOf<String>()

            // First failure: the message surfaces and is tracked as pending until its
            // snackbar finishes.
            showCoalescedFeedbackSnackbar(host, pending, backgroundScope, RATE_LIMITED)
            runCurrent()
            assertEquals(RATE_LIMITED, host.currentSnackbarData?.visuals?.message)
            assertEquals(setOf(RATE_LIMITED), pending)

            // A repeated identical request while that snackbar is displayed: suppressed
            // by the displayed-message arm — nothing queued, nothing added twice.
            showCoalescedFeedbackSnackbar(host, pending, backgroundScope, RATE_LIMITED)
            runCurrent()
            assertEquals(RATE_LIMITED, host.currentSnackbarData?.visuals?.message)
            assertEquals(setOf(RATE_LIMITED), pending)

            // A distinct failure queues once behind the displayed snackbar (the pending
            // arm); repeating it must not queue a second instance of the same message.
            showCoalescedFeedbackSnackbar(host, pending, backgroundScope, LOAD_FAILED)
            showCoalescedFeedbackSnackbar(host, pending, backgroundScope, LOAD_FAILED)
            assertEquals(setOf(RATE_LIMITED, LOAD_FAILED), pending)

            // The displayed snackbar finishing releases its pending entry and hands the
            // host state to the queued distinct message — which surfaces exactly once.
            host.currentSnackbarData?.dismiss()
            runCurrent()
            assertEquals(LOAD_FAILED, host.currentSnackbarData?.visuals?.message)
            assertEquals(setOf(LOAD_FAILED), pending)
        }

    @Test
    fun finishedMessageSurfacesAgainOnALaterIdenticalFailure() = runTest(mainDispatcher) {
        val host = SnackbarHostState()
        val pending = mutableSetOf<String>()

        showCoalescedFeedbackSnackbar(host, pending, backgroundScope, LOAD_FAILED)
        runCurrent()
        host.currentSnackbarData?.dismiss()
        runCurrent()
        assertNull(host.currentSnackbarData)
        assertTrue("a finished snackbar must release its pending entry", pending.isEmpty())

        // The identical failure repeats after the dismissal: it must surface again —
        // the coalescing guards duplicates in flight, not forever (a new offline
        // episode deserves its own feedback, issue #203).
        showCoalescedFeedbackSnackbar(host, pending, backgroundScope, LOAD_FAILED)
        runCurrent()
        assertEquals(LOAD_FAILED, host.currentSnackbarData?.visuals?.message)
    }
}
