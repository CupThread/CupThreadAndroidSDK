package dev.cupthread.feedback

import dev.cupthread.feedback.internal.ChangelogPager
import dev.cupthread.feedback.internal.HttpRequest
import dev.cupthread.feedback.internal.HttpResponse
import dev.cupthread.feedback.internal.HttpTransport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangelogPagingTest {
    private val appKey = "app_testkey123456"

    private fun client(handler: (HttpRequest) -> HttpResponse): FeedbackClient =
        FeedbackClient(
            config = FeedbackClientConfig(baseUrl = "https://api.example.com", appKey = appKey),
            transport = HttpTransport { handler(it) }
        )

    private fun entry(id: String, date: String = "2026-09-18T12:00:00.000Z"): ChangelogEntry =
        ChangelogEntry(
            id = id,
            title = "Release $id",
            body = "Details for $id",
            versionLabel = "1.0.$id",
            publishedAt = date,
            linkedRequests = emptyList()
        )

    @Test
    fun appendCombinesPagesAndDeduplicatesById() {
        val page1 = listOf(entry("cl-1"), entry("cl-2"))
        val page2 = listOf(entry("cl-2"), entry("cl-3"))

        val appended = ChangelogPager.append(page1, page2)
        assertEquals(listOf("cl-1", "cl-2", "cl-3"), appended.map { it.id })
    }

    @Test
    fun appendHandlesEmptyCollections() {
        val page1 = listOf(entry("cl-1"))
        assertEquals(page1, ChangelogPager.append(page1, emptyList()))
        assertEquals(page1, ChangelogPager.append(emptyList(), page1))
    }

    @Test
    fun pagerLoadsPagesSequentiallyUsingNextCursor() = runTest {
        val requests = mutableListOf<String>()
        val client = client { req ->
            requests.add(req.url)
            when {
                req.url.contains("cursor=page1_cursor") -> HttpResponse(
                    200,
                    """
                    {
                      "entries": [
                        {"id":"cl-3","title":"Release 3","content":"C3","publishedAt":"2026-09-16T12:00:00.000Z","version":"1.0.1"}
                      ],
                      "hasMore": false,
                      "nextCursor": null
                    }
                    """.trimIndent()
                )
                else -> HttpResponse(
                    200,
                    """
                    {
                      "entries": [
                        {"id":"cl-1","title":"Release 1","content":"C1","publishedAt":"2026-09-18T12:00:00.000Z","version":"1.0.3"},
                        {"id":"cl-2","title":"Release 2","content":"C2","publishedAt":"2026-09-17T12:00:00.000Z","version":"1.0.2"}
                      ],
                      "hasMore": true,
                      "nextCursor": "page1_cursor"
                    }
                    """.trimIndent()
                )
            }
        }

        val pager = ChangelogPager(pageSize = 100) { limit, cursor ->
            client.fetchChangelogPage(limit, cursor)
        }

        val first = pager.loadFirstPage()!!
        assertEquals(listOf("cl-1", "cl-2"), first.entries.map { it.id })
        assertTrue(first.hasMore)
        assertEquals("page1_cursor", first.nextCursor)
        assertTrue(pager.hasMore)
        assertTrue(requests[0].contains("limit=100"))
        assertFalse(requests[0].contains("cursor="))

        val second = pager.loadNextPage()
        requireNotNull(second)
        assertEquals(listOf("cl-3"), second.entries.map { it.id })
        assertFalse(second.hasMore)
        assertNull(second.nextCursor)
        assertFalse(pager.hasMore)
        assertTrue(requests[1].contains("cursor=page1_cursor"))

        // Subsequent loadNextPage returns null since hasMore is false
        val third = pager.loadNextPage()
        assertNull(third)
    }

    @Test
    fun emptyPageIsTerminalEvenIfServerReportsHasMoreTrue() = runTest {
        val client = client {
            HttpResponse(
                200,
                """
                {
                  "entries": [],
                  "hasMore": true,
                  "nextCursor": "bad_cursor"
                }
                """.trimIndent()
            )
        }

        val pager = ChangelogPager { limit, cursor ->
            client.fetchChangelogPage(limit, cursor)
        }

        val page = pager.loadFirstPage()!!
        assertTrue(page.entries.isEmpty())
        assertFalse(pager.hasMore)
        assertNull(pager.loadNextPage())
    }

    @Test
    fun resetRestartsPagingFromFirstPage() = runTest {
        var cursorSeen: String? = null
        val client = client { req ->
            val uri = java.net.URI(req.url)
            val queryParams = uri.query.split("&").associate {
                val parts = it.split("=")
                parts[0] to parts.getOrElse(1) { "" }
            }
            cursorSeen = queryParams["cursor"]
            HttpResponse(
                200,
                """
                {
                  "entries": [{"id":"cl-1","title":"R1","body":"","publishedAt":"2026-09-18T12:00:00.000Z"}],
                  "hasMore": true,
                  "nextCursor": "c1"
                }
                """.trimIndent()
            )
        }

        val pager = ChangelogPager { limit, cursor ->
            client.fetchChangelogPage(limit, cursor)
        }

        pager.loadFirstPage()
        assertNull(cursorSeen)

        pager.loadNextPage()
        assertEquals("c1", cursorSeen)

        pager.reset()
        assertFalse(pager.hasMore)

        pager.loadFirstPage()
        assertNull(cursorSeen)
    }

    @Test
    fun nullOrBlankNextCursorEndsPagingEvenIfServerReportsHasMoreTrue() = runTest {
        val client = client {
            HttpResponse(
                200,
                """
                {
                  "entries": [{"id":"cl-1","title":"R1","body":"","publishedAt":"2026-09-18T12:00:00.000Z"}],
                  "hasMore": true,
                  "nextCursor": null
                }
                """.trimIndent()
            )
        }

        val pager = ChangelogPager { limit, cursor ->
            client.fetchChangelogPage(limit, cursor)
        }

        val page = pager.loadFirstPage()!!
        assertEquals(1, page.entries.size)
        assertFalse(pager.hasMore)
        assertNull(pager.loadNextPage())
    }

    @Test
    fun repeatedNextCursorEndsPagingToPreventInfiniteLoops() = runTest {
        val client = client {
            HttpResponse(
                200,
                """
                {
                  "entries": [{"id":"cl-1","title":"R1","body":"","publishedAt":"2026-09-18T12:00:00.000Z"}],
                  "hasMore": true,
                  "nextCursor": "same_cursor"
                }
                """.trimIndent()
            )
        }

        val pager = ChangelogPager { limit, cursor ->
            client.fetchChangelogPage(limit, cursor)
        }

        val first = pager.loadFirstPage()!!
        assertEquals("same_cursor", first.nextCursor)
        assertTrue(pager.hasMore)

        val second = pager.loadNextPage()
        requireNotNull(second)
        assertFalse(pager.hasMore)
        assertNull(pager.loadNextPage())
    }

    /** Legacy deployment payload: `{entries, nextCursor}` without a `hasMore` field. */
    private fun legacyChangelogJson(nextCursorJson: String): String =
        """
        {
          "entries": [
            {"id":"cl-1","title":"Release 1","content":"C1","publishedAt":"2026-09-18T12:00:00.000Z","version":"1.0.3"}
          ],
          "nextCursor": $nextCursorJson
        }
        """.trimIndent()

    @Test
    fun legacyPayloadWithoutHasMoreDerivesTrueFromCursor() = runTest {
        val page = client { HttpResponse(200, legacyChangelogJson("\"c2\"")) }
            .fetchChangelogPage()
        assertTrue(page.hasMore)
        assertEquals("c2", page.nextCursor)
    }

    @Test
    fun legacyPayloadWithNullCursorDerivesFalse() = runTest {
        val page = client { HttpResponse(200, legacyChangelogJson("null")) }
            .fetchChangelogPage()
        assertFalse(page.hasMore)
        assertNull(page.nextCursor)
    }

    @Test
    fun legacyPayloadWithBlankCursorDerivesFalse() = runTest {
        val page = client { HttpResponse(200, legacyChangelogJson("\"   \"")) }
            .fetchChangelogPage()
        assertFalse(page.hasMore)
        assertEquals("   ", page.nextCursor)
    }

    @Test
    fun explicitHasMoreFalseStaysAuthoritativeOverStaleCursor() = runTest {
        val page = client {
            HttpResponse(
                200,
                """
                {
                  "entries": [
                    {"id":"cl-1","title":"Release 1","content":"C1","publishedAt":"2026-09-18T12:00:00.000Z","version":"1.0.3"}
                  ],
                  "hasMore": false,
                  "nextCursor": "stale_cursor"
                }
                """.trimIndent()
            )
        }.fetchChangelogPage()
        assertFalse(page.hasMore)
        assertEquals("stale_cursor", page.nextCursor)
    }

    @Test
    fun legacyFirstPageWithCursorTriggersSecondFetch() = runTest {
        val requests = mutableListOf<String>()
        val client = client { req ->
            requests.add(req.url)
            if (req.url.contains("cursor=legacy_c2")) {
                HttpResponse(
                    200,
                    """
                    {
                      "entries": [
                        {"id":"cl-2","title":"Release 2","content":"C2","publishedAt":"2026-09-17T12:00:00.000Z","version":"1.0.2"}
                      ],
                      "nextCursor": null
                    }
                    """.trimIndent()
                )
            } else {
                HttpResponse(
                    200,
                    """
                    {
                      "entries": [
                        {"id":"cl-1","title":"Release 1","content":"C1","publishedAt":"2026-09-18T12:00:00.000Z","version":"1.0.3"}
                      ],
                      "nextCursor": "legacy_c2"
                    }
                    """.trimIndent()
                )
            }
        }

        val pager = ChangelogPager(pageSize = 100) { limit, cursor ->
            client.fetchChangelogPage(limit, cursor)
        }

        val first = pager.loadFirstPage()!!
        assertTrue(first.hasMore)
        assertTrue(pager.hasMore)

        val second = pager.loadNextPage()
        requireNotNull(second)
        assertEquals(listOf("cl-2"), second.entries.map { it.id })
        assertFalse(second.hasMore)
        assertFalse(pager.hasMore)
        assertTrue(requests[1].contains("cursor=legacy_c2"))
    }
}
