package dev.cupthread.feedback

import dev.cupthread.feedback.internal.HttpRequest
import dev.cupthread.feedback.internal.HttpResponse
import dev.cupthread.feedback.internal.HttpTransport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CommentsPagingTest {
    private val appKey = "app_testkey123456"

    private fun client(handler: (HttpRequest) -> HttpResponse): FeedbackClient =
        FeedbackClient(
            config = FeedbackClientConfig(baseUrl = "https://api.example.com", appKey = appKey),
            transport = HttpTransport { handler(it) }
        )

    private fun commentJson(id: String, frId: String = "fr-100", author: String = "User $id"): String = """
        {
          "id": "$id",
          "featureRequestId": "$frId",
          "authorName": "$author",
          "authorEmail": null,
          "authorAvatarUrl": "https://example.com/avatar-$id.png",
          "authorClerkId": "u_$id",
          "body": "Comment text $id",
          "parentId": null,
          "replyToClerkId": null,
          "replyToAuthorName": null,
          "isHidden": false,
          "createdAt": "2026-03-01T12:00:00.000Z"
        }
    """.trimIndent()

    private fun pageJson(
        commentIds: List<String>,
        total: Int,
        hasMore: Boolean,
        nextCursor: String?
    ): String {
        val comments = commentIds.joinToString(",") { commentJson(it) }
        val cursor = if (nextCursor == null) "null" else "\"$nextCursor\""
        return """{"comments":[$comments],"total":$total,"hasMore":$hasMore,"nextCursor":$cursor}"""
    }

    @Test
    fun fetchCommentsPageSendsLimitAndCursorAndDecodesMetadata() = runTest {
        var requestedUrl: String? = null
        val result = client { request ->
            requestedUrl = request.url
            HttpResponse(200, pageJson(listOf("c-1", "c-2"), total = 42, hasMore = true, nextCursor = "cur_page2"))
        }.fetchCommentsPage("fr-100", limit = 50, cursor = "cur_page1")

        assertNotNull(requestedUrl)
        assertTrue(requestedUrl!!.contains("/api/v1/feature-requests/fr-100/comments?"))
        assertTrue(requestedUrl!!.contains("limit=50"))
        assertTrue(requestedUrl!!.contains("cursor=cur_page1"))
        assertEquals(2, result.comments.size)
        assertEquals(42, result.total)
        assertTrue(result.hasMore)
        assertEquals("cur_page2", result.nextCursor)
    }

    @Test
    fun fetchCommentsPageOmitsQueryParametersWhenLimitIsDefaultAndCursorIsNull() = runTest {
        var requestedUrl: String? = null
        val result = client { request ->
            requestedUrl = request.url
            HttpResponse(200, pageJson(listOf("c-1"), total = 1, hasMore = false, nextCursor = null))
        }.fetchCommentsPage("fr-100")

        assertEquals("https://api.example.com/api/v1/feature-requests/fr-100/comments", requestedUrl)
        assertEquals(1, result.comments.size)
        assertEquals(1, result.total)
        assertFalse(result.hasMore)
        assertNull(result.nextCursor)
    }

    @Test
    fun fetchCommentsPageClampsLimitBetweenOneAndTwoHundred() = runTest {
        val urls = mutableListOf<String>()
        val c = client { req ->
            urls.add(req.url)
            HttpResponse(200, pageJson(emptyList(), total = 0, hasMore = false, nextCursor = null))
        }

        // Limit below 1 clamps to 1
        c.fetchCommentsPage("fr-100", limit = 0)
        assertTrue(urls[0].contains("limit=1"))

        // Limit above 200 clamps to 200 (which is default, so query param is omitted when cursor is null)
        c.fetchCommentsPage("fr-100", limit = 500)
        assertFalse(urls[1].contains("limit=500"))

        // Limit above 200 with cursor clamps to 200 and includes limit=200
        c.fetchCommentsPage("fr-100", limit = 500, cursor = "c_next")
        assertTrue(urls[2].contains("limit=200"))
        assertTrue(urls[2].contains("cursor=c_next"))
    }

    @Test
    fun fetchCommentsPageValidatesFeatureRequestIdIsNotBlank() = runTest {
        val c = client { HttpResponse(200, "{}") }

        try {
            c.fetchCommentsPage("   ")
            fail("Expected IllegalArgumentException for blank featureRequestId")
        } catch (ex: IllegalArgumentException) {
            assertTrue(ex.message.orEmpty().contains("featureRequestId cannot be blank"))
        }
    }

    @Test
    fun fetchCommentsPageSurfacesRateLimitErrorAsTypedException() = runTest {
        val c = client {
            HttpResponse(429, """{"error":"Too many requests. Please try again shortly."}""")
        }

        try {
            c.fetchCommentsPage("fr-100")
            fail("Expected FeedbackException.RateLimited")
        } catch (ex: FeedbackException.RateLimited) {
            assertTrue(ex.serverMessage?.contains("Too many requests") == true)
        }
    }

    @Test
    fun fetchCommentsPageSurfacesHttp400InvalidCursorAsUnexpectedStatus() = runTest {
        val c = client {
            HttpResponse(400, """{"error":"Invalid cursor"}""")
        }

        try {
            c.fetchCommentsPage("fr-100", cursor = "malformed_cursor")
            fail("Expected FeedbackException.UnexpectedStatus")
        } catch (ex: FeedbackException.UnexpectedStatus) {
            assertEquals(400, ex.code)
            assertTrue(ex.serverMessage.contains("Invalid cursor"))
        }
    }

    @Test
    fun fetchCommentsWalksAllPagesUntilHasMoreIsFalse() = runTest {
        val requestedUrls = mutableListOf<String>()
        val c = client { req ->
            requestedUrls.add(req.url)
            when {
                req.url.contains("cursor=cur_page2") -> {
                    HttpResponse(200, pageJson(listOf("c-3"), total = 3, hasMore = false, nextCursor = null))
                }
                req.url.contains("cursor=cur_page1") -> {
                    HttpResponse(200, pageJson(listOf("c-2"), total = 3, hasMore = true, nextCursor = "cur_page2"))
                }
                else -> {
                    HttpResponse(200, pageJson(listOf("c-1"), total = 3, hasMore = true, nextCursor = "cur_page1"))
                }
            }
        }

        val all = c.fetchComments("fr-100")

        assertEquals(3, all.size)
        assertEquals(listOf("c-1", "c-2", "c-3"), all.map { it.id })
        assertEquals(3, requestedUrls.size)
        assertFalse(requestedUrls[0].contains("cursor="))
        assertTrue(requestedUrls[1].contains("cursor=cur_page1"))
        assertTrue(requestedUrls[2].contains("cursor=cur_page2"))
    }

    @Test
    fun fetchCommentsHandlesLegacyResponsesWithoutPagingMetadata() = runTest {
        val legacyJson = """
            {
              "comments": [
                ${commentJson("c-1")},
                ${commentJson("c-2")}
              ]
            }
        """.trimIndent()

        val all = client {
            HttpResponse(200, legacyJson)
        }.fetchComments("fr-100")

        assertEquals(2, all.size)
        assertEquals(listOf("c-1", "c-2"), all.map { it.id })
    }

    @Test
    fun fetchCommentsThrowsInvalidResponseIfServerReturnsRepeatedCursor() = runTest {
        var callCount = 0
        val c = client {
            callCount++
            HttpResponse(200, pageJson(listOf("c-$callCount"), total = 10, hasMore = true, nextCursor = "stuck_cursor"))
        }

        try {
            c.fetchComments("fr-100")
            fail("Expected FeedbackException.InvalidResponse on repeated cursor")
        } catch (ex: FeedbackException.InvalidResponse) {
            assertTrue(ex.cause is IllegalStateException)
            assertTrue(ex.cause?.message.orEmpty().contains("repeated cursor"))
        }
        assertEquals(2, callCount)
    }

    @Test
    fun fetchCommentsThrowsInvalidResponseIfCommentsArrayIsEmptyEvenWhenHasMoreIsTrue() = runTest {
        var callCount = 0
        val c = client {
            callCount++
            HttpResponse(200, pageJson(emptyList(), total = 5, hasMore = true, nextCursor = "next_cur"))
        }

        try {
            c.fetchComments("fr-100")
            fail("Expected FeedbackException.InvalidResponse on empty page with hasMore=true")
        } catch (ex: FeedbackException.InvalidResponse) {
            assertTrue(ex.cause is IllegalStateException)
            assertTrue(ex.cause?.message.orEmpty().contains("empty page"))
        }
        assertEquals(1, callCount)
    }

    @Test
    fun fetchCommentsThrowsInvalidResponseIfHasMoreIsTrueButNextCursorIsMissing() = runTest {
        var callCount = 0
        val c = client {
            callCount++
            HttpResponse(200, pageJson(listOf("c-1"), total = 5, hasMore = true, nextCursor = null))
        }

        try {
            c.fetchComments("fr-100")
            fail("Expected FeedbackException.InvalidResponse on missing cursor with hasMore=true")
        } catch (ex: FeedbackException.InvalidResponse) {
            assertTrue(ex.cause is IllegalStateException)
            assertTrue(ex.cause?.message.orEmpty().contains("nextCursor is missing"))
        }
        assertEquals(1, callCount)
    }
}
