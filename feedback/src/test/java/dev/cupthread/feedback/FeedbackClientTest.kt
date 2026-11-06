package dev.cupthread.feedback

import dev.cupthread.feedback.internal.HttpRequest
import dev.cupthread.feedback.internal.HttpResponse
import dev.cupthread.feedback.internal.HttpTransport
import dev.cupthread.feedback.internal.ImageMetadataScrubber
import dev.cupthread.feedback.internal.hmacSha256Hex
import dev.cupthread.feedback.internal.parseAttachment
import dev.cupthread.feedback.internal.shouldFollowRedirects
import dev.cupthread.feedback.internal.userAttributesCanonicalString
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FeedbackClientTest {
    private val appKey = "app_testkey123456"
    private val signingSecret = "cpt_signing_secret_test"

    private fun client(handler: (HttpRequest) -> HttpResponse): FeedbackClient =
        FeedbackClient(
            config = FeedbackClientConfig(baseUrl = "https://api.example.com", appKey = appKey),
            transport = HttpTransport { handler(it) }
        )

    private fun signingClient(handler: (HttpRequest) -> HttpResponse): FeedbackClient =
        FeedbackClient(
            config = FeedbackClientConfig(
                baseUrl = "https://api.example.com",
                appKey = appKey,
                sdkSigningSecret = signingSecret
            ),
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
    fun fetchAppConfigDecodesAllowedEmbedOriginsWhenPresent() = runTest {
        // #50: optional allowedEmbedOrigins added to the public app config (SaaS SEC-518)
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
              "allowedEmbedOrigins":["https://docs.example.com", "https://app.partner.com:8443"]
            }
        """.trimIndent()
        val result = client { HttpResponse(200, json) }.fetchAppConfig()
        assertEquals(listOf("https://docs.example.com", "https://app.partner.com:8443"), result.allowedEmbedOrigins)
    }

    @Test
    fun fetchAppConfigDefaultsAllowedEmbedOriginsToNullWhenAbsent() = runTest {
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
        assertNull(result.allowedEmbedOrigins)
    }

    @Test
    fun fetchAppConfigHandlesNullOrEmptyAllowedEmbedOrigins() = runTest {
        val jsonWithNull = """
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
              "allowedEmbedOrigins":null
            }
        """.trimIndent()
        val resultNull = client { HttpResponse(200, jsonWithNull) }.fetchAppConfig()
        assertNull(resultNull.allowedEmbedOrigins)

        val jsonWithEmptyArray = """
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
              "allowedEmbedOrigins":[]
            }
        """.trimIndent()
        val resultEmpty = client { HttpResponse(200, jsonWithEmptyArray) }.fetchAppConfig()
        assertNull(resultEmpty.allowedEmbedOrigins)

        val jsonWithBlankStrings = """
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
              "allowedEmbedOrigins":["   ", ""]
            }
        """.trimIndent()
        val resultBlank = client { HttpResponse(200, jsonWithBlankStrings) }.fetchAppConfig()
        assertNull(resultBlank.allowedEmbedOrigins)
    }

    @Test
    fun publicAppConfigBackwardCompatibleConstructorPreservesNullOrigins() {
        val config = PublicAppConfig(
            appId = "app-1",
            appKey = "key-1",
            slug = "slug-1",
            name = "App 1",
            storeUrl = null,
            storeKind = null,
            iconUrl = null,
            allowPublic = true,
            allowedPlatforms = listOf(FeedbackPlatform.ANDROID),
            maxAttachmentBytes = 10_000_000L,
            allowAnonymousRoadmap = true,
            allowAnonymousVote = true,
            allowAnonymousFeedback = true,
            allowAnonymousChangelog = true
        )
        assertNull(config.allowedEmbedOrigins)

        // Verify copy preserves allowedEmbedOrigins and backward-compatible copy method exists
        val copyConfig = config.copy(name = "Updated App")
        assertEquals("Updated App", copyConfig.name)
        assertNull(copyConfig.allowedEmbedOrigins)

        val copyMethods = PublicAppConfig::class.java.declaredMethods.filter { it.name == "copy" }
        assertTrue("Expected 20-param copy method for primary constructor with allowedEmbedOrigins", copyMethods.any { it.parameterCount == 20 })
        assertTrue("Expected 19-param copy method for binary backward compatibility", copyMethods.any { it.parameterCount == 19 })

        val copyDefaultMethods = PublicAppConfig::class.java.declaredMethods.filter { it.name == "copy\$default" }
        assertTrue("Expected 23-param copy\$default for primary constructor with allowedEmbedOrigins", copyDefaultMethods.any { it.parameterCount == 23 })
        assertTrue("Expected 22-param copy\$default for binary backward compatibility", copyDefaultMethods.any { it.parameterCount == 22 })

        val constructors = PublicAppConfig::class.java.declaredConstructors
        assertTrue("Expected 20-param constructor with allowedEmbedOrigins", constructors.any { it.parameterTypes.size == 20 })
        assertTrue("Expected 19-param constructor for binary backward compatibility", constructors.any { it.parameterTypes.size == 19 })
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

    /** A complete minimal JPEG: the scrubber rejects structurally truncated images outright. */
    private val validJpegBytes = TestImageFixtures.jpeg(
        TestImageFixtures.jpegSegment(0xE0, TestImageFixtures.jfifPayload())
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
    fun uploadAttachmentStripsGpsMetadataFromImageBeforeUpload() = runTest {
        // #21: the exact EXIF GPS bytes must never appear in the multipart body.
        val photoWithGps = TestImageFixtures.jpegWithExif(bigEndian = false, includeGps = true)
        val scrubbedPhoto = ImageMetadataScrubber.scrub(photoWithGps)!!
        assertNotSame(photoWithGps, scrubbedPhoto)
        var sentBody: ByteArray? = null
        val json = """
            {
              "kind": "image",
              "uploadId": "upl_img_21",
              "status": "uploaded",
              "stored": true,
              "filename": "photo.jpg",
              "mimeType": "image/jpeg",
              "size": ${scrubbedPhoto.size}
            }
        """.trimIndent()

        val attachment = client { request ->
            sentBody = request.body
            HttpResponse(201, json)
        }.uploadAttachment(data = photoWithGps, filename = "photo.jpg", mimeType = "image/jpeg")

        assertEquals("upl_img_21", attachment.uploadId)
        val body = requireNotNull(sentBody)
        // The scrubbed payload is what actually goes on the wire...
        assertTrue(TestImageFixtures.indexOf(body, scrubbedPhoto) >= 0)
        // ...and neither the GPS coordinate rationals nor any GPS IFD entry data survive.
        assertFalse(
            TestImageFixtures.indexOf(
                body,
                TestImageFixtures.fixtureGpsLatitudeBytes(bigEndian = false)
            ) >= 0
        )
        assertFalse(
            TestImageFixtures.indexOf(
                body,
                TestImageFixtures.fixtureGpsLongitudeBytes(bigEndian = false)
            ) >= 0
        )
    }

    @Test
    fun uploadAttachmentKeepsCleanImageBytesIntact() = runTest {
        val cleanPhoto = TestImageFixtures.jpegWithExif(bigEndian = false, includeGps = false)
        var sentBody: ByteArray? = null
        client { request ->
            sentBody = request.body
            HttpResponse(201, """{"kind":"image","uploadId":"upl_2","stored":true}""")
        }.uploadAttachment(data = cleanPhoto, filename = "photo.jpg", mimeType = "image/jpeg")

        // A photo without metadata is forwarded byte-for-byte.
        assertTrue(TestImageFixtures.indexOf(requireNotNull(sentBody), cleanPhoto) >= 0)
    }

    @Test
    fun uploadAttachmentScrubsImageEvenWhenRoutedToUploadSessions() = runTest {
        // #21: an explicit preferredKind=R2 must not bypass the scrubber for image bytes.
        val photoWithGps = TestImageFixtures.jpegWithExif(bigEndian = false, includeGps = true)
        val requests = mutableListOf<HttpRequest>()
        client { request ->
            requests += request
            if (request.url.endsWith("/uploads/sessions")) {
                uploadSessionResponseFor(request)
            } else {
                HttpResponse(
                    200,
                    """{"uploadId":"upl_img_r2","clientFileId":"c","filename":"photo.jpg",""" +
                        """"contentType":"image/jpeg","sizeBytes":${photoWithGps.size},"stored":true}"""
                )
            }
        }.uploadAttachment(
            data = photoWithGps,
            filename = "photo.jpg",
            mimeType = "image/jpeg",
            preferredKind = FeedbackAttachment.Kind.R2,
            userToken = "usr_tok_r2"
        )

        val putRequest = requests.last { it.method == "PUT" }
        val body = requireNotNull(putRequest.body)
        assertFalse(
            TestImageFixtures.indexOf(
                body,
                TestImageFixtures.fixtureGpsLatitudeBytes(bigEndian = false)
            ) >= 0
        )
        // Display-relevant metadata survives the scrub, proving the payload was scrubbed not dropped.
        assertTrue(TestImageFixtures.indexOf(body, "CupThreadCam".toByteArray(Charsets.US_ASCII)) >= 0)
    }

    @Test
    fun uploadAttachmentSendsNonImageBytesUntouched() = runTest {
        val logBytes = "SEVERE: crash log with tokens".toByteArray(Charsets.US_ASCII)
        val requests = mutableListOf<HttpRequest>()
        client { request ->
            requests += request
            if (request.url.endsWith("/uploads/sessions")) {
                uploadSessionResponseFor(request)
            } else {
                HttpResponse(
                    200,
                    """{"uploadId":"upl_log","clientFileId":"c","filename":"log.txt",""" +
                        """"contentType":"text/plain","sizeBytes":${logBytes.size},"stored":true}"""
                )
            }
        }.uploadAttachment(
            data = logBytes,
            filename = "log.txt",
            mimeType = "text/plain",
            userToken = "usr_tok_scrub"
        )

        // Non-image attachments bypass the scrubber entirely.
        val putRequest = requests.last { it.method == "PUT" }
        assertTrue(TestImageFixtures.indexOf(requireNotNull(putRequest.body), logBytes) >= 0)
    }

    @Test
    fun uploadAttachmentRejectsStructurallyCorruptImageOnEveryBackend() = runTest {
        // A recognized JPEG with corrupt framing cannot be promised metadata-free, so it must
        // be rejected locally — even when preferredKind routes it to the upload-session backend.
        val corrupt = TestImageFixtures.SOI +
            TestImageFixtures.jpegSegment(0xE0, TestImageFixtures.jfifPayload()) +
            byteArrayOf(0x12, 0x34) + // a marker was required here
            TestImageFixtures.SCAN_BYTES + TestImageFixtures.EOI
        var requests = 0
        val failingClient = client { _ ->
            requests++
            HttpResponse(200, """{"uploadId":"upl_x","stored":true}""")
        }
        try {
            failingClient.uploadAttachment(
                data = corrupt,
                filename = "broken.jpg",
                mimeType = "image/jpeg",
                preferredKind = FeedbackAttachment.Kind.R2,
                userToken = "usr_tok_r2"
            )
            fail("structurally corrupt image uploads must be rejected")
        } catch (expected: FeedbackException.UnsupportedMediaType) {
            assertNull(expected.requestId)
        }
        assertEquals(0, requests) // rejected before any network I/O
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
    fun uploadAttachmentSucceedsWithModernPrivateResponseWithoutKeyOrUrl() = runTest {
        val json = """
            {
              "kind": "image",
              "uploadId": "upl_abc123xyz",
              "status": "uploaded",
              "stored": true,
              "filename": "shot.png",
              "mimeType": "image/png",
              "size": 12345,
              "sha256": "abcdef0123456789"
            }
        """.trimIndent()

        val attachment = client { HttpResponse(200, json) }.uploadAttachment(
            data = validPngBytes,
            filename = "shot.png",
            mimeType = "image/png"
        )

        assertEquals(FeedbackAttachment.Kind.IMAGE, attachment.kind)
        assertEquals("upl_abc123xyz", attachment.uploadId)
        assertEquals("upl_abc123xyz", attachment.id)
        assertNull(attachment.key)
        assertNull(attachment.url)
        assertEquals("shot.png", attachment.filename)
        assertEquals("image/png", attachment.mimeType)
        assertEquals(12345L, attachment.size)
    }

    @Test
    fun parseAttachmentHandlesBothModernAndLegacySchemas() {
        val modernJson = JSONObject(
            """
            {
              "kind": "image",
              "uploadId": "upl_9876",
              "status": "uploaded",
              "stored": true,
              "filename": "screen.png",
              "mimeType": "image/png",
              "size": 4096
            }
            """.trimIndent()
        )
        val modern = parseAttachment(modernJson)
        assertEquals(FeedbackAttachment.Kind.IMAGE, modern.kind)
        assertEquals("upl_9876", modern.uploadId)
        assertEquals("upl_9876", modern.id)
        assertNull(modern.key)
        assertNull(modern.url)
        assertEquals("screen.png", modern.filename)
        assertEquals("image/png", modern.mimeType)
        assertEquals(4096L, modern.size)

        val legacyJson = JSONObject(
            """
            {
              "kind": "image",
              "key": "uploads/img-legacy.png",
              "url": "https://cdn.example.com/uploads/img-legacy.png",
              "filename": "screen.png",
              "mimeType": "image/png",
              "size": 2048
            }
            """.trimIndent()
        )
        val legacy = parseAttachment(legacyJson)
        assertEquals(FeedbackAttachment.Kind.IMAGE, legacy.kind)
        assertNull(legacy.uploadId)
        assertEquals("uploads/img-legacy.png", legacy.key)
        assertEquals("https://cdn.example.com/uploads/img-legacy.png", legacy.url)
        assertEquals("uploads/img-legacy.png", legacy.id)
        assertEquals("screen.png", legacy.filename)
        assertEquals(2048L, legacy.size)
    }

    @Test
    fun parseAttachmentRejectsResponseWithoutUploadIdOrKey() {
        val jsonWithoutIdOrKey = JSONObject(
            """
            {
              "kind": "image",
              "url": "https://cdn.example.com/orphan.png",
              "filename": "screen.png"
            }
            """.trimIndent()
        )
        try {
            parseAttachment(jsonWithoutIdOrKey)
            fail("expected IllegalArgumentException")
        } catch (ex: IllegalArgumentException) {
            assertTrue(ex.message!!.contains("uploadId or key"))
        }

        val jsonWithBlankIds = JSONObject(
            """
            {
              "kind": "image",
              "uploadId": "   ",
              "key": "",
              "url": "https://cdn.example.com/orphan.png"
            }
            """.trimIndent()
        )
        try {
            parseAttachment(jsonWithBlankIds)
            fail("expected IllegalArgumentException")
        } catch (ex: IllegalArgumentException) {
            assertTrue(ex.message!!.contains("uploadId or key"))
        }
    }

    @Test
    fun uploadAttachmentThrowsUnreadableUploadResponseWhenResponseMissingIdentifier() = runTest {
        val jsonWithoutIdentifier = """
            {
              "kind": "image",
              "url": "https://cdn.example.com/orphan.png",
              "filename": "screen.png"
            }
        """.trimIndent()

        try {
            client { HttpResponse(200, jsonWithoutIdentifier) }.uploadAttachment(
                data = validPngBytes,
                filename = "screen.png",
                mimeType = "image/png"
            )
            fail("expected UnreadableUploadResponse")
        } catch (_: FeedbackException.UnreadableUploadResponse) {
        }
    }

    @Test
    fun feedbackAttachmentCompatibilityAndIdHelper() {
        val modern = FeedbackAttachment(
            kind = FeedbackAttachment.Kind.IMAGE,
            uploadId = "upl_test"
        )
        assertEquals("upl_test", modern.id)
        assertNull(modern.key)
        assertNull(modern.url)

        val legacy = FeedbackAttachment(
            kind = FeedbackAttachment.Kind.R2,
            key = "k_test",
            url = "https://example.com/k"
        )
        assertEquals("k_test", legacy.id)
        assertEquals("k_test", legacy.key)
        assertEquals("https://example.com/k", legacy.url)
        assertNull(legacy.uploadId)

        val both = FeedbackAttachment(
            kind = FeedbackAttachment.Kind.IMAGE,
            key = "k_test",
            url = "https://example.com/k",
            uploadId = "upl_test"
        )
        assertEquals("upl_test", both.id)
    }

    @Test
    fun uploadAttachmentRoutesEndpointsBasedOnMimeAndPreferredKind() = runTest {
        val jsonImage = """{"kind":"image","key":"img","url":"https://cdn.example.com/img"}"""
        val uploadedJson = """
            {"uploadId":"upl_slot1","clientFileId":"c1","filename":"doc.bin","contentType":"application/pdf","sizeBytes":3,"sha256":"aa","stored":true}
        """.trimIndent()

        var urlImage: String? = null
        client { request ->
            urlImage = request.url
            HttpResponse(200, jsonImage)
        }.uploadAttachment(data = validJpegBytes, filename = "photo.jpg", mimeType = "image/jpeg")
        assertEquals("https://api.example.com/api/v1/uploads/images", urlImage)

        val r2Urls = mutableListOf<String>()
        client { request ->
            r2Urls += request.url
            if (request.url.endsWith("/uploads/sessions")) {
                uploadSessionResponseFor(request)
            } else {
                HttpResponse(200, uploadedJson)
            }
        }.uploadAttachment(data = byteArrayOf(1), filename = "log.txt", mimeType = "text/plain", userToken = "tok")
        assertEquals(
            listOf(
                "https://api.example.com/api/v1/uploads/sessions",
                "https://api.example.com/api/v1/uploads/upl_slot1"
            ),
            r2Urls
        )

        val overrideUrls = mutableListOf<String>()
        client { request ->
            overrideUrls += request.url
            if (request.url.endsWith("/uploads/sessions")) {
                uploadSessionResponseFor(request)
            } else {
                HttpResponse(200, uploadedJson)
            }
        }.uploadAttachment(
            data = validPngBytes,
            filename = "photo.png",
            mimeType = "image/png",
            preferredKind = FeedbackAttachment.Kind.R2,
            userToken = "tok"
        )
        assertEquals(
            listOf(
                "https://api.example.com/api/v1/uploads/sessions",
                "https://api.example.com/api/v1/uploads/upl_slot1"
            ),
            overrideUrls
        )
    }

    /** Parses the clientFileId out of a session-create request body and echoes it in a valid 201 response. */
    private fun uploadSessionResponseFor(request: HttpRequest): HttpResponse =
        HttpResponse(201, uploadSessionJson(clientFileIdFromSessionRequest(request)))

    private fun clientFileIdFromSessionRequest(request: HttpRequest): String =
        JSONObject(String(requireNotNull(request.body), Charsets.UTF_8))
            .getJSONArray("files")
            .getJSONObject(0)
            .getString("clientFileId")

    private fun uploadSessionJson(clientFileId: String): String = """
        {
          "session": {
            "sessionId": "ups_1",
            "sessionToken": "cpt_up_t0k3n",
            "expiresAt": "2026-10-27T12:00:00.000Z",
            "maxFileSizeBytes": 20000000,
            "maxFiles": 8
          },
          "files": [
            {
              "clientFileId": "$clientFileId",
              "uploadId": "upl_slot1",
              "uploadUrl": "/api/v1/uploads/upl_slot1",
              "maxSizeBytes": 20000000
            }
          ]
        }
    """.trimIndent()

    @Test
    fun uploadAttachmentNonImageUsesUploadSessionFlow() = runTest {
        val uploadedJson = """
            {"uploadId":"upl_slot1","clientFileId":"c1","filename":"log.txt","contentType":"text/plain","sizeBytes":2,"sha256":"aa","stored":true}
        """.trimIndent()
        val requests = mutableListOf<HttpRequest>()

        val attachment = client { request ->
            requests += request
            if (request.url.endsWith("/uploads/sessions")) {
                uploadSessionResponseFor(request)
            } else {
                HttpResponse(200, uploadedJson)
            }
        }.uploadAttachment(
            data = byteArrayOf(0x0A, 0x0D),
            filename = "log.txt",
            mimeType = "text/plain",
            userToken = "usr_tok_session"
        )

        assertEquals(2, requests.size)

        val create = requests[0]
        assertEquals("POST", create.method)
        assertEquals("https://api.example.com/api/v1/uploads/sessions", create.url)
        assertEquals("usr_tok_session", create.headers["X-User-Token"])
        assertEquals("application/json", create.headers["Content-Type"])
        val createBody = JSONObject(String(requireNotNull(create.body), Charsets.UTF_8))
        assertEquals(appKey, createBody.getString("appKey"))
        assertEquals("feedback_attachment", createBody.getString("purpose"))
        val fileInput = createBody.getJSONArray("files").getJSONObject(0)
        assertEquals("log.txt", fileInput.getString("filename"))
        assertEquals("text/plain", fileInput.getString("contentType"))
        assertEquals(2, fileInput.getInt("sizeBytes"))

        val put = requests[1]
        assertEquals("PUT", put.method)
        assertEquals("https://api.example.com/api/v1/uploads/upl_slot1", put.url)
        assertEquals("Bearer cpt_up_t0k3n", put.headers["Authorization"])
        assertEquals("text/plain", put.headers["Content-Type"])
        assertFalse(put.headers.containsKey("X-User-Token"))
        assertEquals(listOf<Byte>(0x0A, 0x0D), requireNotNull(put.body).toList())

        assertEquals(FeedbackAttachment.Kind.R2, attachment.kind)
        assertEquals("upl_slot1", attachment.uploadId)
        assertEquals("upl_slot1", attachment.id)
        assertNull(attachment.key)
        assertNull(attachment.url)
        assertEquals("log.txt", attachment.filename)
        assertEquals("text/plain", attachment.mimeType)
        assertEquals(2L, attachment.size)
        assertEquals("usr_tok_session", attachment.userToken)
    }

    @Test
    fun uploadAttachmentPopulatesUserTokenInFeedbackAttachment() = runTest {
        val uploadedJson = """
            {"uploadId":"upl_slot1","clientFileId":"c1","filename":"log.txt","contentType":"text/plain","sizeBytes":2,"sha256":"aa","stored":true}
        """.trimIndent()
        val attachment = client { request ->
            if (request.url.endsWith("/uploads/sessions")) {
                uploadSessionResponseFor(request)
            } else {
                HttpResponse(200, uploadedJson)
            }
        }.uploadAttachment(
            data = byteArrayOf(0x0A, 0x0D),
            filename = "log.txt",
            mimeType = "text/plain",
            userToken = "usr_tok_session"
        )
        assertEquals("usr_tok_session", attachment.userToken)
    }

    @Test
    fun uploadAttachmentMapsHttp400UploaderIdentityRequiredToException() = runTest {
        try {
            client { _ ->
                HttpResponse(
                    400,
                    """{"error":"A valid X-User-Token UUID is required to create an upload session when not signed in","code":"uploader_identity_required"}""",
                    headers = mapOf("X-Request-Id" to "req_upl_id_1")
                )
            }.uploadAttachment(
                data = byteArrayOf(1),
                filename = "log.txt",
                mimeType = "text/plain",
                userToken = "invalid_non_uuid_token"
            )
            fail("expected UploaderIdentityRequired")
        } catch (e: FeedbackException.UploaderIdentityRequired) {
            assertEquals("A valid X-User-Token UUID is required to create an upload session when not signed in", e.serverMessage)
            assertEquals("uploader_identity_required", e.serverCode)
            assertEquals("req_upl_id_1", e.requestId)
        }
    }

    @Test
    fun uploadAttachmentMapsHttp400UploaderIdentityRequiredWithBlankBodyToDefaultMessage() = runTest {
        try {
            client { _ ->
                HttpResponse(
                    400,
                    """{"code":"uploader_identity_required"}""",
                    headers = mapOf("X-Request-Id" to "req_upl_id_blank")
                )
            }.uploadAttachment(
                data = byteArrayOf(1),
                filename = "log.txt",
                mimeType = "text/plain",
                userToken = "invalid_token"
            )
            fail("expected UploaderIdentityRequired")
        } catch (e: FeedbackException.UploaderIdentityRequired) {
            assertNull(e.serverMessage)
            assertEquals("A valid user token is required to create an upload session.", e.message)
            assertEquals("uploader_identity_required", e.serverCode)
            assertEquals("req_upl_id_blank", e.requestId)
        }
    }

    @Test
    fun submitMapsHttp400UploaderMismatchToException() = runTest {
        val draft = FeedbackDraft(
            title = "Bug",
            description = "Repro steps",
            platform = FeedbackPlatform.ANDROID,
            attachments = listOf(
                FeedbackAttachment(
                    kind = FeedbackAttachment.Kind.R2,
                    uploadId = "upl_session_1",
                    filename = "log.txt",
                    mimeType = "text/plain",
                    size = 100L
                )
            )
        )
        try {
            client { _ ->
                HttpResponse(
                    400,
                    """{"error":"Upload session was created by a different uploader","code":"uploader_mismatch"}""",
                    headers = mapOf("X-Request-Id" to "req_upl_mis_1")
                )
            }.submit(draft, userToken = "tok_different")
            fail("expected UploaderMismatch")
        } catch (e: FeedbackException.UploaderMismatch) {
            assertEquals("Upload session was created by a different uploader", e.serverMessage)
            assertEquals("uploader_mismatch", e.serverCode)
            assertEquals("req_upl_mis_1", e.requestId)
        }
    }

    @Test
    fun submitMapsHttp400UploaderIdentityRequiredToException() = runTest {
        val draft = FeedbackDraft(
            title = "Bug",
            description = "Repro steps",
            platform = FeedbackPlatform.ANDROID,
            attachments = listOf(
                FeedbackAttachment(
                    kind = FeedbackAttachment.Kind.R2,
                    uploadId = "upl_session_2",
                    filename = "log.txt",
                    mimeType = "text/plain",
                    size = 100L
                )
            )
        )
        try {
            client { _ ->
                HttpResponse(
                    400,
                    """{"error":"A valid X-User-Token UUID is required","code":"uploader_identity_required"}""",
                    headers = mapOf("X-Request-Id" to "req_upl_id_2")
                )
            }.submit(draft, userToken = "tok_unbound")
            fail("expected UploaderIdentityRequired")
        } catch (e: FeedbackException.UploaderIdentityRequired) {
            assertEquals("A valid X-User-Token UUID is required", e.serverMessage)
            assertEquals("uploader_identity_required", e.serverCode)
            assertEquals("req_upl_id_2", e.requestId)
        }
    }

    @Test
    fun submitRejectsMismatchedAttachmentUserTokenClientSide() = runTest {
        var requestSent = false
        val draft = FeedbackDraft(
            title = "Bug",
            description = "Repro steps",
            platform = FeedbackPlatform.ANDROID,
            attachments = listOf(
                FeedbackAttachment(
                    kind = FeedbackAttachment.Kind.R2,
                    uploadId = "upl_slot1",
                    filename = "log.txt",
                    mimeType = "text/plain",
                    size = 10L,
                    userToken = "tok_uploader_A"
                )
            )
        )
        try {
            client { _ ->
                requestSent = true
                HttpResponse(200, """{"submissionId":"s-1","forwardedToGithub":false}""")
            }.submit(draft, userToken = "tok_uploader_B")
            fail("expected UploaderMismatch")
        } catch (e: FeedbackException.UploaderMismatch) {
            assertFalse(requestSent)
            assertTrue(e.message?.contains("does not match submission userToken") == true)
            assertFalse(e.message?.contains("tok_uploader_A") == true)
            assertFalse(e.message?.contains("tok_uploader_B") == true)
        }
    }

    @Test
    fun submitRejectsMissingUserTokenForSessionAttachmentClientSide() = runTest {
        var requestSent = false
        val draft = FeedbackDraft(
            title = "Bug",
            description = "Repro steps",
            platform = FeedbackPlatform.ANDROID,
            attachments = listOf(
                FeedbackAttachment(
                    kind = FeedbackAttachment.Kind.R2,
                    uploadId = "upl_slot1",
                    filename = "log.txt",
                    mimeType = "text/plain",
                    size = 10L,
                    userToken = "tok_uploader_A"
                )
            )
        )
        try {
            client { _ ->
                requestSent = true
                HttpResponse(200, """{"submissionId":"s-1","forwardedToGithub":false}""")
            }.submit(draft, userToken = null)
            fail("expected UploaderIdentityRequired")
        } catch (e: FeedbackException.UploaderIdentityRequired) {
            assertFalse(requestSent)
            assertTrue(e.message?.contains("has no userToken") == true)
        }
    }

    @Test
    fun submitAllowsMatchingUserTokenWithSessionAttachment() = runTest {
        var capturedToken: String? = null
        val draft = FeedbackDraft(
            title = "Bug",
            description = "Repro steps",
            platform = FeedbackPlatform.ANDROID,
            attachments = listOf(
                FeedbackAttachment(
                    kind = FeedbackAttachment.Kind.R2,
                    uploadId = "upl_slot1",
                    filename = "log.txt",
                    mimeType = "text/plain",
                    size = 10L,
                    userToken = "tok_uploader_match"
                )
            )
        )
        val result = client { request ->
            capturedToken = request.headers["X-User-Token"]
            HttpResponse(200, """{"submissionId":"sub_123","forwardedToGithub":false}""")
        }.submit(draft, userToken = "tok_uploader_match")
        assertEquals("tok_uploader_match", capturedToken)
        assertEquals("sub_123", result.submissionId)
    }

    @Test
    fun feedbackAttachmentBinaryBackwardCompatibility() {
        val attachment7 = FeedbackAttachment(
            kind = FeedbackAttachment.Kind.R2,
            uploadId = "upl_test",
            filename = "file.txt"
        )
        assertNull(attachment7.userToken)
        val copy7 = attachment7.copy(filename = "new.txt")
        assertEquals("new.txt", copy7.filename)
        assertNull(copy7.userToken)

        val copyMethods = FeedbackAttachment::class.java.declaredMethods.filter { it.name == "copy" }
        assertTrue("Expected 8-param copy method for primary constructor with userToken", copyMethods.any { it.parameterCount == 8 })
        assertTrue("Expected 7-param copy method for binary backward compatibility", copyMethods.any { it.parameterCount == 7 })

        val constructors = FeedbackAttachment::class.java.declaredConstructors
        assertTrue("Expected 8-param constructor with userToken", constructors.any { it.parameterTypes.size == 8 })
        assertTrue("Expected 7-param constructor for binary backward compatibility", constructors.any { it.parameterTypes.size == 7 })
    }

    @Test
    fun uploadAttachmentNonImageRequiresUserToken() = runTest {
        val attempts = listOf<String?>(null, "   ")
        for (userToken in attempts) {
            var requestCount = 0
            try {
                client { _ ->
                    requestCount += 1
                    HttpResponse(201, uploadSessionJson("c1"))
                }.uploadAttachment(
                    data = byteArrayOf(1),
                    filename = "log.txt",
                    mimeType = "text/plain",
                    userToken = userToken
                )
                fail("expected IllegalArgumentException for userToken=$userToken")
            } catch (_: IllegalArgumentException) {
                assertEquals(0, requestCount)
            }
        }
    }

    @Test
    fun uploadAttachmentNonImageRejectsNonHttpsBaseUrl() = runTest {
        var requestCount = 0
        try {
            FeedbackClient(
                config = FeedbackClientConfig(baseUrl = "http://api.example.com", appKey = appKey),
                transport = HttpTransport { _ ->
                    requestCount += 1
                    HttpResponse(201, uploadSessionJson("c1"))
                }
            ).uploadAttachment(
                data = byteArrayOf(1),
                filename = "log.txt",
                mimeType = "text/plain",
                userToken = "tok"
            )
            fail("expected IllegalArgumentException for non-HTTPS baseUrl")
        } catch (ex: IllegalArgumentException) {
            assertTrue(ex.message!!.contains("HTTPS"))
            assertEquals(0, requestCount)
        }
    }

    @Test
    fun uploadAttachmentNonImageThrowsUnreadableUploadResponseOnBlankUploadId() = runTest {
        val brokenResponses = listOf(
            """{"uploadId":"","clientFileId":"c1","filename":"log.txt","contentType":"text/plain","sizeBytes":1,"sha256":"aa","stored":true}""",
            """{"uploadId":null,"clientFileId":"c1","filename":"log.txt","contentType":"text/plain","sizeBytes":1,"sha256":"aa","stored":true}""",
            """{"clientFileId":"c1","filename":"log.txt","contentType":"text/plain","sizeBytes":1,"sha256":"aa","stored":true}"""
        )
        for (broken in brokenResponses) {
            try {
                client { request ->
                    if (request.url.endsWith("/uploads/sessions")) {
                        uploadSessionResponseFor(request)
                    } else {
                        HttpResponse(200, broken)
                    }
                }.uploadAttachment(
                    data = byteArrayOf(1),
                    filename = "log.txt",
                    mimeType = "text/plain",
                    userToken = "tok"
                )
                fail("expected UnreadableUploadResponse for $broken")
            } catch (_: FeedbackException.UnreadableUploadResponse) {
            }
        }
    }

    @Test
    fun uploadAttachmentNonImageMapsHttp415OnPutToUnsupportedMediaType() = runTest {
        try {
            client { request ->
                if (request.url.endsWith("/uploads/sessions")) {
                    uploadSessionResponseFor(request)
                } else {
                    HttpResponse(415, """{"error":"File content type does not match verified magic bytes"}""")
                }
            }.uploadAttachment(
                data = byteArrayOf(1),
                filename = "log.txt",
                mimeType = "text/plain",
                userToken = "tok"
            )
            fail("expected UnsupportedMediaType")
        } catch (_: FeedbackException.UnsupportedMediaType) {
        }
    }

    @Test
    fun uploadAttachmentNonImageMapsHttp429ToRateLimited() = runTest {
        try {
            client {
                HttpResponse(429, """{"error":"Daily upload byte or rate limit exceeded"}""")
            }.uploadAttachment(
                data = byteArrayOf(1),
                filename = "log.txt",
                mimeType = "text/plain",
                userToken = "tok"
            )
            fail("expected RateLimited")
        } catch (ex: FeedbackException.RateLimited) {
            assertEquals("Daily upload byte or rate limit exceeded", ex.serverMessage)
        }
    }

    @Test
    fun uploadAttachmentNonImageThrowsUnreadableUploadResponseOnMalformedSession() = runTest {
        try {
            client { HttpResponse(201, """{"unexpected":"shape"}""") }.uploadAttachment(
                data = byteArrayOf(1),
                filename = "log.txt",
                mimeType = "text/plain",
                userToken = "tok"
            )
            fail("expected UnreadableUploadResponse")
        } catch (_: FeedbackException.UnreadableUploadResponse) {
        }
    }

    @Test
    fun uploadAttachmentNonImageThrowsUnexpectedStatusOnPut413() = runTest {
        try {
            client { request ->
                if (request.url.endsWith("/uploads/sessions")) {
                    uploadSessionResponseFor(request)
                } else {
                    HttpResponse(413, """{"error":"Payload exceeds size limit","code":"file_too_large"}""")
                }
            }.uploadAttachment(
                data = byteArrayOf(1),
                filename = "log.txt",
                mimeType = "text/plain",
                userToken = "tok"
            )
            fail("expected UnexpectedStatus")
        } catch (ex: FeedbackException.UnexpectedStatus) {
            assertEquals(413, ex.code)
            assertTrue(ex.serverMessage.contains("file_too_large"))
        }
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
    fun uploadAttachmentMapsHttp500WithCodeToImageUploadFailed() = runTest {
        // #62: POST /api/v1/uploads/images returns stable { "error": "Image upload failed. Please try again.", "code": "image_upload_failed" } on 500
        val errorJson = """{"error":"Image upload failed. Please try again.","code":"image_upload_failed"}"""
        try {
            client {
                HttpResponse(500, errorJson, headers = mapOf("X-Request-Id" to "req_img_500"))
            }.uploadAttachment(
                data = validPngBytes,
                filename = "screenshot.png",
                mimeType = "image/png"
            )
            fail("expected ImageUploadFailed")
        } catch (ex: FeedbackException.ImageUploadFailed) {
            assertEquals("Image upload failed. Please try again.", ex.serverMessage)
            assertEquals("image_upload_failed", ex.serverCode)
            assertEquals("req_img_500", ex.requestId)
            assertEquals("Image upload failed. Please try again.", ex.message)
        }
    }

    @Test
    fun uploadAttachmentMapsHttp500WithLegacyMessageToImageUploadFailed() = runTest {
        val legacyJson = """{"error":"Image upload failed: internal storage error"}"""
        try {
            client {
                HttpResponse(500, legacyJson, headers = mapOf("X-Request-Id" to "req_img_legacy"))
            }.uploadAttachment(
                data = validPngBytes,
                filename = "screenshot.png",
                mimeType = "image/png"
            )
            fail("expected ImageUploadFailed")
        } catch (ex: FeedbackException.ImageUploadFailed) {
            assertEquals("Image upload failed: internal storage error", ex.serverMessage)
            assertEquals("image_upload_failed", ex.serverCode)
            assertEquals("req_img_legacy", ex.requestId)
        }
    }

    @Test
    fun uploadAttachmentHttp500WithUnrelatedErrorDegradesToUnexpectedStatus() = runTest {
        try {
            client {
                HttpResponse(500, """{"error":"Database connection timed out","code":"database_timeout"}""")
            }.uploadAttachment(
                data = validPngBytes,
                filename = "screenshot.png",
                mimeType = "image/png"
            )
            fail("expected UnexpectedStatus")
        } catch (ex: FeedbackException.UnexpectedStatus) {
            assertEquals(500, ex.code)
            assertTrue(ex.serverMessage.contains("Database connection timed out"))
        }
    }

    @Test
    fun uploadAttachmentHttp500WithNearMissLegacyMessageDegradesToUnexpectedStatus() = runTest {
        // Without code, near-miss substring matches must not trigger ImageUploadFailed
        val nearMissJson = """{"error":"No image upload failed; database timed out"}"""
        try {
            client {
                HttpResponse(500, nearMissJson, headers = mapOf("X-Request-Id" to "req_img_near_miss"))
            }.uploadAttachment(
                data = validPngBytes,
                filename = "screenshot.png",
                mimeType = "image/png"
            )
            fail("expected UnexpectedStatus")
        } catch (ex: FeedbackException.UnexpectedStatus) {
            assertEquals(500, ex.code)
            assertTrue(ex.serverMessage.contains("No image upload failed; database timed out"))
            assertEquals("req_img_near_miss", ex.requestId)
        }
    }

    @Test
    fun isImageUploadFailedClassificationLogic() {
        assertTrue(FeedbackException.ImageUploadFailed.isImageUploadFailed("image_upload_failed", null))
        assertTrue(FeedbackException.ImageUploadFailed.isImageUploadFailed("image_upload_failed", "arbitrary error"))
        assertTrue(FeedbackException.ImageUploadFailed.isImageUploadFailed(null, "Image upload failed. Please try again."))
        assertTrue(FeedbackException.ImageUploadFailed.isImageUploadFailed(null, "Image upload failed"))
        assertTrue(FeedbackException.ImageUploadFailed.isImageUploadFailed(null, "Image upload failed: internal storage error"))
        assertFalse(FeedbackException.ImageUploadFailed.isImageUploadFailed(null, "No image upload failed; database timed out"))
        assertFalse(FeedbackException.ImageUploadFailed.isImageUploadFailed("unrelated_code", "Image upload failed"))
        assertFalse(FeedbackException.ImageUploadFailed.isImageUploadFailed(null, null))
        assertFalse(FeedbackException.ImageUploadFailed.isImageUploadFailed(null, "   "))
    }

    @Test
    fun nonImageUploadsDoNotMapHttp500ToImageUploadFailed() = runTest {
        try {
            client {
                HttpResponse(500, """{"error":"Image upload failed. Please try again.","code":"image_upload_failed"}""")
            }.submit(FeedbackDraft(title = "Bug", description = "Repro steps", platform = FeedbackPlatform.ANDROID))
            fail("expected UnexpectedStatus")
        } catch (ex: FeedbackException.UnexpectedStatus) {
            assertEquals(500, ex.code)
        }
    }

    @Test
    fun imageUploadFailedDefaultConstructorAndProperties() {
        val defaultEx = FeedbackException.ImageUploadFailed()
        assertNull(defaultEx.serverMessage)
        assertEquals("image_upload_failed", defaultEx.serverCode)
        assertNull(defaultEx.requestId)
        assertEquals("Image upload failed. Please try again.", defaultEx.message)

        val customEx = FeedbackException.ImageUploadFailed(
            serverMessage = "Custom error",
            serverCode = "custom_code",
            requestId = "req_custom"
        )
        assertEquals("Custom error", customEx.serverMessage)
        assertEquals("custom_code", customEx.serverCode)
        assertEquals("req_custom", customEx.requestId)
        assertEquals("Custom error", customEx.message)
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
        val jpegBytes = validJpegBytes
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
    fun submitIncludesUploadIdsAndAttachmentFieldsCorrectly() = runTest {
        var capturedBody: String? = null
        val client = client { request ->
            capturedBody = request.body?.toString(Charsets.UTF_8)
            HttpResponse(201, """{"submissionId":"s-upload-test","forwardedToGithub":false}""")
        }

        val modernAttachment = FeedbackAttachment(
            kind = FeedbackAttachment.Kind.IMAGE,
            uploadId = "upl_modern123",
            filename = "screen.png",
            mimeType = "image/png",
            size = 1000L
        )
        val legacyAttachment = FeedbackAttachment(
            kind = FeedbackAttachment.Kind.R2,
            key = "legacy_key",
            url = "https://cdn.example.com/legacy_key",
            filename = "log.txt",
            mimeType = "text/plain",
            size = 200L
        )
        val draft = FeedbackDraft(
            title = "Test issue with attachments",
            description = "Detailed report",
            platform = FeedbackPlatform.ANDROID,
            attachments = listOf(modernAttachment, legacyAttachment)
        )

        client.submit(draft)

        val payload = JSONObject(requireNotNull(capturedBody))
        assertTrue(payload.has("uploadIds"))
        val uploadIds = payload.getJSONArray("uploadIds")
        assertEquals(1, uploadIds.length())
        assertEquals("upl_modern123", uploadIds.getString(0))

        val attachments = payload.getJSONArray("attachments")
        assertEquals(2, attachments.length())

        val modernJson = attachments.getJSONObject(0)
        assertEquals("image", modernJson.getString("kind"))
        assertEquals("upl_modern123", modernJson.getString("uploadId"))
        assertFalse(modernJson.has("key"))
        assertFalse(modernJson.has("url"))
        assertEquals("screen.png", modernJson.getString("filename"))
        assertEquals("image/png", modernJson.getString("mimeType"))
        assertEquals(1000L, modernJson.getLong("size"))

        val legacyJson = attachments.getJSONObject(1)
        assertEquals("r2", legacyJson.getString("kind"))
        assertEquals("legacy_key", legacyJson.getString("key"))
        assertEquals("https://cdn.example.com/legacy_key", legacyJson.getString("url"))
        assertFalse(legacyJson.has("uploadId"))
        assertEquals("log.txt", legacyJson.getString("filename"))
        assertEquals("text/plain", legacyJson.getString("mimeType"))
        assertEquals(200L, legacyJson.getLong("size"))
    }

    @Test
    fun submitWithoutUploadIdsOmitsUploadIdsField() = runTest {
        var capturedBody: String? = null
        val client = client { request ->
            capturedBody = request.body?.toString(Charsets.UTF_8)
            HttpResponse(201, """{"submissionId":"s-legacy","forwardedToGithub":false}""")
        }

        val legacyAttachment = FeedbackAttachment(
            kind = FeedbackAttachment.Kind.IMAGE,
            key = "legacy_key",
            url = "https://cdn.example.com/legacy_key"
        )
        val draft = FeedbackDraft(
            title = "Legacy attachment test",
            description = "Detailed report",
            platform = FeedbackPlatform.ANDROID,
            attachments = listOf(legacyAttachment)
        )

        client.submit(draft)

        val payload = JSONObject(requireNotNull(capturedBody))
        assertFalse(payload.has("uploadIds"))
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
    fun fetchFeatureRequestThrowsInvalidResponseOnMalformedRequestObject() = runTest {
        val c = client {
            HttpResponse(200, """{"request":{"missing_required_fields":true}}""")
        }
        try {
            c.fetchFeatureRequest("fr-malformed")
            fail("Expected InvalidResponse when 'request' object is malformed")
        } catch (e: FeedbackException.InvalidResponse) {
            assertTrue(e.cause is org.json.JSONException)
        }
    }

    @Test
    fun fetchFeatureRequestRequiresHttpsWhenUserTokenProvided() = runTest {
        val insecureClient = FeedbackClient(
            FeedbackClientConfig(
                baseUrl = "http://api.example.com",
                appKey = "app_testkey123456"
            ),
            transport = { HttpResponse(200, "{}") }
        )

        try {
            insecureClient.fetchFeatureRequest("fr-123", userToken = "anon_user_token_123")
            fail("Expected IllegalArgumentException when userToken transmitted over HTTP")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("HTTPS") == true)
        }

        // Without user token, HTTP is permitted
        val validJson = """
            {
              "request": {
                "id": "fr-123",
                "appId": "app_testkey123456",
                "title": "Allowed over HTTP without token",
                "approved": true,
                "voteCount": 0,
                "hasVoted": false,
                "isOwnRequest": false
              }
            }
        """.trimIndent()
        val insecureAllowedClient = FeedbackClient(
            FeedbackClientConfig(
                baseUrl = "http://api.example.com",
                appKey = "app_testkey123456"
            ),
            transport = { HttpResponse(200, validJson) }
        )
        val item = insecureAllowedClient.fetchFeatureRequest("fr-123")
        assertEquals("fr-123", item.id)
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
    fun subscribeToChangelogHitsEndpointAndParsesSuccessResult() = runTest {
        var requestedUrl: String? = null
        var capturedHeaders: Map<String, String>? = null
        var capturedBody: String? = null
        val userToken = "tok_user_123"

        val result = client { req ->
            requestedUrl = req.url
            capturedHeaders = req.headers
            capturedBody = String(req.body ?: ByteArray(0), Charsets.UTF_8)
            // #26: the response is uniform (HTTP 201, no alreadySubscribed field).
            HttpResponse(201, """{"subscribed":true}""")
        }.subscribeToChangelog("alice@example.com", userToken = userToken)

        assertEquals("https://api.example.com/api/v1/public/apps/$appKey/changelog/subscribe", requestedUrl)
        assertEquals(userToken, capturedHeaders?.get("X-User-Token"))
        assertNotNull(capturedBody)
        val payload = org.json.JSONObject(capturedBody!!)
        assertEquals("alice@example.com", payload.getString("email"))
        assertFalse(payload.has("turnstileToken"))
        assertTrue(result.subscribed)
        // The server no longer reports prior subscriptions; the field stays for source compatibility.
        assertFalse(result.alreadySubscribed)
    }

    @Test
    fun subscribeToChangelogIncludesTurnstileTokenInPayloadWhenProvided() = runTest {
        var capturedBody: String? = null
        val userToken = "tok_user_456"
        val rawTurnstile = "   0.test_turnstile_token_subscribe   "

        val result = client { req ->
            capturedBody = String(req.body ?: ByteArray(0), Charsets.UTF_8)
            HttpResponse(200, """{"subscribed":true,"alreadySubscribed":true}""")
        }.subscribeToChangelog("bob@example.com", userToken = userToken, turnstileToken = rawTurnstile)

        assertTrue(result.subscribed)
        assertTrue(result.alreadySubscribed)
        assertNotNull(capturedBody)
        val payload = org.json.JSONObject(capturedBody!!)
        assertEquals("bob@example.com", payload.getString("email"))
        assertEquals("0.test_turnstile_token_subscribe", payload.getString("turnstileToken"))
    }

    @Test
    fun subscribeToChangelogOmitsTurnstileTokenWhenNullOrBlank() = runTest {
        var capturedBody: String? = null
        val userToken = "tok_user_789"

        val c = client { req ->
            capturedBody = String(req.body ?: ByteArray(0), Charsets.UTF_8)
            HttpResponse(200, """{"subscribed":true,"alreadySubscribed":false}""")
        }

        // null turnstileToken
        c.subscribeToChangelog("user@example.com", userToken = userToken, turnstileToken = null)
        assertFalse(org.json.JSONObject(capturedBody!!).has("turnstileToken"))

        // blank turnstileToken
        c.subscribeToChangelog("user@example.com", userToken = userToken, turnstileToken = "   ")
        assertFalse(org.json.JSONObject(capturedBody!!).has("turnstileToken"))

        // two-argument overload
        c.subscribeToChangelog("user@example.com", userToken = userToken)
        assertFalse(org.json.JSONObject(capturedBody!!).has("turnstileToken"))
    }

    @Test
    fun subscribeToChangelogSurfacesHttp403TurnstileVerificationFailedAsUnexpectedStatus() = runTest {
        val userToken = "tok_anon_user"
        val errorJson = """{"error":"Human verification failed. Please try again.","code":"turnstile_verification_failed"}"""

        try {
            client {
                HttpResponse(403, errorJson)
            }.subscribeToChangelog("attacker@example.com", userToken = userToken, turnstileToken = "invalid_token")
            fail("Expected FeedbackException.UnexpectedStatus on 403")
        } catch (ex: FeedbackException.UnexpectedStatus) {
            assertEquals(403, ex.code)
            assertTrue(ex.serverMessage.contains("turnstile_verification_failed"))
        }
    }

    @Test
    fun unsubscribeFromChangelogHitsEndpointAndParsesResult() = runTest {
        var requestedUrl: String? = null
        var capturedBody: String? = null

        val result = client { req ->
            requestedUrl = req.url
            capturedBody = String(req.body ?: ByteArray(0), Charsets.UTF_8)
            HttpResponse(200, """{"unsubscribed":true}""")
        }.unsubscribeFromChangelog("  sub_token_from_email_link  ")

        assertEquals("https://api.example.com/api/v1/public/apps/$appKey/changelog/unsubscribe", requestedUrl)
        assertNotNull(capturedBody)
        // #26: unsubscribing requires the signed per-subscriber token; the bare-email body was removed.
        val payload = org.json.JSONObject(capturedBody!!)
        assertEquals("sub_token_from_email_link", payload.getString("token"))
        assertFalse(payload.has("email"))
        assertTrue(result.unsubscribed)
    }

    @Test
    fun unsubscribeFromChangelogSurfacesInvalidTokenAsUnexpectedStatus() = runTest {
        try {
            client {
                HttpResponse(400, """{"error":"A valid unsubscribe token is required","code":"invalid_token"}""")
            }.unsubscribeFromChangelog("bogus-token")
            fail("Expected FeedbackException.UnexpectedStatus on 400 invalid token")
        } catch (ex: FeedbackException.UnexpectedStatus) {
            assertEquals(400, ex.code)
        }
    }

    @Test
    fun unsubscribeFromChangelogRejectsHttpDestinationWhenTransmittingToken() = runTest {
        val insecureClient = FeedbackClient(
            config = FeedbackClientConfig(baseUrl = "http://insecure.example.com", appKey = appKey),
            transport = HttpTransport { HttpResponse(200, """{"unsubscribed":true}""") }
        )

        try {
            insecureClient.unsubscribeFromChangelog("sub_token")
            fail("Expected IllegalArgumentException when transmitting the unsubscribe token over HTTP")
        } catch (ex: IllegalArgumentException) {
            assertTrue(ex.message.orEmpty().contains("must use HTTPS when transmitting the unsubscribe token"))
        }
    }

    @Test
    fun confirmChangelogSubscriptionHitsEndpointWithPostAndParsesResult() = runTest {
        var requestedMethod: String? = null
        var requestedUrl: String? = null
        var capturedBody: String? = null

        val result = client { req ->
            requestedMethod = req.method
            requestedUrl = req.url
            capturedBody = String(req.body ?: ByteArray(0), Charsets.UTF_8)
            HttpResponse(200, """{"confirmed":true}""")
        }.confirmChangelogSubscription("conf_token_abc_123")

        // #30: POST is required for confirmation; GET is non-destructive
        assertEquals("POST", requestedMethod)
        assertEquals("https://api.example.com/api/v1/public/apps/$appKey/changelog/confirm", requestedUrl)
        assertNotNull(capturedBody)
        val payload = org.json.JSONObject(capturedBody!!)
        assertEquals("conf_token_abc_123", payload.getString("token"))
        assertTrue(result.confirmed)
    }

    @Test
    fun confirmChangelogSubscriptionTrimsTokenWhitespace() = runTest {
        var capturedBody: String? = null

        val result = client { req ->
            capturedBody = String(req.body ?: ByteArray(0), Charsets.UTF_8)
            HttpResponse(200, """{"confirmed":true}""")
        }.confirmChangelogSubscription("  padded_token_xyz  ")

        val payload = org.json.JSONObject(capturedBody!!)
        assertEquals("padded_token_xyz", payload.getString("token"))
        assertTrue(result.confirmed)
    }

    @Test
    fun confirmChangelogSubscriptionRejectsBlankToken() = runTest {
        try {
            client { HttpResponse(200, """{"confirmed":true}""") }.confirmChangelogSubscription("   ")
            fail("Expected IllegalArgumentException on blank confirmation token")
        } catch (ex: IllegalArgumentException) {
            assertTrue(ex.message.orEmpty().contains("Confirmation token cannot be blank"))
        }
    }

    @Test
    fun confirmChangelogSubscriptionRejectsHttpDestinationWhenTransmittingToken() = runTest {
        val insecureClient = FeedbackClient(
            config = FeedbackClientConfig(baseUrl = "http://insecure.example.com", appKey = appKey),
            transport = HttpTransport { HttpResponse(200, """{"confirmed":true}""") }
        )

        try {
            insecureClient.confirmChangelogSubscription("conf_token")
            fail("Expected IllegalArgumentException when transmitting the confirmation token over HTTP")
        } catch (ex: IllegalArgumentException) {
            assertTrue(ex.message.orEmpty().contains("must use HTTPS when transmitting the confirmation token"))
        }
    }

    @Test
    fun confirmChangelogSubscriptionSurfacesInvalidOrExpiredTokenAsUnexpectedStatus() = runTest {
        try {
            client {
                HttpResponse(400, """{"error":"Invalid or expired confirmation token","code":"invalid_token"}""")
            }.confirmChangelogSubscription("expired-token")
            fail("Expected FeedbackException.UnexpectedStatus on 400 invalid confirmation token")
        } catch (ex: FeedbackException.UnexpectedStatus) {
            assertEquals(400, ex.code)
            assertTrue(ex.serverMessage.contains("Invalid or expired confirmation token"))
        }
    }

    @Test
    fun confirmChangelogSubscriptionHandlesDefaultConfirmedWhenOmitted() = runTest {
        val result = client {
            HttpResponse(200, "{}")
        }.confirmChangelogSubscription("token")

        assertTrue(result.confirmed)
    }

    @Test
    fun confirmChangelogSubscriptionWrapsMalformedResponseInInvalidResponse() = runTest {
        try {
            client {
                HttpResponse(200, """{"confirmed":[]}""")
            }.confirmChangelogSubscription("token")
            fail("Expected FeedbackException.InvalidResponse on malformed response body")
        } catch (ex: FeedbackException.InvalidResponse) {
            assertTrue(ex.cause is org.json.JSONException)
        }
    }

    @Test
    fun voteHitsVoteEndpointWithPostAndPayload() = runTest {
        var method: String? = null
        var url: String? = null
        var userTokenHeader: String? = null
        var requestBody: String? = null
        val token = UUID.randomUUID().toString()

        val result = client { request ->
            method = request.method
            url = request.url
            userTokenHeader = request.headers["X-User-Token"]
            requestBody = request.body?.toString(Charsets.UTF_8)
            HttpResponse(200, """{"voted":true,"voteCount":4}""")
        }.vote("fr-9", token)

        assertEquals("POST", method)
        assertEquals("https://api.example.com/api/v1/feature-requests/fr-9/vote", url)
        assertEquals(token, userTokenHeader)
        assertTrue(result.voted)
        assertEquals(4, result.voteCount)

        val json = JSONObject(requireNotNull(requestBody))
        assertEquals(appKey, json.getString("appKey"))
        assertEquals(token, json.getString("userToken"))
        assertFalse(json.has("turnstileToken"))
        assertFalse(json.has("shipNotifyEmail"))
    }

    @Test
    fun voteOverloadsSupportTurnstileAndShipNotifyEmail() = runTest {
        val token = UUID.randomUUID().toString()
        val turnstile = "0.test_turnstile_token"
        val email = "voter@example.com"
        var requestBody: String? = null

        val result = client { request ->
            requestBody = request.body?.toString(Charsets.UTF_8)
            HttpResponse(200, """{"voted":true,"voteCount":5}""")
        }.vote("fr-100", token, turnstileToken = turnstile, shipNotifyEmail = email)

        assertTrue(result.voted)
        assertEquals(5, result.voteCount)
        val json = JSONObject(requireNotNull(requestBody))
        assertEquals(turnstile, json.getString("turnstileToken"))
        assertEquals(email, json.getString("shipNotifyEmail"))

        val twoParamResult = client {
            HttpResponse(200, """{"voted":true,"voteCount":6}""")
        }.vote("fr-101", token)
        assertTrue(twoParamResult.voted)

        val threeParamResult = client {
            HttpResponse(200, """{"voted":true,"voteCount":7}""")
        }.vote("fr-102", token, turnstileToken = turnstile)
        assertTrue(threeParamResult.voted)
    }

    @Test
    fun voteMapsHttp429ToRateLimited() = runTest {
        try {
            client {
                HttpResponse(429, """{"error":"Too many votes. Please try again shortly."}""")
            }.vote("fr-10", UUID.randomUUID().toString())
            fail("Expected FeedbackException.RateLimited on 429")
        } catch (ex: FeedbackException.RateLimited) {
            assertEquals("Too many votes. Please try again shortly.", ex.serverMessage)
            assertEquals("Too many votes. Please try again shortly.", ex.message)
        }
    }

    @Test
    fun voteRejectsHttpWhenTransmittingShipNotifyEmail() = runTest {
        val insecureClient = FeedbackClient(
            FeedbackClientConfig(baseUrl = "http://api.example.com", appKey = appKey),
            transport = { HttpResponse(200, """{"voted":true,"voteCount":1}""") }
        )
        try {
            insecureClient.vote("fr-204", "tok", shipNotifyEmail = "user@example.com")
            fail("Expected IllegalArgumentException on HTTP transmission of email")
        } catch (ex: IllegalArgumentException) {
            assertTrue(ex.message.orEmpty().contains("must use HTTPS when transmitting shipNotifyEmail"))
        }
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
    fun toggleVoteDecodesEmailNotVerifiedWarning() = runTest {
        val userToken = UUID.randomUUID().toString()
        val body = """
            {
              "voted": true,
              "voteCount": 3,
              "warning": {
                "code": "email_not_verified",
                "error": "Ship notifications on this board are bound to your signed-in email address"
              }
            }
        """.trimIndent()

        val result = client { HttpResponse(200, body) }
            .toggleVote("fr-300", userToken, shipNotifyEmail = "other@example.com")

        assertTrue(result.voted)
        assertEquals(3, result.voteCount)
        val warning = requireNotNull(result.warning)
        assertEquals(VoteWarning.CODE_EMAIL_NOT_VERIFIED, warning.code)
        assertEquals(
            "Ship notifications on this board are bound to your signed-in email address",
            warning.error
        )
    }

    @Test
    fun toggleVoteDecodesShipNotifyVerificationFailedWarning() = runTest {
        val userToken = UUID.randomUUID().toString()
        val body = """
            {
              "voted": true,
              "voteCount": 1,
              "warning": {
                "code": "ship_notify_verification_failed",
                "error": "Human verification failed, so the ship-notification opt-in was not recorded."
              }
            }
        """.trimIndent()

        val result = client { HttpResponse(200, body) }
            .toggleVote("fr-301", userToken, shipNotifyEmail = "voter@example.com")

        assertTrue(result.voted)
        val warning = requireNotNull(result.warning)
        assertEquals(VoteWarning.CODE_SHIP_NOTIFY_VERIFICATION_FAILED, warning.code)
        assertEquals(
            "Human verification failed, so the ship-notification opt-in was not recorded.",
            warning.error
        )
    }

    @Test
    fun toggleVoteLeavesWarningNullWhenServerOmitsIt() = runTest {
        val result = client {
            HttpResponse(200, """{"voted":true,"voteCount":2}""")
        }.toggleVote("fr-302", UUID.randomUUID().toString())

        assertNull(result.warning)
    }

    @Test
    fun toggleVotePreservesUnknownWarningCodes() = runTest {
        val body = """
            {
              "voted": true,
              "voteCount": 5,
              "warning": { "code": "future_warning_code", "error": "Something else was refused" }
            }
        """.trimIndent()

        val result = client { HttpResponse(200, body) }
            .toggleVote("fr-303", UUID.randomUUID().toString())

        val warning = requireNotNull(result.warning)
        assertEquals("future_warning_code", warning.code)
        assertEquals("Something else was refused", warning.error)
    }

    @Test
    fun toggleVotePreservesWarningCodesVerbatim() = runTest {
        val body = """
            {
              "voted": true,
              "voteCount": 4,
              "warning": { "code": " email_not_verified ", "error": "Padded code" }
            }
        """.trimIndent()

        val result = client { HttpResponse(200, body) }
            .toggleVote("fr-307", UUID.randomUUID().toString())

        val warning = requireNotNull(result.warning)
        assertEquals(" email_not_verified ", warning.code)
        assertNotEquals(VoteWarning.CODE_EMAIL_NOT_VERIFIED, warning.code)
    }

    @Test
    fun toggleVoteIgnoresWarningWithoutMachineReadableCode() = runTest {
        val missingCode = """
            {"voted":true,"voteCount":6,"warning":{"error":"No code here"}}
        """.trimIndent()
        val nonObject = """{"voted":true,"voteCount":7,"warning":"refused"}"""

        val withoutCode = client { HttpResponse(200, missingCode) }
            .toggleVote("fr-304", UUID.randomUUID().toString())
        assertNull(withoutCode.warning)
        assertEquals(6, withoutCode.voteCount)

        val stringWarning = client { HttpResponse(200, nonObject) }
            .toggleVote("fr-305", UUID.randomUUID().toString())
        assertNull(stringWarning.warning)
        assertEquals(7, stringWarning.voteCount)
    }

    @Test
    fun toggleVoteTreatsBlankWarningCodeAsAbsent() = runTest {
        val body = """{"voted":true,"voteCount":8,"warning":{"code":"   ","error":"blank"}}"""

        val result = client { HttpResponse(200, body) }
            .toggleVote("fr-306", UUID.randomUUID().toString())

        assertNull(result.warning)
        assertTrue(result.voted)
        assertEquals(8, result.voteCount)
    }

    @Test
    fun voteResultTwoArgumentConstructorKeepsWarningNull() {
        val result = VoteResult(voted = true, voteCount = 9)

        assertTrue(result.voted)
        assertEquals(9, result.voteCount)
        assertNull(result.warning)
    }

    @Test
    fun toggleVoteIncludesTurnstileTokenInPayloadWhenProvided() = runTest {
        var capturedBody: String? = null
        val userToken = UUID.randomUUID().toString()
        val turnstile = "0.test_turnstile_token_12345"

        val result = client { request ->
            capturedBody = String(request.body ?: ByteArray(0), Charsets.UTF_8)
            HttpResponse(200, """{"voted":true,"voteCount":5}""")
        }.toggleVote("fr-100", userToken, turnstileToken = turnstile)

        assertTrue(result.voted)
        assertEquals(5, result.voteCount)
        assertNotNull(capturedBody)
        val payload = org.json.JSONObject(capturedBody!!)
        assertEquals(appKey, payload.getString("appKey"))
        assertEquals(userToken, payload.getString("userToken"))
        assertEquals(turnstile, payload.getString("turnstileToken"))
    }

    @Test
    fun toggleVoteOmitsTurnstileTokenWhenNullOrBlank() = runTest {
        var capturedBody: String? = null
        val userToken = UUID.randomUUID().toString()

        val c = client { request ->
            capturedBody = String(request.body ?: ByteArray(0), Charsets.UTF_8)
            HttpResponse(200, """{"voted":true,"voteCount":1}""")
        }

        // null turnstileToken
        c.toggleVote("fr-101", userToken, turnstileToken = null)
        assertFalse(org.json.JSONObject(capturedBody!!).has("turnstileToken"))

        // blank turnstileToken
        c.toggleVote("fr-101", userToken, turnstileToken = "   ")
        assertFalse(org.json.JSONObject(capturedBody!!).has("turnstileToken"))

        // two-argument overload
        c.toggleVote("fr-101", userToken)
        assertFalse(org.json.JSONObject(capturedBody!!).has("turnstileToken"))
    }

    @Test
    fun toggleVoteIncludesShipNotifyEmailInPayloadWhenProvided() = runTest {
        var capturedBody: String? = null
        val userToken = UUID.randomUUID().toString()
        val rawEmail = "   voter@example.com   "

        val result = client { request ->
            capturedBody = String(request.body ?: ByteArray(0), Charsets.UTF_8)
            HttpResponse(200, """{"voted":true,"voteCount":6}""")
        }.toggleVote("fr-200", userToken, turnstileToken = null, shipNotifyEmail = rawEmail)

        assertTrue(result.voted)
        assertEquals(6, result.voteCount)
        assertNotNull(capturedBody)
        val payload = org.json.JSONObject(capturedBody!!)
        assertEquals(appKey, payload.getString("appKey"))
        assertEquals(userToken, payload.getString("userToken"))
        assertFalse(payload.has("turnstileToken"))
        assertEquals("voter@example.com", payload.getString("shipNotifyEmail"))
    }

    @Test
    fun toggleVoteIncludesBothTurnstileTokenAndShipNotifyEmailWhenProvided() = runTest {
        var capturedBody: String? = null
        val userToken = UUID.randomUUID().toString()
        val turnstile = "0.test_turnstile_action_ship_notify"
        val email = "notify@example.org"

        val result = client { request ->
            capturedBody = String(request.body ?: ByteArray(0), Charsets.UTF_8)
            HttpResponse(200, """{"voted":true,"voteCount":7}""")
        }.toggleVote("fr-201", userToken, turnstileToken = turnstile, shipNotifyEmail = email)

        assertTrue(result.voted)
        assertEquals(7, result.voteCount)
        assertNotNull(capturedBody)
        val payload = org.json.JSONObject(capturedBody!!)
        assertEquals(appKey, payload.getString("appKey"))
        assertEquals(userToken, payload.getString("userToken"))
        assertEquals(turnstile, payload.getString("turnstileToken"))
        assertEquals(email, payload.getString("shipNotifyEmail"))
    }

    @Test
    fun toggleVoteOmitsShipNotifyEmailWhenNullOrBlank() = runTest {
        var capturedBody: String? = null
        val userToken = UUID.randomUUID().toString()

        val c = client { request ->
            capturedBody = String(request.body ?: ByteArray(0), Charsets.UTF_8)
            HttpResponse(200, """{"voted":true,"voteCount":2}""")
        }

        // null shipNotifyEmail (4-arg)
        c.toggleVote("fr-202", userToken, turnstileToken = "tok", shipNotifyEmail = null)
        assertFalse(org.json.JSONObject(capturedBody!!).has("shipNotifyEmail"))

        // blank shipNotifyEmail (4-arg)
        c.toggleVote("fr-202", userToken, turnstileToken = "tok", shipNotifyEmail = "   ")
        assertFalse(org.json.JSONObject(capturedBody!!).has("shipNotifyEmail"))

        // 3-argument overload
        c.toggleVote("fr-202", userToken, turnstileToken = "tok")
        assertFalse(org.json.JSONObject(capturedBody!!).has("shipNotifyEmail"))

        // 2-argument overload
        c.toggleVote("fr-202", userToken)
        assertFalse(org.json.JSONObject(capturedBody!!).has("shipNotifyEmail"))
    }

    @Test
    fun toggleVoteAllowsNamedShipNotifyEmailWithoutTurnstileToken() = runTest {
        var capturedBody: String? = null
        val userToken = UUID.randomUUID().toString()

        val result = client { request ->
            capturedBody = String(request.body ?: ByteArray(0), Charsets.UTF_8)
            HttpResponse(200, """{"voted":true,"voteCount":10}""")
        }.toggleVote("fr-203", userToken, shipNotifyEmail = "named@example.com")

        assertTrue(result.voted)
        assertEquals(10, result.voteCount)
        assertNotNull(capturedBody)
        val payload = org.json.JSONObject(capturedBody!!)
        assertFalse(payload.has("turnstileToken"))
        assertEquals("named@example.com", payload.getString("shipNotifyEmail"))
    }

    @Test
    fun toggleVoteRejectsHttpDestinationWhenTransmittingShipNotifyEmail() = runTest {
        val userToken = UUID.randomUUID().toString()
        val insecureClient = FeedbackClient(
            config = FeedbackClientConfig(baseUrl = "http://insecure.example.com", appKey = appKey),
            transport = HttpTransport { HttpResponse(200, """{"voted":true,"voteCount":1}""") }
        )

        try {
            insecureClient.toggleVote("fr-204", userToken, shipNotifyEmail = "user@example.com")
            fail("Expected IllegalArgumentException when transmitting shipNotifyEmail over HTTP")
        } catch (ex: IllegalArgumentException) {
            assertTrue(ex.message.orEmpty().contains("must use HTTPS when transmitting shipNotifyEmail"))
        }
    }

    @Test
    fun toggleVotePermitsHttpDestinationWhenShipNotifyEmailIsOmittedOrBlank() = runTest {
        val userToken = UUID.randomUUID().toString()
        val insecureClient = FeedbackClient(
            config = FeedbackClientConfig(baseUrl = "http://insecure.example.com", appKey = appKey),
            transport = HttpTransport { HttpResponse(200, """{"voted":true,"voteCount":1}""") }
        )

        val r1 = insecureClient.toggleVote("fr-205", userToken)
        assertTrue(r1.voted)

        val r2 = insecureClient.toggleVote("fr-205", userToken, shipNotifyEmail = "   ")
        assertTrue(r2.voted)
    }

    @Test
    fun toggleVoteSurfacesHttp403TurnstileVerificationFailedAsUnexpectedStatus() = runTest {
        val userToken = UUID.randomUUID().toString()
        val errorJson = """{"error":"Human verification failed. Please try again.","code":"turnstile_verification_failed"}"""

        try {
            client {
                HttpResponse(403, errorJson)
            }.toggleVote("fr-102", userToken, turnstileToken = "invalid_token")
            fail("Expected FeedbackException.UnexpectedStatus on 403")
        } catch (ex: FeedbackException.UnexpectedStatus) {
            assertEquals(403, ex.code)
            assertTrue(ex.serverMessage.contains("turnstile_verification_failed"))
        }
    }

    @Test
    fun fetchFeatureRequestsMapsSearchRateLimitToRateLimited() = runTest {
        // #23: search/list 429s must surface as a typed error, not raw JSON inside
        // UnexpectedStatus.message.
        try {
            client {
                HttpResponse(429, """{"error":"Too many searches. Please try again shortly."}""")
            }.fetchFeatureRequests(userToken = "usr_tok_429", query = "dark mode")
            fail("Expected FeedbackException.RateLimited on 429")
        } catch (ex: FeedbackException.RateLimited) {
            assertEquals("Too many searches. Please try again shortly.", ex.serverMessage)
        }
    }

    @Test
    fun fetchFeatureRequestsMapsBlankBodyRateLimitToRateLimited() = runTest {
        try {
            client {
                HttpResponse(429, "   ")
            }.fetchFeatureRequests(userToken = "usr_tok_429")
            fail("Expected FeedbackException.RateLimited on 429")
        } catch (ex: FeedbackException.RateLimited) {
            assertNull(ex.serverMessage)
        }
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
            assertEquals("You're doing that too fast. Please try again shortly.", ex.message)
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
            assertEquals("You're doing that too fast. Please try again shortly.", ex.message)
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
            assertEquals("You're doing that too fast. Please try again shortly.", ex.message)
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
    fun fetchColumnsMapsHttp429ToRateLimited() = runTest {
        try {
            client {
                HttpResponse(
                    429,
                    """{"error":"Too many requests. Please try again shortly."}""",
                    headers = mapOf("X-Request-Id" to "req-cols-429")
                )
            }.fetchColumns()
            fail("Expected FeedbackException.RateLimited on 429")
        } catch (ex: FeedbackException.RateLimited) {
            assertEquals("Too many requests. Please try again shortly.", ex.serverMessage)
            assertEquals("Too many requests. Please try again shortly.", ex.message)
            assertEquals("req-cols-429", ex.requestId)
        }
    }

    @Test
    fun fetchVersionsMapsHttp429ToRateLimited() = runTest {
        try {
            client {
                HttpResponse(
                    429,
                    """{"error":"Too many requests. Please try again shortly."}""",
                    headers = mapOf("X-Request-Id" to "req-vers-429")
                )
            }.fetchVersions()
            fail("Expected FeedbackException.RateLimited on 429")
        } catch (ex: FeedbackException.RateLimited) {
            assertEquals("Too many requests. Please try again shortly.", ex.serverMessage)
            assertEquals("Too many requests. Please try again shortly.", ex.message)
            assertEquals("req-vers-429", ex.requestId)
        }
    }

    @Test
    fun fetchVersionsMapsHttp429WithBlankBodyToDefaultRateLimitedMessage() = runTest {
        try {
            client {
                HttpResponse(429, "   ")
            }.fetchVersions()
            fail("Expected FeedbackException.RateLimited on 429")
        } catch (ex: FeedbackException.RateLimited) {
            assertNull(ex.serverMessage)
            assertEquals("You're doing that too fast. Please try again shortly.", ex.message)
        }
    }

    @Test
    fun fetchChangelogPageMapsHttp429ToRateLimited() = runTest {
        try {
            client {
                HttpResponse(
                    429,
                    """{"error":"Too many requests. Please try again shortly."}""",
                    headers = mapOf("X-Request-Id" to "req-cl-page-429")
                )
            }.fetchChangelogPage(limit = 10)
            fail("Expected FeedbackException.RateLimited on 429")
        } catch (ex: FeedbackException.RateLimited) {
            assertEquals("Too many requests. Please try again shortly.", ex.serverMessage)
            assertEquals("Too many requests. Please try again shortly.", ex.message)
            assertEquals("req-cl-page-429", ex.requestId)
        }
    }

    @Test
    fun fetchChangelogMapsHttp429ToRateLimited() = runTest {
        try {
            client {
                HttpResponse(
                    429,
                    """{"error":"Too many requests. Please try again shortly."}""",
                    headers = mapOf("X-Request-Id" to "req-cl-429")
                )
            }.fetchChangelog()
            fail("Expected FeedbackException.RateLimited on 429")
        } catch (ex: FeedbackException.RateLimited) {
            assertEquals("Too many requests. Please try again shortly.", ex.serverMessage)
            assertEquals("Too many requests. Please try again shortly.", ex.message)
            assertEquals("req-cl-429", ex.requestId)
        }
    }

    @Test
    fun unmappedEndpointPreservesUnexpectedStatusOnHttp429() = runTest {
        try {
            client {
                HttpResponse(429, """{"error":"Rate limited"}""")
            }.fetchAppConfig()
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
        signingClient { request ->
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
    fun updateUserAttributesSignsPaymentAttributesWithTimestamp() = runTest {
        // #28: isPaying/plan/mrr require an HMAC-signed request (cpt-user-attrs-v1).
        var body: String? = null
        val token = "01234567-89ab-cdef-0123-456789abcdef"
        signingClient { request ->
            body = request.body?.toString(Charsets.UTF_8)
            HttpResponse(200, """{"ok":true,"updatedAt":"2026-02-01T00:00:00.000Z"}""")
        }.updateUserAttributes(userToken = token, isPaying = true, plan = "pro", mrr = 9.99, currency = "USD")
        val payload = requireNotNull(body)
        val json = JSONObject(payload)
        val timestamp = json.getLong("timestamp")
        val signature = json.getString("signature")
        // Timestamp is integer epoch seconds in a plausible range (2026..2038).
        assertTrue(timestamp in 1_760_000_000..2_145_000_000)
        // The signature covers the exact attribute values that were sent.
        val expected = hmacSha256Hex(
            secret = signingSecret,
            message = userAttributesCanonicalString(
                appKey = appKey,
                userToken = token,
                isPaying = true,
                plan = "pro",
                mrr = 9.99,
                currency = "USD",
                timestampSeconds = timestamp
            )
        )
        assertEquals(expected, signature)
        assertEquals(64, signature.length)
    }

    @Test
    fun updateUserAttributesSignsCurrencyOnlyUpdate() = runTest {
        // #44: currency is now a signed payment attribute; it can no longer ride along unsigned.
        var body: String? = null
        val token = "tok-currency-only"
        signingClient { request ->
            body = request.body?.toString(Charsets.UTF_8)
            HttpResponse(200, """{"ok":true,"updatedAt":"2026-02-01T00:00:00.000Z"}""")
        }.updateUserAttributes(userToken = token, currency = "EUR")
        val json = JSONObject(requireNotNull(body))
        val timestamp = json.getLong("timestamp")
        assertEquals(
            hmacSha256Hex(
                secret = signingSecret,
                message = userAttributesCanonicalString(
                    appKey = appKey,
                    userToken = token,
                    isPaying = null,
                    plan = null,
                    mrr = null,
                    currency = "EUR",
                    timestampSeconds = timestamp
                )
            ),
            json.getString("signature")
        )
        assertFalse(json.has("isPaying"))
        assertFalse(json.has("plan"))
        assertFalse(json.has("mrr"))
    }

    @Test
    fun updateUserAttributesWithoutSignedFieldsStaysUnsigned() = runTest {
        // Requests without payment attributes keep working unchanged — and without a secret.
        var body: String? = null
        client { request ->
            body = request.body?.toString(Charsets.UTF_8)
            HttpResponse(200, """{"ok":true,"updatedAt":"2026-02-01T00:00:00.000Z"}""")
        }.updateUserAttributes(userToken = "tok-1")
        val payload = requireNotNull(body)
        assertFalse(payload.contains("signature"))
        assertFalse(payload.contains("timestamp"))
        assertEquals("{}", payload)
    }

    @Test
    fun updateUserAttributesWithPaymentAttributesWithoutSecretFailsFast() = runTest {
        var requests = 0
        try {
            client { request ->
                requests++
                HttpResponse(200, """{"ok":true,"updatedAt":"2026-02-01T00:00:00.000Z"}""")
            }.updateUserAttributes(userToken = "tok-1", isPaying = true)
            fail("Expected IllegalArgumentException when no SDK signing secret is configured")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("sdkSigningSecret"))
        }
        assertEquals(0, requests) // rejected locally; no network call was made
    }

    @Test
    fun updateUserAttributesSurfacesHttp429AsRateLimited() = runTest {
        // #26: PUT /user is rate limited per client IP; bursts surface as the typed error.
        try {
            signingClient { request ->
                HttpResponse(429, """{"error":"Too many requests. Please retry later."}""")
            }.updateUserAttributes(userToken = "tok-1", isPaying = true)
            fail("Expected FeedbackException.RateLimited on 429")
        } catch (ex: FeedbackException.RateLimited) {
            assertEquals("Too many requests. Please retry later.", ex.serverMessage)
        }
    }

    @Test
    fun updateUserAttributesSurfacesHttp429AsRateLimitedForIdentityOnlyUpdate() = runTest {
        try {
            client { request ->
                HttpResponse(429, """{"error":"Too many requests"}""")
            }.updateUserAttributes(userToken = "tok-1")
            fail("Expected FeedbackException.RateLimited on 429")
        } catch (ex: FeedbackException.RateLimited) {
            assertEquals("Too many requests", ex.serverMessage)
        }
    }

    @Test
    fun updateUserAttributesRejectsReservedPlanSentinelsClientSide() = runTest {
        var networkRequests = 0
        val testClient = signingClient {
            networkRequests += 1
            HttpResponse(200, """{"ok":true,"updatedAt":"2026-02-01T00:00:00.000Z"}""")
        }
        for (reserved in listOf("unset", "null")) {
            try {
                testClient.updateUserAttributes(userToken = "tok-1", plan = reserved)
                fail("Expected IllegalArgumentException for reserved plan '$reserved'")
            } catch (ex: IllegalArgumentException) {
                assertTrue(ex.message!!.contains("reserved protocol sentinels"))
            }
        }
        assertEquals(0, networkRequests)
    }

    @Test
    fun updateUserAttributesAllowsNearMissPlanNamesClientSide() = runTest {
        val nearMisses = listOf("UNSET", "Unset", "unsettled", "nullify", "NULL", "Null")
        for (planName in nearMisses) {
            var capturedBody: String? = null
            val testClient = signingClient { request ->
                capturedBody = request.body?.toString(Charsets.UTF_8)
                HttpResponse(200, """{"ok":true,"updatedAt":"2026-02-01T00:00:00.000Z"}""")
            }
            val result = testClient.updateUserAttributes(userToken = "tok-1", plan = planName)
            assertTrue(result.ok)
            assertNotNull(capturedBody)
            assertTrue(capturedBody!!.contains("\"plan\":\"$planName\""))
        }
    }

    @Test
    fun updateUserAttributesMapsHttp400PlanReservedSentinelToDedicatedException() = runTest {
        val errorBody = """
            {
              "error": "Validation failed",
              "details": {
                "formErrors": [],
                "fieldErrors": {
                  "plan": ["plan_reserved_sentinel"]
                }
              }
            }
        """.trimIndent()
        try {
            signingClient { _ ->
                HttpResponse(400, errorBody, headers = mapOf("X-Request-Id" to "req_plan_sentinel_1"))
            }.updateUserAttributes(userToken = "tok-1", plan = "legacy_plan")
            fail("Expected FeedbackException.ReservedPlanSentinel on HTTP 400 plan_reserved_sentinel")
        } catch (ex: FeedbackException.ReservedPlanSentinel) {
            assertEquals("Validation failed", ex.serverMessage)
            assertEquals("plan_reserved_sentinel", ex.serverCode)
            assertEquals("req_plan_sentinel_1", ex.requestId)
        }

        val defaultEx = FeedbackException.ReservedPlanSentinel()
        assertEquals(
            "The plan value is reserved ('unset' and 'null' are protocol sentinels).",
            defaultEx.message
        )
        assertFalse(defaultEx.message!!.contains("clear the plan"))
    }

    @Test
    fun validationFailedBodyWithFieldErrorsMapsToValidationFailed() = runTest {
        val errorBody = """
            {
              "error": "Validation failed",
              "details": {
                "formErrors": [],
                "fieldErrors": {
                  "appKey": ["Required"],
                  "title": ["String must contain at least 1 character(s)", "Required"]
                }
              }
            }
        """.trimIndent()
        try {
            client { _ ->
                HttpResponse(400, errorBody, headers = mapOf("X-Request-Id" to "req_validation_1"))
            }.updateUserAttributes(userToken = "tok-1")
            fail("Expected FeedbackException.ValidationFailed on 400 Validation failed with details")
        } catch (ex: FeedbackException.ValidationFailed) {
            assertEquals("Validation failed", ex.serverMessage)
            assertNull(ex.serverCode)
            assertEquals(emptyList<String>(), ex.formErrors)
            assertEquals(
                mapOf(
                    "appKey" to listOf("Required"),
                    "title" to listOf("String must contain at least 1 character(s)", "Required")
                ),
                ex.fieldErrors
            )
            assertEquals("req_validation_1", ex.requestId)
            assertEquals("Validation failed (appKey: Required)", ex.message)
        }
    }

    @Test
    fun validationFailedBodyWithFormErrorsMapsToValidationFailed() = runTest {
        val errorBody = """
            {
              "error": "Validation failed",
              "code": "unprocessable_entity",
              "details": {
                "formErrors": ["Body must be a valid JSON object"],
                "fieldErrors": {}
              }
            }
        """.trimIndent()
        try {
            client { _ -> HttpResponse(400, errorBody) }.updateUserAttributes(userToken = "tok-1")
            fail("Expected FeedbackException.ValidationFailed on form-level validation error")
        } catch (ex: FeedbackException.ValidationFailed) {
            assertEquals("Validation failed", ex.serverMessage)
            assertEquals("unprocessable_entity", ex.serverCode)
            assertEquals(listOf("Body must be a valid JSON object"), ex.formErrors)
            assertEquals(emptyMap<String, List<String>>(), ex.fieldErrors)
            assertNull(ex.requestId)
        }
    }

    @Test
    fun http400WithoutValidationDetailsStaysUnexpectedStatus() = runTest {
        val plainBodies = listOf(
            """{"error":"App not found"}""",
            """{"error":"Validation failed"}""",
            """{"error":"Validation failed","details":{"formErrors":[],"fieldErrors":{}}}""",
            "not-json-at-all"
        )
        for (body in plainBodies) {
            try {
                client { _ -> HttpResponse(400, body) }.updateUserAttributes(userToken = "tok-1")
                fail("Expected UnexpectedStatus for body: $body")
            } catch (ex: FeedbackException.UnexpectedStatus) {
                assertEquals(400, ex.code)
            }
        }
    }

    @Test
    fun specificInvalidParentMapperWinsOverGenericValidationFailed() = runTest {
        val errorBody = """
            {
              "error": "Validation failed",
              "code": "invalid_parent",
              "details": {
                "formErrors": [],
                "fieldErrors": { "parentId": ["invalid_parent"] }
              }
            }
        """.trimIndent()
        try {
            client { _ ->
                HttpResponse(400, errorBody, headers = mapOf("X-Request-Id" to "req_inv_parent_99"))
            }.postComment(
                featureRequestId = "fr-42",
                draft = CommentDraft(body = "Reply", parentId = "deleted-parent-id"),
                userToken = "tok-123"
            )
            fail("Expected FeedbackException.InvalidParentComment to take precedence")
        } catch (ex: FeedbackException.InvalidParentComment) {
            assertEquals("invalid_parent", ex.serverCode)
            assertEquals("req_inv_parent_99", ex.requestId)
        }
    }

    @Test
    fun validationFailedFallsThroughWhenNoSpecificMapperMatches() = runTest {
        // Same `Validation failed` + details shape, but no dedicated code: must surface as
        // ValidationFailed even on a comment submission (mapCommentErrors = true path).
        val errorBody = """
            {
              "error": "Validation failed",
              "details": {
                "formErrors": [],
                "fieldErrors": { "body": ["String must contain at least 1 character(s)"] }
              }
            }
        """.trimIndent()
        try {
            client { _ ->
                HttpResponse(400, errorBody, headers = mapOf("X-Request-Id" to "req_val_77"))
            }.postComment(
                featureRequestId = "fr-42",
                draft = CommentDraft(body = "Reply"),
                userToken = "tok-123"
            )
            fail("Expected FeedbackException.ValidationFailed when no specific mapper claims the body")
        } catch (ex: FeedbackException.ValidationFailed) {
            assertEquals(mapOf("body" to listOf("String must contain at least 1 character(s)")), ex.fieldErrors)
            assertEquals("req_val_77", ex.requestId)
        }
    }

    @Test
    fun validationFailedFromErrorBodyRejectsNonValidationErrors() {
        assertNull(FeedbackException.ValidationFailed.fromErrorBody("""{"error":"Too many requests"}"""))
        assertNull(FeedbackException.ValidationFailed.fromErrorBody("[]"))
        assertNull(FeedbackException.ValidationFailed.fromErrorBody(""))
        val parsed = FeedbackException.ValidationFailed.fromErrorBody(
            """{"error":"Validation failed","details":{"formErrors":["Bad"],"fieldErrors":{"a":["x"]}}}""",
            requestId = "req_direct_1"
        )
        assertNotNull(parsed)
        assertEquals("req_direct_1", parsed!!.requestId)
        assertEquals(listOf("Bad"), parsed.formErrors)
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
            updatedAt = "2026-01-01T00:00:00.000Z",
            importedVotes = 2
        )
        val updated = base.withVoteState(voted = true, count = 4)
        assertEquals(4, updated.voteCount)
        assertEquals(2, updated.importedVotes)
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
    fun postCommentMapsHttp400InvalidParentToDedicatedException() = runTest {
        val errorJson = """
            {
              "error": "The parent comment was not found on this feature request.",
              "code": "invalid_parent"
            }
        """.trimIndent()

        try {
            client { _ ->
                HttpResponse(400, errorJson, headers = mapOf("X-Request-Id" to "req_inv_parent_42"))
            }.postComment(
                featureRequestId = "fr-42",
                draft = CommentDraft(
                    body = "Reply to deleted parent",
                    parentId = "deleted-parent-id"
                ),
                userToken = "tok-123"
            )
            fail("Expected FeedbackException.InvalidParentComment")
        } catch (ex: FeedbackException.InvalidParentComment) {
            assertEquals("The parent comment was not found on this feature request.", ex.serverMessage)
            assertEquals("invalid_parent", ex.serverCode)
            assertEquals("req_inv_parent_42", ex.requestId)
            assertEquals("The parent comment was not found on this feature request.", ex.message)
        }
    }

    @Test
    fun postCommentMapsOtherHttp400ToUnexpectedStatus() = runTest {
        val errorJson = """
            {
              "error": "Validation failed",
              "code": "invalid_body"
            }
        """.trimIndent()

        try {
            client { _ ->
                HttpResponse(400, errorJson, headers = mapOf("X-Request-Id" to "req_other_400"))
            }.postComment(
                featureRequestId = "fr-42",
                draft = CommentDraft(
                    body = "Some comment"
                ),
                userToken = "tok-123"
            )
            fail("Expected FeedbackException.UnexpectedStatus")
        } catch (ex: FeedbackException.UnexpectedStatus) {
            assertEquals(400, ex.code)
            assertEquals("req_other_400", ex.requestId)
        }
    }

    @Test
    fun postCommentMapsHttp429ToRateLimited() = runTest {
        val errorJson = """
            {
              "error": "You're commenting too fast. Please slow down.",
              "code": "rate_limited"
            }
        """.trimIndent()

        try {
            client { _ ->
                HttpResponse(429, errorJson, headers = mapOf("X-Request-Id" to "req_comment_429"))
            }.postComment(
                featureRequestId = "fr-42",
                draft = CommentDraft(body = "Fast comment"),
                userToken = "tok-123"
            )
            fail("Expected FeedbackException.RateLimited")
        } catch (ex: FeedbackException.RateLimited) {
            assertEquals("You're commenting too fast. Please slow down.", ex.serverMessage)
            assertEquals("req_comment_429", ex.requestId)
        }
    }


    @Test
    fun postCommentMapsHttp429MessageFieldToServerMessage() = runTest {
        val errorJson = """
            {
              "message": "Gateway rate limit reached. Retry in a minute.",
              "code": "rate_limited"
            }
        """.trimIndent()

        try {
            client { _ ->
                HttpResponse(429, errorJson, headers = mapOf("X-Request-Id" to "req_comment_429_msg"))
            }.postComment(
                featureRequestId = "fr-42",
                draft = CommentDraft(body = "Fast comment"),
                userToken = "tok-123"
            )
            fail("Expected FeedbackException.RateLimited")
        } catch (ex: FeedbackException.RateLimited) {
            assertEquals("Gateway rate limit reached. Retry in a minute.", ex.serverMessage)
            assertEquals("req_comment_429_msg", ex.requestId)
        }
    }

    @Test
    fun postCommentMapsHttp429MessageFieldFallsBackToDefaultWhenBlank() = runTest {
        try {
            client { _ ->
                HttpResponse(429, """{"message": "", "code": "rate_limited"}""")
            }.postComment(
                featureRequestId = "fr-42",
                draft = CommentDraft(body = "Fast comment"),
                userToken = "tok-123"
            )
            fail("Expected FeedbackException.RateLimited")
        } catch (ex: FeedbackException.RateLimited) {
            assertEquals("You're doing that too fast. Please try again shortly.", ex.message)
            assertNull(ex.serverMessage)
        }
    }

    @Test
    fun invalidParentCommentDefaultConstructorPreservesCodeAndDefaultMessage() {
        val ex = FeedbackException.InvalidParentComment()
        assertEquals(FeedbackException.InvalidParentComment.CODE_INVALID_PARENT, ex.serverCode)
        assertNull(ex.serverMessage)
        assertNull(ex.requestId)
        assertEquals("The comment being replied to does not exist or is no longer available.", ex.message)
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
    fun parseFeatureRequestItemDecodesImportedVotes() {
        val json = org.json.JSONObject(
            """
            {
              "id": "fr-imported",
              "appId": "app-1",
              "title": "Imported feature from GitHub",
              "description": "Originally from GitHub issue #42",
              "status": "planned",
              "approved": true,
              "voteCount": 124,
              "importedVotes": 120,
              "hasVoted": true,
              "isOwnRequest": false,
              "createdAt": "2026-03-01T00:00:00.000Z",
              "updatedAt": "2026-03-02T00:00:00.000Z"
            }
            """.trimIndent()
        )

        val item = dev.cupthread.feedback.internal.parseFeatureRequestItem(json)
        assertEquals("fr-imported", item.id)
        assertEquals(124, item.voteCount)
        assertEquals(120, item.importedVotes)
        assertTrue(item.hasVoted)
    }

    @Test
    fun parseFeatureRequestItemDefaultsImportedVotesToZeroWhenAbsentOrNull() {
        val jsonWithoutImported = org.json.JSONObject(
            """
            {
              "id": "fr-native",
              "appId": "app-1",
              "title": "Native feature",
              "description": "Created in CupThread",
              "status": "planned",
              "approved": true,
              "voteCount": 5,
              "hasVoted": false,
              "isOwnRequest": false,
              "createdAt": "2026-03-01T00:00:00.000Z",
              "updatedAt": "2026-03-02T00:00:00.000Z"
            }
            """.trimIndent()
        )
        val item1 = dev.cupthread.feedback.internal.parseFeatureRequestItem(jsonWithoutImported)
        assertEquals(5, item1.voteCount)
        assertEquals(0, item1.importedVotes)

        val jsonWithNullImported = org.json.JSONObject(
            """
            {
              "id": "fr-null-imported",
              "appId": "app-1",
              "title": "Native feature null imported",
              "description": "Created in CupThread",
              "status": "planned",
              "approved": true,
              "voteCount": 3,
              "importedVotes": null,
              "hasVoted": false,
              "isOwnRequest": false,
              "createdAt": "2026-03-01T00:00:00.000Z",
              "updatedAt": "2026-03-02T00:00:00.000Z"
            }
            """.trimIndent()
        )
        val item2 = dev.cupthread.feedback.internal.parseFeatureRequestItem(jsonWithNullImported)
        assertEquals(3, item2.voteCount)
        assertEquals(0, item2.importedVotes)
    }

    @Test
    fun fetchFeatureRequestsPopulatesImportedVotesInList() = runTest {
        val json = """
            {
              "requests": [
                {
                  "id": "fr-1",
                  "appId": "app-1",
                  "title": "Imported from GitHub",
                  "status": "planned",
                  "approved": true,
                  "voteCount": 105,
                  "importedVotes": 100,
                  "hasVoted": false,
                  "isOwnRequest": false,
                  "createdAt": "2026-01-01T00:00:00.000Z",
                  "updatedAt": "2026-01-01T00:00:00.000Z"
                },
                {
                  "id": "fr-2",
                  "appId": "app-1",
                  "title": "Native in-app request",
                  "status": "planned",
                  "approved": true,
                  "voteCount": 8,
                  "importedVotes": 0,
                  "hasVoted": true,
                  "isOwnRequest": false,
                  "createdAt": "2026-01-02T00:00:00.000Z",
                  "updatedAt": "2026-01-02T00:00:00.000Z"
                }
              ],
              "total": 2
            }
        """.trimIndent()

        val result = client { HttpResponse(200, json) }.fetchFeatureRequests("user-token-123")
        assertEquals(2, result.requests.size)
        assertEquals(105, result.requests[0].voteCount)
        assertEquals(100, result.requests[0].importedVotes)
        assertEquals(8, result.requests[1].voteCount)
        assertEquals(0, result.requests[1].importedVotes)
    }

    @Test
    fun featureRequestItemPositionalConstructorMaintainsCompatibility() {
        val item = FeatureRequestItem(
            "fr-1",
            "app-1",
            "Title",
            "Desc",
            "planned",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            true,
            10,
            false,
            false,
            emptyList(),
            false,
            "2026-01-01T00:00:00.000Z",
            "2026-01-01T00:00:00.000Z"
        )
        assertEquals(0, item.importedVotes)
        assertEquals(10, item.voteCount)
        assertFalse(item.hasVoted)
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
    fun fetchUserProfileMapsHttp429ToRateLimited() = runTest {
        try {
            client {
                HttpResponse(
                    429,
                    """{"error":"Too many requests. Please try again shortly."}""",
                    headers = mapOf("X-Request-Id" to "req-profile-429")
                )
            }.fetchUserProfile("u_abcdef1234567890abcdef1234567890")
            fail("Expected FeedbackException.RateLimited on 429")
        } catch (ex: FeedbackException.RateLimited) {
            assertEquals("Too many requests. Please try again shortly.", ex.serverMessage)
            assertEquals("Too many requests. Please try again shortly.", ex.message)
            assertEquals("req-profile-429", ex.requestId)
        }
    }

    @Test
    fun fetchUserProfileMapsHttp429WithBlankBodyToDefaultRateLimitedMessage() = runTest {
        try {
            client {
                HttpResponse(429, "   ")
            }.fetchUserProfile("u_abcdef1234567890abcdef1234567890")
            fail("Expected FeedbackException.RateLimited on 429")
        } catch (ex: FeedbackException.RateLimited) {
            assertNull(ex.serverMessage)
            assertEquals("You're doing that too fast. Please try again shortly.", ex.message)
        }
    }

    @Test
    fun fetchUserProfileRejectsBlankUserId() = runTest {
        try {
            client { HttpResponse(200, "{}") }.fetchUserProfile("   ")
            fail("Expected IllegalArgumentException for blank userId")
        } catch (ex: IllegalArgumentException) {
            assertEquals("userId cannot be blank", ex.message)
        }
    }

    @Test
    fun fetchUserProfileTrimsUserIdInPath() = runTest {
        var url: String? = null
        val profileJson = """
            {
              "profile": {
                "clerkUserId": "u_test",
                "createdAt": "2026-01-01T00:00:00Z"
              }
            }
        """.trimIndent()

        client { request ->
            url = request.url
            HttpResponse(200, profileJson)
        }.fetchUserProfile("  u_test  ")

        assertEquals("https://api.example.com/api/v1/users/u_test/profile?appKey=$appKey", url)
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
    fun submitFeedbackMaps409AlreadyFinalizedToAttachmentAlreadyFinalized() = runTest {
        val client = client {
            HttpResponse(
                409,
                """{"error":"Upload object upl_123 has already been finalized into another submission","code":"already_finalized"}""",
                headers = mapOf("X-Request-Id" to "req-409-af")
            )
        }
        val draft = FeedbackDraft(title = "Bug", description = "Repro steps", platform = FeedbackPlatform.ANDROID)
        try {
            client.submit(draft, userToken = "tok")
            fail("Expected AttachmentAlreadyFinalized exception")
        } catch (ex: FeedbackException.AttachmentAlreadyFinalized) {
            assertEquals("Upload object upl_123 has already been finalized into another submission", ex.serverMessage)
            assertEquals("Upload object upl_123 has already been finalized into another submission", ex.message)
            assertEquals(FeedbackException.AttachmentAlreadyFinalized.CODE_ALREADY_FINALIZED, ex.serverCode)
            assertEquals("req-409-af", ex.requestId)
        }
    }

    @Test
    fun submitFeedbackMaps409UploadFinalizedConcurrentlyToAttachmentAlreadyFinalized() = runTest {
        val client = client {
            HttpResponse(
                409,
                """{"error":"Concurrent finalization detected","code":"upload_finalized_concurrently"}"""
            )
        }
        val draft = FeedbackDraft(title = "Bug", description = "Repro steps", platform = FeedbackPlatform.ANDROID)
        try {
            client.submit(draft, userToken = "tok")
            fail("Expected AttachmentAlreadyFinalized exception")
        } catch (ex: FeedbackException.AttachmentAlreadyFinalized) {
            assertEquals("Concurrent finalization detected", ex.serverMessage)
            assertEquals(FeedbackException.AttachmentAlreadyFinalized.CODE_UPLOAD_FINALIZED_CONCURRENTLY, ex.serverCode)
        }
    }

    @Test
    fun submitFeedbackMaps409AlreadyFinalizedWithoutErrorFieldToDefaultMessage() = runTest {
        val client = client {
            HttpResponse(
                409,
                """{"code":"already_finalized"}"""
            )
        }
        val draft = FeedbackDraft(title = "Bug", description = "Repro steps", platform = FeedbackPlatform.ANDROID)
        try {
            client.submit(draft, userToken = "tok")
            fail("Expected AttachmentAlreadyFinalized exception")
        } catch (ex: FeedbackException.AttachmentAlreadyFinalized) {
            assertNull(ex.serverMessage)
            assertEquals("This attachment has already been submitted.", ex.message)
            assertEquals(FeedbackException.AttachmentAlreadyFinalized.CODE_ALREADY_FINALIZED, ex.serverCode)
        }
    }

    @Test
    fun submitFeedbackMaps409AlreadyFinalizedWithExplicitNullErrorToDefaultMessage() = runTest {
        val client = client {
            HttpResponse(
                409,
                """{"code":"already_finalized","error":null}"""
            )
        }
        val draft = FeedbackDraft(title = "Bug", description = "Repro steps", platform = FeedbackPlatform.ANDROID)
        try {
            client.submit(draft, userToken = "tok")
            fail("Expected AttachmentAlreadyFinalized exception")
        } catch (ex: FeedbackException.AttachmentAlreadyFinalized) {
            assertNull(ex.serverMessage)
            assertEquals("This attachment has already been submitted.", ex.message)
        }
    }

    @Test
    fun submitFeedbackMaps409LegacyMessageWithoutCodeToAttachmentAlreadyFinalized() = runTest {
        val client = client {
            HttpResponse(
                409,
                """{"error":"Upload object upl_xyz has already been finalized into another submission"}"""
            )
        }
        val draft = FeedbackDraft(title = "Bug", description = "Repro steps", platform = FeedbackPlatform.ANDROID)
        try {
            client.submit(draft, userToken = "tok")
            fail("Expected AttachmentAlreadyFinalized exception")
        } catch (ex: FeedbackException.AttachmentAlreadyFinalized) {
            assertEquals("Upload object upl_xyz has already been finalized into another submission", ex.serverMessage)
            assertEquals(FeedbackException.AttachmentAlreadyFinalized.CODE_ALREADY_FINALIZED, ex.serverCode)
        }
    }

    @Test
    fun submitFeedbackMapsOther409CodesToUnexpectedStatus() = runTest {
        val client = client {
            HttpResponse(
                409,
                """{"error":"Some other conflict occurred","code":"version_conflict"}"""
            )
        }
        val draft = FeedbackDraft(title = "Bug", description = "Repro steps", platform = FeedbackPlatform.ANDROID)
        try {
            client.submit(draft, userToken = "tok")
            fail("Expected UnexpectedStatus exception")
        } catch (ex: FeedbackException.UnexpectedStatus) {
            assertEquals(409, ex.code)
            assertTrue(ex.serverMessage.contains("version_conflict"))
        }
    }

    @Test
    fun submitFeedbackMapsHttp429ToRateLimited() = runTest {
        val client = client {
            HttpResponse(
                429,
                """{"error":"Too many submissions. Please try again shortly.","code":"rate_limited"}""",
                headers = mapOf("X-Request-Id" to "req_submit_429")
            )
        }
        val draft = FeedbackDraft(title = "Bug", description = "Repro steps", platform = FeedbackPlatform.ANDROID)
        try {
            client.submit(draft, userToken = "tok")
            fail("Expected FeedbackException.RateLimited")
        } catch (ex: FeedbackException.RateLimited) {
            assertEquals("Too many submissions. Please try again shortly.", ex.serverMessage)
            assertEquals("req_submit_429", ex.requestId)
        }
    }

    @Test
    fun submitFeedbackMapsHttp429MessageFieldToServerMessage() = runTest {
        val client = client {
            HttpResponse(
                429,
                """{"message":"Gateway rate limit reached. Retry in a minute.","code":"rate_limited"}""",
                headers = mapOf("X-Request-Id" to "req_submit_429_msg")
            )
        }
        val draft = FeedbackDraft(title = "Bug", description = "Repro steps", platform = FeedbackPlatform.ANDROID)
        try {
            client.submit(draft, userToken = "tok")
            fail("Expected FeedbackException.RateLimited")
        } catch (ex: FeedbackException.RateLimited) {
            assertEquals("Gateway rate limit reached. Retry in a minute.", ex.serverMessage)
            assertEquals("req_submit_429_msg", ex.requestId)
        }
    }

    @Test
    fun nonSubmitEndpointsDoNotMapHttp409ToAttachmentAlreadyFinalized() = runTest {
        val client = client {
            HttpResponse(
                409,
                """{"error":"Upload object upl_123 has already been finalized into another submission","code":"already_finalized"}"""
            )
        }
        try {
            client.fetchColumns()
            fail("Expected UnexpectedStatus exception")
        } catch (ex: FeedbackException.UnexpectedStatus) {
            assertEquals(409, ex.code)
        }
    }

    @Test
    fun attachmentAlreadyFinalizedConstructorsSupportCompatibility() {
        val defaultEx = FeedbackException.AttachmentAlreadyFinalized()
        assertNull(defaultEx.serverMessage)
        assertEquals(FeedbackException.AttachmentAlreadyFinalized.CODE_ALREADY_FINALIZED, defaultEx.serverCode)
        assertNull(defaultEx.requestId)
        assertEquals("This attachment has already been submitted.", defaultEx.message)

        val oneArgEx = FeedbackException.AttachmentAlreadyFinalized("Custom error message")
        assertEquals("Custom error message", oneArgEx.serverMessage)
        assertEquals("Custom error message", oneArgEx.message)
        assertEquals(FeedbackException.AttachmentAlreadyFinalized.CODE_ALREADY_FINALIZED, oneArgEx.serverCode)
        assertNull(oneArgEx.requestId)

        val twoArgEx = FeedbackException.AttachmentAlreadyFinalized("Custom error message", "custom_code")
        assertEquals("Custom error message", twoArgEx.serverMessage)
        assertEquals("custom_code", twoArgEx.serverCode)
        assertNull(twoArgEx.requestId)

        val threeArgEx = FeedbackException.AttachmentAlreadyFinalized("Custom error message", "custom_code", "req-test")
        assertEquals("Custom error message", threeArgEx.serverMessage)
        assertEquals("custom_code", threeArgEx.serverCode)
        assertEquals("req-test", threeArgEx.requestId)
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

    @Test
    fun parseUploadSessionDecodesModernNestedFormat() {
        val json = JSONObject("""
            {
              "session": {
                "sessionId": "ups_nested_1",
                "sessionToken": "cpt_up_nested_token",
                "expiresAt": "2026-11-01T10:00:00.000Z"
              },
              "files": [
                {
                  "clientFileId": "client-1",
                  "uploadId": "upl_file_1",
                  "uploadUrl": "/api/v1/uploads/upl_file_1",
                  "maxSizeBytes": 5000000
                }
              ]
            }
        """.trimIndent())

        val res = dev.cupthread.feedback.internal.parseUploadSession(json, "client-1")
        assertEquals("cpt_up_nested_token", res.sessionToken)
        assertEquals("ups_nested_1", res.sessionId)
        assertEquals("2026-11-01T10:00:00.000Z", res.expiresAt)
        assertEquals("client-1", res.slot.clientFileId)
        assertEquals("upl_file_1", res.slot.uploadId)
        assertEquals(5000000L, res.slot.maxSizeBytes)
        assertEquals("/api/v1/uploads/upl_file_1", res.slot.uploadUrl)
    }

    @Test
    fun parseUploadSessionDecodesLegacyFlatMirrorFormat() {
        val json = JSONObject("""
            {
              "sessionId": "ups_flat_1",
              "sessionToken": "cpt_up_flat_token",
              "expiresAt": "2026-11-01T11:00:00.000Z",
              "uploads": [
                {
                  "clientFileId": "client-flat-1",
                  "uploadId": "upl_legacy_1",
                  "uploadUrl": "/api/v1/uploads/upl_legacy_1",
                  "filename": "screenshot.png",
                  "mimeType": "image/png",
                  "maxBytes": 10000000
                }
              ]
            }
        """.trimIndent())

        val res = dev.cupthread.feedback.internal.parseUploadSession(json, "client-flat-1")
        assertEquals("cpt_up_flat_token", res.sessionToken)
        assertEquals("ups_flat_1", res.sessionId)
        assertEquals("2026-11-01T11:00:00.000Z", res.expiresAt)
        assertEquals("client-flat-1", res.slot.clientFileId)
        assertEquals("upl_legacy_1", res.slot.uploadId)
        assertEquals(10000000L, res.slot.maxSizeBytes)
        assertEquals("screenshot.png", res.slot.filename)
        assertEquals("image/png", res.slot.mimeType)
        assertEquals("/api/v1/uploads/upl_legacy_1", res.slot.uploadUrl)
    }

    @Test
    fun parseUploadSessionDecodesCombinedNestedAndFlatMirrors() {
        val json = JSONObject("""
            {
              "sessionId": "ups_both",
              "sessionToken": "cpt_up_both",
              "expiresAt": "2026-11-01T12:00:00.000Z",
              "session": {
                "sessionId": "ups_both",
                "sessionToken": "cpt_up_both",
                "expiresAt": "2026-11-01T12:00:00.000Z"
              },
              "files": [
                {
                  "clientFileId": "client-both",
                  "uploadId": "upl_both_1",
                  "maxSizeBytes": 8000000
                }
              ],
              "uploads": [
                {
                  "clientFileId": "client-both",
                  "uploadId": "upl_both_1",
                  "maxBytes": 8000000
                }
              ]
            }
        """.trimIndent())

        val res = dev.cupthread.feedback.internal.parseUploadSession(json, "client-both")
        assertEquals("cpt_up_both", res.sessionToken)
        assertEquals("upl_both_1", res.slot.uploadId)
        assertEquals(8000000L, res.slot.maxSizeBytes)
    }

    @Test
    fun parseUploadSessionThrowsWhenNoMatchingClientFileId() {
        val json = JSONObject("""
            {
              "sessionToken": "cpt_up_token",
              "files": [
                {
                  "clientFileId": "other-id",
                  "uploadId": "upl_1",
                  "maxSizeBytes": 1000
                }
              ]
            }
        """.trimIndent())

        try {
            dev.cupthread.feedback.internal.parseUploadSession(json, "client-target")
            fail("Expected NoSuchElementException when slot doesn't match clientFileId")
        } catch (_: NoSuchElementException) {
        }
    }

    @Test
    fun parseUploadedSessionFileDecodesSizeFromSizeBytesOrMaxBytes() {
        val jsonWithSize = JSONObject("""{"uploadId":"upl_1","filename":"f.txt","contentType":"text/plain","sizeBytes":123}""")
        val file1 = dev.cupthread.feedback.internal.parseUploadedSessionFile(jsonWithSize)
        assertEquals("upl_1", file1.uploadId)
        assertEquals("f.txt", file1.filename)
        assertEquals("text/plain", file1.contentType)
        assertEquals(123L, file1.sizeBytes)

        val jsonWithMaxBytes = JSONObject("""{"uploadId":"upl_2","filename":"f2.txt","mimeType":"text/plain","maxBytes":456}""")
        val file2 = dev.cupthread.feedback.internal.parseUploadedSessionFile(jsonWithMaxBytes)
        assertEquals("upl_2", file2.uploadId)
        assertEquals("f2.txt", file2.filename)
        assertEquals("text/plain", file2.contentType)
        assertEquals(456L, file2.sizeBytes)
    }

    @Test
    fun parseFeatureRequestItemDecodesCommentCount() {
        val json = JSONObject("""
            {
              "id": "fr-comment-count",
              "appId": "app-1",
              "title": "Title with comments",
              "description": "Desc",
              "status": "in_progress",
              "approved": true,
              "voteCount": 42,
              "hasVoted": false,
              "isOwnRequest": false,
              "importedVotes": 5,
              "commentCount": 17,
              "createdAt": "2026-01-01T00:00:00.000Z",
              "updatedAt": "2026-01-02T00:00:00.000Z"
            }
        """.trimIndent())

        val item = dev.cupthread.feedback.internal.parseFeatureRequestItem(json)
        assertEquals(17, item.commentCount)
        assertEquals(5, item.importedVotes)
        assertEquals(42, item.voteCount)
    }

    @Test
    fun parseFeatureRequestItemDefaultsCommentCountToZeroWhenAbsentOrNull() {
        val jsonWithout = JSONObject("""
            {
              "id": "fr-no-comment-count",
              "appId": "app-1",
              "title": "Title without commentCount",
              "createdAt": "2026-01-01T00:00:00.000Z"
            }
        """.trimIndent())
        val item1 = dev.cupthread.feedback.internal.parseFeatureRequestItem(jsonWithout)
        assertEquals(0, item1.commentCount)

        val jsonNull = JSONObject("""
            {
              "id": "fr-null-comment-count",
              "appId": "app-1",
              "title": "Title with null commentCount",
              "commentCount": null,
              "createdAt": "2026-01-01T00:00:00.000Z"
            }
        """.trimIndent())
        val item2 = dev.cupthread.feedback.internal.parseFeatureRequestItem(jsonNull)
        assertEquals(0, item2.commentCount)
    }

    @Test
    fun featureRequestItemConstructorsPreserveCompatibilityAndCopyState() {
        // Constructor prior to commentCount (23 params)
        val item23 = FeatureRequestItem(
            "fr-c1",
            "app-1",
            "Title",
            "Desc",
            "planned",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            true,
            10,
            false,
            false,
            emptyList(),
            false,
            "2026-01-01T00:00:00.000Z",
            "2026-01-01T00:00:00.000Z",
            importedVotes = 3
        )
        assertEquals(0, item23.commentCount)
        assertEquals(3, item23.importedVotes)

        // withVoteState preserves commentCount
        val withComments = item23.copy(commentCount = 25)
        val updated = withComments.withVoteState(voted = true, count = 11)
        assertEquals(25, updated.commentCount)

        // Verify binary compatibility copy method exists for callers prior to commentCount
        val copyMethods = FeatureRequestItem::class.java.declaredMethods.filter { it.name == "copy" }
        assertTrue("Expected 25-param copy method for primary constructor with commentCount", copyMethods.any { it.parameterCount == 25 })
        assertTrue("Expected 24-param copy method for binary backward compatibility", copyMethods.any { it.parameterCount == 24 })
        assertEquals(11, updated.voteCount)
        assertTrue(updated.hasVoted)
    }

    @Test
    fun submitFeedbackAcceptsHttp200And201And202Statuses() = runTest {
        val draft = FeedbackDraft(title = "Bug report", description = "Crash repro", platform = FeedbackPlatform.ANDROID)

        // 200 - forwarded to GitHub
        val client200 = client {
            HttpResponse(200, """{"submissionId":"sub-200","forwardedToGithub":true,"githubDiscussionUrl":"https://github.com/test/discussions/1"}""")
        }
        val res200 = client200.submit(draft, userToken = "tok")
        assertEquals("sub-200", res200.submissionId)
        assertTrue(res200.forwardedToGithub)
        assertEquals("https://github.com/test/discussions/1", res200.githubDiscussionUrl)
        assertNull(res200.warning)

        // 201 - stored in CupThread inbox without GitHub forwarding, advisory warning present
        val client201 = client {
            HttpResponse(201, """{"submissionId":"sub-201","forwardedToGithub":false,"warning":"No GitHub discussion integration configured for this app"}""")
        }
        val res201 = client201.submit(draft, userToken = "tok")
        assertEquals("sub-201", res201.submissionId)
        assertFalse(res201.forwardedToGithub)
        assertEquals("No GitHub discussion integration configured for this app", res201.warning)

        // 202 - stored, GitHub delivery queued/retrying, advisory warning present
        val client202 = client {
            HttpResponse(202, """{"submissionId":"sub-202","forwardedToGithub":false,"warning":"GitHub discussion delivery is queued and retrying"}""")
        }
        val res202 = client202.submit(draft, userToken = "tok")
        assertEquals("sub-202", res202.submissionId)
        assertFalse(res202.forwardedToGithub)
        assertEquals("GitHub discussion delivery is queued and retrying", res202.warning)
    }

    @Test
    fun previouslyPublicReadsMapHttp401ToAuthenticationRequired() = runTest {
        // #39: anonymous-access enforcement now returns 401 authentication_required for these
        // previously-public reads; the SDK must surface a single typed sign-in error.
        val authErrorJson = """{"error":"Sign-in required","code":"authentication_required"}"""

        suspend fun assertSignInRequired(call: suspend (FeedbackClient) -> Unit) {
            try {
                call(client { HttpResponse(401, authErrorJson, headers = mapOf("X-Request-Id" to "req_anon_401")) })
                fail("Expected FeedbackException.AuthenticationRequired")
            } catch (ex: FeedbackException.AuthenticationRequired) {
                assertEquals("req_anon_401", ex.requestId)
            }
        }

        assertSignInRequired { it.fetchColumns() }
        assertSignInRequired { it.fetchVersions() }
        assertSignInRequired { it.fetchCommentsPage("fr-1") }
        assertSignInRequired { it.subscribeToChangelog("user@example.com", userToken = "tok_anon") }
    }

    @Test
    fun subscribeToChangelogMapsHttp403EmailNotVerifiedToTypedError() = runTest {
        val errorJson = """
            {
              "error": "The address does not match your signed-in account.",
              "code": "email_not_verified"
            }
        """.trimIndent()

        try {
            client {
                HttpResponse(403, errorJson, headers = mapOf("X-Request-Id" to "req_email_403"))
            }.subscribeToChangelog("other@example.com", userToken = "tok_anon")
            fail("Expected FeedbackException.EmailNotVerified")
        } catch (ex: FeedbackException.EmailNotVerified) {
            assertEquals("The address does not match your signed-in account.", ex.serverMessage)
            assertEquals(FeedbackException.EmailNotVerified.CODE_EMAIL_NOT_VERIFIED, ex.serverCode)
            assertEquals("req_email_403", ex.requestId)
        }
    }

    @Test
    fun subscribeToChangelogEmailNotVerifiedFallsBackToDefaultMessage() = runTest {
        try {
            client {
                HttpResponse(403, """{"code":"email_not_verified"}""")
            }.subscribeToChangelog("other@example.com", userToken = "tok_anon")
            fail("Expected FeedbackException.EmailNotVerified")
        } catch (ex: FeedbackException.EmailNotVerified) {
            assertNull(ex.serverMessage)
            assertEquals("email_not_verified", ex.serverCode)
            assertEquals(
                "Subscribe with the email address of the account you're signed in with.",
                ex.message
            )
        }
    }

    @Test
    fun subscribeToChangelogStillSurfacesUnrelated403AsUnexpectedStatus() = runTest {
        // Only the email_not_verified code maps; other 403s (e.g. public surfaces disabled)
        // keep their raw server body.
        try {
            client {
                HttpResponse(403, """{"error":"Public surfaces are disabled for this app"}""")
            }.subscribeToChangelog("user@example.com", userToken = "tok_anon")
            fail("Expected FeedbackException.UnexpectedStatus")
        } catch (ex: FeedbackException.UnexpectedStatus) {
            assertEquals(403, ex.code)
            assertTrue(ex.serverMessage.contains("Public surfaces are disabled"))
        }
    }

    @Test
    fun subscribeToChangelogMapsHttp429ToRateLimited() = runTest {
        try {
            client {
                HttpResponse(
                    429,
                    """{"error":"Too many subscription attempts. Please try again shortly.","code":"rate_limited"}""",
                    headers = mapOf("X-Request-Id" to "req_subscribe_429")
                )
            }.subscribeToChangelog("user@example.com", userToken = "tok_anon")
            fail("Expected FeedbackException.RateLimited")
        } catch (ex: FeedbackException.RateLimited) {
            assertEquals("Too many subscription attempts. Please try again shortly.", ex.serverMessage)
            assertEquals("req_subscribe_429", ex.requestId)
        }
    }

    @Test
    fun fetchCommentsPageMapsHttp404ToCommentsUnavailable() = runTest {
        // #39: a comments thread returns 404 while the request awaits approval; callers must not
        // conclude that the feature request never existed.
        try {
            client {
                HttpResponse(404, """{"error":"Not found"}""", headers = mapOf("X-Request-Id" to "req_comments_404"))
            }.fetchCommentsPage("fr-pending")
            fail("Expected FeedbackException.CommentsUnavailable")
        } catch (ex: FeedbackException.CommentsUnavailable) {
            assertEquals("Not found", ex.serverMessage)
            assertEquals("req_comments_404", ex.requestId)
        }
    }

    @Test
    fun fetchCommentsPropagatesCommentsUnavailable() = runTest {
        try {
            client {
                HttpResponse(404, "   ")
            }.fetchComments("fr-pending")
            fail("Expected FeedbackException.CommentsUnavailable")
        } catch (ex: FeedbackException.CommentsUnavailable) {
            assertNull(ex.serverMessage)
            assertEquals("Comments aren't available for this feature request yet.", ex.message)
        }
    }

    @Test
    fun nonCommentEndpointsStillSurfaceHttp404AsUnexpectedStatus() = runTest {
        try {
            client {
                HttpResponse(404, """{"error":"Not found"}""")
            }.fetchVersions()
            fail("Expected FeedbackException.UnexpectedStatus")
        } catch (ex: FeedbackException.UnexpectedStatus) {
            assertEquals(404, ex.code)
        }
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
