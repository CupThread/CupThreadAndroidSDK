package dev.cupthread.feedback

import dev.cupthread.feedback.internal.HttpRequest
import dev.cupthread.feedback.internal.HttpResponse
import dev.cupthread.feedback.internal.HttpTransport
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Turnstile-gated intake contract (#20): the configured [TurnstileTokenProvider] feeds
 * `turnstileToken` on feedback, feature-request, and upload-session intake; a 403 with a
 * `turnstile_*` code maps to [FeedbackException.VerificationRequired] and triggers exactly one
 * fresh-token retry when a provider is configured and the code is retryable.
 */
class TurnstileIntakeTest {
    private val appKey = "app_testkey123456"

    /** Provider that records every consultation and hands out scripted tokens in order. */
    private class RecordingProvider(vararg tokens: String?) : TurnstileTokenProvider {
        private val queue = tokens.toMutableList()
        val challenges = mutableListOf<TurnstileChallenge>()

        override suspend fun token(challenge: TurnstileChallenge): String? {
            challenges += challenge
            return queue.removeFirstOrNull()
        }
    }

    private fun turnstileClient(
        provider: TurnstileTokenProvider?,
        handler: (HttpRequest) -> HttpResponse
    ): FeedbackClient = FeedbackClient(
        config = FeedbackClientConfig(
            baseUrl = "https://api.example.com",
            appKey = appKey,
            turnstileTokenProvider = provider
        ),
        transport = HttpTransport { handler(it) }
    )

    private fun submitOk() = HttpResponse(201, """{"submissionId":"s-1","forwardedToGithub":false}""")

    private fun body(request: HttpRequest): JSONObject =
        JSONObject(String(requireNotNull(request.body), Charsets.UTF_8))

    private val draft = FeedbackDraft(
        title = "Crash on launch",
        description = "It crashed on launch",
        platform = FeedbackPlatform.ANDROID
    )

    private val featureDraft = FeatureRequestDraft(
        title = "Custom hotkeys",
        description = "Allow users to bind custom shortcuts."
    )

    // region submit (POST /api/v1/feedback)

    @Test
    fun submitConsultsProviderAndSendsTokenUnderFeedbackBinding() = runTest {
        val provider = RecordingProvider("0.test_fresh_token")
        var captured: HttpRequest? = null
        val result = turnstileClient(provider) { request ->
            captured = request
            submitOk()
        }.submit(draft)

        assertEquals("s-1", result.submissionId)
        assertEquals(
            listOf(TurnstileChallenge(TurnstileAction.FEEDBACK, appKey)),
            provider.challenges
        )
        assertEquals("0.test_fresh_token", body(requireNotNull(captured)).getString("turnstileToken"))
    }

    @Test
    fun submitOmitsTurnstileTokenWithoutProvider() = runTest {
        var captured: HttpRequest? = null
        turnstileClient(null) { request ->
            captured = request
            submitOk()
        }.submit(draft)
        assertFalse(body(requireNotNull(captured)).has("turnstileToken"))
    }

    @Test
    fun submitTrimsAndDropsBlankProviderTokens() = runTest {
        val provider = RecordingProvider("   ")
        var captured: HttpRequest? = null
        turnstileClient(provider) { request ->
            captured = request
            submitOk()
        }.submit(draft)
        assertFalse(body(requireNotNull(captured)).has("turnstileToken"))
    }

    @Test
    fun submitExplicitTokenWinsAndSkipsProviderOnSuccess() = runTest {
        val provider = RecordingProvider("0.test_should_not_be_minted")
        var captured: HttpRequest? = null
        turnstileClient(provider) { request ->
            captured = request
            submitOk()
        }.submit(draft, turnstileToken = "0.test_explicit_token")

        assertEquals(0, provider.challenges.size)
        assertEquals("0.test_explicit_token", body(requireNotNull(captured)).getString("turnstileToken"))
    }

    @Test
    fun submitRetriesExactlyOnceWithFreshTokenAfterTurnstileRequired() = runTest {
        val provider = RecordingProvider("0.test_stale_token", "0.test_fresh_token")
        val firstBodies = mutableListOf<JSONObject>()
        val result = turnstileClient(provider) { request ->
            firstBodies += body(request)
            if (firstBodies.size == 1) {
                HttpResponse(403, """{"error":"Human verification (Turnstile) is required","code":"turnstile_required"}""")
            } else {
                submitOk()
            }
        }.submit(draft)

        assertEquals("s-1", result.submissionId)
        assertEquals(2, firstBodies.size)
        assertEquals("0.test_stale_token", firstBodies[0].getString("turnstileToken"))
        assertEquals("0.test_fresh_token", firstBodies[1].getString("turnstileToken"))
        assertEquals(2, provider.challenges.size)
    }

    @Test
    fun submitExplicitTokenFallsBackToProviderForRetry() = runTest {
        val provider = RecordingProvider("0.test_retry_token")
        var attempts = 0
        turnstileClient(provider) { request ->
            attempts += 1
            if (attempts == 1) {
                HttpResponse(403, """{"error":"Human verification failed. Please try again.","code":"turnstile_verification_failed"}""")
            } else {
                assertEquals("0.test_retry_token", body(request).getString("turnstileToken"))
                submitOk()
            }
        }.submit(draft, turnstileToken = "0.test_rejected_explicit_token")

        assertEquals(2, attempts)
        assertEquals(1, provider.challenges.size)
    }

    @Test
    fun submitThrowsVerificationRequiredWhenProviderYieldsNoRetryToken() = runTest {
        val provider = RecordingProvider(null)
        var attempts = 0
        try {
            turnstileClient(provider) { request ->
                attempts += 1
                HttpResponse(403, """{"error":"Human verification (Turnstile) is required","code":"turnstile_required"}""")
            }.submit(draft)
            fail("expected VerificationRequired")
        } catch (ex: FeedbackException.VerificationRequired) {
            assertEquals(FeedbackException.VerificationRequired.CODE_REQUIRED, ex.serverCode)
            assertTrue(ex.canRetryWithFreshToken)
        }
        assertEquals(1, attempts)
        // Consulted before the first attempt and again for the retry, which yielded null.
        assertEquals(2, provider.challenges.size)
    }

    @Test
    fun submitThrowsVerificationRequiredWhenRetryIsRejectedAgain() = runTest {
        val provider = RecordingProvider("0.test_first", "0.test_second")
        var attempts = 0
        try {
            turnstileClient(provider) { request ->
                attempts += 1
                HttpResponse(403, """{"error":"Human verification failed. Please try again.","code":"turnstile_verification_failed"}""")
            }.submit(draft)
            fail("expected VerificationRequired")
        } catch (ex: FeedbackException.VerificationRequired) {
            assertEquals(FeedbackException.VerificationRequired.CODE_VERIFICATION_FAILED, ex.serverCode)
        }
        assertEquals(2, attempts)
    }

    @Test
    fun submitDoesNotRetryWhenServerIsNotConfigured() = runTest {
        val provider = RecordingProvider("0.test_token")
        var attempts = 0
        try {
            turnstileClient(provider) { request ->
                attempts += 1
                HttpResponse(403, """{"error":"Turnstile secret is missing on the server","code":"turnstile_not_configured"}""")
            }.submit(draft)
            fail("expected VerificationRequired")
        } catch (ex: FeedbackException.VerificationRequired) {
            assertEquals(FeedbackException.VerificationRequired.CODE_NOT_CONFIGURED, ex.serverCode)
            assertFalse(ex.canRetryWithFreshToken)
        }
        assertEquals(1, attempts)
    }

    @Test
    fun submitSurfacesVerificationRequiredWithoutProvider() = runTest {
        var attempts = 0
        try {
            turnstileClient(null) { request ->
                attempts += 1
                HttpResponse(403, """{"error":"Human verification (Turnstile) is required","code":"turnstile_required"}""")
            }.submit(draft)
            fail("expected VerificationRequired")
        } catch (ex: FeedbackException.VerificationRequired) {
            assertEquals(FeedbackException.VerificationRequired.CODE_REQUIRED, ex.serverCode)
        }
        assertEquals(1, attempts)
    }

    @Test
    fun submitMapsLegacyMessageOnlyTurnstile403() = runTest {
        try {
            turnstileClient(null) {
                HttpResponse(403, """{"error":"Human verification (Turnstile) is required"}""")
            }.submit(draft)
            fail("expected VerificationRequired")
        } catch (ex: FeedbackException.VerificationRequired) {
            assertNull(ex.serverCode)
            assertTrue(ex.canRetryWithFreshToken)
            assertTrue(ex.serverMessage!!.contains("Turnstile"))
        }
    }

    @Test
    fun submitKeepsUnexpectedStatusForNonTurnstile403() = runTest {
        val provider = RecordingProvider("0.test_token")
        try {
            turnstileClient(provider) {
                HttpResponse(403, """{"error":"Feedback is disabled for this app","code":"feedback_disabled"}""")
            }.submit(draft)
            fail("expected UnexpectedStatus")
        } catch (ex: FeedbackException.UnexpectedStatus) {
            assertEquals(403, ex.code)
        }
        // The provider is consulted once before the first attempt; a non-verification 403
        // must not trigger a second (retry) mint.
        assertEquals(1, provider.challenges.size)
    }

    @Test
    fun verificationRequiredSurfacesEchoedRequestId() = runTest {
        val echoed = UUID.randomUUID().toString()
        try {
            turnstileClient(null) {
                HttpResponse(
                    403,
                    """{"error":"Human verification (Turnstile) is required","code":"turnstile_required"}""",
                    headers = mapOf("X-Request-Id" to echoed)
                )
            }.submit(draft)
            fail("expected VerificationRequired")
        } catch (ex: FeedbackException.VerificationRequired) {
            assertEquals(echoed, ex.requestId)
        }
    }

    @Test
    fun providerFailureDegradesToUnverifiedRequest() = runTest {
        val provider = TurnstileTokenProvider { throw IllegalStateException("challenge unavailable") }
        var captured: HttpRequest? = null
        turnstileClient(provider) { request ->
            captured = request
            submitOk()
        }.submit(draft)
        assertFalse(body(requireNotNull(captured)).has("turnstileToken"))
    }

    // endregion

    // region submitFeatureRequest (POST /api/v1/feature-requests)

    @Test
    fun submitFeatureRequestSendsProviderTokenUnderFeatureRequestBinding() = runTest {
        val provider = RecordingProvider("0.test_fr_token")
        var captured: HttpRequest? = null
        turnstileClient(provider) { request ->
            captured = request
            HttpResponse(201, """{"featureRequestId":"fr_new_1","pending":false}""")
        }.submitFeatureRequest(featureDraft, userToken = "anon_user_token")

        assertEquals(
            listOf(TurnstileChallenge(TurnstileAction.FEATURE_REQUEST, appKey)),
            provider.challenges
        )
        assertEquals("0.test_fr_token", body(requireNotNull(captured)).getString("turnstileToken"))
    }

    @Test
    fun submitFeatureRequestRetriesOnceWithFreshToken() = runTest {
        val provider = RecordingProvider("0.test_fr_stale", "0.test_fr_fresh")
        val payloadTokens = mutableListOf<String?>()
        turnstileClient(provider) { request ->
            val payload = body(request)
            payloadTokens += if (payload.has("turnstileToken")) payload.getString("turnstileToken") else null
            if (payloadTokens.size == 1) {
                HttpResponse(403, """{"error":"Human verification (Turnstile) is required","code":"turnstile_required"}""")
            } else {
                HttpResponse(201, """{"featureRequestId":"fr_new_1","pending":true}""")
            }
        }.submitFeatureRequest(featureDraft, userToken = "anon_user_token")

        assertEquals(listOf("0.test_fr_stale", "0.test_fr_fresh"), payloadTokens)
    }

    // endregion

    // region uploadAttachment (POST /api/v1/uploads/sessions)

    private fun uploadSessionOk(clientFileId: String): String = """
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

    private fun uploadedFileOk() =
        """{"uploadId":"upl_slot1","clientFileId":"c1","filename":"log.txt","contentType":"text/plain","sizeBytes":2,"sha256":"aa","stored":true}"""

    @Test
    fun uploadSessionCreationSendsProviderTokenUnderFeedbackBinding() = runTest {
        val provider = RecordingProvider("0.test_upload_token")
        val sessionBodies = mutableListOf<JSONObject>()
        val attachment = turnstileClient(provider) { request ->
            if (request.url.endsWith("/uploads/sessions")) {
                val payload = body(request)
                sessionBodies += payload
                HttpResponse(201, uploadSessionOk(payload.getJSONArray("files").getJSONObject(0).getString("clientFileId")))
            } else {
                HttpResponse(200, uploadedFileOk())
            }
        }.uploadAttachment(
            data = byteArrayOf(0x0A),
            filename = "log.txt",
            mimeType = "text/plain",
            userToken = "usr_tok_session"
        )

        assertEquals("upl_slot1", attachment.uploadId)
        assertEquals(1, sessionBodies.size)
        assertEquals(
            listOf(TurnstileChallenge(TurnstileAction.FEEDBACK, appKey)),
            provider.challenges
        )
        assertEquals("0.test_upload_token", sessionBodies[0].getString("turnstileToken"))
    }

    @Test
    fun uploadSessionCreationRetriesOnceAfterTurnstileRequired() = runTest {
        val provider = RecordingProvider("0.test_upload_stale", "0.test_upload_fresh")
        val sessionBodies = mutableListOf<JSONObject>()
        turnstileClient(provider) { request ->
            if (request.url.endsWith("/uploads/sessions")) {
                val payload = body(request)
                sessionBodies += payload
                if (sessionBodies.size == 1) {
                    return@turnstileClient HttpResponse(
                        403,
                        """{"error":"Human verification (Turnstile) is required","code":"turnstile_required"}"""
                    )
                }
                HttpResponse(201, uploadSessionOk(payload.getJSONArray("files").getJSONObject(0).getString("clientFileId")))
            } else {
                HttpResponse(200, uploadedFileOk())
            }
        }.uploadAttachment(
            data = byteArrayOf(0x0A),
            filename = "log.txt",
            mimeType = "text/plain",
            userToken = "usr_tok_session"
        )

        assertEquals(2, sessionBodies.size)
        assertEquals("0.test_upload_stale", sessionBodies[0].getString("turnstileToken"))
        assertEquals("0.test_upload_fresh", sessionBodies[1].getString("turnstileToken"))
    }

    @Test
    fun imageUploadPathDoesNotConsultProvider() = runTest {
        val provider = RecordingProvider("0.test_should_not_be_minted")
        turnstileClient(provider) {
            HttpResponse(200, """{"kind":"image","key":"k1","url":"https://cdn.example.com/k1"}""")
        }.uploadAttachment(
            data = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte()),
            filename = "screenshot.png",
            mimeType = "image/png"
        )
        assertEquals(0, provider.challenges.size)
    }

    // endregion

    // region PublicAppConfig.turnstile

    private fun configJson(turnstile: String?) = """
        {
          "appId":"app-1","appKey":"$appKey","slug":"demo-app","name":"Demo App",
          "storeUrl":null,"storeKind":null,"iconUrl":null,"allowPublic":true,
          "allowedPlatforms":["android"],"maxAttachmentBytes":20000000,
          "requireAnonymousVoteTurnstile":true,
          "turnstile":$turnstile
        }
    """.trimIndent()

    @Test
    fun fetchAppConfigDecodesTurnstileSiteKey() = runTest {
        val withSiteKey = turnstileClient(null) { HttpResponse(200, configJson("""{"siteKey":"0x4AAAAAAA_site_key"}""")) }.fetchAppConfig()
        assertEquals("0x4AAAAAAA_site_key", withSiteKey.turnstile?.siteKey)

        val withNullSiteKey = turnstileClient(null) { HttpResponse(200, configJson("""{"siteKey":null}""")) }.fetchAppConfig()
        assertNull(withNullSiteKey.turnstile?.siteKey)
    }

    @Test
    fun fetchAppConfigLeavesTurnstileAbsentWithoutObject() = runTest {
        val absent = turnstileClient(null) { HttpResponse(200, configJson(null)) }.fetchAppConfig()
        assertNull(absent.turnstile)
    }

    @Test
    fun fetchAppConfigCollapsesBlankTurnstileSiteKeyToNull() = runTest {
        val blank = turnstileClient(null) { HttpResponse(200, configJson("""{"siteKey":"   "}""")) }.fetchAppConfig()
        assertNull(blank.turnstile?.siteKey)
    }

    @Test
    fun fetchAppConfigToleratesUnexpectedSiteKeyTypes() = runTest {
        // Numbers are not coerced and object/array values must not fail the whole config parse.
        for (broken in listOf("123", "true", "{}", "[]")) {
            val config = turnstileClient(null) { HttpResponse(200, configJson("""{"siteKey":$broken}""")) }.fetchAppConfig()
            assertNull("siteKey $broken should degrade to null", config.turnstile?.siteKey)
            assertEquals("Demo App", config.name)
        }
    }

    // endregion

    // region binary-compatibility overloads

    @Test
    fun legacyJvmSignaturesArePreservedForBinaryCompatibility() {
        // Suspend functions carry a trailing Continuation parameter in their JVM descriptors.
        val continuation = kotlin.coroutines.Continuation::class.java
        val client = FeedbackClient::class.java
        // POST /api/v1/feedback — the pre-Turnstile two-parameter descriptor.
        client.getDeclaredMethod("submit", FeedbackDraft::class.java, String::class.java, continuation)
        // POST /api/v1/feature-requests — the pre-Turnstile two-parameter descriptor.
        client.getDeclaredMethod("submitFeatureRequest", FeatureRequestDraft::class.java, String::class.java, continuation)
        // uploadAttachment — the pre-Turnstile five-parameter descriptor.
        client.getDeclaredMethod(
            "uploadAttachment",
            ByteArray::class.java,
            String::class.java,
            String::class.java,
            FeedbackAttachment.Kind::class.java,
            String::class.java,
            continuation
        )
        // FeedbackClientConfig — the constructors that existed before the provider was added.
        FeedbackClientConfig::class.java.getConstructor(
            String::class.java,
            String::class.java,
            FeedbackPlatform::class.java
        )
        FeedbackClientConfig::class.java.getConstructor(
            String::class.java,
            String::class.java,
            FeedbackPlatform::class.java,
            Function0::class.java
        )
    }

    // endregion

    // region FeedbackException.VerificationRequired retryability

    @Test
    fun canRetryFlagFollowsMachineReadableCode() {
        assertTrue(
            FeedbackException.VerificationRequired(
                FeedbackException.VerificationRequired.CODE_REQUIRED
            ).canRetryWithFreshToken
        )
        assertTrue(
            FeedbackException.VerificationRequired(
                FeedbackException.VerificationRequired.CODE_VERIFICATION_FAILED
            ).canRetryWithFreshToken
        )
        assertTrue(FeedbackException.VerificationRequired().canRetryWithFreshToken)
        assertFalse(
            FeedbackException.VerificationRequired(
                FeedbackException.VerificationRequired.CODE_NOT_CONFIGURED
            ).canRetryWithFreshToken
        )
    }

    // endregion
}
