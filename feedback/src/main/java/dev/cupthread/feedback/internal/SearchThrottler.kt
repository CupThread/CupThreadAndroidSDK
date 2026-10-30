package dev.cupthread.feedback.internal

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
 * The screens call [delayBeforeNext] right before firing an automatic search, [recordFired]
 * when the request goes out, and [penalize] when the server answers HTTP 429. After a penalty
 * the next automatic search waits [penaltyMs], letting the exhausted window drain instead of
 * retrying into it. User-initiated loads (initial load, pull-to-refresh, filter changes) do
 * not consult the throttler.
 *
 * The clock is injectable so JVM unit tests can simulate typing cadence without real delays.
 */
internal class SearchThrottler(
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val penaltyMs: Long = DEFAULT_PENALTY_MS,
    private val now: () -> Long = { System.currentTimeMillis() }
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

    companion object {
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
