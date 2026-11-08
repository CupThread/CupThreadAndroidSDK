package dev.cupthread.feedback.ui

import dev.cupthread.feedback.BoardColumn
import dev.cupthread.feedback.BoardColumnKind
import dev.cupthread.feedback.FeatureRequestItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for [makeGroups], the column-grouping policy behind [RoadmapBoardScreen]
 * (issue #217):
 *
 * - Requests whose [FeatureRequestItem.columnId] matches a [BoardColumn.id] must be mapped into
 *   that column's [RoadmapGroup].
 * - Requests whose [FeatureRequestItem.columnId] is `null` or does not match any column in [columns]
 *   (e.g. archived, deleted, or unlisted columns, or requests loaded before columns are known)
 *   must be collected into a synthetic "Other" fallback group ([RoadmapGroup.column] == `null`)
 *   instead of being silently discarded.
 */
class RoadmapBoardGroupsTest {

    private fun column(
        id: String,
        name: String,
        position: Int = 0
    ) = BoardColumn(
        id = id,
        appId = "app-1",
        name = name,
        slug = name.lowercase().replace(" ", "-"),
        position = position,
        isVisible = true,
        isSystem = false,
        kind = BoardColumnKind.NORMAL,
        createdAt = "2026-01-01T00:00:00.000Z",
        updatedAt = "2026-01-01T00:00:00.000Z"
    )

    private fun request(
        id: String,
        columnId: String? = null,
        title: String = "Feature $id"
    ) = FeatureRequestItem(
        id = id,
        appId = "app-1",
        title = title,
        description = "Description for $id",
        status = "open",
        columnId = columnId,
        columnSlug = null,
        columnName = null,
        versionId = null,
        versionLabel = null,
        releasedVersion = null,
        requesterName = "Alice",
        approved = true,
        voteCount = 5,
        hasVoted = false,
        isOwnRequest = false,
        createdAt = "2026-02-01T12:00:00.000Z",
        updatedAt = "2026-02-01T12:00:00.000Z"
    )

    @Test
    fun requestsWithMatchingColumnIdArePlacedInTheirRespectiveGroups() {
        val col1 = column("col-planned", "Planned", position = 0)
        val col2 = column("col-in-progress", "In Progress", position = 1)
        val columns = listOf(col1, col2)

        val req1 = request("req-1", columnId = "col-planned")
        val req2 = request("req-2", columnId = "col-in-progress")
        val req3 = request("req-3", columnId = "col-planned")

        val groups = makeGroups(columns, listOf(req1, req2, req3))

        assertEquals(2, groups.size)
        assertEquals(col1, groups[0].column)
        assertEquals(listOf(req1, req3), groups[0].requests)
        assertEquals(col2, groups[1].column)
        assertEquals(listOf(req2), groups[1].requests)
    }

    @Test
    fun requestsWithNullColumnIdArePlacedInFallbackOtherGroup() {
        val col1 = column("col-planned", "Planned")
        val columns = listOf(col1)

        val req1 = request("req-1", columnId = "col-planned")
        val reqNull = request("req-null", columnId = null)

        val groups = makeGroups(columns, listOf(req1, reqNull))

        assertEquals(2, groups.size)
        assertEquals(col1, groups[0].column)
        assertEquals(listOf(req1), groups[0].requests)

        assertNull(groups[1].column)
        assertEquals("uncategorized", groups[1].id)
        assertEquals("Other", groups[1].name)
        assertEquals(listOf(reqNull), groups[1].requests)
    }

    @Test
    fun requestsWithUnknownOrDeletedColumnIdArePlacedInFallbackOtherGroup() {
        // Issue #217: feature requests assigned to deleted or unlisted columns must not be dropped.
        val colActive = column("col-active", "Active")
        val columns = listOf(colActive)

        val reqActive = request("req-active", columnId = "col-active")
        val reqDeletedCol = request("req-orphaned", columnId = "col-deleted-from-console")

        val groups = makeGroups(columns, listOf(reqActive, reqDeletedCol))

        assertEquals(2, groups.size)
        assertEquals(colActive, groups[0].column)
        assertEquals(listOf(reqActive), groups[0].requests)

        assertNull(groups[1].column)
        assertEquals(listOf(reqDeletedCol), groups[1].requests)
    }

    @Test
    fun requestsWithBothNullAndUnknownColumnIdsAreAggregatedInFallbackGroupPreservingOrder() {
        val colActive = column("col-active", "Active")
        val columns = listOf(colActive)

        val req1 = request("req-1", columnId = null)
        val reqActive = request("req-active", columnId = "col-active")
        val req2 = request("req-2", columnId = "col-archived-99")
        val req3 = request("req-3", columnId = null)

        val groups = makeGroups(columns, listOf(req1, reqActive, req2, req3))

        assertEquals(2, groups.size)
        assertEquals(listOf(reqActive), groups[0].requests)

        assertNull(groups[1].column)
        assertEquals(listOf(req1, req2, req3), groups[1].requests)
    }

    @Test
    fun emptyColumnsPlacesAllRequestsInFallbackGroupWithoutDataLoss() {
        val req1 = request("req-1", columnId = "col-unfetched")
        val req2 = request("req-2", columnId = null)

        val groups = makeGroups(emptyList(), listOf(req1, req2))

        assertEquals(1, groups.size)
        assertNull(groups[0].column)
        assertEquals("uncategorized", groups[0].id)
        assertEquals("Other", groups[0].name)
        assertEquals(listOf(req1, req2), groups[0].requests)
    }

    @Test
    fun emptyRequestsProducesColumnsWithNoFallbackGroup() {
        val col = column("col-1", "Stage")
        val groups = makeGroups(listOf(col), emptyList())

        assertEquals(1, groups.size)
        assertEquals(col, groups[0].column)
        assertTrue(groups[0].requests.isEmpty())
    }

    @Test
    fun bothColumnsAndRequestsEmptyProducesEmptyGroups() {
        val groups = makeGroups(emptyList(), emptyList())
        assertTrue(groups.isEmpty())
    }
}
