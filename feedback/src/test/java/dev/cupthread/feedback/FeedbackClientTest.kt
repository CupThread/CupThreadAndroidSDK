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
    fun uploadAttachmentSendsUserTokenHeaderWhenProvided() = runTest {
        var capturedRequest: HttpRequest? = null
        val token = "usr_tok_${UUID.randomUUID()}"
        val json = """
            {
              "kind": "image",
              "key": "uploads/img-123.png",
              "url": "https://cdn.example.com/uploads/img-123.png",
              "filename": "screenshot.png",
              "mimeType": "image/png",
              "size": 1024
            }
        """.trimIndent()

        val attachment = client { request ->
            capturedRequest = request
            HttpResponse(201, json)
        }.uploadAttachment(
            data = "fake-image-bytes".toByteArray(),
            filename = "screenshot.png",
            mimeType = "image/png",
            userToken = token
        )

        val req = requireNotNull(capturedRequest)
        assertEquals(token, req.headers["X-User-Token"])
        assertTrue(req.headers["Content-Type"]?.startsWith("multipart/form-data; boundary=") == true)
        assertTrue(req.contentType?.startsWith("multipart/form-data; boundary=") == true)
        assertFalse(req.headers["Content-Type"] == "application/json")
        assertEquals(FeedbackAttachment.Kind.IMAGE, attachment.kind)
        assertEquals("uploads/img-123.png", attachment.key)
        assertEquals("https://cdn.example.com/uploads/img-123.png", attachment.url)
    }

    @Test
    fun uploadAttachmentOmitsUserTokenHeaderWhenNullOrBlank() = runTest {
        val json = """{"kind":"image","key":"k1","url":"https://cdn.example.com/k1"}"""

        var capturedReq1: HttpRequest? = null
        client { request ->
            capturedReq1 = request
            HttpResponse(200, json)
        }.uploadAttachment(
            data = byteArrayOf(1, 2, 3),
            filename = "file.png",
            mimeType = "image/png",
            userToken = null
        )
        assertFalse(requireNotNull(capturedReq1).headers.containsKey("X-User-Token"))

        var capturedReq2: HttpRequest? = null
        client { request ->
            capturedReq2 = request
            HttpResponse(200, json)
        }.uploadAttachment(
            data = byteArrayOf(1, 2, 3),
            filename = "file.png",
            mimeType = "image/png",
            userToken = "   "
        )
        assertFalse(requireNotNull(capturedReq2).headers.containsKey("X-User-Token"))
    }

    @Test
    fun uploadAttachmentAcceptsHttp200And201() = runTest {
        val json = """{"kind":"image","key":"k1","url":"https://cdn.example.com/k1"}"""

        val res200 = client { HttpResponse(200, json) }.uploadAttachment(
            data = byteArrayOf(1), filename = "f.png", mimeType = "image/png"
        )
        assertEquals("k1", res200.key)

        val res201 = client { HttpResponse(201, json) }.uploadAttachment(
            data = byteArrayOf(1), filename = "f.png", mimeType = "image/png"
        )
        assertEquals("k1", res201.key)
    }

    @Test
    fun uploadAttachmentThrowsAuthenticationRequiredOnHttp401() = runTest {
        try {
            client { HttpResponse(401, """{"error":"unauthorized"}""") }.uploadAttachment(
                data = byteArrayOf(1), filename = "f.png", mimeType = "image/png", userToken = "invalid_tok"
            )
            fail("expected AuthenticationRequired")
        } catch (_: FeedbackException.AuthenticationRequired) {
        }
    }

    @Test
    fun uploadAttachmentThrowsUnexpectedStatusOnHttp400() = runTest {
        try {
            client { HttpResponse(400, "Payload too large") }.uploadAttachment(
                data = byteArrayOf(1), filename = "f.png", mimeType = "image/png"
            )
            fail("expected UnexpectedStatus")
        } catch (ex: FeedbackException.UnexpectedStatus) {
            assertEquals(400, ex.code)
            assertEquals("Payload too large", ex.serverMessage)
        }
    }

    @Test
    fun uploadAttachmentThrowsUnreadableUploadResponseOnInvalidJson() = runTest {
        try {
            client { HttpResponse(200, """{"random":"payload"}""") }.uploadAttachment(
                data = byteArrayOf(1), filename = "f.png", mimeType = "image/png"
            )
            fail("expected UnreadableUploadResponse")
        } catch (_: FeedbackException.UnreadableUploadResponse) {
        }
    }

    @Test
    fun uploadAttachmentRoutesEndpointsBasedOnMimeAndPreferredKind() = runTest {
        val jsonImage = """{"kind":"image","key":"img","url":"https://cdn.example.com/img"}"""
        val jsonR2 = """{"kind":"r2","key":"doc","url":"https://cdn.example.com/doc"}"""

        var urlImage: String? = null
        client { request ->
            urlImage = request.url
            HttpResponse(200, jsonImage)
        }.uploadAttachment(data = byteArrayOf(1), filename = "photo.jpg", mimeType = "image/jpeg")
        assertEquals("https://api.example.com/api/v1/uploads/images", urlImage)

        var urlR2: String? = null
        client { request ->
            urlR2 = request.url
            HttpResponse(200, jsonR2)
        }.uploadAttachment(data = byteArrayOf(1), filename = "log.txt", mimeType = "text/plain")
        assertEquals("https://api.example.com/api/v1/uploads/r2", urlR2)

        var urlOverride: String? = null
        client { request ->
            urlOverride = request.url
            HttpResponse(200, jsonImage)
        }.uploadAttachment(
            data = byteArrayOf(1),
            filename = "blob.bin",
            mimeType = "application/octet-stream",
            preferredKind = FeedbackAttachment.Kind.IMAGE
        )
        assertEquals("https://api.example.com/api/v1/uploads/images", urlOverride)
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
            requesterAvatarUrl = "https://example.com/avatar.png",
            requesterClerkId = "user_123",
            approved = true,
            voteCount = 3,
            hasVoted = false,
            isOwnRequest = false,
            recentCommenters = listOf(RecentCommenter("Alice", "user_alice", "https://example.com/alice.png")),
            hasMoreCommenters = true,
            createdAt = "2026-01-01T00:00:00.000Z",
            updatedAt = "2026-01-01T00:00:00.000Z"
        )
        val updated = base.withVoteState(voted = true, count = 4)
        assertEquals(4, updated.voteCount)
        assertTrue(updated.hasVoted)
        assertEquals(base.title, updated.title)
        assertEquals(base.columnName, updated.columnName)
        assertEquals("https://example.com/avatar.png", updated.requesterAvatarUrl)
        assertEquals("user_123", updated.requesterClerkId)
        assertEquals(1, updated.recentCommenters.size)
        assertTrue(updated.hasMoreCommenters)
    }

    @Test
    fun fetchCommentsReturnsCommentsWithAuthorAndReplyMetadata() = runTest {
        var capturedUrl: String? = null
        val commentsJson = """
            {
              "comments": [
                {
                  "id": "c-1",
                  "featureRequestId": "fr-1",
                  "authorName": "Bob",
                  "authorEmail": "bob@example.com",
                  "authorAvatarUrl": "https://example.com/bob.png",
                  "authorClerkId": "user_bob",
                  "body": "Great feature request!",
                  "parentId": null,
                  "replyToClerkId": null,
                  "replyToAuthorName": null,
                  "isHidden": false,
                  "createdAt": "2026-02-01T10:00:00.000Z"
                },
                {
                  "id": "c-2",
                  "featureRequestId": "fr-1",
                  "authorName": "Alice",
                  "authorEmail": null,
                  "authorAvatarUrl": "https://example.com/alice.png",
                  "authorClerkId": "user_alice",
                  "body": "I agree completely!",
                  "parentId": "c-1",
                  "replyToClerkId": "user_bob",
                  "replyToAuthorName": "Bob",
                  "isHidden": false,
                  "createdAt": "2026-02-01T11:00:00.000Z"
                }
              ]
            }
        """.trimIndent()
        val result = client { request ->
            capturedUrl = request.url
            HttpResponse(200, commentsJson)
        }.fetchComments("fr-1")

        assertEquals("https://api.example.com/api/v1/feature-requests/fr-1/comments", capturedUrl)
        assertEquals(2, result.size)
        assertEquals("Bob", result[0].authorName)
        assertEquals("https://example.com/bob.png", result[0].authorAvatarUrl)
        assertEquals("user_bob", result[0].authorClerkId)
        assertEquals("c-1", result[1].parentId)
        assertEquals("user_bob", result[1].replyToClerkId)
        assertEquals("Bob", result[1].replyToAuthorName)
    }

    @Test
    fun postCommentSendsReplyMetadataAndUserTokenHeader() = runTest {
        var header: String? = null
        var body: String? = null
        var url: String? = null
        val token = UUID.randomUUID().toString()
        val responseJson = """
            {
              "id": "c-2",
              "featureRequestId": "fr-1",
              "authorName": "Alice",
              "authorAvatarUrl": "https://example.com/alice.png",
              "body": "@Bob Thanks for the feedback!",
              "parentId": "c-1",
              "replyToClerkId": "user_bob",
              "replyToAuthorName": "Bob",
              "createdAt": "2026-02-01T12:00:00.000Z"
            }
        """.trimIndent()

        val comment = client { request ->
            url = request.url
            header = request.headers["X-User-Token"]
            body = request.body?.toString(Charsets.UTF_8)
            HttpResponse(201, responseJson)
        }.postComment(
            featureRequestId = "fr-1",
            draft = CommentDraft(
                body = "@Bob Thanks for the feedback!",
                authorName = "Alice",
                authorAvatarUrl = "https://example.com/alice.png",
                parentId = "c-1",
                replyToClerkId = "user_bob",
                replyToAuthorName = "Bob"
            ),
            userToken = token
        )

        assertEquals("https://api.example.com/api/v1/feature-requests/fr-1/comments", url)
        assertEquals(token, header)
        val payload = requireNotNull(body)
        assertTrue(payload.contains("\"body\":\"@Bob Thanks for the feedback!\""))
        assertTrue(payload.contains("\"authorName\":\"Alice\""))
        assertTrue(payload.contains("\"parentId\":\"c-1\""))
        assertTrue(payload.contains("\"replyToClerkId\":\"user_bob\""))
        assertTrue(payload.contains("\"replyToAuthorName\":\"Bob\""))
        assertEquals("c-2", comment.id)
        assertEquals("Bob", comment.replyToAuthorName)
    }

    @Test
    fun fetchUserProfileReturnsProfileAppsAndRecentComments() = runTest {
        var url: String? = null
        val profileJson = """
            {
              "profile": {
                "clerkUserId": "user_lex",
                "displayName": "Lex",
                "avatarUrl": "https://example.com/lex.png",
                "bio": "Building things",
                "websiteUrl": "https://cupthread.com",
                "hideComments": false,
                "createdAt": "2026-01-01T00:00:00.000Z",
                "updatedAt": "2026-01-02T00:00:00.000Z"
              },
              "apps": [
                {
                  "id": "app-1",
                  "name": "CupThread",
                  "slug": "cupthread",
                  "iconUrl": "https://example.com/icon.png",
                  "description": "Feedback SDK",
                  "requestCount": 42
                }
              ],
              "recentComments": [
                {
                  "id": "rc-1",
                  "body": "Looking forward to this!",
                  "createdAt": "2026-02-01T00:00:00.000Z",
                  "featureRequestId": "fr-1",
                  "featureRequestTitle": "Dark Mode Support",
                  "appId": "app-1",
                  "appName": "CupThread"
                }
              ],
              "hideComments": false
            }
        """.trimIndent()

        val result = client { request ->
            url = request.url
            HttpResponse(200, profileJson)
        }.fetchUserProfile("user_lex")

        assertEquals("https://api.example.com/api/v1/users/user_lex/profile", url)
        assertEquals("Lex", result.profile.displayName)
        assertEquals("https://example.com/lex.png", result.profile.avatarUrl)
        assertEquals("Building things", result.profile.bio)
        assertEquals("https://cupthread.com", result.profile.websiteUrl)
        assertEquals(1, result.apps.size)
        assertEquals("CupThread", result.apps[0].name)
        assertEquals(42, result.apps[0].requestCount)
        assertEquals(1, result.recentComments.size)
        assertEquals("Dark Mode Support", result.recentComments[0].featureRequestTitle)
        assertFalse(result.hideComments)
    }

    @Test
    fun changelogStoreTracksSeenVersionsAndIds() {
        val prefs = FakeSharedPreferences()
        val store = ChangelogStore(prefs)

        assertFalse(store.hasSeenChangelog("2.1.0"))
        assertFalse(store.hasSeenChangelog("cl_123"))

        store.markChangelogSeen("2.1.0")
        store.markChangelogSeen("cl_123")

        assertTrue(store.hasSeenChangelog("2.1.0"))
        assertTrue(store.hasSeenChangelog("cl_123"))
        assertEquals(setOf("2.1.0", "cl_123"), store.getSeenChangelogs())

        store.markChangelogUnseen("2.1.0")
        assertFalse(store.hasSeenChangelog("2.1.0"))
        assertTrue(store.hasSeenChangelog("cl_123"))

        store.clearSeenChangelogs()
        assertFalse(store.hasSeenChangelog("cl_123"))
        assertTrue(store.getSeenChangelogs().isEmpty())
    }

    @Test
    fun userTokenStoreGeneratesAndPersistsStableToken() {
        val prefs = FakeSharedPreferences()
        val store1 = UserTokenStore(prefs)
        val token1 = store1.token
        assertTrue(token1.isNotBlank())

        val store2 = UserTokenStore(prefs)
        val token2 = store2.token
        assertEquals(token1, token2)
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

private class FakeSharedPreferences : android.content.SharedPreferences {
    private val map = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = map.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (map[key] as? Set<String>)?.toMutableSet() ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = map.containsKey(key)
    override fun edit(): android.content.SharedPreferences.Editor = FakeEditor()
    override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private inner class FakeEditor : android.content.SharedPreferences.Editor {
        private val temp = mutableMapOf<String, Any?>()
        private var clear = false

        override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor = apply { temp[key!!] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?): android.content.SharedPreferences.Editor = apply { temp[key!!] = values?.toSet() }
        override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor = apply { temp[key!!] = value }
        override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor = apply { temp[key!!] = value }
        override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor = apply { temp[key!!] = value }
        override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor = apply { temp[key!!] = value }
        override fun remove(key: String?): android.content.SharedPreferences.Editor = apply { temp[key!!] = this }
        override fun clear(): android.content.SharedPreferences.Editor = apply { clear = true }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clear) map.clear()
            temp.forEach { (k, v) ->
                if (v === this) map.remove(k) else map[k] = v
            }
        }
    }
}
