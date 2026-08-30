package dev.cupthread.feedback

import dev.cupthread.feedback.internal.HttpRequest
import dev.cupthread.feedback.internal.HttpResponse
import dev.cupthread.feedback.internal.HttpTransport
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FeedbackClientTest {
    private val appKey = "app_testkey123456"

    private fun client(handler: (HttpRequest) -> HttpResponse): FeedbackClient =
        FeedbackClient(
            config = FeedbackClientConfig(baseUrl = "https://api.example.com", appKey = appKey),
            transport = HttpTransport { handler(it) }
        )

    @Test
    fun fetchAppConfigHitsConfigEndpoint() = runTest {
        var captured: String? = null
        val result = client { request ->
            captured = request.url
            HttpResponse(200, CONFIG_JSON)
        }.fetchAppConfig()

        assertEquals("https://api.example.com/api/v1/public/config/$appKey", captured)
        assertEquals("Demo App", result.name)
        assertEquals(true, result.allowPublic)
        assertEquals(listOf(FeedbackPlatform.IOS, FeedbackPlatform.MACOS), result.allowedPlatforms)
        assertEquals(20_000_000L, result.maxAttachmentBytes)
        assertFalse(result.allowAnonymousVote)
        assertEquals(SdkTheme.SYSTEM, result.sdk.theme)
        assertTrue(result.sdk.features.changelog)
        assertEquals(3, result.sdk.changelogOverlay.entryCount)
    }

    @Test
    fun fetchAppConfigDecodesSdkAppearance() = runTest {
        val json = """
            {
              "appId":"app-1",
              "appKey":"$appKey",
              "slug":"demo-app",
              "name":"Demo App",
              "storeUrl":null,
              "storeKind":null,
              "iconUrl":null,
              "allowPublic":true,
              "allowedPlatforms":["android"],
              "maxAttachmentBytes":20000000,
              "sdk":{
                "theme":"ocean",
                "features":{"feedback":true,"featureRequests":false,"roadmap":true,"changelog":true},
                "changelogOverlay":{"title":"Just shipped","subtitle":"Here's what changed","entryCount":2,"primaryButton":"Got it","closeButton":"Not now"}
              }
            }
        """.trimIndent()
        val result = client { HttpResponse(200, json) }.fetchAppConfig()
        assertEquals(SdkTheme.OCEAN, result.sdk.theme)
        assertFalse(result.sdk.features.featureRequests)
        assertEquals("Just shipped", result.sdk.changelogOverlay.title)
        assertEquals(2, result.sdk.changelogOverlay.entryCount)
    }

    @Test
    fun prepareChangelogOverlayReturnsNullWhenHidden() = runTest {
        val json = """
            {
              "appId":"app-1","appKey":"$appKey","slug":"demo-app","name":"Demo App",
              "storeUrl":null,"storeKind":null,"iconUrl":null,"allowPublic":true,
              "allowedPlatforms":["android"],"maxAttachmentBytes":20000000,
              "sdk":{"theme":"system","features":{"changelog":false},"changelogOverlay":{"title":"What's New","entryCount":3}}
            }
        """.trimIndent()
        val prepared = client { request ->
            if (request.url.contains("/changelog")) {
                HttpResponse(200, """{"entries":[]}""")
            } else {
                HttpResponse(200, json)
            }
        }.prepareChangelogOverlay()
        assertEquals(null, prepared)
    }

    @Test
    fun fetchAppConfigThrowsOn404() = runTest {
        try {
            client { HttpResponse(404, """{"error":"App not found"}""") }.fetchAppConfig()
            fail("expected error")
        } catch (error: FeedbackException.UnexpectedStatus) {
            assertEquals(404, error.code)
        }
    }

    @Test
    fun fetchColumnsSortsByPosition() = runTest {
        val json = """
            {"columns":[
              {"id":"c2","appId":"a","name":"In Progress","slug":"in-progress","position":1,"isVisible":true,"isSystem":false,"kind":"normal","createdAt":"","updatedAt":""},
              {"id":"c1","appId":"a","name":"Backlog","slug":"backlog","position":0,"isVisible":true,"isSystem":true,"kind":"pending_review","createdAt":"","updatedAt":""},
              {"id":"c3","appId":"a","name":"Done","slug":"done","position":2,"isVisible":true,"isSystem":false,"kind":"done","createdAt":"","updatedAt":""}
            ]}
        """.trimIndent()
        val columns = client { HttpResponse(200, json) }.fetchColumns()
        assertEquals(listOf("c1", "c2", "c3"), columns.map { it.id })
        assertEquals(BoardColumnKind.PENDING_REVIEW, columns.first().kind)
        assertEquals(BoardColumnKind.DONE, columns.last().kind)
    }

    @Test
    fun submitSetsUserTokenHeader() = runTest {
        var header: String? = null
        val token = UUID.randomUUID().toString()
        client { request ->
            header = request.headers["X-User-Token"]
            HttpResponse(201, """{"submissionId":"s-1","forwardedToGithub":false}""")
        }.submit(
            FeedbackDraft(title = "Crash", description = "It crashed on launch", platform = FeedbackPlatform.ANDROID),
            userToken = token
        )
        assertEquals(token, header)
    }

    @Test
    fun submitSendsAndroidPlatformAndSdkMetadata() = runTest {
        var body: String? = null
        client { request ->
            body = request.body?.toString(Charsets.UTF_8)
            HttpResponse(201, """{"submissionId":"s-1","forwardedToGithub":false}""")
        }.submit(
            FeedbackDraft(title = "Bug", description = "Something broke here", platform = FeedbackPlatform.ANDROID)
        )
        val payload = requireNotNull(body)
        assertTrue(payload.contains("\"platform\":\"android\""))
        assertTrue(payload.contains("cupthread-android"))
    }

    @Test
    fun fetchFeatureRequestsIncludesSearchQuery() = runTest {
        var url: String? = null
        client { request ->
            url = request.url
            HttpResponse(200, """{"requests":[],"total":0}""")
        }.fetchFeatureRequests(userToken = UUID.randomUUID().toString(), query = "dark mode")
        assertTrue(url!!.contains("/api/v1/feature-requests?"))
        assertTrue(url.contains("q=dark%20mode"))
    }

    @Test
    fun fetchChangelogMaps401ToAuthenticationRequired() = runTest {
        try {
            client { HttpResponse(401, """{"code":"authentication_required"}""") }.fetchChangelog()
            fail("expected auth error")
        } catch (_: FeedbackException.AuthenticationRequired) {
        }
    }

    @Test
    fun fetchChangelogSortsNewestFirst() = runTest {
        val json = """
            {"entries":[
              {"id":"e1","title":"Old","body":"","versionLabel":"1.0","publishedAt":"2026-01-01T00:00:00.000Z","linkedRequests":[]},
              {"id":"e2","title":"New","body":"","versionLabel":"1.1","publishedAt":"2026-02-01T00:00:00.000Z","linkedRequests":[{"id":"fr-1","title":"Dark mode"}]}
            ]}
        """.trimIndent()
        val entries = client { HttpResponse(200, json) }.fetchChangelog()
        assertEquals(listOf("e2", "e1"), entries.map { it.id })
        assertEquals("Dark mode", entries.first().linkedRequests.single().title)
    }

    @Test
    fun toggleVoteHitsVoteEndpoint() = runTest {
        var url: String? = null
        val result = client { request ->
            url = request.url
            HttpResponse(200, """{"voted":true,"voteCount":4}""")
        }.toggleVote("fr-9", UUID.randomUUID().toString())
        assertEquals("https://api.example.com/api/v1/feature-requests/fr-9/vote", url)
        assertTrue(result.voted)
        assertEquals(4, result.voteCount)
    }

    @Test
    fun updateUserAttributesSendsTokenAndBody() = runTest {
        var header: String? = null
        var body: String? = null
        val token = UUID.randomUUID().toString()
        client { request ->
            header = request.headers["X-User-Token"]
            body = request.body?.toString(Charsets.UTF_8)
            HttpResponse(200, """{"ok":true,"updatedAt":"2026-02-01T00:00:00.000Z"}""")
        }.updateUserAttributes(userToken = token, isPaying = true, plan = "pro", mrr = 9.0, currency = "USD")
        assertEquals(token, header)
        val payload = requireNotNull(body)
        assertTrue(payload.contains("\"isPaying\":true"))
        assertTrue(payload.contains("\"plan\":\"pro\""))
    }

    @Test
    fun withVoteStateOnlyChangesVoteFields() {
        val base = FeatureRequestItem(
            id = "fr-1",
            appId = "app-1",
            title = "T",
            description = "D",
            status = "backlog",
            columnId = null,
            columnSlug = null,
            columnName = "Backlog",
            versionId = null,
            versionLabel = null,
            releasedVersion = null,
            requesterName = null,
            approved = true,
            voteCount = 3,
            hasVoted = false,
            isOwnRequest = false,
            createdAt = "2026-01-01T00:00:00.000Z",
            updatedAt = "2026-01-01T00:00:00.000Z"
        )
        val updated = base.withVoteState(voted = true, count = 4)
        assertEquals(4, updated.voteCount)
        assertTrue(updated.hasVoted)
        assertEquals(base.title, updated.title)
        assertEquals(base.columnName, updated.columnName)
    }

    companion object {
        private val CONFIG_JSON = """
            {
              "appId":"app-1",
              "appKey":"app_testkey123456",
              "slug":"demo-app",
              "name":"Demo App",
              "storeUrl":null,
              "storeKind":null,
              "iconUrl":"https://example.com/icon.png",
              "allowPublic":true,
              "allowedPlatforms":["ios","macos"],
              "maxAttachmentBytes":20000000,
              "allowAnonymousRoadmap":true,
              "allowAnonymousVote":false,
              "allowAnonymousFeedback":true
            }
        """.trimIndent()
    }
}
