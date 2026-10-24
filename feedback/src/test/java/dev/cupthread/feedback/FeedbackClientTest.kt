package dev.cupthread.feedback

import dev.cupthread.feedback.internal.HttpRequest
import dev.cupthread.feedback.internal.HttpResponse
import dev.cupthread.feedback.internal.HttpTransport
import dev.cupthread.feedback.internal.shouldFollowRedirects
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertNull(result.websiteUrl)
        assertFalse(result.hideSiteBranding)
        assertEquals(true, result.allowPublic)
        assertEquals(listOf(FeedbackPlatform.IOS, FeedbackPlatform.MACOS), result.allowedPlatforms)
        assertEquals(20_000_000L, result.maxAttachmentBytes)
        assertFalse(result.allowAnonymousVote)
        assertEquals(SdkTheme.SYSTEM, result.sdk.theme)
        assertTrue(result.sdk.features.changelog)
        assertEquals(3, result.sdk.changelogOverlay.entryCount)
    }

    @Test
    fun fetchAppConfigDecodesWebsiteFields() = runTest {
        // #2: additive PublicAppConfig fields (websiteUrl, hideSiteBranding).
        val json = """
            {
              "appId":"app-1",
              "appKey":"$appKey",
              "slug":"demo-app",
              "name":"Demo App",
              "storeUrl":null,
              "storeKind":null,
              "iconUrl":null,
              "websiteUrl":"https://cupthread.com",
              "hideSiteBranding":true,
              "allowPublic":true,
              "allowedPlatforms":["android"],
              "maxAttachmentBytes":20000000
            }
        """.trimIndent()
        val result = client { HttpResponse(200, json) }.fetchAppConfig()
        assertEquals("https://cupthread.com", result.websiteUrl)
        assertTrue(result.hideSiteBranding)
    }

    @Test
    fun fetchAppConfigDefaultsWebsiteFieldsWhenAbsent() = runTest {
        // Older deployments predate the fields; both decode leniently.
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
              "maxAttachmentBytes":20000000
            }
        """.trimIndent()
        val result = client { HttpResponse(200, json) }.fetchAppConfig()
        assertNull(result.websiteUrl)
        assertFalse(result.hideSiteBranding)
    }

    @Test
    fun fetchAppConfigHandlesNullWebsiteFields() = runTest {
        val json = """
            {
              "appId":"app-1",
              "appKey":"$appKey",
              "slug":"demo-app",
              "name":"Demo App",
              "storeUrl":null,
              "storeKind":null,
              "iconUrl":null,
              "websiteUrl":null,
              "hideSiteBranding":false,
              "allowPublic":true,
              "allowedPlatforms":[],
              "maxAttachmentBytes":10000
            }
        """.trimIndent()
        val result = client { HttpResponse(200, json) }.fetchAppConfig()
        assertNull(result.websiteUrl)
        assertFalse(result.hideSiteBranding)
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
    fun fetchAppConfigDecodesRequireAnonymousVoteTurnstile() = runTest {
        val withTurnstileTrue = """
            {
              "appId":"app-1","appKey":"$appKey","slug":"demo-app","name":"Demo App",
              "storeUrl":null,"storeKind":null,"iconUrl":null,"allowPublic":true,
              "allowedPlatforms":["android"],"maxAttachmentBytes":20000000,
              "requireAnonymousVoteTurnstile":true
            }
        """.trimIndent()
        val resultTrue = client { HttpResponse(200, withTurnstileTrue) }.fetchAppConfig()
        assertTrue(resultTrue.requireAnonymousVoteTurnstile)

        val withTurnstileFalse = """
            {
              "appId":"app-1","appKey":"$appKey","slug":"demo-app","name":"Demo App",
              "storeUrl":null,"storeKind":null,"iconUrl":null,"allowPublic":true,
              "allowedPlatforms":["android"],"maxAttachmentBytes":20000000,
              "requireAnonymousVoteTurnstile":false
            }
        """.trimIndent()
        val resultFalse = client { HttpResponse(200, withTurnstileFalse) }.fetchAppConfig()
        assertFalse(resultFalse.requireAnonymousVoteTurnstile)

        val withTurnstileNull = """
            {
              "appId":"app-1","appKey":"$appKey","slug":"demo-app","name":"Demo App",
              "storeUrl":null,"storeKind":null,"iconUrl":null,"allowPublic":true,
              "allowedPlatforms":["android"],"maxAttachmentBytes":20000000,
              "requireAnonymousVoteTurnstile":null
            }
        """.trimIndent()
        val resultNull = client { HttpResponse(200, withTurnstileNull) }.fetchAppConfig()
        assertFalse(resultNull.requireAnonymousVoteTurnstile)
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
    fun submitDecodesWarningCode() = runTest {
        val result = client {
            HttpResponse(
                201,
                """{"submissionId":"s-1","forwardedToGithub":false,"warning":"Attachments unavailable","warningCode":"attachment_signing_unconfigured"}"""
            )
        }.submit(
            FeedbackDraft(title = "Crash", description = "It crashed on launch", platform = FeedbackPlatform.ANDROID)
        )

        assertEquals("Attachments unavailable", result.warning)
        assertEquals("attachment_signing_unconfigured", result.warningCode)
    }

    private val validPngBytes = byteArrayOf(
        0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
        0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte()
    )
    private val validJpegBytes = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()
    )

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
            data = validPngBytes,
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
            data = validPngBytes,
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
            data = validPngBytes,
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
            data = validPngBytes, filename = "f.png", mimeType = "image/png"
        )
        assertEquals("k1", res200.key)

        val res201 = client { HttpResponse(201, json) }.uploadAttachment(
            data = validPngBytes, filename = "f.png", mimeType = "image/png"
        )
        assertEquals("k1", res201.key)
    }

    @Test
    fun uploadAttachmentThrowsAuthenticationRequiredOnHttp401() = runTest {
        try {
            client { HttpResponse(401, """{"error":"unauthorized"}""") }.uploadAttachment(
                data = validPngBytes, filename = "f.png", mimeType = "image/png", userToken = "invalid_tok"
            )
            fail("expected AuthenticationRequired")
        } catch (_: FeedbackException.AuthenticationRequired) {
        }
    }

    @Test
    fun uploadAttachmentThrowsUnexpectedStatusOnHttp400() = runTest {
        try {
            client { HttpResponse(400, "Payload too large") }.uploadAttachment(
                data = validPngBytes, filename = "f.png", mimeType = "image/png"
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
                data = validPngBytes, filename = "f.png", mimeType = "image/png"
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
        }.uploadAttachment(data = validJpegBytes, filename = "photo.jpg", mimeType = "image/jpeg")
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
            HttpResponse(200, jsonR2)
        }.uploadAttachment(
            data = validPngBytes,
            filename = "photo.png",
            mimeType = "image/png",
            preferredKind = FeedbackAttachment.Kind.R2
        )
        assertEquals("https://api.example.com/api/v1/uploads/r2", urlOverride)
    }

    @Test
    fun uploadAttachmentMapsHttp415ToUnsupportedMediaTypeWithServerMessage() = runTest {
        try {
            client {
                HttpResponse(
                    415,
                    """
                    {
                      "error": "Uploaded file content does not match declared image/png format",
                      "code": "unsupported_media_type"
                    }
                    """.trimIndent()
                )
            }.uploadAttachment(
                data = validPngBytes,
                filename = "screenshot.png",
                mimeType = "image/png"
            )
            fail("expected UnsupportedMediaType")
        } catch (ex: FeedbackException.UnsupportedMediaType) {
            assertEquals("Uploaded file content does not match declared image/png format", ex.serverMessage)
            assertEquals("Uploaded file content does not match declared image/png format", ex.message)
        }
    }

    @Test
    fun uploadAttachmentMapsHttp415WithBlankBodyToUnsupportedMediaTypeDefaultMessage() = runTest {
        try {
            client {
                HttpResponse(415, "   ")
            }.uploadAttachment(
                data = validPngBytes,
                filename = "screenshot.png",
                mimeType = "image/png"
            )
            fail("expected UnsupportedMediaType")
        } catch (ex: FeedbackException.UnsupportedMediaType) {
            assertNull(ex.serverMessage)
            assertEquals(
                "This image format is not supported. Please upload a PNG, JPEG, WebP, or GIF image.",
                ex.message
            )
        }
    }

    @Test
    fun nonUploadRequestsDoNotMapHttp415ToUnsupportedMediaType() = runTest {
        try {
            client {
                HttpResponse(415, "Unsupported")
            }.submit(FeedbackDraft(title = "Bug", description = "Repro steps", platform = FeedbackPlatform.ANDROID))
            fail("expected UnexpectedStatus")
        } catch (ex: FeedbackException.UnexpectedStatus) {
            assertEquals(415, ex.code)
            assertEquals("Unsupported", ex.serverMessage)
        }

        try {
            client {
                HttpResponse(415, "")
            }.fetchAppConfig()
            fail("expected UnexpectedStatus")
        } catch (ex: FeedbackException.UnexpectedStatus) {
            assertEquals(415, ex.code)
            assertEquals("Unknown error", ex.serverMessage)
        }
    }

    @Test
    fun uploadAttachmentRejectsSvgClientSide() = runTest {
        try {
            client {
                HttpResponse(200, """{"kind":"image","key":"k1","url":"https://cdn.example.com/k1"}""")
            }.uploadAttachment(
                data = "<svg></svg>".toByteArray(),
                filename = "logo.svg",
                mimeType = "image/svg+xml"
            )
            fail("expected UnsupportedMediaType")
        } catch (ex: FeedbackException.UnsupportedMediaType) {
            assertTrue(ex.message?.contains("SVG") == true)
        }
    }

    @Test
    fun uploadAttachmentRejectsMismatchedMagicBytesClientSide() = runTest {
        val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        try {
            client {
                HttpResponse(200, """{"kind":"image","key":"k1","url":"https://cdn.example.com/k1"}""")
            }.uploadAttachment(
                data = jpegBytes,
                filename = "screenshot.png",
                mimeType = "image/png"
            )
            fail("expected UnsupportedMediaType")
        } catch (ex: FeedbackException.UnsupportedMediaType) {
            assertTrue(ex.message?.contains("does not match") == true)
        }
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
    fun fetchFeatureRequestsTransmitsUserTokenInHeaderNotQuery() = runTest {
        var capturedRequest: HttpRequest? = null
        val token = "anon_user_token_12345"
        client { request ->
            capturedRequest = request
            HttpResponse(200, """{"requests":[],"total":0}""")
        }.fetchFeatureRequests(userToken = token)

        val req = requireNotNull(capturedRequest)
        assertEquals("GET", req.method)
        assertEquals(token, req.headers["X-User-Token"])
        assertFalse(req.url.contains("userToken="))
        assertTrue(req.url.contains("/api/v1/feature-requests?"))
        assertTrue(req.url.contains("appKey=app_testkey123456"))
    }

    @Test
    fun fetchFeatureRequestReturnsFeatureRequestItemOnSuccess() = runTest {
        var capturedRequest: HttpRequest? = null
        val token = "anon_user_token_999"
        val json = """
            {
              "request": {
                "id": "fr-123",
                "appId": "app_testkey123456",
                "title": "Landscape mode for tablets",
                "description": "Support two-pane tablet layout",
                "status": "in_progress",
                "columnId": "col-2",
                "columnName": "In Progress",
                "columnColor": "#3b82f6",
                "versionId": "ver-1",
                "versionLabel": "v1.2.0",
                "releasedVersion": null,
                "requesterName": "Alex",
                "requesterAvatarUrl": "https://example.com/avatar.png",
                "requesterClerkId": "u_testuser",
                "approved": true,
                "voteCount": 42,
                "hasVoted": true,
                "isOwnRequest": false,
                "recentCommenters": [],
                "hasMoreCommenters": false,
                "createdAt": "2026-03-01T12:00:00.000Z",
                "updatedAt": "2026-03-02T15:30:00.000Z"
              }
            }
        """.trimIndent()

        val item = client { request ->
            capturedRequest = request
            HttpResponse(200, json)
        }.fetchFeatureRequest("fr-123", userToken = token)

        val req = requireNotNull(capturedRequest)
        assertEquals("GET", req.method)
        assertEquals("https://api.example.com/api/v1/feature-requests/fr-123", req.url)
        assertEquals(token, req.headers["X-User-Token"])

        assertEquals("fr-123", item.id)
        assertEquals("app_testkey123456", item.appId)
        assertEquals("Landscape mode for tablets", item.title)
        assertEquals("Support two-pane tablet layout", item.description)
        assertEquals("in_progress", item.status)
        assertEquals("col-2", item.columnId)
        assertEquals("In Progress", item.columnName)
        assertEquals("#3b82f6", item.columnColor)
        assertEquals("ver-1", item.versionId)
        assertEquals("v1.2.0", item.versionLabel)
        assertNull(item.releasedVersion)
        assertEquals("Alex", item.requesterName)
        assertEquals("https://example.com/avatar.png", item.requesterAvatarUrl)
        assertEquals("u_testuser", item.requesterClerkId)
        assertTrue(item.approved)
        assertEquals(42, item.voteCount)
        assertTrue(item.hasVoted)
        assertFalse(item.isOwnRequest)
        assertEquals("2026-03-01T12:00:00.000Z", item.createdAt)
        assertEquals("2026-03-02T15:30:00.000Z", item.updatedAt)
    }

    @Test
    fun fetchFeatureRequestSingleArgumentOverloadOmitsUserTokenHeader() = runTest {
        var capturedRequest: HttpRequest? = null
        val json = """
            {
              "request": {
                "id": "fr-456",
                "appId": "app_testkey123456",
                "title": "Offline caching",
                "approved": true,
                "voteCount": 5,
                "hasVoted": false,
                "isOwnRequest": false
              }
            }
        """.trimIndent()

        val item = client { request ->
            capturedRequest = request
            HttpResponse(200, json)
        }.fetchFeatureRequest("fr-456")

        val req = requireNotNull(capturedRequest)
        assertEquals("GET", req.method)
        assertEquals("https://api.example.com/api/v1/feature-requests/fr-456", req.url)
        assertNull(req.headers["X-User-Token"])
        assertEquals("fr-456", item.id)
        assertEquals("Offline caching", item.title)
        assertFalse(item.hasVoted)
    }

    @Test
    fun fetchFeatureRequestThrowsOnBlankId() = runTest {
        val c = client { HttpResponse(200, "{}") }
        try {
            c.fetchFeatureRequest("")
            fail("Expected IllegalArgumentException for empty featureRequestId")
        } catch (_: IllegalArgumentException) {
            // Expected
        }

        try {
            c.fetchFeatureRequest("   ")
            fail("Expected IllegalArgumentException for blank featureRequestId")
        } catch (_: IllegalArgumentException) {
            // Expected
        }
    }

    @Test
    fun fetchFeatureRequestSurfaces404NotFoundAsUnexpectedStatus() = runTest {
        val c = client {
            HttpResponse(404, """{"error":"Feature request not found"}""")
        }
        try {
            c.fetchFeatureRequest("fr-nonexistent")
            fail("Expected UnexpectedStatus on 404")
        } catch (e: FeedbackException.UnexpectedStatus) {
            assertEquals(404, e.code)
            assertTrue(e.serverMessage.contains("Feature request not found"))
        }
    }

    @Test
    fun fetchFeatureRequestSurfaces401AsAuthenticationRequired() = runTest {
        val c = client {
            HttpResponse(401, """{"error":"Authentication required","code":"authentication_required"}""")
        }
        try {
            c.fetchFeatureRequest("fr-private")
            fail("Expected AuthenticationRequired on 401")
        } catch (_: FeedbackException.AuthenticationRequired) {
            // Expected
        }
    }

    @Test
    fun fetchFeatureRequestSurfaces429AsRateLimited() = runTest {
        val c = client {
            HttpResponse(429, """{"error":"Rate limit exceeded. Please slow down."}""")
        }
        try {
            c.fetchFeatureRequest("fr-ratelimited")
            fail("Expected RateLimited on 429")
        } catch (e: FeedbackException.RateLimited) {
            assertEquals("Rate limit exceeded. Please slow down.", e.serverMessage)
        }
    }

    @Test
    fun fetchFeatureRequestThrowsInvalidResponseOnMissingRequestWrapper() = runTest {
        val c = client {
            HttpResponse(200, """{"item":{"id":"fr-missing-wrapper"}}""")
        }
        try {
            c.fetchFeatureRequest("fr-missing-wrapper")
            fail("Expected InvalidResponse when 'request' wrapper is missing")
        } catch (_: FeedbackException.InvalidResponse) {
            // Expected
        }
    }

    @Test
    fun linkEndUserSendsHeadersAndPayload() = runTest {
        var capturedRequest: HttpRequest? = null
        val sessionToken = "clerk_sess_xyz"
        val userToken = "anon_user_token_999"
        val client = client { request ->
            capturedRequest = request
            HttpResponse(
                200,
                """
                {
                    "linked": true,
                    "endUserId": "enduser-42",
                    "clerkUserId": "user_2abc"
                }
                """.trimIndent()
            )
        }
        val result = client.linkEndUser(sessionToken = sessionToken, userToken = userToken)

        val req = requireNotNull(capturedRequest)
        assertEquals("POST", req.method)
        assertTrue(req.url.endsWith("/api/v1/me/link"))
        assertEquals("Bearer $sessionToken", req.headers["Authorization"])
        assertEquals(userToken, req.headers["X-User-Token"])
        assertEquals("application/json", req.headers["Content-Type"])

        val bodyString = req.body?.let { String(it, Charsets.UTF_8) } ?: ""
        val json = JSONObject(bodyString)
        assertEquals("app_testkey123456", json.getString("appKey"))

        assertTrue(result.linked)
        assertEquals("enduser-42", result.endUserId)
        assertEquals("user_2abc", result.clerkUserId)
    }

    @Test
    fun linkEndUserDecodesEndUserEnvelopePayload() = runTest {
        val client = client { _ ->
            HttpResponse(
                200,
                """
                {
                    "endUser": {
                        "id": "enduser-99",
                        "appId": "app-1",
                        "anonymousToken": "anon-1",
                        "clerkUserId": "user_clerk_99",
                        "identityConfirmed": true,
                        "createdAt": "2026-10-23T00:00:00.000Z",
                        "updatedAt": "2026-10-23T00:00:00.000Z"
                    }
                }
                """.trimIndent()
            )
        }
        val result = client.linkEndUser(sessionToken = "sess", userToken = "user")
        assertTrue(result.linked)
        assertEquals("enduser-99", result.endUserId)
        assertEquals("user_clerk_99", result.clerkUserId)
    }

    @Test
    fun linkEndUserThrowsOnBlankTokens() = runTest {
        val client = client { HttpResponse(200, "{}") }
        try {
            client.linkEndUser(sessionToken = "", userToken = "tok")
            fail("Expected IllegalArgumentException for blank sessionToken")
        } catch (_: IllegalArgumentException) {
        }

        try {
            client.linkEndUser(sessionToken = "sess", userToken = "   ")
            fail("Expected IllegalArgumentException for blank userToken")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun linkEndUserMaps401ToAuthenticationRequired() = runTest {
        val client = client { HttpResponse(401, """{"error":"Session expired"}""") }
        try {
            client.linkEndUser(sessionToken = "expired_sess", userToken = "user_tok")
            fail("Expected AuthenticationRequired exception")
        } catch (_: FeedbackException.AuthenticationRequired) {
        }
    }

    @Test
    fun linkEndUserSurfaces409ConflictAsUnexpectedStatus() = runTest {
        val client = client {
            HttpResponse(
                409,
                """{"error":"Profile already confirmed to another identity","code":"already_confirmed"}"""
            )
        }
        try {
            client.linkEndUser(sessionToken = "sess", userToken = "tok")
            fail("Expected UnexpectedStatus exception on 409 conflict")
        } catch (ex: FeedbackException.UnexpectedStatus) {
            assertEquals(409, ex.code)
            assertTrue(ex.serverMessage.contains("Profile already confirmed"))
        }
    }

    @Test
    fun linkEndUserRejectsNonHttpsDestination() = runTest {
        val insecureClient = FeedbackClient(
            config = FeedbackClientConfig(
                baseUrl = "http://insecure.cupthread.com",
                appKey = appKey
            ),
            transport = HttpTransport { HttpResponse(200, "{}") }
        )
        try {
            insecureClient.linkEndUser(sessionToken = "sess", userToken = "user")
            fail("Expected IllegalArgumentException when linkEndUser baseUrl is not HTTPS")
        } catch (ex: IllegalArgumentException) {
            assertTrue(ex.message.orEmpty().contains("HTTPS"))
        }
    }

    @Test
    fun parseEndUserLinkResultHandlesJsonNullAndEmptyStringsCorrectly() {
        val envelopeWithNulls = JSONObject(
            """
            {
                "endUser": {
                    "id": null,
                    "endUserId": null,
                    "clerkUserId": null,
                    "identityConfirmed": false
                }
            }
            """.trimIndent()
        )
        val res1 = dev.cupthread.feedback.internal.parseEndUserLinkResult(envelopeWithNulls)
        assertFalse(res1.linked)
        assertNull(res1.endUserId)
        assertNull(res1.clerkUserId)

        val flatWithNulls = JSONObject(
            """
            {
                "linked": true,
                "endUserId": null,
                "id": "",
                "clerkUserId": null
            }
            """.trimIndent()
        )
        val res2 = dev.cupthread.feedback.internal.parseEndUserLinkResult(flatWithNulls)
        assertTrue(res2.linked)
        assertNull(res2.endUserId)
        assertNull(res2.clerkUserId)
    }

    @Test
    fun linkEndUserWrapsJsonParsingFailureInInvalidResponse() = runTest {
        val client = client { _ ->
            HttpResponse(
                200,
                """
                {
                    "endUser": {
                        "id": "u1",
                        "identityConfirmed": "not-a-boolean"
                    }
                }
                """.trimIndent()
            )
        }
        try {
            client.linkEndUser(sessionToken = "sess", userToken = "tok")
            fail("Expected FeedbackException.InvalidResponse on malformed payload")
        } catch (ex: FeedbackException.InvalidResponse) {
            assertTrue(ex.cause is org.json.JSONException)
        }
    }

    @Test
    fun httpRequestShouldFollowRedirectsOnlyWithoutCredentials() {
        val publicReq = HttpRequest(
            method = "GET",
            url = "https://api.cupthread.com/api/v1/public/config/app_123"
        )
        assertTrue(publicReq.shouldFollowRedirects())

        val userTokenReq = HttpRequest(
            method = "GET",
            url = "https://api.cupthread.com/api/v1/feature-requests",
            headers = mapOf("X-User-Token" to "tok_123")
        )
        assertFalse(userTokenReq.shouldFollowRedirects())

        val authReq = HttpRequest(
            method = "POST",
            url = "https://api.cupthread.com/api/v1/me/link",
            headers = mapOf("Authorization" to "Bearer sess_abc")
        )
        assertFalse(authReq.shouldFollowRedirects())

        val blankAuthReq = HttpRequest(
            method = "GET",
            url = "https://api.cupthread.com/api/v1/public/config/app_123",
            headers = mapOf("Authorization" to "   ", "X-User-Token" to "")
        )
        assertTrue(blankAuthReq.shouldFollowRedirects())
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
    fun fetchChangelogPageDefaultsToLimit100AndParsesPaginationMetadata() = runTest {
        var requestedUrl: String? = null
        val json = """
            {
              "entries": [
                {"id":"cl-1","title":"Release 1","content":"Details 1","version":"1.0.0","publishedAt":"2026-09-18T12:00:00.000Z"}
              ],
              "hasMore": true,
              "nextCursor": "cursor_page_1"
            }
        """.trimIndent()
        val page = client { req ->
            requestedUrl = req.url
            HttpResponse(200, json)
        }.fetchChangelogPage()

        val url = requireNotNull(requestedUrl)
        assertTrue(url.contains("/api/v1/public/apps/$appKey/changelog"))
        assertTrue(url.contains("limit=100"))
        assertFalse(url.contains("cursor="))

        assertEquals(1, page.entries.size)
        val entry = page.entries.first()
        assertEquals("cl-1", entry.id)
        assertEquals("Release 1", entry.title)
        assertEquals("Details 1", entry.body)
        assertEquals("1.0.0", entry.versionLabel)
        assertTrue(page.hasMore)
        assertEquals("cursor_page_1", page.nextCursor)
    }

    @Test
    fun fetchChangelogPageClampsLimitToBetween1And100() = runTest {
        val requestedUrls = mutableListOf<String>()
        val dummyJson = """{"entries":[],"hasMore":false,"nextCursor":null}"""
        val client = client { req ->
            requestedUrls.add(req.url)
            HttpResponse(200, dummyJson)
        }

        client.fetchChangelogPage(limit = -10, cursor = "cur_123")
        assertTrue(requestedUrls[0].contains("limit=1"))
        assertTrue(requestedUrls[0].contains("cursor=cur_123"))

        client.fetchChangelogPage(limit = 250)
        assertTrue(requestedUrls[1].contains("limit=100"))
        assertFalse(requestedUrls[1].contains("cursor="))

        client.fetchChangelogPage(limit = 42)
        assertTrue(requestedUrls[2].contains("limit=42"))
    }

    @Test
    fun fetchChangelogPageHandlesMissingPaginationFieldsGracefully() = runTest {
        val legacyJson = """
            {
              "entries": [
                {"id":"cl-old","title":"Legacy","body":"Legacy notes","publishedAt":"2026-01-01T00:00:00.000Z"}
              ]
            }
        """.trimIndent()
        val page = client { HttpResponse(200, legacyJson) }.fetchChangelogPage()
        assertEquals(1, page.entries.size)
        assertFalse(page.hasMore)
        assertNull(page.nextCursor)
    }

    @Test
    fun fetchChangelogSupportsLimitAndCursorAndReturnsEntries() = runTest {
        var requestedUrl: String? = null
        val json = """
            {
              "entries": [
                {"id":"cl-a","title":"A","publishedAt":"2026-02-01T00:00:00.000Z"},
                {"id":"cl-b","title":"B","publishedAt":"2026-03-01T00:00:00.000Z"}
              ],
              "hasMore": false,
              "nextCursor": null
            }
        """.trimIndent()
        val entries = client { req ->
            requestedUrl = req.url
            HttpResponse(200, json)
        }.fetchChangelog(limit = 20, cursor = "cur_abc")

        assertTrue(requestedUrl!!.contains("limit=20"))
        assertTrue(requestedUrl!!.contains("cursor=cur_abc"))
        assertEquals(listOf("cl-b", "cl-a"), entries.map { it.id })
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
    fun toggleVoteMapsHttp429ToRateLimitedWithServerMessage() = runTest {
        try {
            client {
                HttpResponse(429, """{"error":"Too many votes. Please try again shortly."}""")
            }.toggleVote("fr-10", UUID.randomUUID().toString())
            fail("Expected FeedbackException.RateLimited on 429")
        } catch (ex: FeedbackException.RateLimited) {
            assertEquals("Too many votes. Please try again shortly.", ex.serverMessage)
            assertEquals("Too many votes. Please try again shortly.", ex.message)
        }
    }

    @Test
    fun toggleVoteMapsHttp429WithBlankBodyToDefaultRateLimitedMessage() = runTest {
        try {
            client {
                HttpResponse(429, "   ")
            }.toggleVote("fr-11", UUID.randomUUID().toString())
            fail("Expected FeedbackException.RateLimited on 429")
        } catch (ex: FeedbackException.RateLimited) {
            assertNull(ex.serverMessage)
            assertEquals("You're voting too fast. Please try again shortly.", ex.message)
        }
    }

    @Test
    fun toggleVoteMapsHttp429WithJsonCodeOnlyToNullServerMessage() = runTest {
        try {
            client {
                HttpResponse(429, """{"code":"too_many_requests"}""")
            }.toggleVote("fr-11-code", UUID.randomUUID().toString())
            fail("Expected FeedbackException.RateLimited on 429")
        } catch (ex: FeedbackException.RateLimited) {
            assertNull(ex.serverMessage)
            assertEquals("You're voting too fast. Please try again shortly.", ex.message)
        }
    }

    @Test
    fun toggleVoteMapsHttp429WithJsonNullErrorToNullServerMessage() = runTest {
        try {
            client {
                HttpResponse(429, """{"code":"too_many_requests","error":null}""")
            }.toggleVote("fr-11-null-error", UUID.randomUUID().toString())
            fail("Expected FeedbackException.RateLimited on 429")
        } catch (ex: FeedbackException.RateLimited) {
            assertNull(ex.serverMessage)
            assertEquals("You're voting too fast. Please try again shortly.", ex.message)
        }
    }

    @Test
    fun toggleVoteMapsHttp429WithPlainTextBodyToServerMessage() = runTest {
        try {
            client {
                HttpResponse(429, "Rate limit reached from client IP")
            }.toggleVote("fr-11-text", UUID.randomUUID().toString())
            fail("Expected FeedbackException.RateLimited on 429")
        } catch (ex: FeedbackException.RateLimited) {
            assertEquals("Rate limit reached from client IP", ex.serverMessage)
            assertEquals("Rate limit reached from client IP", ex.message)
        }
    }

    @Test
    fun deleteVoteHitsDeleteEndpointWithRateLimitMapping() = runTest {
        var method: String? = null
        var url: String? = null
        var userTokenHeader: String? = null
        val token = UUID.randomUUID().toString()

        val result = client { request ->
            method = request.method
            url = request.url
            userTokenHeader = request.headers["X-User-Token"]
            HttpResponse(200, """{"voted":false,"voteCount":3}""")
        }.deleteVote("fr-12", token)

        assertEquals("DELETE", method)
        assertEquals("https://api.example.com/api/v1/feature-requests/fr-12/vote", url)
        assertEquals(token, userTokenHeader)
        assertFalse(result.voted)
        assertEquals(3, result.voteCount)

        try {
            client {
                HttpResponse(429, """{"error":"Too many votes. Please try again shortly."}""")
            }.deleteVote("fr-12", token)
            fail("Expected FeedbackException.RateLimited on 429")
        } catch (ex: FeedbackException.RateLimited) {
            assertEquals("Too many votes. Please try again shortly.", ex.serverMessage)
        }
    }

    @Test
    fun nonVoteEndpointsDoNotMapHttp429ToRateLimited() = runTest {
        try {
            client {
                HttpResponse(429, """{"error":"Rate limited"}""")
            }.fetchColumns()
            fail("Expected FeedbackException.UnexpectedStatus on 429")
        } catch (ex: FeedbackException.UnexpectedStatus) {
            assertEquals(429, ex.code)
            assertEquals("""{"error":"Rate limited"}""", ex.serverMessage)
        }
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
    fun postCommentUnwrapsCommentEnvelopeResponse() = runTest {
        var header: String? = null
        var url: String? = null
        val token = UUID.randomUUID().toString()
        val responseJson = """
            {
              "comment": {
                "id": "c-enveloped-1",
                "featureRequestId": "fr-42",
                "authorName": "Charlie",
                "authorEmail": "charlie@example.com",
                "authorAvatarUrl": "https://example.com/charlie.png",
                "authorClerkId": "u_app_scoped_charlie",
                "body": "This is wrapped in a comment envelope per OpenAPI spec.",
                "parentId": "c-root-1",
                "replyToClerkId": "u_app_scoped_alice",
                "replyToAuthorName": "Alice",
                "isHidden": false,
                "createdAt": "2026-03-15T08:30:00.000Z"
              }
            }
        """.trimIndent()

        val comment = client { request ->
            url = request.url
            header = request.headers["X-User-Token"]
            HttpResponse(201, responseJson)
        }.postComment(
            featureRequestId = "fr-42",
            draft = CommentDraft(
                body = "This is wrapped in a comment envelope per OpenAPI spec.",
                authorName = "Charlie",
                authorEmail = "charlie@example.com",
                authorAvatarUrl = "https://example.com/charlie.png",
                parentId = "c-root-1",
                replyToClerkId = "u_app_scoped_alice",
                replyToAuthorName = "Alice"
            ),
            userToken = token
        )

        assertEquals("https://api.example.com/api/v1/feature-requests/fr-42/comments", url)
        assertEquals(token, header)
        assertEquals("c-enveloped-1", comment.id)
        assertEquals("fr-42", comment.featureRequestId)
        assertEquals("Charlie", comment.authorName)
        assertEquals("charlie@example.com", comment.authorEmail)
        assertEquals("https://example.com/charlie.png", comment.authorAvatarUrl)
        assertEquals("u_app_scoped_charlie", comment.authorClerkId)
        assertEquals("This is wrapped in a comment envelope per OpenAPI spec.", comment.body)
        assertEquals("c-root-1", comment.parentId)
        assertEquals("u_app_scoped_alice", comment.replyToClerkId)
        assertEquals("Alice", comment.replyToAuthorName)
        assertEquals(false, comment.isHidden)
        assertEquals("2026-03-15T08:30:00.000Z", comment.createdAt)
    }

    @Test
    fun parseFeatureRequestCommentHandlesBothEnvelopeAndBareShapes() {
        val envelopedJson = org.json.JSONObject(
            """
            {
              "comment": {
                "id": "c-env",
                "featureRequestId": "fr-1",
                "authorName": "Dan",
                "body": "Enveloped comment",
                "createdAt": "2026-03-16T10:00:00Z"
              }
            }
            """.trimIndent()
        )
        val bareJson = org.json.JSONObject(
            """
            {
              "id": "c-bare",
              "featureRequestId": "fr-1",
              "authorName": "Dan",
              "body": "Bare comment",
              "createdAt": "2026-03-16T10:00:00Z"
            }
            """.trimIndent()
        )

        val parsedEnveloped = dev.cupthread.feedback.internal.parseFeatureRequestComment(envelopedJson)
        assertEquals("c-env", parsedEnveloped.id)
        assertEquals("Enveloped comment", parsedEnveloped.body)

        val parsedBare = dev.cupthread.feedback.internal.parseFeatureRequestComment(bareJson)
        assertEquals("c-bare", parsedBare.id)
        assertEquals("Bare comment", parsedBare.body)
    }

    @Test
    fun parseFeatureRequestCommentDecodesAppIdAndUpdatedAt() {
        val json = org.json.JSONObject(
            """
            {
              "id": "c-full",
              "featureRequestId": "fr-99",
              "appId": "app-test-42",
              "authorName": "Elena",
              "body": "Comment with new metadata fields",
              "createdAt": "2026-03-20T12:00:00Z",
              "updatedAt": "2026-03-20T12:05:00Z"
            }
            """.trimIndent()
        )

        val comment = dev.cupthread.feedback.internal.parseFeatureRequestComment(json)
        assertEquals("c-full", comment.id)
        assertEquals("fr-99", comment.featureRequestId)
        assertEquals("app-test-42", comment.appId)
        assertEquals("Elena", comment.authorName)
        assertEquals("Comment with new metadata fields", comment.body)
        assertEquals("2026-03-20T12:00:00Z", comment.createdAt)
        assertEquals("2026-03-20T12:05:00Z", comment.updatedAt)
    }

    @Test
    fun parseFeatureRequestCommentHandlesMissingOrNullFields() {
        val jsonWithNulls = org.json.JSONObject(
            """
            {
              "id": "c-minimal",
              "featureRequestId": null,
              "appId": null,
              "authorName": null,
              "body": "Minimal comment",
              "createdAt": null,
              "updatedAt": null
            }
            """.trimIndent()
        )

        val comment = dev.cupthread.feedback.internal.parseFeatureRequestComment(jsonWithNulls)
        assertEquals("c-minimal", comment.id)
        assertEquals("", comment.featureRequestId)
        assertNull(comment.appId)
        assertNull(comment.authorName)
        assertEquals("Minimal comment", comment.body)
        assertEquals("", comment.createdAt)
        assertNull(comment.updatedAt)
    }

    @Test
    fun fetchUserProfileReturnsProfileAppsAndRecentComments() = runTest {
        var url: String? = null
        val profileJson = """
            {
              "profile": {
                "clerkUserId": "u_abcdef1234567890abcdef1234567890",
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
        }.fetchUserProfile("u_abcdef1234567890abcdef1234567890")

        assertEquals("https://api.example.com/api/v1/users/u_abcdef1234567890abcdef1234567890/profile?appKey=$appKey", url)
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
    fun fetchUserProfileHandlesAnonymousPlaceholderForNonPseudonymOrOptedOutUser() = runTest {
        var url: String? = null
        val placeholderJson = """
            {
              "profile": {
                "clerkUserId": "user_raw_clerk_id",
                "displayName": null,
                "avatarUrl": null,
                "bio": null,
                "websiteUrl": null,
                "createdAt": "2026-01-01T00:00:00Z"
              },
              "publicApps": [],
              "recentComments": []
            }
        """.trimIndent()

        val result = client { request ->
            url = request.url
            HttpResponse(200, placeholderJson)
        }.fetchUserProfile("user_raw_clerk_id")

        assertEquals("https://api.example.com/api/v1/users/user_raw_clerk_id/profile?appKey=$appKey", url)
        assertNull(result.profile.displayName)
        assertNull(result.profile.avatarUrl)
        assertNull(result.profile.bio)
        assertNull(result.profile.websiteUrl)
        assertTrue(result.apps.isEmpty())
        assertTrue(result.recentComments.isEmpty())
    }

    @Test
    fun fetchUserProfileThrowsUnexpectedStatusOn404() = runTest {
        try {
            client {
                HttpResponse(404, """{"error":"User not found"}""")
            }.fetchUserProfile("u_unknown_hex_id")
            fail("Expected FeedbackException.UnexpectedStatus on 404")
        } catch (ex: FeedbackException.UnexpectedStatus) {
            assertEquals(404, ex.code)
            assertTrue(ex.serverMessage.contains("User not found"))
        }
    }

    @Test
    fun fetchUserProfileHandlesExplicitNullsInPayload() = runTest {
        val payloadWithNulls = """
            {
              "profile": {
                "clerkUserId": null,
                "displayName": null,
                "avatarUrl": null,
                "bio": null,
                "websiteUrl": null,
                "createdAt": null,
                "updatedAt": null,
                "hideComments": null
              },
              "publicApps": [
                {
                  "id": "app_1",
                  "name": "App One",
                  "appSlug": null,
                  "slug": "legacy-app-one"
                }
              ],
              "recentComments": [
                {
                  "id": "comm_1",
                  "body": "Comment text",
                  "createdAt": null,
                  "featureRequestId": "req_1",
                  "featureRequestTitle": "Title",
                  "appId": null,
                  "appName": "App One"
                }
              ]
            }
        """.trimIndent()

        val result = client {
            HttpResponse(200, payloadWithNulls)
        }.fetchUserProfile("u_null_test")

        assertEquals("", result.profile.clerkUserId)
        assertEquals("", result.profile.createdAt)
        assertEquals("", result.profile.updatedAt)
        assertFalse(result.profile.hideComments)
        assertEquals(1, result.apps.size)
        assertEquals("legacy-app-one", result.apps[0].slug)
        assertEquals(1, result.recentComments.size)
        assertEquals("", result.recentComments[0].appId)
        assertEquals("", result.recentComments[0].createdAt)
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

    @Test
    fun eraseEndUserSendsHeadersAndPayloadWithUserToken() = runTest {
        var capturedRequest: HttpRequest? = null
        val token = "anon_user_token_12345"
        val client = client { request ->
            capturedRequest = request
            HttpResponse(200, """{"erased":true,"endUserId":"enduser-777"}""")
        }
        val result = client.eraseEndUser(userToken = token)

        val req = requireNotNull(capturedRequest)
        assertEquals("POST", req.method)
        assertTrue(req.url.endsWith("/api/v1/me/erase"))
        assertEquals(token, req.headers["X-User-Token"])
        assertNull(req.headers["Authorization"])
        assertEquals("application/json", req.headers["Content-Type"])

        val bodyString = req.body?.let { String(it, Charsets.UTF_8) } ?: ""
        val json = JSONObject(bodyString)
        assertEquals("app_testkey123456", json.getString("appKey"))

        assertTrue(result.erased)
        assertEquals("enduser-777", result.endUserId)
    }

    @Test
    fun eraseEndUserSendsBearerTokenWhenProvided() = runTest {
        var capturedRequest: HttpRequest? = null
        val sessionToken = "clerk_session_abc"
        val client = client { request ->
            capturedRequest = request
            HttpResponse(200, """{"erased":true,"endUserId":"enduser-888"}""")
        }
        val result = client.eraseEndUser(sessionToken = sessionToken)

        val req = requireNotNull(capturedRequest)
        assertEquals("POST", req.method)
        assertEquals("Bearer $sessionToken", req.headers["Authorization"])
        assertNull(req.headers["X-User-Token"])
        assertTrue(result.erased)
        assertEquals("enduser-888", result.endUserId)
    }

    @Test
    fun eraseEndUserThrowsOnBlankTokens() = runTest {
        val client = client { HttpResponse(200, "{}") }
        try {
            client.eraseEndUser(userToken = "", sessionToken = "")
            fail("Expected IllegalArgumentException when both tokens are blank")
        } catch (_: IllegalArgumentException) {
        }

        try {
            client.eraseEndUser(userToken = "   ", sessionToken = null)
            fail("Expected IllegalArgumentException when userToken is whitespace and sessionToken null")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun eraseEndUserRejectsNonHttpsWhenTransmittingCredentials() = runTest {
        val insecureClient = FeedbackClient(
            config = FeedbackClientConfig(
                baseUrl = "http://insecure.example.com",
                appKey = appKey
            ),
            transport = HttpTransport { HttpResponse(200, "{}") }
        )
        try {
            insecureClient.eraseEndUser(sessionToken = "secret_session")
            fail("Expected IllegalArgumentException when sessionToken is sent to non-HTTPS URL")
        } catch (ex: IllegalArgumentException) {
            assertTrue(ex.message.orEmpty().contains("HTTPS"))
        }

        try {
            insecureClient.eraseEndUser(userToken = "secret_user_token")
            fail("Expected IllegalArgumentException when userToken is sent to non-HTTPS URL")
        } catch (ex: IllegalArgumentException) {
            assertTrue(ex.message.orEmpty().contains("HTTPS"))
        }
    }

    @Test
    fun eraseEndUserMaps401ToAuthenticationRequired() = runTest {
        val client = client { HttpResponse(401, """{"error":"Authentication required"}""") }
        try {
            client.eraseEndUser(userToken = "expired_or_invalid")
            fail("Expected FeedbackException.AuthenticationRequired on 401")
        } catch (_: FeedbackException.AuthenticationRequired) {
        }
    }

    @Test
    fun eraseEndUserSurfaces404AsUnexpectedStatus() = runTest {
        val client = client { HttpResponse(404, """{"erased":false,"error":"Profile not found"}""") }
        try {
            client.eraseEndUser(userToken = "unknown_token")
            fail("Expected FeedbackException.UnexpectedStatus on 404")
        } catch (ex: FeedbackException.UnexpectedStatus) {
            assertEquals(404, ex.code)
            assertTrue(ex.serverMessage.contains("Profile not found"))
        }
    }

    @Test
    fun eraseEndUserSurfaces429AsUnexpectedStatus() = runTest {
        val client = client { HttpResponse(429, """{"error":"Too many requests"}""") }
        try {
            client.eraseEndUser(userToken = "rate_limited_token")
            fail("Expected FeedbackException.UnexpectedStatus on 429")
        } catch (ex: FeedbackException.UnexpectedStatus) {
            assertEquals(429, ex.code)
            assertTrue(ex.serverMessage.contains("Too many requests"))
        }
    }

    @Test
    fun eraseEndUserThrowsInvalidResponseWhenErasedFieldMissing() = runTest {
        val client = client { HttpResponse(200, """{"endUserId":"user-1"}""") }
        try {
            client.eraseEndUser(userToken = "valid_tok")
            fail("Expected FeedbackException.InvalidResponse when erased field is missing")
        } catch (_: FeedbackException.InvalidResponse) {
        }
    }

    @Test
    fun parseEndUserEraseResultHandlesNullAndEmptyEndUserId() {
        val jsonWithNull = JSONObject("""{"erased":true,"endUserId":null}""")
        val res1 = dev.cupthread.feedback.internal.parseEndUserEraseResult(jsonWithNull)
        assertTrue(res1.erased)
        assertNull(res1.endUserId)

        val jsonWithEmpty = JSONObject("""{"erased":false,"id":""}""")
        val res2 = dev.cupthread.feedback.internal.parseEndUserEraseResult(jsonWithEmpty)
        assertFalse(res2.erased)
        assertNull(res2.endUserId)
    }

    @Test
    fun submitFeedbackSanitizesDraftMetadata() = runTest {
        var capturedRequest: HttpRequest? = null
        val client = client { request ->
            capturedRequest = request
            HttpResponse(201, """{"submissionId":"s-1","forwardedToGithub":false}""")
        }
        val draft = FeedbackDraft(
            title = "Test issue",
            description = "Detailed test issue",
            platform = FeedbackPlatform.ANDROID,
            metadata = mapOf(
                "app_token" to "secret_token_123",
                "device_model" to "Pixel 8 Pro",
                "invalid key with spaces" to "drop_me"
            )
        )
        client.submit(draft, userToken = "tok")

        val req = requireNotNull(capturedRequest)
        val body = req.body?.let { String(it, Charsets.UTF_8) } ?: ""
        val json = JSONObject(body)
        val meta = json.getJSONObject("metadata")

        assertEquals("[redacted]", meta.getString("app_token"))
        assertEquals("Pixel 8 Pro", meta.getString("device_model"))
        assertFalse(meta.has("invalid key with spaces"))
        assertEquals("cupthread-android", meta.getString("sdk"))
        assertEquals("android", meta.getString("platform"))
        assertTrue(meta.has("submittedAt"))
    }

    @Test
    fun submitFeedbackCapsTotalMetadataKeysAt24IncludingDefaults() = runTest {
        var capturedRequest: HttpRequest? = null
        val client = client { request ->
            capturedRequest = request
            HttpResponse(201, """{"submissionId":"s-2","forwardedToGithub":false}""")
        }
        val customMeta = (1..30).associate { i ->
            String.format(java.util.Locale.ROOT, "k_%02d", i) to "v_$i"
        }
        val draft = FeedbackDraft(
            title = "Test",
            description = "Desc",
            platform = FeedbackPlatform.ANDROID,
            metadata = customMeta
        )
        client.submit(draft, userToken = "tok")

        val req = requireNotNull(capturedRequest)
        val body = req.body?.let { String(it, Charsets.UTF_8) } ?: ""
        val json = JSONObject(body)
        val meta = json.getJSONObject("metadata")
        assertEquals(24, meta.length())
        assertEquals("cupthread-android", meta.getString("sdk"))
        assertEquals("android", meta.getString("platform"))
        assertTrue(meta.has("submittedAt"))
    }

    @Test
    fun submitFeatureRequestSubmitsPayloadAndParsesResult() = runTest {
        var capturedRequest: HttpRequest? = null
        val token = "anon_user_token_123"
        val client = client { request ->
            capturedRequest = request
            HttpResponse(201, """{"featureRequestId":"fr_new_1","pending":false}""")
        }
        val draft = FeatureRequestDraft(
            title = "Custom Keyboard Shortcuts",
            description = "Allow users to bind custom hotkeys.",
            requesterName = "Alice"
        )
        val result = client.submitFeatureRequest(draft, userToken = token)

        val req = requireNotNull(capturedRequest)
        assertEquals("POST", req.method)
        assertTrue(req.url.endsWith("/api/v1/feature-requests"))
        val bodyJson = JSONObject(String(req.body!!, Charsets.UTF_8))
        assertEquals("app_testkey123456", bodyJson.getString("appKey"))
        assertEquals("Custom Keyboard Shortcuts", bodyJson.getString("title"))
        assertEquals("Allow users to bind custom hotkeys.", bodyJson.getString("description"))
        assertEquals("Alice", bodyJson.getString("requesterName"))
        assertEquals(token, bodyJson.getString("requesterToken"))

        assertEquals("fr_new_1", result.featureRequestId)
        assertFalse(result.pending)
    }

    @Test
    fun submitFeatureRequestMaps402SubmissionQuotaExceeded() = runTest {
        val client = client {
            HttpResponse(
                402,
                """{"error":"Workspace monthly submission quota reached","code":"tier_limit_submissions"}"""
            )
        }
        val draft = FeatureRequestDraft(title = "Idea", description = "More details")
        try {
            client.submitFeatureRequest(draft, userToken = "tok")
            fail("Expected SubmissionQuotaExceeded exception")
        } catch (ex: FeedbackException.SubmissionQuotaExceeded) {
            assertEquals("Workspace monthly submission quota reached", ex.serverMessage)
            assertEquals("Workspace monthly submission quota reached", ex.message)
        }
    }

    @Test
    fun submitFeatureRequestMaps402SubscriptionInactive() = runTest {
        val client = client {
            HttpResponse(
                402,
                """{"error":"Workspace subscription is inactive","code":"subscription_inactive"}"""
            )
        }
        val draft = FeatureRequestDraft(title = "Idea", description = "More details")
        try {
            client.submitFeatureRequest(draft, userToken = "tok")
            fail("Expected SubscriptionInactive exception")
        } catch (ex: FeedbackException.SubscriptionInactive) {
            assertEquals("Workspace subscription is inactive", ex.serverMessage)
            assertEquals("Workspace subscription is inactive", ex.message)
        }
    }

    @Test
    fun submitFeedbackMaps402SubmissionQuotaExceeded() = runTest {
        val client = client {
            HttpResponse(
                402,
                """{"error":"Monthly feedback quota exceeded","code":"tier_limit_submissions"}"""
            )
        }
        val draft = FeedbackDraft(title = "Bug", description = "Repro steps", platform = FeedbackPlatform.ANDROID)
        try {
            client.submit(draft, userToken = "tok")
            fail("Expected SubmissionQuotaExceeded exception")
        } catch (ex: FeedbackException.SubmissionQuotaExceeded) {
            assertEquals("Monthly feedback quota exceeded", ex.serverMessage)
            assertEquals("Monthly feedback quota exceeded", ex.message)
        }
    }

    @Test
    fun submitFeedbackMaps402SubscriptionInactive() = runTest {
        val client = client {
            HttpResponse(
                402,
                """{"error":"Subscription has expired","code":"subscription_inactive"}"""
            )
        }
        val draft = FeedbackDraft(title = "Bug", description = "Repro steps", platform = FeedbackPlatform.ANDROID)
        try {
            client.submit(draft, userToken = "tok")
            fail("Expected SubscriptionInactive exception")
        } catch (ex: FeedbackException.SubscriptionInactive) {
            assertEquals("Subscription has expired", ex.serverMessage)
            assertEquals("Subscription has expired", ex.message)
        }
    }

    @Test
    fun submissionsMapUnknownOrMissing402CodeToUnexpectedStatus() = runTest {
        val clientUnknown = client {
            HttpResponse(
                402,
                """{"error":"Unknown billing issue","code":"some_other_code"}"""
            )
        }
        val draft = FeatureRequestDraft(title = "Idea", description = "Details")
        try {
            clientUnknown.submitFeatureRequest(draft, userToken = "tok")
            fail("Expected UnexpectedStatus for unknown 402 code")
        } catch (ex: FeedbackException.UnexpectedStatus) {
            assertEquals(402, ex.code)
            assertTrue(ex.serverMessage.contains("some_other_code"))
        }

        val clientNoCode = client {
            HttpResponse(402, """{"error":"Payment required"}""")
        }
        try {
            clientNoCode.submitFeatureRequest(draft, userToken = "tok")
            fail("Expected UnexpectedStatus for 402 without code")
        } catch (ex: FeedbackException.UnexpectedStatus) {
            assertEquals(402, ex.code)
            assertTrue(ex.serverMessage.contains("Payment required"))
        }
    }

    @Test
    fun nonSubmissionRequestsDoNotMap402ToQuotaExceptions() = runTest {
        val client = client {
            HttpResponse(
                402,
                """{"error":"Workspace monthly submission quota reached","code":"tier_limit_submissions"}"""
            )
        }
        try {
            client.fetchColumns()
            fail("Expected UnexpectedStatus for non-submission 402")
        } catch (ex: FeedbackException.UnexpectedStatus) {
            assertEquals(402, ex.code)
            assertTrue(ex.serverMessage.contains("tier_limit_submissions"))
        }
    }

    @Test
    fun submitFeedbackMaps422ScanRejectedToAttachmentScanRejected() = runTest {
        val client = client {
            HttpResponse(
                422,
                """{"error":"Upload object upl_abc was rejected by content scan: prohibited content","code":"scan_rejected"}"""
            )
        }
        val draft = FeedbackDraft(title = "Bug", description = "Repro steps", platform = FeedbackPlatform.ANDROID)
        try {
            client.submit(draft, userToken = "tok")
            fail("Expected AttachmentScanRejected exception")
        } catch (ex: FeedbackException.AttachmentScanRejected) {
            assertEquals("Upload object upl_abc was rejected by content scan: prohibited content", ex.serverMessage)
            assertEquals("Upload object upl_abc was rejected by content scan: prohibited content", ex.message)
        }
    }

    @Test
    fun submitFeedbackMaps422ScanRejectedWithoutErrorFieldToDefaultMessage() = runTest {
        val client = client {
            HttpResponse(
                422,
                """{"code":"scan_rejected"}"""
            )
        }
        val draft = FeedbackDraft(title = "Bug", description = "Repro steps", platform = FeedbackPlatform.ANDROID)
        try {
            client.submit(draft, userToken = "tok")
            fail("Expected AttachmentScanRejected exception")
        } catch (ex: FeedbackException.AttachmentScanRejected) {
            assertNull(ex.serverMessage)
            assertEquals("An attachment could not be uploaded due to content inspection rejection.", ex.message)
        }
    }

    @Test
    fun submitFeedbackMaps422ScanRejectedWithExplicitNullErrorToDefaultMessage() = runTest {
        val client = client {
            HttpResponse(
                422,
                """{"code":"scan_rejected","error":null}"""
            )
        }
        val draft = FeedbackDraft(title = "Bug", description = "Repro steps", platform = FeedbackPlatform.ANDROID)
        try {
            client.submit(draft, userToken = "tok")
            fail("Expected AttachmentScanRejected exception")
        } catch (ex: FeedbackException.AttachmentScanRejected) {
            assertNull(ex.serverMessage)
            assertEquals("An attachment could not be uploaded due to content inspection rejection.", ex.message)
        }
    }

    @Test
    fun submitFeedbackMapsOther422CodesToUnexpectedStatus() = runTest {
        val client = client {
            HttpResponse(
                422,
                """{"error":"Validation failed","code":"unprocessable_entity"}"""
            )
        }
        val draft = FeedbackDraft(title = "Bug", description = "Repro steps", platform = FeedbackPlatform.ANDROID)
        try {
            client.submit(draft, userToken = "tok")
            fail("Expected UnexpectedStatus exception")
        } catch (ex: FeedbackException.UnexpectedStatus) {
            assertEquals(422, ex.code)
            assertTrue(ex.serverMessage.contains("Validation failed"))
        }
    }

    @Test
    fun nonSubmitEndpointsDoNotMapHttp422ToAttachmentScanRejected() = runTest {
        val client = client {
            HttpResponse(
                422,
                """{"error":"Upload object upl_abc was rejected by content scan: prohibited content","code":"scan_rejected"}"""
            )
        }
        try {
            client.fetchColumns()
            fail("Expected UnexpectedStatus exception")
        } catch (ex: FeedbackException.UnexpectedStatus) {
            assertEquals(422, ex.code)
        }
    }

    @Test
    fun submissionQuotaExceptionsUseDefaultMessagesWhenServerMessageIsOmitted() {
        val quotaEx = FeedbackException.SubmissionQuotaExceeded(null)
        assertNull(quotaEx.serverMessage)
        assertEquals(
            "This app has reached its submission limit for this month. Please try again later.",
            quotaEx.message
        )

        val inactiveEx = FeedbackException.SubscriptionInactive("")
        assertEquals("", inactiveEx.serverMessage)
        assertEquals(
            "Submissions are unavailable for this app right now. Please try again later.",
            inactiveEx.message
        )
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
