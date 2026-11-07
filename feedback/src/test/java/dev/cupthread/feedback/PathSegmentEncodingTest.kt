package dev.cupthread.feedback

import dev.cupthread.feedback.internal.HttpRequest
import dev.cupthread.feedback.internal.HttpResponse
import dev.cupthread.feedback.internal.HttpTransport
import java.net.URI
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Path-encoding contract for issue #196: every host-supplied value interpolated into a
 * request path must go through `encodePathSegment`, so `?`/`#`/whitespace/`/` inside an id
 * can neither hijack the query string, truncate at a fragment, nor escape its segment — and
 * the trim policy must agree between the read and the mutation paths.
 */
class PathSegmentEncodingTest {
    private val captured = mutableListOf<String>()

    private fun client(appKey: String = APP_KEY): FeedbackClient {
        val urls = captured
        return FeedbackClient(
            config = FeedbackClientConfig(baseUrl = "https://api.example.com", appKey = appKey),
            transport = HttpTransport { request ->
                urls += request.url
                HttpResponse(200, responseFor(request))
            }
        )
    }

    /** Minimal 200 body per endpoint so any public method can complete. */
    private fun responseFor(request: HttpRequest): String = when {
        request.url.contains("/api/v1/public/config/") -> CONFIG_JSON
        request.url.contains("/api/v1/public/columns/") -> """{"columns":[]}"""
        request.url.contains("/api/v1/public/versions/") -> """{"versions":[]}"""
        request.url.contains("/changelog/subscribe") -> """{"subscribed":true}"""
        request.url.contains("/changelog/unsubscribe") -> """{"unsubscribed":true}"""
        request.url.contains("/changelog/confirm") -> """{"confirmed":true}"""
        request.url.contains("/api/v1/public/apps/") ->
            """{"ok":true,"updatedAt":"2026-02-01T00:00:00.000Z"}"""
        request.url.contains("/vote") -> """{"voted":false,"voteCount":10}"""
        request.url.contains("/comments") ->
            """{"id":"c-1","featureRequestId":"fr-1","body":"hi","createdAt":"2026-02-01T12:00:00.000Z"}"""
        request.url.contains("/api/v1/users/") -> PROFILE_JSON
        request.url.contains("/api/v1/feature-requests/") -> SINGLE_REQUEST_JSON
        else -> """{"ok":true}"""
    }

    @Test
    fun fetchFeatureRequestEncodesQueryFragmentAndSlashInSegment() = runTest {
        client().fetchFeatureRequest("a b/c?x#y")

        assertEquals(
            "https://api.example.com/api/v1/feature-requests/a%20b%2Fc%3Fx%23y",
            captured.single()
        )
    }

    @Test
    fun voteEncodesQueryAndFragmentInSegment() = runTest {
        client().vote("fr#1?x", userToken = TOKEN)

        assertEquals(
            "https://api.example.com/api/v1/feature-requests/fr%231%3Fx/vote",
            captured.single()
        )
    }

    @Test
    fun deleteVoteEncodesQueryAndFragmentInSegment() = runTest {
        client().deleteVote("fr#1?x", userToken = TOKEN)

        assertEquals(
            "https://api.example.com/api/v1/feature-requests/fr%231%3Fx/vote",
            captured.single()
        )
    }

    @Test
    fun postCommentEncodesQueryAndFragmentInSegment() = runTest {
        client().postComment("fr#1?x", draft = CommentDraft(body = "hi"), userToken = TOKEN)

        assertEquals(
            "https://api.example.com/api/v1/feature-requests/fr%231%3Fx/comments",
            captured.single()
        )
    }

    @Test
    fun fetchCommentsPageEncodesQueryAndFragmentInSegment() = runTest {
        client().fetchCommentsPage("fr#1?x")

        assertEquals(
            "https://api.example.com/api/v1/feature-requests/fr%231%3Fx/comments",
            captured.single()
        )
    }

    @Test
    fun fetchUserProfileEncodesWhitespaceAndFragmentInSegment() = runTest {
        client().fetchUserProfile("u #1")

        assertEquals(
            "https://api.example.com/api/v1/users/u%20%231/profile?appKey=$APP_KEY",
            captured.single()
        )
    }

    @Test
    fun paddedIdsBehaveIdenticallyOnReadAndMutationPaths() = runTest {
        val api = client()
        api.fetchFeatureRequest(" fr_1 ")
        api.vote(" fr_1 ", userToken = TOKEN)
        api.deleteVote(" fr_1 ", userToken = TOKEN)
        api.postComment(" fr_1 ", draft = CommentDraft(body = "hi"), userToken = TOKEN)
        api.fetchCommentsPage(" fr_1 ")

        // Every path trims once at the public-method entry: no whitespace ever reaches the
        // request, and read vs mutation paths cannot disagree about the id anymore (#196).
        captured.forEach { url ->
            assertTrue("untrimmed id reached $url", "/feature-requests/fr_1" in url)
            assertFalse("padded id reached $url", url.contains("%20fr_1") || url.contains("fr_1%20"))
        }
    }

    @Test
    fun blankIdsAreRejectedOnMutationPathsLikeOnReadPaths() = runTest {
        val api = client()
        val mutations: List<suspend (String) -> Unit> = listOf(
            { id -> api.vote(id, userToken = TOKEN) },
            { id -> api.deleteVote(id, userToken = TOKEN) },
            { id -> api.postComment(id, draft = CommentDraft(body = "hi"), userToken = TOKEN) }
        )
        mutations.forEach { mutation ->
            listOf("", "  ").forEach { id ->
                try {
                    mutation(id)
                    fail("blank featureRequestId must be rejected")
                } catch (expected: IllegalArgumentException) {
                    assertTrue(expected.message.orEmpty().contains("featureRequestId cannot be blank"))
                }
            }
        }
    }

    @Test
    fun appKeyIsEncodedWhereverItEntersAPath() = runTest {
        val hostileKey = "app k?y#z"
        val urls = mutableListOf<String>()
        val api = FeedbackClient(
            config = FeedbackClientConfig(baseUrl = "https://api.example.com", appKey = hostileKey),
            transport = HttpTransport { request ->
                urls += request.url
                HttpResponse(200, responseFor(request))
            }
        )
        api.fetchAppConfig()
        api.fetchColumns()
        api.fetchVersions()
        api.fetchChangelogPage()
        api.subscribeToChangelog("alice@example.com", userToken = TOKEN)
        api.unsubscribeFromChangelog(token = "sub_token")
        api.confirmChangelogSubscription(token = "conf_token")
        api.updateUserAttributes(userToken = TOKEN)

        assertEquals(8, urls.size)
        urls.forEach { url ->
            assertTrue("raw appKey reached $url", "app%20k%3Fy%23z" in url)
            assertFalse("raw appKey reached $url", url.contains(hostileKey))
        }
    }

    /**
     * Sweep guardrail: with hostile ids in play, no emitted URL may carry raw `?`/`#`/space
     * syntax or a `..` segment in its path portion beyond the single intended query separator
     * — the guard against the next endpoint reintroducing raw interpolation (#196).
     */
    @Test
    fun sweepNoEmittedUrlCarriesRawQueryOrFragmentSyntaxInItsPath() = runTest {
        val api = client()
        api.fetchFeatureRequest("../public/apps/other/changelog")
        api.vote("fr#1?x", userToken = TOKEN)
        api.deleteVote(" fr_1 ", userToken = TOKEN)
        api.postComment("a b/c", draft = CommentDraft(body = "hi"), userToken = TOKEN)
        api.fetchCommentsPage("fr?x#y")
        api.fetchUserProfile("u#x")
        api.fetchChangelogPage(cursor = "cur sor?1")

        assertTrue(captured.size >= 6)
        captured.forEach { url ->
            val queryStart = url.indexOf('?')
            assertTrue("more than one query separator in $url", queryStart == url.lastIndexOf('?'))
            val pathPortion = if (queryStart == -1) url else url.substring(0, queryStart)
            assertFalse("raw '?' in path of $url", '?' in pathPortion)
            assertFalse("raw '#' in path of $url", '#' in pathPortion)
            assertFalse("raw space in path of $url", ' ' in pathPortion)
            val segments = URI(url).rawPath.split('/')
            assertFalse("traversal segment in $url", ".." in segments)
        }
        // The traversal attempt stays one encoded segment instead of escaping upward.
        assertEquals(
            "..%2Fpublic%2Fapps%2Fother%2Fchangelog",
            URI(captured.first()).rawPath.substringAfterLast('/')
        )
    }

    private companion object {
        const val APP_KEY = "app_testkey123456"
        const val TOKEN = "anon_user_token_999"
        val CONFIG_JSON = """
            {"appId":"app-1","appKey":"$APP_KEY","slug":"path-encoding","name":"Demo App",
             "allowPublic":true,"allowedPlatforms":["android"],"maxAttachmentBytes":1000}
        """.trimIndent()
        val SINGLE_REQUEST_JSON = """
            {"request":{"id":"fr-123","appId":"$APP_KEY","title":"T","description":"D",
             "status":"open","voteCount":10,"hasVoted":false,"isOwnRequest":false,
             "createdAt":"2026-03-01T12:00:00.000Z","updatedAt":"2026-03-02T15:30:00.000Z"}}
        """.trimIndent()
        val PROFILE_JSON = """
            {"profile":{"clerkUserId":"u_abcdef1234567890abcdef1234567890","displayName":"Lex",
             "bio":"Building things","hideComments":false,
             "createdAt":"2026-01-01T00:00:00.000Z","updatedAt":"2026-01-02T00:00:00.000Z"},
             "apps":[],"recentComments":[],"hideComments":false}
        """.trimIndent()
    }
}
