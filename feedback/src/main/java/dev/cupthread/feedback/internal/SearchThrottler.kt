package dev.cupthread.feedback.internal

import android.os.SystemClock
import kotlinx.coroutines.delay

/**
 * Gates as-you-type search requests so ordinary typing stays within the backend's shared
 * per-IP search budget of 30 requests / 60 s (issue #23).
 *
 * Both bundled search surfaces ([dev.cupthread.feedback.ui.FeatureRequestsScreen] and
 * [dev.cupthread.feedback.ui.RoadmapBoardScreen]) share one budget with every other client
 * behind the same egress IP, and personalized searches never hit the backend's anonymous
 * search cache. A hunt-and-peck typist firing one request per keystroke (~350 ms apart) can
 * exhaust the budget within ~10 seconds, after which the server answers 429 to the whole IP.
 *
 * The screens call [awaitSearchTurn] from their debounced search collector and mark its first
 * emission — the initial load or a re-run after a filter change — as user-initiated via
 * [awaitSearchTurn.bypassThrottle]. Every subsequent emission is a genuine query change and is
 * throttled, even when the text happens to match an earlier query.
 *
 * The clock defaults to the monotonic [SystemClock.elapsedRealtime] so a wall-clock jump can
 * never stretch the intervals; tests inject a fake clock instead. [awaitSearchTurn]'s wait
 * hook is injectable for the same reason.
 */
internal class SearchThrottler(
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val penaltyMs: Long = DEFAULT_PENALTY_MS,
    private val now: () -> Long = { SystemClock.elapsedRealtime() }
) {
    private var lastFiredAt = Long.MIN_VALUE + minIntervalMs
    private var penalizedUntil = Long.MIN_VALUE

    /** Milliseconds to wait before the next automatic search may fire; `0` when it may fire now. */
    fun delayBeforeNext(): Long {
        val t = now()
        if (t < penalizedUntil) return penalizedUntil - t
        return (lastFiredAt + minIntervalMs - t).coerceAtLeast(0L)
    }

    /** Records that a search request is being issued now. */
    fun recordFired() {
        lastFiredAt = now()
    }

    /** Blocks automatic searches for [penaltyMs] after an HTTP 429 rejection. */
    fun penalize() {
        penalizedUntil = now() + penaltyMs
    }

    /**
     * Suspends until an automatic search for [query] may fire, then records it.
     *
     * Waits out the debounce for non-empty queries, then the throttle interval and any 429
     * penalty — re-reading the deadline after every wait, because a concurrent rejection can
     * extend the penalty while we are suspended. With [bypassThrottle] the call returns
     * immediately: the caller determined this load is user-initiated.
     *
     * @param waitFor Sleep hook, injectable so tests run without real time passing.
     */
    suspend fun awaitSearchTurn(
        query: String,
        bypassThrottle: Boolean = false,
        waitFor: suspend (Long) -> Unit = { delay(it) }
    ) {
        if (bypassThrottle) return
        if (query.isNotEmpty()) waitFor(DEBOUNCE_MS)
        var waitMs = delayBeforeNext()
        while (waitMs > 0) {
            waitFor(waitMs)
            waitMs = delayBeforeNext()
        }
        recordFired()
    }

    companion object {
        /** Debounce applied before an automatic search for a non-empty query goes out. */
        const val DEBOUNCE_MS = 350L

        /**
         * Minimum spacing between automatic searches. Sustained typing therefore produces at
         * most one search every 2 s (30/min), matching the backend's per-IP search budget.
         */
        const val DEFAULT_MIN_INTERVAL_MS = 2_000L

        /**
         * Cool-down applied after an HTTP 429. The server window is 60 s, so waiting half of
         * it guarantees a meaningful slice of the budget has recovered before the next try.
         */
        const val DEFAULT_PENALTY_MS = 30_000L
    }
}
