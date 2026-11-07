package dev.cupthread.feedback.ui

import dev.cupthread.feedback.FeatureRequestItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for the vote revert/reconcile helpers shared by [FeatureRequestsScreen] and
 * [RoadmapBoardScreen] (issue #178 and its review follow-ups):
 *
 * - [revertedItems] must revert only the row instance the optimistic update created, must
 *   leave a refetched row with genuinely newer vote state untouched, and must reconcile a
 *   stale refetch that still carries the pre-revert vote state by applying the tap-time
 *   vote values onto the refetched row (keeping its newer fields).
 * - [revertedSelection] must restore vote values only while the sheet still shows the
 *   optimistic selection instance.
 * - [reconciledSelection] must apply a successful vote's returned state only while the
 *   selection still shows the voted request, and never resurrect a dismissed sheet.
 */
class VoteRevertHelpersTest {

    private fun item(
        id: String = "fr-1",
        title: String = "Alpha request",
        description: String = "first",
        voteCount: Int = 10,
        hasVoted: Boolean = false,
        approved: Boolean = true
    ): FeatureRequestItem = FeatureRequestItem(
        id = id,
        appId = "app-1",
        title = title,
        description = description,
        status = "open",
        columnId = null,
        columnSlug = null,
        columnName = null,
        versionId = null,
        versionLabel = null,
        releasedVersion = null,
        requesterName = null,
        approved = approved,
        voteCount = voteCount,
        hasVoted = hasVoted,
        isOwnRequest = false,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z"
    )

    // revertedItems -------------------------------------------------------------------------

    @Test
    fun `revertedItems replaces only the optimistic instance with the tap-time row`() {
        val previous = item(voteCount = 10, hasVoted = false)
        val optimistic = previous.withVoteState(voted = true, count = 11)
        val otherBefore = item(id = "fr-2", title = "Beta request")
        val otherAfter = item(id = "fr-2", title = "Beta request", voteCount = 20)
        val items = listOf(otherBefore, optimistic, otherAfter)

        val reverted = revertedItems(items, previous, optimistic)

        assertSame(previous, reverted[1])
        // Neighbouring rows keep their instances, including newer data refetched elsewhere.
        assertSame(otherBefore, reverted[0])
        assertSame(otherAfter, reverted[2])
        assertEquals(3, reverted.size)
    }

    @Test
    fun `revertedItems keeps a refetched row whose vote state already moved past the revert`() {
        val previous = item(voteCount = 10, hasVoted = false)
        val optimistic = previous.withVoteState(voted = true, count = 11)
        // A refresh landed after the upvote was applied server-side: fresh count from other
        // users' votes, so it matches neither the tap-time nor the optimistic values.
        val refetched = item(voteCount = 13, hasVoted = true, description = "refetched")
        val items = listOf(refetched)

        val reverted = revertedItems(items, previous, optimistic)

        // The fresh server row survives whole — the revert must not discard newer data.
        assertSame(refetched, reverted[0])
        assertEquals(13, reverted[0].voteCount)
        assertTrue(reverted[0].hasVoted)
        assertEquals("refetched", reverted[0].description)
    }

    @Test
    fun `revertedItems reconciles a stale refetch that still carries the optimistic vote state`() {
        // The upvote succeeded; the row now shows (voted, 11). The user taps to unvote:
        // `previous` is the upvoted row, the optimistic flip is the unvoted (10).
        val previous = item(voteCount = 11, hasVoted = true)
        val optimistic = previous.withVoteState(voted = false, count = 10)
        // A refresh snapshotted BEFORE the upvote was applied lands while the unvote is in
        // flight: it replaced the optimistic instance and carries the optimistic (pre-revert)
        // vote values on a different instance.
        val staleRefetch = item(voteCount = 10, hasVoted = false, description = "stale snapshot")
        val items = listOf(staleRefetch)

        val reverted = revertedItems(items, previous, optimistic)

        // The failed unvote restores the successful upvote's vote state while the refetched
        // row's other fields survive.
        assertNotSame(staleRefetch, reverted[0])
        assertEquals("stale snapshot", reverted[0].description)
        assertTrue(reverted[0].hasVoted)
        assertEquals(11, reverted[0].voteCount)
    }

    @Test
    fun `revertedItems reconciles only rows of the same request id`() {
        val previous = item(id = "fr-1", voteCount = 10, hasVoted = false)
        val optimistic = previous.withVoteState(voted = true, count = 11)
        // Same vote values as the optimistic row, but a different request: never touched.
        val otherRequest = item(id = "fr-2", title = "Beta request", voteCount = 11, hasVoted = true)
        val items = listOf(otherRequest)

        val reverted = revertedItems(items, previous, optimistic)

        assertSame(otherRequest, reverted[0])
        assertEquals(11, reverted[0].voteCount)
    }

    @Test
    fun `revertedItems returns the list unchanged when no optimistic row exists`() {
        val previous = item(voteCount = 10, hasVoted = false)
        val row = item(voteCount = 12, hasVoted = false)
        val items = listOf(row)

        assertSame(items, revertedItems(items, previous, null))
    }

    // revertedSelection ---------------------------------------------------------------------

    @Test
    fun `revertedSelection restores tap-time vote values while the sheet shows the optimistic instance`() {
        val previous = item(voteCount = 10, hasVoted = false)
        val optimisticSelection = previous.withVoteState(voted = true, count = 11)

        val reverted = revertedSelection(optimisticSelection, optimisticSelection, previous)

        assertFalse(reverted!!.hasVoted)
        assertEquals(10, reverted.voteCount)
    }

    @Test
    fun `revertedSelection leaves a selection refetched to a different instance untouched`() {
        val previous = item(voteCount = 10, hasVoted = false)
        val optimisticSelection = previous.withVoteState(voted = true, count = 11)
        val refetchedSelection = item(voteCount = 12, hasVoted = true, description = "refetched")

        val reverted = revertedSelection(refetchedSelection, optimisticSelection, previous)

        assertSame(refetchedSelection, reverted)
        assertEquals(12, reverted!!.voteCount)
    }

    @Test
    fun `revertedSelection keeps a dismissed sheet dismissed`() {
        val previous = item(voteCount = 10, hasVoted = false)
        val optimisticSelection = previous.withVoteState(voted = true, count = 11)

        assertEquals(null, revertedSelection(null, optimisticSelection, previous))
    }

    // reconciledSelection -------------------------------------------------------------------

    @Test
    fun `reconciledSelection applies the returned vote state onto the current selection`() {
        val selection = item(voteCount = 12, hasVoted = false, description = "refetched")

        val reconciled = reconciledSelection(selection, votedId = "fr-1", voted = true, voteCount = 13)

        assertNotSame(selection, reconciled)
        assertTrue(reconciled!!.hasVoted)
        assertEquals(13, reconciled.voteCount)
        // Fresh non-vote fields of the current selection survive.
        assertEquals("refetched", reconciled.description)
        assertEquals(12, selection.voteCount)
    }

    @Test
    fun `reconciledSelection ignores a selection reopened on another request`() {
        val other = item(id = "fr-2", title = "Beta request")

        val reconciled = reconciledSelection(other, votedId = "fr-1", voted = true, voteCount = 11)

        assertSame(other, reconciled)
        assertFalse(reconciled!!.hasVoted)
    }

    @Test
    fun `reconciledSelection never resurrects a dismissed sheet`() {
        assertEquals(null, reconciledSelection(null, votedId = "fr-1", voted = true, voteCount = 11))
    }
}
