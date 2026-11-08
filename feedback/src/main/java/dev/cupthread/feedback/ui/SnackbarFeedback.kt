package dev.cupthread.feedback.ui

import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Coalesces repeated feedback snackbar requests into at most one displayed instance of a
 * message. The vote-tap paths introduced the guard for themselves (issues #187/#190);
 * issue #203 migrated the refresh-failure paths of [FeatureRequestsScreen] and
 * [RoadmapBoardScreen] onto it and extracted this shared implementation — before the
 * extraction the helper existed as two copy-pasted per-screen locals, and the generic
 * (non-429) refresh-failure branch of both screens missed the migration precisely
 * because of that duplication, queueing an identical snackbar per retry tap.
 *
 * Sequence:
 *
 * 1. A message equal to the snackbar currently on display is dropped: [SnackbarHostState]
 *    serializes every `showSnackbar` call, so an un-coalesced identical request would
 *    queue behind the displayed one and re-surface for a Short duration per repeated
 *    failure (retry taps while offline).
 * 2. A message already pending behind the displayed snackbar is dropped via
 *    [pendingMessages]: the set holds every message whose snackbar is still running, so
 *    identical repeated failures queue at most once. A pending entry leaves the set when
 *    its snackbar finishes, so a later identical failure can surface again.
 * 3. Anything else is published through [scope] on [Dispatchers.Main.immediate]. The call
 *    sites all run on the main dispatcher, so the immediate dispatcher publishes the
 *    snackbar data inline without an extra dispatcher hop — the plain composition-scope
 *    launch queued the coroutine on the ui dispatcher's frame-driven trampoline, which
 *    Robolectric tests observed starving indefinitely once the tree went quiescent (the
 *    CI flake the Main.immediate publication fixed).
 *
 * [pendingMessages] is the composition-remembered plain set shared by all callers of one
 * screen's host state; it needs no synchronization because every caller runs on the main
 * dispatcher.
 */
internal fun showCoalescedFeedbackSnackbar(
    snackbarHostState: SnackbarHostState,
    pendingMessages: MutableSet<String>,
    scope: CoroutineScope,
    message: String
) {
    if (snackbarHostState.currentSnackbarData?.visuals?.message == message) return
    if (!pendingMessages.add(message)) return
    scope.launch(Dispatchers.Main.immediate) {
        try {
            snackbarHostState.showSnackbar(message)
        } finally {
            pendingMessages.remove(message)
        }
    }
}
