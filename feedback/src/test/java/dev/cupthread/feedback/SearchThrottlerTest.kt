package dev.cupthread.feedback

import dev.cupthread.feedback.internal.SearchThrottler
import kotlin.math.floor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun shouldThrottleOnlyForChangedQueries() {
        val throttler = SearchThrottler(now = { 0L })
        assertTrue(throttler.shouldThrottle("dark")) // never fired yet
        throttler.recordFired("dark")
        assertFalse(throttler.shouldThrottle("dark")) // unchanged re-run: user-initiated
        assertTrue(throttler.shouldThrottle("dark mode"))
    }

    @Test
    fun awaitSearchTurnDebouncesThenRecordsTheQuery() = runTest {
        var t = 0L
        val waits = mutableListOf<Long>()
        val throttler = SearchThrottler(now = { t })

        throttler.awaitSearchTurn("dark") { ms -> waits += ms; t += ms }
        assertEquals(listOf(SearchThrottler.DEBOUNCE_MS), waits)
        assertEquals("dark", lastFiredQueryOf(throttler))

        // An immediate re-run of the same query is user-initiated: no waits, no re-record.
        throttler.awaitSearchTurn("dark") { ms -> waits += ms; t += ms }
        assertEquals(listOf(SearchThrottler.DEBOUNCE_MS), waits)
    }

    @Test
    fun awaitSearchTurnWaitsOutAPenaltyExtendedDuringTheWait() = runTest {
        var t = 0L
        val waits = mutableListOf<Long>()
        val throttler = SearchThrottler(now = { t })
        throttler.awaitSearchTurn("dark") { ms -> waits += ms; t += ms } // fires at t = 350

        // HTTP 429 at t = 350: everything is blocked until t = 30_350 ...
        throttler.penalize()
        // ... and another 429 lands while a different query is suspended in the interval wait
        // (not during its debounce), which must extend the deadline instead of firing into
        // the exhausted window.
        var extended = false
        throttler.awaitSearchTurn("dark mode") { ms ->
            waits += ms
            t += ms
            if (ms >= 1_000 && !extended) {
                extended = true
                throttler.penalize() // now blocked until t = 60_350
            }
        }

        assertEquals(listOf(350L, 350L, 29_650L, 30_000L), waits)
    }

    /** Reads the recorded query via a fresh shouldThrottle probe (state is otherwise private). */
    private fun lastFiredQueryOf(throttler: SearchThrottler): String =
        listOf("dark", "dark mode", "").first { !throttler.shouldThrottle(it) }
}
