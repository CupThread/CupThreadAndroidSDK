package dev.cupthread.feedback

import dev.cupthread.feedback.internal.FeatureRequestPageRequest
import dev.cupthread.feedback.internal.FeatureRequestPager
import dev.cupthread.feedback.internal.HttpRequest
import dev.cupthread.feedback.internal.HttpResponse
import dev.cupthread.feedback.internal.HttpTransport
import dev.cupthread.feedback.internal.parseListFeatureRequests
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureRequestPagingTest {
    private val appKey = "app_testkey123456"

    private fun client(handler: (HttpRequest) -> HttpResponse): FeedbackClient =
        FeedbackClient(
            config = FeedbackClientConfig(baseUrl = "https://api.example.com", appKey = appKey),
            transport = HttpTransport { handler(it) }
        )

    private fun itemJson(id: String): String = """
        {"id":"$id","appId":"app-1","title":"Request $id","description":"","status":"planned",
         "columnId":"col-1","columnSlug":"planned","columnName":"Planned","approved":true,
         "voteCount":1,"hasVoted":false,"isOwnRequest":false,
         "createdAt":"2026-01-01T00:00:00.000Z","updatedAt":"2026-01-01T00:00:00.000Z"}
    """.trimIndent()

    private fun ids(prefix: String, count: Int): List<String> =
        (1..count).map { "$prefix-$it" }

    private fun pageJson(
        ids: List<String>,
        total: Int,
        hasMore: Boolean,
        nextCursor: String?
    ): String {
        val requests = ids.joinToString(",") { itemJson(it) }
        val cursor = if (nextCursor == null) "null" else "\"$nextCursor\""
        return """{"requests":[$requests],"total":$total,"hasMore":$hasMore,"nextCursor":$cursor}"""
    }

    private fun item(id: String, voted: Boolean = false): FeatureRequestItem = FeatureRequestItem(
        id = id,
        appId = "app-1",
        title = "Request $id",
        description = "",
        status = "planned",
        columnId = "col-1",
        columnSlug = "planned",
        columnName = "Planned",
        versionId = null,
        versionLabel = null,
        releasedVersion = null,
        requesterName = null,
        approved = true,
        voteCount = 1,
        hasVoted = voted,
        isOwnRequest = false,
        createdAt = "2026-01-01T00:00:00.000Z",
        updatedAt = "2026-01-01T00:00:00.000Z"
    )

    @Test
    fun fetchFeatureRequestsSendsCursorAndDecodesPagingFields() = runTest {
        var url: String? = null
        val result = client { request ->
            url = request.url
            HttpResponse(200, pageJson(ids("fr", 1), total = 120, hasMore = true, nextCursor = "cur-2"))
        }.fetchFeatureRequests(userToken = "token", limit = 50, cursor = "cur-1")

        assertTrue(url!!.contains("/api/v1/feature-requests?"))
        assertTrue(url.contains("limit=50"))
        assertTrue(url.contains("cursor=cur-1"))
        assertEquals(120, result.total)
        assertTrue(result.hasMore)
        assertEquals("cur-2", result.nextCursor)
    }

    @Test
    fun fetchFeatureRequestsOmitsCursorWhenNotPaging() = runTest {
        var url: String? = null
        client { request ->
            url = request.url
            HttpResponse(200, pageJson(emptyList(), total = 0, hasMore = false, nextCursor = null))
        }.fetchFeatureRequests(userToken = "token")

        assertFalse(url!!.contains("cursor="))
    }

    @Test
    fun legacyResponseDerivesHasMoreFromOffsetAndTotal() = runTest {
        // Deployments that predate keyset paging answer {requests,total} only.
        val firstJson = """{"requests":[${ids("a", 50).joinToString(",") { itemJson(it) }}],"total":120}"""
        val first = client { HttpResponse(200, firstJson) }
            .fetchFeatureRequests(userToken = "token", limit = 50, offset = 0)
        assertTrue(first.hasMore)
        assertNull(first.nextCursor)

        val lastJson = """{"requests":[${ids("c", 20).joinToString(",") { itemJson(it) }}],"total":120}"""
        val last = client { HttpResponse(200, lastJson) }
            .fetchFeatureRequests(userToken = "token", limit = 50, offset = 100)
        assertFalse(last.hasMore)
    }

    @Test
    fun pagerLoadsEveryPageWithoutDuplicates() = runTest {
        val requested = mutableListOf<FeatureRequestPageRequest>()
        val pages = listOf(
            pageJson(ids("a", 50), total = 120, hasMore = true, nextCursor = "cur-2"),
            pageJson(ids("b", 50), total = 120, hasMore = true, nextCursor = "cur-3"),
            pageJson(ids("c", 20), total = 120, hasMore = false, nextCursor = null)
        )
        var served = 0
        val pager = FeatureRequestPager(pageSize = 50) { request ->
            requested += request
            parseListFeatureRequests(JSONObject(pages[served++]), request.offset)
        }

        var items = pager.loadFirstPage().requests
        while (pager.hasMore) {
            val page = pager.loadNextPage() ?: break
            items = FeatureRequestPager.append(items, page.requests)
        }

        assertEquals(120, items.size)
        assertEquals(120, items.map { it.id }.toSet().size)
        assertEquals(listOf(0, 50, 100), requested.map { it.offset })
        assertEquals(listOf(null, "cur-2", "cur-3"), requested.map { it.cursor })
        assertEquals(120, pager.total)
        assertFalse(pager.hasMore)
        assertNull(pager.loadNextPage())
        assertEquals(3, requested.size)
    }

    @Test
    fun appendDeduplicatesIdsAndKeepsLoadedObjects() {
        val first = listOf(item("fr-1", voted = true), item("fr-2"))
        val page = listOf(item("fr-2"), item("fr-3"))

        val merged = FeatureRequestPager.append(first, page)

        assertEquals(listOf("fr-1", "fr-2", "fr-3"), merged.map { it.id })
        assertSame(first[0], merged[0])
        assertSame(first[1], merged[1])
        assertTrue(merged[0].hasVoted)
    }

    @Test
    fun appendHandlesDuplicateIdsInsideOnePage() {
        val merged = FeatureRequestPager.append(
            existing = listOf(item("fr-1")),
            page = listOf(item("fr-1"), item("fr-1"), item("fr-2"))
        )

        assertEquals(listOf("fr-1", "fr-2"), merged.map { it.id })
    }

    @Test
    fun resetRestartsFromTheFirstPage() = runTest {
        val requested = mutableListOf<FeatureRequestPageRequest>()
        val pages = listOf(
            pageJson(ids("a", 50), total = 120, hasMore = true, nextCursor = "cur-2"),
            pageJson(ids("b", 50), total = 120, hasMore = true, nextCursor = "cur-3")
        )
        var served = 0
        val pager = FeatureRequestPager(pageSize = 50) { request ->
            requested += request
            parseListFeatureRequests(JSONObject(pages[served++]), request.offset)
        }

        pager.loadFirstPage()
        pager.loadNextPage()
        assertEquals(50, requested.last().offset)
        assertEquals("cur-2", requested.last().cursor)

        served = 0
        pager.loadFirstPage()
        assertEquals(0, requested.last().offset)
        assertNull(requested.last().cursor)
        assertEquals(120, pager.total)
    }

    @Test
    fun emptyPageStopsPagingEvenWhenHasMoreIsReported() = runTest {
        val pager = FeatureRequestPager(pageSize = 50) { request ->
            parseListFeatureRequests(
                JSONObject(pageJson(emptyList(), total = 120, hasMore = true, nextCursor = "cur-x")),
                request.offset
            )
        }

        val first = pager.loadFirstPage()

        assertTrue(first.requests.isEmpty())
        assertFalse(pager.hasMore)
        assertNull(pager.loadNextPage())
    }

    @Test
    fun singlePageResultDoesNotRequestMore() = runTest {
        var requests = 0
        val pager = FeatureRequestPager(pageSize = 50) { request ->
            requests++
            parseListFeatureRequests(
                JSONObject(pageJson(ids("a", 10), total = 10, hasMore = false, nextCursor = null)),
                request.offset
            )
        }

        val items = pager.loadFirstPage().requests

        assertEquals(10, items.size)
        assertFalse(pager.hasMore)
        assertNull(pager.loadNextPage())
        assertEquals(1, requests)
    }
}
