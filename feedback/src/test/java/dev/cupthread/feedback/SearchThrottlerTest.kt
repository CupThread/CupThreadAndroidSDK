package dev.cupthread.feedback

import dev.cupthread.feedback.internal.SearchThrottler
import kotlin.math.floor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchThrottlerTest {

    @Test
    fun firstSearchFiresImmediately() {
        var t = 1_000L
        val throttler = SearchThrottler(now = { t })
        assertEquals(0L, throttler.delayBeforeNext())
    }

    @Test
    fun rapidSearchesWaitForTheMinimumInterval() {
        var t = 1_000L
        val throttler = SearchThrottler(now = { t })
        throttler.recordFired()
        t += 500
        assertEquals(1_500L, throttler.delayBeforeNext())
        t += 1_499
        assertEquals(1L, throttler.delayBeforeNext())
        t += 1
        assertEquals(0L, throttler.delayBeforeNext())
    }

    @Test
    fun sustainedTypingNeverExceedsTheSharedSearchBudget() {
        // Simulates the screens' behavior: one debounced search attempt per 350 ms of typing.
        // Automatic searches must space out to at most one per minIntervalMs so sustained
        // typing stays within the server's 30 searches / 60 s per-IP budget (issue #23).
        var t = 0L
        val throttler = SearchThrottler(now = { t })
        var fired = 0
        val keystrokes = 600 // 600 x 350 ms = 3.5 minutes of continuous typing
        repeat(keystrokes) {
            t += 350
            if (throttler.delayBeforeNext() == 0L) {
                throttler.recordFired()
                fired++
            }
        }
        val minutes = keystrokes * 350.0 / 60_000.0
        assertTrue("fired=$fired exceeds budget", fired <= floor(minutes * 30) + 1)
        assertTrue("fired=$fired over-throttles", fired >= floor(minutes * 30) - 5)
    }

    @Test
    fun rateLimitPenaltyBlocksAutomaticSearchesForTheCoolDown() {
        var t = 1_000L
        val throttler = SearchThrottler(now = { t })
        throttler.recordFired()
        t += 5_000
        throttler.penalize()
        // The penalty dominates the plain interval: a full cool-down from the moment of the 429.
        assertEquals(30_000L, throttler.delayBeforeNext())
        t += 29_999
        assertEquals(1L, throttler.delayBeforeNext())
        t += 1
        assertEquals(0L, throttler.delayBeforeNext())
    }
}
