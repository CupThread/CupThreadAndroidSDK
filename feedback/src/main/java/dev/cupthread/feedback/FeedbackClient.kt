package dev.cupthread.feedback

import android.content.Context
import dev.cupthread.feedback.internal.REQUEST_ID_HEADER
import dev.cupthread.feedback.internal.REQUEST_ID_PATTERN
import dev.cupthread.feedback.internal.HttpRequest
import dev.cupthread.feedback.internal.HttpTransport
import dev.cupthread.feedback.internal.SdkAppearanceCache
import dev.cupthread.feedback.internal.UrlConnectionTransport
import dev.cupthread.feedback.internal.AttachmentLimits
import dev.cupthread.feedback.internal.ImageMetadataScrubber
import dev.cupthread.feedback.internal.encodeQuery
import dev.cupthread.feedback.internal.hmacSha256Hex
import dev.cupthread.feedback.internal.joinUrl
import dev.cupthread.feedback.internal.multipartBody
import dev.cupthread.feedback.internal.MetadataSanitizer
import dev.cupthread.feedback.internal.parseAppVersion
import dev.cupthread.feedback.internal.parseAttachment
import dev.cupthread.feedback.internal.parseBoardColumn
import dev.cupthread.feedback.internal.parseChangelogEntry
import dev.cupthread.feedback.internal.parseChangelogSubscription
import dev.cupthread.feedback.internal.parseChangelogConfirmationResult
import dev.cupthread.feedback.internal.parseChangelogUnsubscribe
import dev.cupthread.feedback.internal.parseWeeklyDigestUnsubscribe
import dev.cupthread.feedback.internal.parseCommentList
import dev.cupthread.feedback.internal.parseListComments
import dev.cupthread.feedback.internal.parseEndUserEraseResult
import dev.cupthread.feedback.internal.parseEndUserLinkResult
import dev.cupthread.feedback.internal.parseFeatureRequestComment
import dev.cupthread.feedback.internal.parseFeatureRequestItem
import dev.cupthread.feedback.internal.parseFeatureRequestSubmission
import dev.cupthread.feedback.internal.parseFeedbackSubmission
import dev.cupthread.feedback.internal.parseListChangelog
import dev.cupthread.feedback.internal.parseListFeatureRequests
import dev.cupthread.feedback.internal.parsePublicAppConfig
import dev.cupthread.feedback.internal.parsePublicUserProfileResult
import dev.cupthread.feedback.internal.parseUserAttributesUpdate
import dev.cupthread.feedback.internal.parseUploadSession
import dev.cupthread.feedback.internal.parseUploadedSessionFile
import dev.cupthread.feedback.internal.parseVoteResult
import dev.cupthread.feedback.internal.putOptString
import dev.cupthread.feedback.internal.sanitizeMultipartFilename
import dev.cupthread.feedback.internal.userAttributesCanonicalString
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Primary HTTP API client for interacting with the CupThread developer platform.
 *
 * Provides asynchronous, coroutine-based access to:
 * - Feedback drafting and binary attachment uploads ([submit], [uploadAttachment])
 * - Public application configuration and styling ([fetchAppConfig])
 * - Roadmap kanban boards and milestones ([fetchColumns], [fetchVersions])
 * - Feature request submissions, filtering, and voting ([fetchFeatureRequests], [submitFeatureRequest], [vote], [deleteVote])
 * - Threaded feature request comments and replies ([fetchComments], [postComment])
 * - What's-New changelog streams and email subscriptions ([fetchChangelog], [subscribeToChangelog], [confirmChangelogSubscription], [unsubscribeFromChangelog], [unsubscribeFromWeeklyDigest])
 * - User telemetry and customer segment attributes ([updateUserAttributes])
 * - Public user profiles ([fetchUserProfile])
 *
 * ### Thread Safety and Concurrency
 * Every public method is a `suspend` function that automatically dispatches network operations
 * on [Dispatchers.IO]. [FeedbackClient] maintains no mutable state and is safe to use concurrently
 * from multiple coroutines. You should create a single instance during application startup
 * and share it across your dependency injection graph.
 *
 * ### Error Handling
 * All API and network failures are wrapped in subclasses of [FeedbackException], allowing
 * predictable and uniform exception handling:
 * - [FeedbackException.AuthenticationRequired]: Endpoint requires an authenticated or permitted user session (HTTP 401).
 * - [FeedbackException.SubmissionQuotaExceeded]: Workspace monthly submission quota reached (HTTP 402 `tier_limit_submissions`).
 * - [FeedbackException.SubscriptionInactive]: Workspace subscription is inactive or canceled (HTTP 402 `subscription_inactive`).
 * - [FeedbackException.VerificationRequired]: Human-verification (Turnstile) gate rejected an intake call (HTTP 403 `turnstile_*`).
 * - [FeedbackException.UnsupportedMediaType]: File format or image media type is unsupported (HTTP 415 `unsupported_media_type`).
 * - [FeedbackException.AttachmentScanRejected]: Attachment was rejected by backend virus/malware inspection (HTTP 422 `scan_rejected`).
 * - [FeedbackException.AttachmentAlreadyFinalized]: Attachment was already consumed by a concurrent submission (HTTP 409 `already_finalized`).
 * - [FeedbackException.RateLimited]: Client exceeded request rate limit, such as voting too frequently (HTTP 429).
 * - [FeedbackException.UnexpectedStatus]: Server returned an unexpected HTTP error code.
 * - [FeedbackException.ValidationFailed]: Intake request rejected by server-side validation with
 *   field-level details (HTTP 400 `Validation failed` envelope; see the endpoint's `@throws` docs).
 * - [FeedbackException.InvalidResponse]: Network transport failure or malformed JSON payload.
 * - [FeedbackException.UnreadableUploadResponse]: Upload succeeded but server response descriptor was unreadable.
 *
 * Every request carries an `X-Request-Id` header (a fresh UUID by default, or the value returned by
 * [FeedbackClientConfig.requestIdProvider]). The server's echoed identifier is available as
 * [FeedbackException.requestId] on thrown errors — quote it in support conversations so CupThread
 * can locate the exact request in its logs.
 *
 * ### Example Initialization
 * ```kotlin
 * val config = FeedbackClientConfig(
 *     baseUrl = "https://api.cupthread.com",
 *     appKey = "app_live_yourConsoleAppKey",
 *     defaultPlatform = FeedbackPlatform.ANDROID
 * )
 * val client = FeedbackClient(config)
 * val userToken = UserTokenStore.create(context).token
 *
 * lifecycleScope.launch {
 *     try {
 *         val appConfig = client.fetchAppConfig()
 *         println("Loaded app: ${appConfig.name}")
 *     } catch (e: FeedbackException) {
 *         Log.e("CupThread", "Failed to load config", e)
 *     }
 * }
 * ```
 *
 * @param config Immutable client configuration ([FeedbackClientConfig]).
 * @param transport Internal HTTP transport implementation; defaults to standard `HttpURLConnection`.
 */
class FeedbackClient internal constructor(
    val config: FeedbackClientConfig,
    internal val transport: HttpTransport
) {
    /**
     * Constructs a [FeedbackClient] with default HTTP URL connection transport.
     *
     * @param config The [FeedbackClientConfig] holding the API base URL and app key.
     *
     * Example:
     * ```kotlin
     * val client = FeedbackClient(
     *     FeedbackClientConfig(
     *         baseUrl = "https://api.cupthread.com",
     *         appKey = "app_live_sample123"
     *     )
     * )
     * ```
     */
    constructor(config: FeedbackClientConfig) : this(config, UrlConnectionTransport())

    /**
     * Submits a completed feedback draft to `POST /api/v1/feedback`.
     *
     * Text fields are automatically trimmed, and blank optional values are omitted from
     * the payload. The SDK injects default diagnostic metadata (`sdk`, `platform`, `submittedAt`)
     * alongside any custom properties in [FeedbackDraft.metadata].
     *
     * All attachments included in [FeedbackDraft.attachments] must be uploaded beforehand via
     * [uploadAttachment].
     *
     * When the deployment enforces Cloudflare Turnstile human verification, the request must
     * carry a `turnstileToken`. It is resolved from [explicitToken][turnstileToken] when
     * provided, otherwise from the configured
     * [FeedbackClientConfig.turnstileTokenProvider]; if the server rejects the first attempt,
     * the provider is consulted once more for a fresh (single-use) token and the submission is
     * retried once before [FeedbackException.VerificationRequired] is thrown.
     *
     * The server answers with one of three success status codes (all returning [FeedbackSubmissionResult]):
     * - HTTP 200: Forwarded to GitHub Discussions synchronously (`forwardedToGithub = true`).
     * - HTTP 201: Stored in the CupThread inbox without GitHub delivery configured (`forwardedToGithub = false`, advisory [FeedbackSubmissionResult.warning] present).
     * - HTTP 202: Stored in the CupThread inbox with GitHub delivery queued and retrying (`forwardedToGithub = false`, advisory [FeedbackSubmissionResult.warning] present).
     *
     * @param draft The feedback content, reporter details, and uploaded attachments ([FeedbackDraft]).
     * @param userToken Optional stable anonymous user token from [UserTokenStore]. Sent as the `X-User-Token`
     *   header to attribute submissions.
     * @param turnstileToken Optional Cloudflare Turnstile token minted for the
     *   [TurnstileAction.FEEDBACK] action with the app key as `cdata`. When provided, the configured
     *   token provider is not consulted before the first attempt.
     * @return [FeedbackSubmissionResult] containing the server-assigned submission ID and optional GitHub discussion URL.
     * @throws FeedbackException.AuthenticationRequired if anonymous feedback is disabled and token is missing (HTTP 401).
     * @throws FeedbackException.SubmissionQuotaExceeded if the workspace has reached its monthly submission limit (HTTP 402 `tier_limit_submissions`).
     * @throws FeedbackException.SubscriptionInactive if the workspace subscription is inactive (HTTP 402 `subscription_inactive`).
     * @throws FeedbackException.VerificationRequired if the human-verification gate rejects the submission (HTTP 403 `turnstile_*`).
     * @throws FeedbackException.AttachmentScanRejected if an attachment was rejected during backend content inspection (HTTP 422 `scan_rejected`).
     * @throws FeedbackException.AttachmentAlreadyFinalized if an attachment was already consumed by a concurrent submission (HTTP 409 `already_finalized`).
     * @throws FeedbackException.UploaderIdentityRequired if submitting upload-session attachments without an uploader identity (HTTP 400 `uploader_identity_required`).
     * @throws FeedbackException.UploaderMismatch if an attachment was uploaded with a different identity than the submission (HTTP 400 `uploader_mismatch`).
     * @throws FeedbackException.RateLimited if the per-client submission rate limit is exceeded (HTTP 429).
     * @throws FeedbackException.UnexpectedStatus if the server rejects the submission with an unexpected status code.
     * @throws FeedbackException.InvalidResponse if a network transport failure occurs or response parsing fails.
     *
     * Example:
     * ```kotlin
     * val draft = FeedbackDraft.autofilled(
     *     versionName = BuildConfig.VERSION_NAME,
     *     versionCode = BuildConfig.VERSION_CODE.toString()
     * ).copy(
     *     title = "Audio drops out on Bluetooth disconnect",
     *     description = "When disconnecting headphones during playback, sound does not route to speaker.",
     *     reporterEmail = "listener@example.com"
     * )
     *
     * val result = client.submit(draft, userToken = userToken)
     * Log.d("Feedback", "Submitted: ${result.submissionId}")
     * ```
     */
    suspend fun submit(
        draft: FeedbackDraft,
        userToken: String? = null,
        turnstileToken: String? = null
    ): FeedbackSubmissionResult {
        val trimmedToken = userToken?.trim()?.takeIf { it.isNotEmpty() }
        for (attachment in draft.attachments) {
            if (attachment.uploadId != null) {
                val attachmentToken = attachment.userToken?.trim()?.takeIf { it.isNotEmpty() }
                if (attachmentToken != null && trimmedToken != null && attachmentToken != trimmedToken) {
                    throw FeedbackException.UploaderMismatch(
                        serverMessage = "Attachment '${attachment.filename ?: attachment.uploadId}' was uploaded with a userToken that does not match submission userToken."
                    )
                }
                if (attachmentToken != null && trimmedToken == null) {
                    throw FeedbackException.UploaderIdentityRequired(
                        serverMessage = "Attachment '${attachment.filename ?: attachment.uploadId}' was created with an anonymous userToken, but submission has no userToken."
                    )
                }
            }
        }
        return sendWithTurnstileRetry(
            challenge = TurnstileChallenge.feedback(config.appKey),
            explicitToken = turnstileToken
        ) { token ->
            parseFeedbackSubmission(
                sendJson(
                    method = "POST",
                    path = "/api/v1/feedback",
                    body = feedbackSubmitPayload(draft, token),
                    userToken = userToken,
                    accepted = setOf(200, 201, 202),
                    mapSubmissionQuotaErrors = true,
                    mapAttachmentScanErrors = true,
                    mapAttachmentAlreadyFinalizedErrors = true,
                    mapVerificationErrors = true,
                    mapUploaderErrors = true,
                    mapRateLimitErrors = true
                )
            )
        }
    }

    /**
     * Submits a completed feedback draft without a Turnstile token.
     *
     * Preserved for binary backward compatibility with callers compiled against the
     * two-parameter signature; delegates to [submit] with a `null` `turnstileToken`.
     */
    suspend fun submit(draft: FeedbackDraft, userToken: String?): FeedbackSubmissionResult =
        submit(draft, userToken, turnstileToken = null)

    private fun feedbackSubmitPayload(draft: FeedbackDraft, turnstileToken: String?): JSONObject =
        JSONObject().apply {
            put("appKey", config.appKey)
            put("title", draft.title.trim())
            put("description", draft.description.trim())
            putOptString("reporterName", draft.reporterName.trim().ifEmpty { null })
            putOptString("reporterEmail", draft.reporterEmail.trim().ifEmpty { null })
            put("platform", draft.platform.wireValue)
            putOptString("appVersion", draft.appVersion.trim().ifEmpty { null })
            putOptString("buildNumber", draft.buildNumber.trim().ifEmpty { null })
            put("metadata", JSONObject(defaultMetadata(draft)))
            val attachments = JSONArray()
            val uploadIds = JSONArray()
            for (attachment in draft.attachments) {
                attachments.put(
                    JSONObject().apply {
                        put("kind", attachment.kind.wireValue)
                        putOptString("uploadId", attachment.uploadId)
                        putOptString("key", attachment.key)
                        putOptString("url", attachment.url)
                        putOptString("filename", attachment.filename)
                        putOptString("mimeType", attachment.mimeType)
                        if (attachment.size != null) put("size", attachment.size)
                    }
                )
                val uploadId = attachment.uploadId?.trim()?.takeIf { it.isNotEmpty() }
                if (uploadId != null) {
                    uploadIds.put(uploadId)
                }
            }
            put("attachments", attachments)
            if (uploadIds.length() > 0) {
                put("uploadIds", uploadIds)
            }
            if (turnstileToken != null) {
                put("turnstileToken", turnstileToken)
            }
        }

    /**
     * Uploads a binary file or screenshot attachment to storage and returns a descriptor ready
     * to be attached to a [FeedbackDraft].
     *
     * - Image MIME types (e.g., `image/png`, `image/jpeg`) are routed to `POST /api/v1/uploads/images`.
     *   Image attachments are stored as **private** objects: the response carries no public CDN
     *   `url`, and the file is only retrievable through the authorized, signed download route
     *   after the feedback submission finalizes. Never treat an uploaded image as publicly fetchable.
     * - All other files (e.g. logs, crashes, text) are uploaded through the upload-session lifecycle:
     *   `POST /api/v1/uploads/sessions` reserves a slot, then the raw bytes are stored via
     *   `PUT /api/v1/uploads/{uploadId}`. The legacy `POST /api/v1/uploads/r2` endpoint is removed
     *   server-side and answers `410 Gone`.
     * - Use [preferredKind] to explicitly override the destination storage backend.
     *
     * Image payloads are scrubbed before upload regardless of the destination backend, including
     * when [preferredKind] routes them to upload sessions (issue #21): EXIF GPS coordinates, XMP,
     * IPTC and comment metadata are stripped so location data never leaves the device; pixel data
     * is not re-encoded. Non-image payloads are passed through untouched.
     *
     * Accepts HTTP 200 OK and 201 Created responses. If [userToken] is provided, it is forwarded
     * as the `X-User-Token` header to associate the upload with the anonymous user identity.
     * Non-image uploads require [userToken]: upload sessions are identity-bound, so the same
     * token must later be passed to [submit].
     *
     * Non-image (upload-session) creation is Turnstile-gated like feedback intake; the resolved
     * `turnstileToken` is sent on the session-create call. It is resolved from
     * [explicitToken][turnstileToken] when provided, otherwise from the configured
     * [FeedbackClientConfig.turnstileTokenProvider], with the same single fresh-token retry.
     *
     * @param data Binary payload to upload.
     * @param filename Display file name including extension (e.g. `screenshot.png`). Control
     *   characters — CR/LF in particular — are rewritten to spaces before the name is written into
     *   a multipart header, an upload-session payload, or echoed back in the resulting
     *   [FeedbackAttachment], so the name is sanitized, never trusted verbatim. The returned
     *   [FeedbackAttachment.filename] may also be supplied by the server rather than echoing
     *   this name; it is sanitized the same way.
     * @param mimeType MIME content type (e.g. `image/png`, `application/pdf`).
     * @param preferredKind Optional explicit [FeedbackAttachment.Kind], overriding MIME-based auto-detection.
     * @param userToken Stable user token (from [UserTokenStore]) to attribute the upload; required for non-image uploads.
     * @param turnstileToken Optional Cloudflare Turnstile token minted for the
     *   [TurnstileAction.FEEDBACK] action with the app key as `cdata`; only consulted for the
     *   upload-session (non-image) path.
     * @return A populated [FeedbackAttachment] carrying the server-assigned `uploadId` (and legacy
     *   `key`/`url` only for responses from deployments predating private image attachments).
 * @throws IllegalArgumentException if a non-image upload is attempted without a non-blank [userToken].
 * @throws FeedbackException.AuthenticationRequired on HTTP 401.
 * @throws FeedbackException.UnsupportedMediaType on HTTP 415, when an unsupported image format (e.g. SVG) is uploaded,
 *   or when a recognized image attachment is structurally corrupt so its metadata cannot be removed safely.
     * @throws FeedbackException.ImageUploadFailed if the server encounters an unexpected storage failure (HTTP 500 `image_upload_failed`).
     * @throws FeedbackException.UploaderIdentityRequired if creating an upload session without an uploader identity (HTTP 400 `uploader_identity_required`).
     * @throws FeedbackException.VerificationRequired if the upload-session human-verification gate rejects the request (HTTP 403 `turnstile_*`).
     * @throws FeedbackException.UnreadableUploadResponse if the upload succeeded on the server but the response could not be parsed.
     * @throws FeedbackException.RateLimited if the upload rate limit is exceeded (HTTP 429).
     * @throws FeedbackException.UnexpectedStatus if the file upload is rejected (e.g. exceeded the session's per-file byte budget).
     * @throws FeedbackException.InvalidResponse if a network error occurs.
     *
     * Example:
     * ```kotlin
     * val screenshotBytes = captureViewAsPngBytes(window.decorView)
     * val attachment = client.uploadAttachment(
     *     data = screenshotBytes,
     *     filename = "device_screenshot.png",
     *     mimeType = "image/png",
     *     userToken = userToken
     * )
     * val draft = baseDraft.copy(attachments = listOf(attachment))
     * client.submit(draft, userToken)
     * ```
     */
    suspend fun uploadAttachment(
        data: ByteArray,
        filename: String,
        mimeType: String,
        preferredKind: FeedbackAttachment.Kind? = null,
        userToken: String? = null,
        turnstileToken: String? = null
    ): FeedbackAttachment {
        val kind = preferredKind ?: if (mimeType.startsWith("image/")) {
            FeedbackAttachment.Kind.IMAGE
        } else {
            FeedbackAttachment.Kind.R2
        }
        // Metadata (EXIF GPS coordinates in particular) never leaves the device, regardless of
        // which storage backend the payload is routed to. Validation runs on the scrubbed bytes
        // because they are what actually gets sent. A recognized image whose structure cannot
        // be parsed confidently is rejected outright: unparseable bytes cannot be promised
        // metadata-free.
        val scrubbedData = ImageMetadataScrubber.scrub(data)
            ?: throw FeedbackException.UnsupportedMediaType(
                "Image metadata could not be removed safely ($mimeType). The upload was not sent."
            )
        // #162: the filename is caller-controlled, so it is sanitized once here — before it can
        // reach a multipart header or a session payload — and the clean name is what gets
        // validated, uploaded, and echoed back.
        val safeFilename = sanitizeMultipartFilename(filename)
        if (kind == FeedbackAttachment.Kind.IMAGE) {
            AttachmentLimits.validateImageAttachment(scrubbedData, safeFilename, mimeType)
            val boundary = "Boundary-${UUID.randomUUID()}"
            val body = multipartBody(boundary, config.appKey, safeFilename, mimeType, scrubbedData)
            val json = sendMultipart(
                path = "/api/v1/uploads/images",
                boundary = boundary,
                body = body,
                userToken = userToken,
                accepted = setOf(200, 201),
                mapUnsupportedMediaType = true,
                mapRateLimitErrors = true,
                mapImageUploadErrors = true
            )
            return try {
                val attachment = parseAttachment(json)
                // The response filename is server-controlled, so it passes through the same
                // #162 sanitizer before it can reach the caller or the composer UI.
                attachment.copy(
                    filename = attachment.filename?.let(::sanitizeMultipartFilename),
                    userToken = userToken?.trim()?.takeIf { it.isNotEmpty() }
                )
            } catch (_: Exception) {
                throw FeedbackException.UnreadableUploadResponse()
            }
        }
        return uploadViaSession(scrubbedData, safeFilename, mimeType, userToken, turnstileToken)
    }

    /**
     * Uploads a binary file or screenshot attachment without a Turnstile token.
     *
     * Preserved for binary backward compatibility with callers compiled against the
     * five-parameter signature; delegates to [uploadAttachment] with a `null` `turnstileToken`.
     */
    suspend fun uploadAttachment(
        data: ByteArray,
        filename: String,
        mimeType: String,
        preferredKind: FeedbackAttachment.Kind?,
        userToken: String?
    ): FeedbackAttachment = uploadAttachment(
        data = data,
        filename = filename,
        mimeType = mimeType,
        preferredKind = preferredKind,
        userToken = userToken,
        turnstileToken = null
    )

    /**
     * Uploads a non-image attachment through the upload-session lifecycle so the resulting
     * `uploadId` can be finalized by [submit] (`uploadIds` array).
     */
    private suspend fun uploadViaSession(
        data: ByteArray,
        filename: String,
        mimeType: String,
        userToken: String?,
        turnstileToken: String?
    ): FeedbackAttachment {
        val token = userToken?.trim()?.takeIf { it.isNotEmpty() }
        requireNotNull(token) {
            "userToken is required for non-image uploads: upload sessions are identity-bound (X-User-Token)"
        }
        val targetUrl = joinUrl(config.baseUrl, "/api/v1/uploads/sessions")
        val parsedUri = try {
            java.net.URI(targetUrl)
        } catch (_: Exception) {
            null
        }
        require(parsedUri?.scheme?.equals("https", ignoreCase = true) == true) {
            "uploadAttachment destination must use HTTPS when transmitting upload-session credentials: $targetUrl"
        }
        val clientFileId = "client-${UUID.randomUUID()}"
        val sessionJson = sendWithTurnstileRetry(
            challenge = TurnstileChallenge.feedback(config.appKey),
            explicitToken = turnstileToken
        ) { verificationToken ->
            val payload = JSONObject().apply {
                put("appKey", config.appKey)
                put("purpose", "feedback_attachment")
                if (verificationToken != null) {
                    put("turnstileToken", verificationToken)
                }
                put(
                    "files",
                    JSONArray().put(
                        JSONObject().apply {
                            put("clientFileId", clientFileId)
                            put("filename", filename)
                            put("contentType", mimeType)
                            put("sizeBytes", data.size)
                        }
                    )
                )
            }
            sendJson(
                method = "POST",
                path = "/api/v1/uploads/sessions",
                body = payload,
                userToken = token,
                accepted = setOf(200, 201),
                mapRateLimitErrors = true,
                mapVerificationErrors = true,
                mapUploaderErrors = true
            )
        }
        val reservation = try {
            parseUploadSession(sessionJson, clientFileId)
        } catch (_: Exception) {
            throw FeedbackException.UnreadableUploadResponse()
        }
        val uploadedJson = sendBinary(
            method = "PUT",
            path = "/api/v1/uploads/${reservation.slot.uploadId}",
            contentType = mimeType,
            body = data,
            bearerToken = reservation.sessionToken,
            accepted = setOf(200, 201),
            mapUnsupportedMediaType = true,
            mapRateLimitErrors = true,
            mapUploaderErrors = true
        )
        val uploaded = try {
            parseUploadedSessionFile(uploadedJson)
        } catch (_: Exception) {
            throw FeedbackException.UnreadableUploadResponse()
        }
        return FeedbackAttachment(
            kind = FeedbackAttachment.Kind.R2,
            uploadId = uploaded.uploadId,
            // `filename` here is already the sanitized caller name; a server-supplied
            // response filename goes through the same #162 sanitizer.
            filename = uploaded.filename?.let(::sanitizeMultipartFilename) ?: filename,
            mimeType = uploaded.contentType ?: mimeType,
            size = uploaded.sizeBytes ?: data.size.toLong(),
            userToken = token
        )
    }

    /**
     * Fetches public app configuration from `GET /api/v1/public/config/{appKey}`.
     *
     * Returns app metadata, anonymous permission flags ([PublicAppConfig.allowAnonymousFeedback],
     * [PublicAppConfig.allowAnonymousVote], etc.), attachment size limits, and the console-configured
     * SDK appearance theme ([PublicAppConfig.sdk]).
     *
     * Public configuration is served only for public apps. An app with `allowPublic = false`
     * (private portal) and an unknown [FeedbackClientConfig.appKey] both answer HTTP 404 with
     * `{"error": "App not found"}`; the two cases are intentionally indistinguishable. A 200
     * payload therefore always describes a public app, so [PublicAppConfig.allowPublic] is `true`
     * in every successful response.
     *
     * A failed fetch is fail-closed: [FeedbackException.UnexpectedStatus] with `code == 404` is
     * thrown and the previously cached [SdkAppearance] (if any) is left untouched, so callers can
     * fall back to bundled defaults without retrying a permanent rejection.
     *
     * @return The [PublicAppConfig] for the configured application.
     * @throws FeedbackException.UnexpectedStatus on HTTP errors, including HTTP 404 when the app
     *   key is unknown or the app is not public. Check `code` to treat that case as "app not found
     *   / not public".
     * @throws FeedbackException.InvalidResponse on network failure or response parsing errors.
     *
     * Example:
     * ```kotlin
     * val appConfig = client.fetchAppConfig()
     * println("App Name: ${appConfig.name}")
     * println("Theme: ${appConfig.sdk.theme}")
     * ```
     */
    suspend fun fetchAppConfig(): PublicAppConfig {
        val appConfig = parsePublicAppConfig(get("/api/v1/public/config/${config.appKey}"))
        SdkAppearanceCache.put(config.baseUrl, config.appKey, appConfig.sdk)
        return appConfig
    }

    /**
     * Fetches the roadmap kanban columns from `GET /api/v1/public/columns/{appKey}`.
     *
     * Columns are returned sorted in ascending order by their [BoardColumn.position].
     *
     * @return List of [BoardColumn] instances configured for the app.
     * @throws FeedbackException.RateLimited if the per-client IP rate limit is exceeded (HTTP 429).
     * @throws FeedbackException.UnexpectedStatus on HTTP errors.
     * @throws FeedbackException.InvalidResponse on network failure or parsing errors.
     *
     * Example:
     * ```kotlin
     * val columns = client.fetchColumns()
     * columns.forEach { column ->
     *     println("Column: ${column.name} (${column.kind})")
     * }
     * ```
     */
    suspend fun fetchColumns(): List<BoardColumn> {
        val json = get("/api/v1/public/columns/${config.appKey}", mapRateLimitErrors = true)
        val array = json.optJSONArray("columns") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) add(parseBoardColumn(array.getJSONObject(i)))
        }.sortedBy { it.position }
    }

    /**
     * Fetches milestone release versions from `GET /api/v1/public/versions/{appKey}`.
     *
     * Versions are returned sorted in ascending order by their [AppVersion.position].
     * Version IDs can be passed to [fetchFeatureRequests] to filter requests by release target.
     *
     * @return List of [AppVersion] instances configured for the app.
     * @throws FeedbackException.RateLimited if the per-client IP rate limit is exceeded (HTTP 429).
     * @throws FeedbackException.UnexpectedStatus on HTTP errors.
     * @throws FeedbackException.InvalidResponse on network failure or parsing errors.
     *
     * Example:
     * ```kotlin
     * val versions = client.fetchVersions()
     * val nextMilestone = versions.firstOrNull { !it.released }
     * println("Upcoming release: ${nextMilestone?.label}")
     * ```
     */
    suspend fun fetchVersions(): List<AppVersion> {
        val json = get("/api/v1/public/versions/${config.appKey}", mapRateLimitErrors = true)
        val array = json.optJSONArray("versions") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) add(parseAppVersion(array.getJSONObject(i)))
        }.sortedBy { it.position }
    }

    /**
     * Fetches a paginated list of public feature requests from `GET /api/v1/feature-requests`.
     *
     * Each returned [FeatureRequestItem] is annotated with the caller's vote status ([FeatureRequestItem.hasVoted])
     * and creator status ([FeatureRequestItem.isOwnRequest]), derived from the provided [userToken].
     *
     * Responses include [ListFeatureRequestsResult.hasMore] and [ListFeatureRequestsResult.nextCursor].
     * Pass [nextCursor][ListFeatureRequestsResult.nextCursor] back as [cursor] for keyset paging, or
     * advance [offset] by the number of returned requests. Keyset paging avoids deep offsets, which
     * scan and discard rows server-side.
     *
     * @param userToken Stable anonymous user token from [UserTokenStore].
     * @param limit Maximum number of requests to return per page; default is 50.
     * @param offset Number of requests to skip for pagination; default is 0.
     * @param versionId Optional [AppVersion.id] to restrict results to a specific release target.
     * @param query Optional free-text search query matching against titles and descriptions.
     * @param cursor Optional opaque keyset cursor from a previous page's
     *   [ListFeatureRequestsResult.nextCursor]. Malformed cursors are rejected with HTTP 400.
     * @return [ListFeatureRequestsResult] containing the list of requests and total match count.
     * @throws FeedbackException.RateLimited when the per-IP search rate limit is exceeded (HTTP 429) —
     *   queries, unlike unfiltered list reads, share a 30 requests / 60 s budget with every other
     *   client behind the same egress IP.
     * @throws FeedbackException.ValidationFailed on HTTP 400 with a usable validation envelope
     *   (e.g. a malformed [cursor]); check [FeedbackException.ValidationFailed.fieldErrors] for details.
     * @throws FeedbackException.UnexpectedStatus on other HTTP errors.
     * @throws FeedbackException.InvalidResponse on network failure or parsing errors.
     *
     * Example:
     * ```kotlin
     * val result = client.fetchFeatureRequests(
     *     userToken = userToken,
     *     query = "dark mode",
     *     limit = 20
     * )
     * println("Found ${result.total} matching requests")
     * result.requests.forEach { item ->
     *     println("${item.title} (${item.voteCount} votes)")
     * }
     * ```
     */
    suspend fun fetchFeatureRequests(
        userToken: String,
        limit: Int = 50,
        offset: Int = 0,
        versionId: String? = null,
        query: String? = null,
        cursor: String? = null
    ): ListFeatureRequestsResult {
        val params = mutableListOf(
            "appKey" to config.appKey,
            "limit" to limit.toString(),
            "offset" to offset.toString()
        )
        if (!versionId.isNullOrBlank()) params += "versionId" to versionId
        if (!query.isNullOrBlank()) params += "q" to query
        if (!cursor.isNullOrBlank()) params += "cursor" to cursor
        return parseListFeatureRequests(
            get(
                path = "/api/v1/feature-requests?${encodeQuery(params)}",
                userToken = userToken,
                // Search 429s surface as a typed RateLimited error instead of a raw JSON body
                // inside UnexpectedStatus (issue #23).
                mapRateLimitErrors = true
            ),
            offset
        )
    }

    /**
     * Fetches a single feature request by its [featureRequestId] via `GET /api/v1/feature-requests/{id}`.
     *
     * Preserved for binary backward compatibility with callers who do not pass [userToken].
     *
     * @param featureRequestId Unique identifier of the target [FeatureRequestItem].
     * @return The [FeatureRequestItem] record.
     * @throws IllegalArgumentException if [featureRequestId] is blank.
     * @throws FeedbackException.AuthenticationRequired if the app's board requires sign-in and the caller has no session (HTTP 401).
     * @throws FeedbackException.RateLimited if the per-client rate limit is exceeded (HTTP 429).
     * @throws FeedbackException.UnexpectedStatus on HTTP 404 (feature request not found or unapproved) or other errors.
     * @throws FeedbackException.InvalidResponse on network failure or invalid response JSON.
     *
     * Example:
     * ```kotlin
     * val item = client.fetchFeatureRequest("fr_123")
     * println("Feature request: ${item.title} (${item.voteCount} votes)")
     * ```
     */
    suspend fun fetchFeatureRequest(
        featureRequestId: String
    ): FeatureRequestItem = fetchFeatureRequest(featureRequestId, userToken = null)

    /**
     * Fetches a single feature request by its [featureRequestId] via `GET /api/v1/feature-requests/{id}`.
     *
     * When [userToken] is provided, the returned [FeatureRequestItem] is personalized:
     * [FeatureRequestItem.hasVoted] reflects that token's ballot and [FeatureRequestItem.isOwnRequest]
     * indicates whether the caller created the feature request.
     *
     * @param featureRequestId Unique identifier of the target [FeatureRequestItem].
     * @param userToken Optional anonymous end-user token from [UserTokenStore].
     * @return The [FeatureRequestItem] record.
     * @throws IllegalArgumentException if [featureRequestId] is blank.
     * @throws FeedbackException.AuthenticationRequired if the app's board requires sign-in and the caller has no session (HTTP 401).
     * @throws FeedbackException.RateLimited if the per-client rate limit is exceeded (HTTP 429).
     * @throws FeedbackException.UnexpectedStatus on HTTP 404 (feature request not found or unapproved) or other errors.
     * @throws FeedbackException.InvalidResponse on network failure or invalid response JSON.
     *
     * Example:
     * ```kotlin
     * val item = client.fetchFeatureRequest("fr_123", userToken = userToken)
     * println("Feature request: ${item.title}, voted: ${item.hasVoted}")
     * ```
     */
    suspend fun fetchFeatureRequest(
        featureRequestId: String,
        userToken: String?
    ): FeatureRequestItem {
        require(featureRequestId.isNotBlank()) { "featureRequestId cannot be blank" }
        val trimmedToken = userToken?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmedToken != null) {
            val targetUrl = joinUrl(config.baseUrl, "/api/v1/feature-requests/${featureRequestId.trim()}")
            val parsedUri = try {
                java.net.URI(targetUrl)
            } catch (_: Exception) {
                null
            }
            require(parsedUri?.scheme?.equals("https", ignoreCase = true) == true) {
                "fetchFeatureRequest destination must use HTTPS when transmitting userToken: $targetUrl"
            }
        }
        val json = get(
            path = "/api/v1/feature-requests/${featureRequestId.trim()}",
            userToken = trimmedToken,
            mapRateLimitErrors = true
        )
        val requestJson = json.optJSONObject("request")
            ?: throw FeedbackException.InvalidResponse(IllegalStateException("Missing 'request' in response: $json"))
        return try {
            parseFeatureRequestItem(requestJson)
        } catch (error: JSONException) {
            throw FeedbackException.InvalidResponse(error)
        }
    }

    /**
     * Submits a new feature request proposal without a Turnstile token.
     *
     * Preserved for binary backward compatibility with callers compiled against the
     * two-parameter signature; delegates to [submitFeatureRequest] with a `null` `turnstileToken`.
     */
    suspend fun submitFeatureRequest(
        draft: FeatureRequestDraft,
        userToken: String
    ): FeatureRequestSubmissionResult = submitFeatureRequest(draft, userToken, turnstileToken = null)

    /**
     * Submits a new feature request proposal to `POST /api/v1/feature-requests`.
     *
     * Depending on the console's moderation settings, new requests may be immediately visible
     * or placed into moderation review (indicated by [FeatureRequestSubmissionResult.pending]).
     *
     * When the deployment enforces Cloudflare Turnstile human verification, the request must
     * carry a `turnstileToken`. It is resolved from [turnstileToken] when provided, otherwise
     * from the configured [FeedbackClientConfig.turnstileTokenProvider]; if the server rejects
     * the first attempt, the provider is consulted once more for a fresh (single-use) token and
     * the proposal is retried once before [FeedbackException.VerificationRequired] is thrown.
     *
     * @param draft The proposed title, description, and optional requester display name ([FeatureRequestDraft]).
     * @param userToken Stable anonymous user token from [UserTokenStore] that owns the request.
     * @param turnstileToken Optional Cloudflare Turnstile token minted for the
     *   [TurnstileAction.FEATURE_REQUEST] action with the app key as `cdata`. When provided, the
     *   configured token provider is not consulted before the first attempt.
     * @return [FeatureRequestSubmissionResult] with the assigned ID and moderation state.
     * @throws FeedbackException.AuthenticationRequired if the app's roadmap requires sign-in (`allowAnonymousRoadmap=false`)
     *   and no end-user session is present (HTTP 401 `authentication_required`).
     * @throws FeedbackException.SubmissionQuotaExceeded if the workspace has reached its monthly submission limit (HTTP 402 `tier_limit_submissions`).
     * @throws FeedbackException.SubscriptionInactive if the workspace subscription is inactive (HTTP 402 `subscription_inactive`).
     * @throws FeedbackException.VerificationRequired if the human-verification gate rejects the proposal (HTTP 403 `turnstile_*`).
     * @throws FeedbackException.UnexpectedStatus on HTTP rejection.
     * @throws FeedbackException.InvalidResponse on network or parsing failure.
     *
     * Example:
     * ```kotlin
     * val draft = FeatureRequestDraft(
     *     title = "Support Tablet Landscape Mode",
     *     description = "Split view layouts for larger tablets and foldables.",
     *     requesterName = "Taylor"
     * )
     * val result = client.submitFeatureRequest(draft, userToken = userToken)
     * if (result.pending) {
     *     println("Request submitted for moderator review: ${result.featureRequestId}")
     * } else {
     *     println("Request published to board: ${result.featureRequestId}")
     * }
     * ```
     */
    suspend fun submitFeatureRequest(
        draft: FeatureRequestDraft,
        userToken: String,
        turnstileToken: String? = null
    ): FeatureRequestSubmissionResult = sendWithTurnstileRetry(
        challenge = TurnstileChallenge.featureRequest(config.appKey),
        explicitToken = turnstileToken
    ) { token ->
        val payload = JSONObject().apply {
            put("appKey", config.appKey)
            put("title", draft.title.trim())
            put("description", draft.description.trim())
            putOptString("requesterName", draft.requesterName.trim().ifEmpty { null })
            put("requesterToken", userToken)
            if (token != null) {
                put("turnstileToken", token)
            }
        }
        parseFeatureRequestSubmission(
            sendJson(
                method = "POST",
                path = "/api/v1/feature-requests",
                body = payload,
                userToken = null,
                accepted = setOf(200, 201),
                mapSubmissionQuotaErrors = true,
                mapVerificationErrors = true
            )
        )
    }

    /**
     * Submits an upvote on a feature request via `POST /api/v1/feature-requests/{id}/vote`.
     *
     * Repeating the call is idempotent: it records the upvote and returns the reconciled
     * vote count (`voted = true`). It does not toggle or remove the vote. To remove an existing
     * upvote, call [deleteVote] instead.
     *
     * Preserved for binary backward compatibility with callers who do not pass [turnstileToken]
     * or [shipNotifyEmail].
     *
     * @param featureRequestId Unique ID of the target [FeatureRequestItem].
     * @param userToken Stable anonymous user token from [UserTokenStore].
     * @return [VoteResult] containing the reconciled vote state (`voted`) and current total count (`voteCount`).
     */
    suspend fun vote(
        featureRequestId: String,
        userToken: String
    ): VoteResult = vote(featureRequestId, userToken, turnstileToken = null, shipNotifyEmail = null)

    /**
     * Submits an upvote on a feature request via `POST /api/v1/feature-requests/{id}/vote`.
     *
     * Repeating the call is idempotent: it records the upvote and returns the reconciled
     * vote count (`voted = true`). It does not toggle or remove the vote. To remove an existing
     * upvote, call [deleteVote] instead.
     *
     * Preserved for binary backward compatibility with callers who do not pass [shipNotifyEmail].
     *
     * When [PublicAppConfig.requireAnonymousVoteTurnstile] is enabled, anonymous voters must provide
     * a valid [turnstileToken] (solved with action `"vote"` and cdata matching the configured `appKey`).
     *
     * @param featureRequestId Unique ID of the target [FeatureRequestItem].
     * @param userToken Stable anonymous user token from [UserTokenStore].
     * @param turnstileToken Optional Cloudflare Turnstile token for human verification.
     * @return [VoteResult] containing the updated vote state (`voted`) and current total count (`voteCount`).
     * @throws FeedbackException.AuthenticationRequired if anonymous voting is disallowed (HTTP 401).
     * @throws FeedbackException.RateLimited if the per-IP vote rate limit is exceeded (HTTP 429).
     * @throws FeedbackException.UnexpectedStatus on HTTP rejection (such as HTTP 403 when human verification is required or fails).
     * @throws FeedbackException.InvalidResponse on network or parsing failure.
     */
    suspend fun vote(
        featureRequestId: String,
        userToken: String,
        turnstileToken: String?
    ): VoteResult = vote(featureRequestId, userToken, turnstileToken = turnstileToken, shipNotifyEmail = null)

    /**
     * Submits an upvote on a feature request via `POST /api/v1/feature-requests/{id}/vote`.
     *
     * The endpoint is insert-only and idempotent: submitting a vote leaves the vote in place
     * and returns the reconciled server vote state (`voted = true`, `voteCount`). It no longer
     * removes a vote when called a second time. To remove an existing upvote, call [deleteVote] instead.
     *
     * When [PublicAppConfig.requireAnonymousVoteTurnstile] is enabled, anonymous voters must provide
     * a valid [turnstileToken] (solved with action `"vote"` or `"ship-notify"` and cdata matching the configured `appKey`).
     *
     * An optional [shipNotifyEmail] opts the address into double opt-in ship notifications;
     * a single-use confirmation email is dispatched by CupThread. The endpoint is idempotent,
     * so submitting an upvote again with [shipNotifyEmail] on an already-voted request registers
     * the notification opt-in without toggling the vote.
     *
     * The opt-in can be refused while the vote still succeeds: on a sign-in-required board the
     * address must match a verified address of the session ([VoteWarning.CODE_EMAIL_NOT_VERIFIED]),
     * and an anonymous opt-in must pass human verification
     * ([VoteWarning.CODE_SHIP_NOTIFY_VERIFICATION_FAILED]). Both cases return HTTP 200 with the
     * vote recorded and a [VoteResult.warning] describing the refusal; inspect it before telling
     * the user they will be notified.
     *
     * When [FeedbackClientConfig.turnstileTokenProvider] is configured, a rejected verification
     * (`turnstile_required` / `turnstile_verification_failed`) is retried once with a freshly
     * minted token under the same `(action, cdata)` binding. Without a provider the rejection
     * surfaces directly as [FeedbackException.VerificationRequired].
     *
     * @param featureRequestId Unique ID of the target [FeatureRequestItem].
     * @param userToken Stable anonymous user token from [UserTokenStore].
     * @param turnstileToken Optional Cloudflare Turnstile token for human verification.
     * @param shipNotifyEmail Optional email address to opt into ship notifications for this request.
     * @return [VoteResult] containing the updated vote state (`voted`), current total count (`voteCount`),
     *   and an optional [VoteResult.warning] when a secondary action was refused.
     * @throws FeedbackException.AuthenticationRequired if anonymous voting is disallowed (HTTP 401).
     * @throws FeedbackException.RateLimited if the per-IP vote rate limit is exceeded (HTTP 429).
     * @throws FeedbackException.VerificationRequired if the human-verification gate rejects the vote (HTTP 403 `turnstile_*`).
     * @throws FeedbackException.UnexpectedStatus on other HTTP rejections.
     * @throws FeedbackException.InvalidResponse on network or parsing failure.
     *
     * Example:
     * ```kotlin
     * val result = client.vote(
     *     featureRequestId = "req_123",
     *     userToken = userToken,
     *     turnstileToken = "0.test_turnstile_token...",
     *     shipNotifyEmail = "voter@example.com"
     * )
     * println("Voted: ${result.voted}, New total: ${result.voteCount}")
     * ```
     */
    suspend fun vote(
        featureRequestId: String,
        userToken: String,
        turnstileToken: String? = null,
        shipNotifyEmail: String? = null
    ): VoteResult {
        val trimmedEmail = shipNotifyEmail?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmedEmail != null) {
            val targetUrl = joinUrl(config.baseUrl, "/api/v1/feature-requests/$featureRequestId/vote")
            val parsedUri = try {
                java.net.URI(targetUrl)
            } catch (_: Exception) {
                null
            }
            require(parsedUri?.scheme?.equals("https", ignoreCase = true) == true) {
                "vote destination must use HTTPS when transmitting shipNotifyEmail: $targetUrl"
            }
        }
        return sendWithTurnstileRetry(
            challenge = if (trimmedEmail != null) {
                TurnstileChallenge.shipNotify(config.appKey)
            } else {
                TurnstileChallenge.vote(config.appKey)
            },
            explicitToken = turnstileToken
        ) { verificationToken ->
            val payload = JSONObject().apply {
                put("appKey", config.appKey)
                put("userToken", userToken)
                verificationToken?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    put("turnstileToken", it)
                }
                trimmedEmail?.let {
                    put("shipNotifyEmail", it)
                }
            }
            parseVoteResult(
                sendJson(
                    method = "POST",
                    path = "/api/v1/feature-requests/$featureRequestId/vote",
                    body = payload,
                    userToken = userToken,
                    accepted = setOf(200),
                    mapRateLimitErrors = true,
                    mapVerificationErrors = true
                )
            )
        }
    }

    /**
     * @deprecated The API endpoint `POST /api/v1/feature-requests/{id}/vote` is insert-only and no longer
     *   toggles off when called a second time. Use [vote] to cast an upvote and [deleteVote] to remove it.
     */
    @Deprecated(
        message = "POST /api/v1/feature-requests/{id}/vote is insert-only and no longer toggles. Use vote() to cast an upvote or deleteVote() to remove it.",
        replaceWith = ReplaceWith("vote(featureRequestId, userToken)")
    )
    suspend fun toggleVote(
        featureRequestId: String,
        userToken: String
    ): VoteResult = vote(featureRequestId, userToken)

    /**
     * @deprecated The API endpoint `POST /api/v1/feature-requests/{id}/vote` is insert-only and no longer
     *   toggles off when called a second time. Use [vote] to cast an upvote and [deleteVote] to remove it.
     */
    @Deprecated(
        message = "POST /api/v1/feature-requests/{id}/vote is insert-only and no longer toggles. Use vote() to cast an upvote or deleteVote() to remove it.",
        replaceWith = ReplaceWith("vote(featureRequestId, userToken, turnstileToken)")
    )
    suspend fun toggleVote(
        featureRequestId: String,
        userToken: String,
        turnstileToken: String?
    ): VoteResult = vote(featureRequestId, userToken, turnstileToken = turnstileToken)

    /**
     * @deprecated The API endpoint `POST /api/v1/feature-requests/{id}/vote` is insert-only and no longer
     *   toggles off when called a second time. Use [vote] to cast an upvote and [deleteVote] to remove it.
     */
    @Deprecated(
        message = "POST /api/v1/feature-requests/{id}/vote is insert-only and no longer toggles. Use vote() to cast an upvote or deleteVote() to remove it.",
        replaceWith = ReplaceWith("vote(featureRequestId, userToken, turnstileToken, shipNotifyEmail)")
    )
    suspend fun toggleVote(
        featureRequestId: String,
        userToken: String,
        turnstileToken: String? = null,
        shipNotifyEmail: String? = null
    ): VoteResult = vote(featureRequestId, userToken, turnstileToken, shipNotifyEmail)

    /**
     * Removes an upvote from a feature request via `DELETE /api/v1/feature-requests/{id}/vote`.
     *
     * The endpoint is idempotent: removing a vote that is not present returns HTTP 200 with
     * `voted = false` and the current total count (`voteCount`), not an error.
     *
     * @param featureRequestId Unique ID of the target [FeatureRequestItem].
     * @param userToken Stable anonymous user token from [UserTokenStore].
     * @return [VoteResult] containing the reconciled vote state (`voted = false`) and current total count (`voteCount`).
     * @throws FeedbackException.AuthenticationRequired if anonymous voting is disallowed (HTTP 401).
     * @throws FeedbackException.RateLimited if the per-IP vote rate limit is exceeded (HTTP 429).
     * @throws FeedbackException.UnexpectedStatus on HTTP rejection.
     * @throws FeedbackException.InvalidResponse on network or parsing failure.
     */
    suspend fun deleteVote(featureRequestId: String, userToken: String): VoteResult {
        val payload = JSONObject().apply {
            put("appKey", config.appKey)
            put("userToken", userToken)
        }
        return parseVoteResult(
            sendJson(
                method = "DELETE",
                path = "/api/v1/feature-requests/$featureRequestId/vote",
                body = payload,
                userToken = userToken,
                accepted = setOf(200),
                mapRateLimitErrors = true
            )
        )
    }

    /**
     * Fetches a paginated page of comments on a feature request from
     * `GET /api/v1/feature-requests/{id}/comments`.
     *
     * Comments are returned in ascending chronological order (`createdAt ASC, id ASC`, oldest first).
     *
     * Responses include [ListCommentsResult.hasMore] and [ListCommentsResult.nextCursor].
     * Pass [nextCursor][ListCommentsResult.nextCursor] back as [cursor] for keyset paging to load
     * subsequent pages without building deep offsets.
     *
     * @param featureRequestId Unique identifier of the target [FeatureRequestItem].
     * @param limit Maximum number of comments to return per page (clamped between 1 and 200; default is 200).
     * @param cursor Optional opaque keyset cursor from a previous page's [ListCommentsResult.nextCursor].
     *   Malformed cursors are rejected by the server with HTTP 400.
     * @return [ListCommentsResult] containing the comments on this page along with pagination metadata.
     * @throws IllegalArgumentException if [featureRequestId] is blank.
     * @throws FeedbackException.RateLimited if the per-client rate limit is exceeded (HTTP 429).
     * @throws FeedbackException.CommentsUnavailable if the thread is not visible (HTTP 404), for
     *   example because the feature request is still awaiting approval. The request may still
     *   exist — treat this as "comments not available" rather than "request never existed".
     * @throws FeedbackException.ValidationFailed on HTTP 400 with a usable validation envelope
     *   (e.g. a malformed [cursor]); check [FeedbackException.ValidationFailed.fieldErrors] for details.
     * @throws FeedbackException.UnexpectedStatus on other HTTP errors (such as HTTP 400 for an
     *   invalid cursor without a validation envelope).
     * @throws FeedbackException.InvalidResponse on network or parsing failure.
     *
     * Example:
     * ```kotlin
     * val page = client.fetchCommentsPage("req_123", limit = 50)
     * println("Found ${page.total} comments (page size: ${page.comments.size}, hasMore: ${page.hasMore})")
     * if (page.hasMore && page.nextCursor != null) {
     *     val nextPage = client.fetchCommentsPage("req_123", cursor = page.nextCursor)
     * }
     * ```
     */
    suspend fun fetchCommentsPage(
        featureRequestId: String,
        limit: Int = 200,
        cursor: String? = null
    ): ListCommentsResult {
        require(featureRequestId.isNotBlank()) { "featureRequestId cannot be blank" }
        val clampedLimit = limit.coerceIn(1, 200)
        val params = mutableListOf<Pair<String, String>>()
        if (clampedLimit != 200 || !cursor.isNullOrBlank()) {
            params += "limit" to clampedLimit.toString()
        }
        if (!cursor.isNullOrBlank()) {
            params += "cursor" to cursor
        }
        val path = if (params.isNotEmpty()) {
            "/api/v1/feature-requests/${featureRequestId.trim()}/comments?${encodeQuery(params)}"
        } else {
            "/api/v1/feature-requests/${featureRequestId.trim()}/comments"
        }
        val json = get(
            path,
            mapRateLimitErrors = true,
            mapCommentsUnavailableErrors = true
        )
        return parseListComments(json)
    }

    /**
     * Fetches all visible comments on a feature request from `GET /api/v1/feature-requests/{id}/comments`.
     *
     * Keyset-paginated on the server with a maximum page size of 200. This method automatically walks
     * subsequent pages using [fetchCommentsPage] until [ListCommentsResult.hasMore] is `false`,
     * collecting all comments for callers that display the entire comment thread.
     *
     * @param featureRequestId Target [FeatureRequestItem.id].
     * @return List of all [FeatureRequestComment] records on the thread, ordered ascending by creation time.
     * @throws IllegalArgumentException if [featureRequestId] is blank.
     * @throws FeedbackException.RateLimited if the per-client rate limit is exceeded (HTTP 429).
     * @throws FeedbackException.CommentsUnavailable if the thread is not visible (HTTP 404), for
     *   example because the feature request is still awaiting approval.
     * @throws FeedbackException.UnexpectedStatus on other HTTP errors.
     * @throws FeedbackException.InvalidResponse on network or parsing errors.
     *
     * Example:
     * ```kotlin
     * val comments = client.fetchComments("req_123")
     * comments.filter { !it.isHidden }.forEach { comment ->
     *     println("${comment.authorName ?: "Anonymous"}: ${comment.body}")
     * }
     * ```
     */
    suspend fun fetchComments(featureRequestId: String): List<FeatureRequestComment> {
        val allComments = mutableListOf<FeatureRequestComment>()
        var currentCursor: String? = null
        val seenCursors = mutableSetOf<String>()
        do {
            val page = fetchCommentsPage(
                featureRequestId = featureRequestId,
                limit = 200,
                cursor = currentCursor
            )
            allComments.addAll(page.comments)
            if (page.hasMore) {
                if (page.comments.isEmpty()) {
                    throw FeedbackException.InvalidResponse(
                        IllegalStateException("Server indicated more comments are available but returned an empty page")
                    )
                }
                val next = page.nextCursor
                if (next.isNullOrBlank()) {
                    throw FeedbackException.InvalidResponse(
                        IllegalStateException("Server indicated more comments are available but nextCursor is missing")
                    )
                }
                if (!seenCursors.add(next)) {
                    throw FeedbackException.InvalidResponse(
                        IllegalStateException("Server returned repeated cursor during comment pagination: $next")
                    )
                }
                currentCursor = next
            } else {
                currentCursor = null
            }
        } while (currentCursor != null)
        return allComments
    }

    /**
     * Posts a new comment or threaded `@reply` on a feature request via
     * `POST /api/v1/feature-requests/{id}/comments`.
     *
     * @param featureRequestId ID of the feature request being commented on.
     * @param draft Comment text and optional author/reply metadata ([CommentDraft]).
     * @param userToken Stable anonymous token from [UserTokenStore], sent as the `X-User-Token` header.
     * @return The created [FeatureRequestComment] with server-assigned ID and timestamp.
     * @throws FeedbackException.AuthenticationRequired if commenting requires sign-in (HTTP 401).
     * @throws FeedbackException.InvalidParentComment if [CommentDraft.parentId] does not resolve to a visible comment on the target request (HTTP 400 `invalid_parent`).
     * @throws FeedbackException.RateLimited if the per-client comment rate limit is exceeded (HTTP 429).
     * @throws FeedbackException.UnexpectedStatus on HTTP errors.
     * @throws FeedbackException.InvalidResponse on network or parsing errors.
     *
     * Example:
     * ```kotlin
     * val draft = CommentDraft(
     *     body = "Great idea! I can help test this if you need beta feedback.",
     *     authorName = "Jordan",
     *     parentId = parentComment?.id,
     *     replyToAuthorName = parentComment?.authorName
     * )
     * val comment = client.postComment("req_123", draft, userToken = userToken)
     * println("Posted comment: ${comment.id}")
     * ```
     */
    suspend fun postComment(
        featureRequestId: String,
        draft: CommentDraft,
        userToken: String
    ): FeatureRequestComment {
        val payload = JSONObject().apply {
            put("body", draft.body.trim())
            putOptString("authorName", draft.authorName?.trim()?.ifEmpty { null })
            putOptString("authorEmail", draft.authorEmail?.trim()?.ifEmpty { null })
            putOptString("authorAvatarUrl", draft.authorAvatarUrl?.trim()?.ifEmpty { null })
            putOptString("parentId", draft.parentId)
            putOptString("replyToClerkId", draft.replyToClerkId)
            putOptString("replyToAuthorName", draft.replyToAuthorName?.trim()?.ifEmpty { null })
        }
        return parseFeatureRequestComment(
            sendJson(
                method = "POST",
                path = "/api/v1/feature-requests/$featureRequestId/comments",
                body = payload,
                userToken = userToken,
                accepted = setOf(200, 201),
                mapCommentErrors = true,
                mapRateLimitErrors = true
            )
        )
    }

    /**
     * Fetches a paginated page of published changelog entries and release notes from
     * `GET /api/v1/public/apps/{appKey}/changelog`.
     *
     * Entries are returned in descending chronological order by [ChangelogEntry.publishedAt] (newest first).
     *
     * Responses include [ListChangelogResult.hasMore] and [ListChangelogResult.nextCursor].
     * Pass [nextCursor][ListChangelogResult.nextCursor] back as [cursor] for keyset paging to load
     * subsequent pages without building deep offsets.
     *
     * @param limit Maximum number of changelog entries to return per page (clamped between 1 and 100; default is 100).
     * @param cursor Optional opaque keyset cursor from a previous page's [ListChangelogResult.nextCursor].
     *   Malformed cursors are rejected by the server with HTTP 400.
     * @return [ListChangelogResult] containing the entries on this page along with pagination metadata.
     * @throws FeedbackException.RateLimited if the per-client IP rate limit is exceeded (HTTP 429).
     * @throws FeedbackException.UnexpectedStatus on HTTP errors.
     * @throws FeedbackException.InvalidResponse on network or parsing errors.
     *
     * Example:
     * ```kotlin
     * val page = client.fetchChangelogPage(limit = 50)
     * println("Fetched ${page.entries.size} entries (hasMore=${page.hasMore})")
     * if (page.hasMore && page.nextCursor != null) {
     *     val nextPage = client.fetchChangelogPage(cursor = page.nextCursor)
     * }
     * ```
     */
    suspend fun fetchChangelogPage(
        limit: Int = 100,
        cursor: String? = null
    ): ListChangelogResult {
        val clampedLimit = limit.coerceIn(1, 100)
        val params = mutableListOf(
            "limit" to clampedLimit.toString()
        )
        if (!cursor.isNullOrBlank()) {
            params += "cursor" to cursor
        }
        val json = get(
            "/api/v1/public/apps/${config.appKey}/changelog?${encodeQuery(params)}",
            mapRateLimitErrors = true
        )
        return parseListChangelog(json)
    }

    /**
     * Fetches published changelog entries and release notes from
     * `GET /api/v1/public/apps/{appKey}/changelog`.
     *
     * Entries are returned in descending chronological order by [ChangelogEntry.publishedAt] (newest first).
     *
     * @param limit Maximum number of changelog entries to return per page (clamped between 1 and 100; default is 100).
     * @param cursor Optional keyset cursor from a previous page's [ListChangelogResult.nextCursor].
     * @return List of published [ChangelogEntry] objects.
     * @throws FeedbackException.RateLimited if the per-client IP rate limit is exceeded (HTTP 429).
     * @throws FeedbackException.UnexpectedStatus on HTTP errors.
     * @throws FeedbackException.InvalidResponse on network or parsing errors.
     *
     * Example:
     * ```kotlin
     * val changelog = client.fetchChangelog()
     * changelog.forEach { entry ->
     *     println("${entry.versionLabel ?: "Release"}: ${entry.title}")
     *     println(entry.body)
     * }
     * ```
     */
    suspend fun fetchChangelog(
        limit: Int = 100,
        cursor: String? = null
    ): List<ChangelogEntry> = fetchChangelogPage(limit = limit, cursor = cursor).entries

    /**
     * Prepares changelog entries and appearance styling for What's-New presentation overlays
     * ([dev.cupthread.feedback.ui.ChangelogOverlay] and [dev.cupthread.feedback.ui.presentLatestChangelog]).
     *
     * Automatically honors the console-configured `changelogOverlay.entryCount` setting,
     * clamped between 1 and 10 entries.
     *
     * When [onlyIfUnseen] is `true` and [context] is provided, checks [ChangelogStore] on the device
     * and returns `null` if the latest release has already been viewed.
     *
     * @param context Optional Android [Context] used to look up seen changelog records in [ChangelogStore].
     * @param onlyIfUnseen If `true`, returns `null` when the latest changelog version or ID has already been marked as seen.
     * @return A [Pair] containing the list of entries and the active [SdkAppearance],
     *   or `null` if the changelog feature is disabled, no entries exist, or the latest entry was already seen.
     * @throws FeedbackException on network or parsing failure.
     *
     * Example:
     * ```kotlin
     * val overlayData = client.prepareChangelogOverlay(context, onlyIfUnseen = true)
     * if (overlayData != null) {
     *     val (entries, appearance) = overlayData
     *     println("Displaying ${entries.size} new updates with title '${appearance.changelogOverlay.title}'")
     * }
     * ```
     */
    suspend fun prepareChangelogOverlay(
        context: Context? = null,
        onlyIfUnseen: Boolean = false
    ): Pair<List<ChangelogEntry>, SdkAppearance>? {
        val appearance = fetchAppConfig().sdk
        if (!appearance.features.changelog) return null
        val entries = fetchChangelog().take(appearance.changelogOverlay.clampedEntryCount)
        if (entries.isEmpty()) return null

        if (onlyIfUnseen && context != null) {
            val store = ChangelogStore.create(context)
            val latest = entries.firstOrNull()
            if (latest != null) {
                val hasSeenLabel = !latest.versionLabel.isNullOrBlank() && store.hasSeenChangelog(latest.versionLabel)
                val hasSeenId = store.hasSeenChangelog(latest.id)
                if (hasSeenLabel || hasSeenId) {
                    return null
                }
            }
        }

        return entries to appearance
    }

    /**
     * Checks whether the user has already viewed the changelog for [versionOrId] on this device.
     *
     * @param context Android [Context].
     * @param versionOrId Version string (e.g. `"1.2.0"`) or unique changelog entry ID.
     * @return `true` if previously viewed, `false` otherwise.
     */
    fun hasSeenChangelog(context: Context, versionOrId: String): Boolean =
        ChangelogStore.create(context).hasSeenChangelog(versionOrId)

    /**
     * Marks the changelog for [versionOrId] as seen on this device.
     *
     * @param context Android [Context].
     * @param versionOrId Version string (e.g. `"1.2.0"`) or unique changelog entry ID.
     */
    fun markChangelogSeen(context: Context, versionOrId: String) {
        ChangelogStore.create(context).markChangelogSeen(versionOrId)
    }

    /**
     * Subscribes an email address to product update emails via
     * `POST /api/v1/public/apps/{appKey}/changelog/subscribe`.
     *
     * Preserved for binary backward compatibility with callers who do not pass [turnstileToken].
     *
     * @param email Valid email address to subscribe.
     * @param userToken Stable anonymous token from [UserTokenStore].
     * @return [ChangelogSubscriptionResult] indicating the subscription request was accepted
     *   (pending confirmation).
     * @throws FeedbackException.UnexpectedStatus on HTTP errors.
     * @throws FeedbackException.InvalidResponse on network or parsing errors.
     */
    suspend fun subscribeToChangelog(
        email: String,
        userToken: String
    ): ChangelogSubscriptionResult = subscribeToChangelog(email, userToken, turnstileToken = null)

    /**
     * Subscribes an email address to product update emails via
     * `POST /api/v1/public/apps/{appKey}/changelog/subscribe`.
     *
     * The flow is double opt-in: a successful call places the address in the **pending** state
     * and dispatches a confirmation email with a single-use link. Pending subscriptions never
     * receive changelog emails and are not counted until the emailed link is confirmed. The
     * server response is uniform — it no longer reports whether the address was already
     * subscribed ([ChangelogSubscriptionResult.alreadySubscribed] is always `false`).
     *
     * When human verification is enabled on the server, anonymous subscription requests require
     * a valid [turnstileToken].
     *
     * @param email Valid email address to subscribe.
     * @param userToken Stable anonymous token from [UserTokenStore].
     * @param turnstileToken Optional Cloudflare Turnstile token for human verification.
     * @return [ChangelogSubscriptionResult] indicating the subscription request was accepted
     *   (pending confirmation).
     * @throws FeedbackException.EmailNotVerified if the app requires a signed-in session and
     *   [email] does not match that session's verified address (HTTP 403 `email_not_verified`).
     *   Prompt the user to subscribe with their signed-in account address.
     * @throws FeedbackException.AuthenticationRequired if anonymous changelog subscriptions are
     *   disabled for the app (HTTP 401 `authentication_required`).
     * @throws FeedbackException.RateLimited if the per-client rate limit is exceeded (HTTP 429).
     *   The changelog POST spends its rate-limit budget before the request body is parsed, so a
     *   429 can arrive ahead of any validation error.
     * @throws FeedbackException.UnexpectedStatus on other HTTP errors (such as HTTP 403 when human
     *   verification is required or fails).
     * @throws FeedbackException.InvalidResponse on network or parsing errors.
     *
     * Example:
     * ```kotlin
     * val result = client.subscribeToChangelog(
     *     email = "user@example.com",
     *     userToken = userToken,
     *     turnstileToken = "0.test_turnstile_token..."
     * )
     * if (result.subscribed) {
     *     println("Check your inbox to confirm the subscription.")
     * }
     * ```
     */
    suspend fun subscribeToChangelog(
        email: String,
        userToken: String,
        turnstileToken: String?
    ): ChangelogSubscriptionResult {
        val payload = JSONObject().apply {
            put("email", email.trim())
            turnstileToken?.trim()?.takeIf { it.isNotEmpty() }?.let {
                put("turnstileToken", it)
            }
        }
        return parseChangelogSubscription(
            sendJson(
                "POST",
                "/api/v1/public/apps/${config.appKey}/changelog/subscribe",
                payload,
                userToken = userToken,
                accepted = setOf(200, 201),
                mapRateLimitErrors = true,
                mapEmailNotVerifiedErrors = true
            )
        )
    }

    /**
     * Unsubscribes from product update emails via
     * `POST /api/v1/public/apps/{appKey}/changelog/unsubscribe` using the per-subscriber token
     * carried by the unsubscribe link in every changelog/confirmation email.
     *
     * The unauthenticated bare-email unsubscribe (`{"email": "..."}`) was removed from the API:
     * only the signed `token` issued to a subscriber is accepted, so unsubscribe is a
     * user-driven action performed from the emailed link (or that token pasted into an app),
     * never from an in-app email entry alone. Responses are uniform whether or not the
     * subscription existed.
     *
     * Token acceptance (API contract sync, issue #58):
     * - A token whose signature matches this app is honored even after its `exp` claim and even
     *   when the app's public surfaces are disabled; the call then returns HTTP 200 and completes
     *   the unsubscribe. Do not discard a signed link because it is old or the board stopped being
     *   public.
     * - On an app with public surfaces disabled, a missing or unverifiable token fails with HTTP
     *   403 (`{"error": "Public surfaces are disabled for this app"}`). That response does not
     *   reveal whether the app exists, so it means "token missing or not valid for this app".
     * - On an app with public surfaces enabled, a missing token fails with HTTP 400
     *   (`"An unsubscribe token is required"`) and a signature that does not verify fails with
     *   HTTP 400 (`"Invalid or expired unsubscribe token"`). Despite that wording, the 400 is only
     *   returned for unauthentic signatures: an authentic token is never rejected for being past
     *   its `exp`.
     *
     * @param token Per-subscriber signed unsubscribe token from the changelog/confirmation email link.
     * @return [ChangelogUnsubscribeResult] indicating whether the address was removed.
     * @throws IllegalArgumentException when the configured [FeedbackClientConfig.baseUrl] is not
     *   HTTPS — the token is a bearer credential for the subscription and is never sent in cleartext.
     * @throws FeedbackException.UnexpectedStatus on HTTP errors without a validation envelope,
     *   including HTTP 403 when public surfaces are disabled for the app (token missing or not
     *   valid for this app) and HTTP 400 for an unauthentic token or a missing token without a
     *   validation envelope.
     * @throws FeedbackException.ValidationFailed on HTTP 400 with a usable validation envelope
     *   (e.g. a missing token); check [FeedbackException.ValidationFailed.fieldErrors] for details.
     * @throws FeedbackException.InvalidResponse on network or parsing errors.
     *
     * Example:
     * ```kotlin
     * val result = client.unsubscribeFromChangelog(token = "sub_token_from_email_link")
     * println("Unsubscribed: ${result.unsubscribed}")
     * ```
     */
    suspend fun unsubscribeFromChangelog(token: String): ChangelogUnsubscribeResult {
        val targetUrl = joinUrl(config.baseUrl, "/api/v1/public/apps/${config.appKey}/changelog/unsubscribe")
        val parsedUri = try {
            java.net.URI(targetUrl)
        } catch (_: Exception) {
            null
        }
        require(parsedUri?.scheme?.equals("https", ignoreCase = true) == true) {
            "unsubscribeFromChangelog destination must use HTTPS when transmitting the unsubscribe token: $targetUrl"
        }
        val payload = JSONObject().put("token", token.trim())
        return parseChangelogUnsubscribe(
            sendJson(
                "POST",
                "/api/v1/public/apps/${config.appKey}/changelog/unsubscribe",
                payload,
                accepted = setOf(200)
            )
        )
    }

    /**
     * Unsubscribes an address from the weekly digest email via
     * `POST /api/v1/public/digest/unsubscribe` using the signed per-subscriber token carried
     * by the digest email's `List-Unsubscribe` header and footer link (RFC 8058 one-click).
     *
     * This endpoint is not app-scoped: the signed token alone identifies the workspace and
     * the subscriber, so no [FeedbackClientConfig.appKey] participates in the request. The
     * SDK surfaces it for hosts that want to drive digest management from the app (an
     * in-app notification-settings screen, a support flow, etc.); the primary consumer of
     * the endpoint remains the email footer itself, and the `GET` confirm page is a
     * non-destructive browser interstitial (HTTP 405 for JSON clients) that the SDK
     * intentionally does not wrap (API sync, issue #36).
     *
     * Behavior (API contract):
     * - Replay is idempotent: the uniform 200 `{"unsubscribed": true}` is returned whether
     *   or not the address was still subscribed. A 200 without the boolean `unsubscribed`
     *   confirmation is rejected as [FeedbackException.InvalidResponse] rather than treated
     *   as a silent success (issue #36 review).
     * - Unsubscribing clears only the workspace `weekly.digest` **email** event; inbox
     *   notifications are unchanged.
     * - A missing, invalid, or expired token fails with HTTP 400.
     * - The endpoint is rate limited per client IP (HTTP 429), surfaced as
     *   [FeedbackException.RateLimited] like every other SDK call.
     *
     * @param token Signed digest-unsubscribe token from the digest email's unsubscribe link.
     * @return [WeeklyDigestUnsubscribeResult] indicating whether the request was processed.
     * @throws IllegalArgumentException when the configured [FeedbackClientConfig.baseUrl] is not
     *   HTTPS — the token is a bearer credential for the subscription and is never sent in cleartext.
     * @throws FeedbackException.RateLimited on HTTP 429 (per-client-IP rate limiting).
     * @throws FeedbackException.UnexpectedStatus on HTTP errors without a validation envelope,
     *   including HTTP 400 for a missing, invalid, or expired token.
     * @throws FeedbackException.ValidationFailed on HTTP 400 with a usable validation envelope;
     *   check [FeedbackException.ValidationFailed.fieldErrors] for details.
     * @throws FeedbackException.InvalidResponse on network or parsing errors.
     *
     * Example:
     * ```kotlin
     * val result = client.unsubscribeFromWeeklyDigest(token = "digest_token_from_email_link")
     * println("Unsubscribed: ${result.unsubscribed}")
     * ```
     */
    suspend fun unsubscribeFromWeeklyDigest(token: String): WeeklyDigestUnsubscribeResult {
        val targetUrl = joinUrl(config.baseUrl, "/api/v1/public/digest/unsubscribe")
        val parsedUri = try {
            java.net.URI(targetUrl)
        } catch (_: Exception) {
            null
        }
        require(parsedUri?.scheme?.equals("https", ignoreCase = true) == true) {
            "unsubscribeFromWeeklyDigest destination must use HTTPS when transmitting the unsubscribe token: $targetUrl"
        }
        val payload = JSONObject().put("token", token.trim())
        return parseWeeklyDigestUnsubscribe(
            sendJson(
                "POST",
                "/api/v1/public/digest/unsubscribe",
                payload,
                accepted = setOf(200),
                mapRateLimitErrors = true
            )
        )
    }

    /**
     * Confirms an email subscription to product updates via
     * `POST /api/v1/public/apps/{appKey}/changelog/confirm` using the single-use token
     * delivered in the confirmation email after calling [subscribeToChangelog].
     *
     * Per API contract updates (issue #30), confirmation requires a mutating `POST` request.
     * The `GET` endpoint is non-destructive (rendering an HTML confirmation interstitial) and returns
     * HTTP 405 Method Not Allowed to JSON clients.
     *
     * @param token Single-use confirmation token from the emailed confirmation link.
     * @return [ChangelogConfirmationResult] indicating whether the subscription was confirmed.
     * @throws FeedbackException.UnexpectedStatus on HTTP errors without a validation envelope
     *   (including other HTTP 400 responses for an invalid, expired, or already-consumed token).
     * @throws FeedbackException.ValidationFailed on HTTP 400 with a usable validation envelope
     *   (e.g. a missing token); check [FeedbackException.ValidationFailed.fieldErrors] for details.
     * @throws IllegalArgumentException if [token] is blank or when the configured
     *   [FeedbackClientConfig.baseUrl] is not HTTPS — the token is a bearer credential and is
     *   never sent in cleartext.
     * @throws FeedbackException.InvalidResponse on network or parsing errors.
     *
     * Example:
     * ```kotlin
     * val result = client.confirmChangelogSubscription(token = "conf_token_from_email")
     * println("Confirmed: ${result.confirmed}")
     * ```
     */
    suspend fun confirmChangelogSubscription(token: String): ChangelogConfirmationResult {
        val cleanToken = token.trim()
        require(cleanToken.isNotEmpty()) { "Confirmation token cannot be blank" }
        val targetUrl = joinUrl(config.baseUrl, "/api/v1/public/apps/${config.appKey}/changelog/confirm")
        val parsedUri = try {
            java.net.URI(targetUrl)
        } catch (_: Exception) {
            null
        }
        require(parsedUri?.scheme?.equals("https", ignoreCase = true) == true) {
            "confirmChangelogSubscription destination must use HTTPS when transmitting the confirmation token: $targetUrl"
        }
        val payload = JSONObject().put("token", cleanToken)
        val response = sendJson(
            "POST",
            "/api/v1/public/apps/${config.appKey}/changelog/confirm",
            payload,
            accepted = setOf(200)
        )
        return try {
            parseChangelogConfirmationResult(response)
        } catch (e: JSONException) {
            throw FeedbackException.InvalidResponse(e)
        }
    }

    /**
     * Reports self-declared user segmentation attributes and telemetry via
     * `PUT /api/v1/public/apps/{appKey}/user`.
     *
     * Parameters provided as `null` are left untouched on the server, allowing partial updates.
     *
     * Calls that carry any payment attribute ([isPaying], [plan], [mrr]) or [currency] are
     * HMAC-SHA256 signed with the app's SDK signing secret ([FeedbackClientConfig.sdkSigningSecret]):
     * the request body gains `signature` + `timestamp` computed over the `cpt-user-attrs-v1`
     * canonical string, and unsigned calls are rejected by the server with HTTP 422
     * `payment_attributes_require_signature`. Identity-only calls (no attributes) stay unsigned.
     *
     * > [!NOTE]
     * > Financial values such as [mrr] and [isPaying] are self-declared signals used for feedback prioritization
     * > and feature triage, never actual payment transaction details.
     *
     * @param userToken Stable anonymous token identifying the user ([UserTokenStore]).
     * @param isPaying Whether the user is on a paid subscription tier.
     * @param plan Identifier or name of the subscription plan (e.g. `"pro"`, `"enterprise"`).
     *   The literal strings `"unset"` and `"null"` are reserved sentinels in the `cpt-user-attrs-v1`
     *   signature protocol; supplying either throws [IllegalArgumentException] client-side and is rejected
     *   by the server with HTTP 400 `plan_reserved_sentinel`. Pass `null` (or omit) to keep unchanged.
     * @param mrr Monthly recurring revenue associated with the user account.
     * @param currency ISO 4217 currency code for [mrr] (e.g., `"USD"`, `"EUR"`). Treated as a
     *   payment attribute: sending it requires a signing secret.
     * @return [UserAttributesUpdateResult] indicating whether the update was saved.
     * @throws IllegalArgumentException if [plan] is `"unset"` or `"null"`, or if a payment attribute
     *   or currency is supplied but no [FeedbackClientConfig.sdkSigningSecret] is configured.
     * @throws FeedbackException.AuthenticationRequired on HTTP 401 (`invalid_signature` /
     *   `stale_signature` when the configured secret does not match the app's).
     * @throws FeedbackException.ReservedPlanSentinel on HTTP 400 when the server rejects a reserved
     *   plan sentinel (`plan_reserved_sentinel`).
     * @throws FeedbackException.RateLimited when the per-client-IP upsert rate limit is exceeded
     *   (HTTP 429); retry with backoff, especially when syncing many users behind one shared IP.
     * @throws FeedbackException.UnexpectedStatus on other HTTP rejection (HTTP 422
     *   `payment_attributes_require_signature` when the app has no SDK signing secret yet).
     * @throws FeedbackException.InvalidResponse on network failure.
     *
     * Example:
     * ```kotlin
     * client.updateUserAttributes(
     *     userToken = userToken,
     *     isPaying = true,
     *     plan = "Pro Annual",
     *     mrr = 9.99,
     *     currency = "USD"
     * )
     * ```
     */
    suspend fun updateUserAttributes(
        userToken: String,
        isPaying: Boolean? = null,
        plan: String? = null,
        mrr: Double? = null,
        currency: String? = null
    ): UserAttributesUpdateResult {
        if (plan != null) {
            require(plan != "unset" && plan != "null") {
                "plan cannot be 'unset' or 'null' ('unset' and 'null' are reserved protocol sentinels)"
            }
        }
        val payload = JSONObject().apply {
            if (isPaying != null) put("isPaying", isPaying)
            putOptString("plan", plan)
            if (mrr != null) put("mrr", mrr)
            putOptString("currency", currency)
        }
        val carriesSignedAttributes = isPaying != null || plan != null || mrr != null || currency != null
        if (carriesSignedAttributes) {
            val secret = config.sdkSigningSecret?.trim()?.takeIf { it.isNotEmpty() }
            require(secret != null) {
                "updateUserAttributes with isPaying/plan/mrr/currency requires " +
                    "FeedbackClientConfig.sdkSigningSecret (CupThread console: App Access → App " +
                    "Credentials → SDK signing secret); the server rejects unsigned payment " +
                    "attributes with HTTP 422 payment_attributes_require_signature"
            }
            val timestampSeconds = System.currentTimeMillis() / 1000
            val signature = hmacSha256Hex(
                secret = secret,
                message = userAttributesCanonicalString(
                    appKey = config.appKey,
                    userToken = userToken,
                    isPaying = isPaying,
                    plan = plan,
                    mrr = mrr,
                    currency = currency,
                    timestampSeconds = timestampSeconds
                )
            )
            payload.put("timestamp", timestampSeconds)
            payload.put("signature", signature)
        }
        return parseUserAttributesUpdate(
            sendJson(
                "PUT",
                "/api/v1/public/apps/${config.appKey}/user",
                payload,
                userToken = userToken,
                accepted = setOf(200),
                mapRateLimitErrors = true,
                mapUserAttributeErrors = true
            )
        )
    }

    /**
     * Fetches a public user profile from `GET /api/v1/users/{userId}/profile?appKey={appKey}`.
     *
     * Resolves app-scoped pseudonymous identifiers (`u_*`) carried by board and comment payloads.
     * Note: Raw Clerk user IDs (`user_*`) are no longer resolved and return an anonymous placeholder.
     *
     * @param userId App-scoped pseudonymous user identifier (`u_*`) from board or comment payloads.
     * @return [PublicUserProfileResult] containing the user profile, associated apps, and comments.
     * @throws IllegalArgumentException if [userId] is blank.
     * @throws FeedbackException.RateLimited if the per-client IP rate limit is exceeded (HTTP 429).
     * @throws FeedbackException.UnexpectedStatus on HTTP 404 if the user profile does not exist.
     * @throws FeedbackException.InvalidResponse on network or parsing failure.
     *
     * Example:
     * ```kotlin
     * val userProfile = client.fetchUserProfile("u_1234567890abcdef1234567890abcdef")
     * println("User: ${userProfile.profile.displayName}")
     * println("Bio: ${userProfile.profile.bio}")
     * println("Apps count: ${userProfile.apps.size}")
     * ```
     */
    suspend fun fetchUserProfile(userId: String): PublicUserProfileResult {
        require(userId.isNotBlank()) { "userId cannot be blank" }
        val query = encodeQuery(listOf("appKey" to config.appKey))
        return parsePublicUserProfileResult(
            get(
                path = "/api/v1/users/${userId.trim()}/profile?$query",
                mapRateLimitErrors = true
            )
        )
    }

    /**
     * Erases the caller's own end-user profile for this application via `POST /api/v1/me/erase`.
     *
     * Authenticates with the provided [userToken] transmitted in the `X-User-Token` header,
     * or an authenticated [sessionToken] in the `Authorization: Bearer` header. At least one
     * of [userToken] or [sessionToken] must be non-blank.
     *
     * Upon success, the caller's stored PII (display name, email, IP, user agent) is removed and
     * requests/votes are anonymized on the backend. After erasing, call [UserTokenStore.clearToken]
     * or [UserTokenStore.rotateToken] to prevent subsequent calls from reusing the revoked token.
     *
     * @param userToken Anonymous user token identifying the profile to erase.
     * @param sessionToken Optional authenticated user session token.
     * @return [EndUserEraseResult] confirming the erasure.
     * @throws IllegalArgumentException if both [userToken] and [sessionToken] are blank, or if either credential would be sent over cleartext HTTP.
     * @throws FeedbackException.AuthenticationRequired if the server rejects authentication (HTTP 401).
     * @throws FeedbackException.UnexpectedStatus if the profile is not found (HTTP 404), rate-limited (HTTP 429), or another error occurs.
     *
     * Example:
     * ```kotlin
     * val result = client.eraseEndUser(userToken = userTokenStore.token)
     * if (result.erased) {
     *     userTokenStore.clearToken()
     * }
     * ```
     */
    suspend fun eraseEndUser(
        userToken: String? = null,
        sessionToken: String? = null
    ): EndUserEraseResult {
        require(!userToken.isNullOrBlank() || !sessionToken.isNullOrBlank()) {
            "Either userToken or sessionToken must be non-blank for eraseEndUser"
        }
        val targetUrl = joinUrl(config.baseUrl, "/api/v1/me/erase")
        val parsedUri = try {
            java.net.URI(targetUrl)
        } catch (_: Exception) {
            null
        }
        require(parsedUri?.scheme?.equals("https", ignoreCase = true) == true) {
            "eraseEndUser destination must use HTTPS when transmitting credentials: $targetUrl"
        }

        val payload = JSONObject().apply {
            put("appKey", config.appKey)
        }
        val response = sendJson(
            method = "POST",
            path = "/api/v1/me/erase",
            body = payload,
            userToken = userToken?.trim()?.takeIf { it.isNotEmpty() },
            bearerToken = sessionToken?.trim()?.takeIf { it.isNotEmpty() },
            accepted = setOf(200)
        )
        return try {
            parseEndUserEraseResult(response)
        } catch (e: JSONException) {
            throw FeedbackException.InvalidResponse(e)
        }
    }

    /**
     * Links an anonymous end-user profile to an authenticated user session via `POST /api/v1/me/link`.
     *
     * Enables explicit linking of an anonymous end-user profile (and its historical feedback, votes,
     * and comments) to an authenticated account identity.
     *
     * @param sessionToken Bearer authentication token representing the signed-in user session.
     * @param userToken Stable anonymous user token from [UserTokenStore].
     * @return [EndUserLinkResult] containing the linking status and profile identifiers.
     * @throws IllegalArgumentException if [sessionToken] or [userToken] is blank.
     * @throws FeedbackException.AuthenticationRequired if the session token is missing or expired (HTTP 401).
     * @throws FeedbackException.ValidationFailed on HTTP 400 with a usable validation envelope;
     *   check [FeedbackException.ValidationFailed.fieldErrors] for details.
     * @throws FeedbackException.UnexpectedStatus on HTTP 400 without a validation envelope, 404,
     *   409 (conflict), or other server rejections.
     * @throws FeedbackException.InvalidResponse on network failure or invalid response JSON.
     *
     * Example:
     * ```kotlin
     * val result = client.linkEndUser(
     *     sessionToken = "sess_abc123",
     *     userToken = userToken
     * )
     * println("Linked: ${result.linked}, User ID: ${result.clerkUserId}")
     * ```
     */
    suspend fun linkEndUser(sessionToken: String, userToken: String): EndUserLinkResult {
        require(sessionToken.isNotBlank()) { "sessionToken cannot be blank" }
        require(userToken.isNotBlank()) { "userToken cannot be blank" }

        val targetUrl = joinUrl(config.baseUrl, "/api/v1/me/link")
        val parsedUri = try {
            java.net.URI(targetUrl)
        } catch (_: Exception) {
            null
        }
        require(parsedUri?.scheme?.equals("https", ignoreCase = true) == true) {
            "linkEndUser destination must use HTTPS to protect sessionToken: $targetUrl"
        }

        val payload = JSONObject().apply {
            put("appKey", config.appKey)
        }
        val response = sendJson(
            method = "POST",
            path = "/api/v1/me/link",
            body = payload,
            userToken = userToken.trim(),
            bearerToken = sessionToken.trim(),
            accepted = setOf(200)
        )
        return try {
            parseEndUserLinkResult(response)
        } catch (e: JSONException) {
            throw FeedbackException.InvalidResponse(e)
        }
    }

    private fun defaultMetadata(draft: FeedbackDraft): Map<String, String> {
        val submittedAt = iso8601Now()
        val defaults = mapOf(
            "sdk" to "cupthread-android",
            "platform" to draft.platform.wireValue,
            "submittedAt" to submittedAt
        )
        return MetadataSanitizer.sanitize(draft.metadata, defaults)
    }

    private suspend fun get(
        path: String,
        userToken: String? = null,
        bearerToken: String? = null,
        mapRateLimitErrors: Boolean = false,
        mapCommentsUnavailableErrors: Boolean = false
    ): JSONObject = sendJson(
        "GET",
        path,
        body = null,
        userToken = userToken,
        bearerToken = bearerToken,
        mapRateLimitErrors = mapRateLimitErrors,
        mapCommentsUnavailableErrors = mapCommentsUnavailableErrors
    )

    private fun buildHeaders(
        contentType: String?,
        userToken: String?,
        bearerToken: String? = null,
        requestId: String? = null
    ): Map<String, String> = buildMap {
        if (contentType != null) put("Content-Type", contentType)
        if (!userToken.isNullOrBlank()) put("X-User-Token", userToken)
        if (!bearerToken.isNullOrBlank()) put("Authorization", "Bearer $bearerToken")
        if (!requestId.isNullOrBlank()) put(REQUEST_ID_HEADER, requestId)
    }

    /**
     * Resolves the outgoing `X-Request-Id` header value for a single API call.
     *
     * Returns the configured [FeedbackClientConfig.requestIdProvider] value when it matches the
     * server's `^[A-Za-z0-9._-]{8,64}$` contract, or `null` when a configured provider declines
     * (the server then mints its own identifier). Provider values are validated as returned —
     * surrounding whitespace is never trimmed away, so padded values are treated as invalid.
     * Without a provider, a fresh UUID is generated per request so every call is correlatable
     * in server logs.
     */
    private fun resolveRequestId(): String? {
        val provider = config.requestIdProvider ?: return UUID.randomUUID().toString()
        val provided = provider.invoke()?.takeIf { it.isNotBlank() } ?: return null
        return provided.takeIf { REQUEST_ID_PATTERN.matches(it) }
    }

    /**
     * Resolves a Turnstile token from the configured provider for [challenge], trimming empty
     * results to `null` and swallowing provider failures so an unavailable token degrades to an
     * unverified request (which the server answers with [FeedbackException.VerificationRequired]).
     */
    private suspend fun resolveTurnstileToken(challenge: TurnstileChallenge): String? =
        try {
            config.turnstileTokenProvider?.token(challenge)?.trim()?.takeIf { it.isNotEmpty() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }

    /**
     * Sends a Turnstile-gated intake request, retrying exactly once with a freshly provided
     * token when the server's human-verification gate rejects the first attempt (HTTP 403) and
     * a token provider is configured.
     *
     * [explicitToken] (the caller-supplied `turnstileToken` parameter) takes precedence for the
     * first attempt; the configured provider is consulted only for the retry. Tokens are
     * single-use, so the provider is asked for a fresh token on every consultation.
     */
    private suspend fun <T> sendWithTurnstileRetry(
        challenge: TurnstileChallenge,
        explicitToken: String?,
        attempt: suspend (turnstileToken: String?) -> T
    ): T {
        val firstToken = explicitToken?.trim()?.takeIf { it.isNotEmpty() }
            ?: resolveTurnstileToken(challenge)
        return try {
            attempt(firstToken)
        } catch (error: FeedbackException.VerificationRequired) {
            if (config.turnstileTokenProvider == null || !error.canRetryWithFreshToken) throw error
            val retryToken = resolveTurnstileToken(challenge) ?: throw error
            attempt(retryToken)
        }
    }

    private suspend fun sendJson(
        method: String,
        path: String,
        body: JSONObject?,
        userToken: String? = null,
        bearerToken: String? = null,
        accepted: Set<Int> = setOf(200),
        mapSubmissionQuotaErrors: Boolean = false,
        mapAttachmentScanErrors: Boolean = false,
        mapAttachmentAlreadyFinalizedErrors: Boolean = false,
        mapRateLimitErrors: Boolean = false,
        mapVerificationErrors: Boolean = false,
        mapUserAttributeErrors: Boolean = false,
        mapUploaderErrors: Boolean = false,
        mapCommentErrors: Boolean = false,
        mapEmailNotVerifiedErrors: Boolean = false,
        mapCommentsUnavailableErrors: Boolean = false
    ): JSONObject {
        val contentType = if (body != null) "application/json" else null
        return send(
            HttpRequest(
                method = method,
                url = joinUrl(config.baseUrl, path),
                headers = buildHeaders(contentType, userToken, bearerToken, resolveRequestId()),
                contentType = contentType,
                body = body?.toString()?.toByteArray(Charsets.UTF_8)
            ),
            accepted,
            mapSubmissionQuotaErrors = mapSubmissionQuotaErrors,
            mapAttachmentScanErrors = mapAttachmentScanErrors,
            mapAttachmentAlreadyFinalizedErrors = mapAttachmentAlreadyFinalizedErrors,
            mapRateLimitErrors = mapRateLimitErrors,
            mapVerificationErrors = mapVerificationErrors,
            mapUserAttributeErrors = mapUserAttributeErrors,
            mapUploaderErrors = mapUploaderErrors,
            mapCommentErrors = mapCommentErrors,
            mapEmailNotVerifiedErrors = mapEmailNotVerifiedErrors,
            mapCommentsUnavailableErrors = mapCommentsUnavailableErrors
        )
    }

    private suspend fun sendMultipart(
        path: String,
        boundary: String,
        body: ByteArray,
        userToken: String? = null,
        bearerToken: String? = null,
        accepted: Set<Int> = setOf(200, 201),
        mapUnsupportedMediaType: Boolean = false,
        mapRateLimitErrors: Boolean = false,
        mapImageUploadErrors: Boolean = false
    ): JSONObject {
        val contentType = "multipart/form-data; boundary=$boundary"
        return send(
            HttpRequest(
                method = "POST",
                url = joinUrl(config.baseUrl, path),
                headers = buildHeaders(contentType, userToken, bearerToken, resolveRequestId()),
                contentType = contentType,
                body = body
            ),
            accepted,
            mapUnsupportedMediaType = mapUnsupportedMediaType,
            mapRateLimitErrors = mapRateLimitErrors,
            mapImageUploadErrors = mapImageUploadErrors
        )
    }

    private suspend fun sendBinary(
        method: String,
        path: String,
        contentType: String,
        body: ByteArray,
        bearerToken: String? = null,
        accepted: Set<Int> = setOf(200),
        mapUnsupportedMediaType: Boolean = false,
        mapRateLimitErrors: Boolean = false,
        mapUploaderErrors: Boolean = false
    ): JSONObject = send(
        HttpRequest(
            method = method,
            url = joinUrl(config.baseUrl, path),
            headers = buildHeaders(contentType, null, bearerToken, resolveRequestId()),
            contentType = contentType,
            body = body
        ),
        accepted,
        mapUnsupportedMediaType = mapUnsupportedMediaType,
        mapRateLimitErrors = mapRateLimitErrors,
        mapUploaderErrors = mapUploaderErrors
    )

    private suspend fun send(
        request: HttpRequest,
        accepted: Set<Int>,
        mapSubmissionQuotaErrors: Boolean = false,
        mapUnsupportedMediaType: Boolean = false,
        mapAttachmentScanErrors: Boolean = false,
        mapAttachmentAlreadyFinalizedErrors: Boolean = false,
        mapRateLimitErrors: Boolean = false,
        mapVerificationErrors: Boolean = false,
        mapUserAttributeErrors: Boolean = false,
        mapUploaderErrors: Boolean = false,
        mapCommentErrors: Boolean = false,
        mapImageUploadErrors: Boolean = false,
        mapEmailNotVerifiedErrors: Boolean = false,
        mapCommentsUnavailableErrors: Boolean = false
    ): JSONObject =
        withContext(Dispatchers.IO) {
            val response = try {
                transport.execute(request)
            } catch (error: FeedbackException) {
                throw error
            } catch (error: Exception) {
                throw FeedbackException.InvalidResponse(error)
            }
            if (response.code !in accepted) {
                val requestId = response.headers.entries
                    .firstOrNull { (name, _) -> name.equals(REQUEST_ID_HEADER, ignoreCase = true) }
                    ?.value?.trim()?.takeIf { it.isNotEmpty() }
                if (response.code == 400 && mapCommentErrors) {
                    val errorPayload = parseErrorPayload(response.body)
                    val code = errorPayload?.first
                    val message = errorPayload?.second
                    if (FeedbackException.InvalidParentComment.isInvalidParent(code)) {
                        throw FeedbackException.InvalidParentComment(
                            serverMessage = message,
                            serverCode = code ?: FeedbackException.InvalidParentComment.CODE_INVALID_PARENT,
                            requestId = requestId
                        )
                    }
                }
                if (response.code == 400 && mapUserAttributeErrors) {
                    val errorPayload = parseErrorPayload(response.body)
                    val code = errorPayload?.first
                    val message = errorPayload?.second
                    if (FeedbackException.ReservedPlanSentinel.isPlanReservedSentinel(code, message, response.body)) {
                        throw FeedbackException.ReservedPlanSentinel(
                            serverMessage = message,
                            serverCode = code ?: FeedbackException.ReservedPlanSentinel.CODE_PLAN_RESERVED_SENTINEL,
                            requestId = requestId
                        )
                    }
                }
                if (response.code == 400 && mapUploaderErrors) {
                    val errorPayload = parseErrorPayload(response.body)
                    val code = errorPayload?.first
                    val message = errorPayload?.second
                    val rawOrMessage = message ?: response.body.ifBlank { null }
                    if (FeedbackException.UploaderIdentityRequired.isUploaderIdentityRequired(code, rawOrMessage)) {
                        throw FeedbackException.UploaderIdentityRequired(
                            serverMessage = message,
                            serverCode = code ?: FeedbackException.UploaderIdentityRequired.CODE_UPLOADER_IDENTITY_REQUIRED,
                            requestId = requestId
                        )
                    }
                    if (FeedbackException.UploaderMismatch.isUploaderMismatch(code, rawOrMessage)) {
                        throw FeedbackException.UploaderMismatch(
                            serverMessage = message,
                            serverCode = code ?: FeedbackException.UploaderMismatch.CODE_UPLOADER_MISMATCH,
                            requestId = requestId
                        )
                    }
                }
                if (response.code == 401) throw FeedbackException.AuthenticationRequired(requestId)
                if (response.code == 403 && mapEmailNotVerifiedErrors) {
                    val errorPayload = parseErrorPayload(response.body)
                    if (errorPayload?.first == FeedbackException.EmailNotVerified.CODE_EMAIL_NOT_VERIFIED) {
                        throw FeedbackException.EmailNotVerified(
                            serverMessage = errorPayload.second,
                            serverCode = errorPayload.first,
                            requestId = requestId
                        )
                    }
                }
                if (response.code == 404 && mapCommentsUnavailableErrors) {
                    val errorPayload = parseErrorPayload(response.body)
                    throw FeedbackException.CommentsUnavailable(
                        serverMessage = errorPayload?.second ?: response.body.ifBlank { null },
                        requestId = requestId
                    )
                }
                if (response.code == 403 && mapVerificationErrors) {
                    val errorPayload = parseErrorPayload(response.body)
                    val code = errorPayload?.first
                    val message = errorPayload?.second ?: response.body.ifBlank { null }
                    if (FeedbackException.VerificationRequired.isTurnstileRejection(code, message)) {
                        throw FeedbackException.VerificationRequired(code, message, requestId)
                    }
                }
                if (response.code == 402 && mapSubmissionQuotaErrors) {
                    val errorPayload = parseErrorPayload(response.body)
                    when (errorPayload?.first) {
                        "tier_limit_submissions" -> throw FeedbackException.SubmissionQuotaExceeded(errorPayload.second, requestId)
                        "subscription_inactive" -> throw FeedbackException.SubscriptionInactive(errorPayload.second, requestId)
                    }
                }
                if (response.code == 409 && mapAttachmentAlreadyFinalizedErrors) {
                    val errorPayload = parseErrorPayload(response.body)
                    val code = errorPayload?.first
                    val message = errorPayload?.second
                    if (FeedbackException.AttachmentAlreadyFinalized.isAlreadyFinalized(code, message ?: response.body.ifBlank { null })) {
                        throw FeedbackException.AttachmentAlreadyFinalized(
                            serverMessage = message,
                            serverCode = code ?: FeedbackException.AttachmentAlreadyFinalized.CODE_ALREADY_FINALIZED,
                            requestId = requestId
                        )
                    }
                }
                if (response.code == 415 && mapUnsupportedMediaType) {
                    val errorPayload = parseErrorPayload(response.body)
                    throw FeedbackException.UnsupportedMediaType(errorPayload?.second ?: response.body.ifBlank { null }, requestId)
                }
                if (response.code == 422 && mapAttachmentScanErrors) {
                    val errorPayload = parseErrorPayload(response.body)
                    if (errorPayload?.first == "scan_rejected") {
                        val message = errorPayload.second
                        throw FeedbackException.AttachmentScanRejected(message, requestId)
                    }
                }
                if (response.code == 429 && mapRateLimitErrors) {
                    val errorPayload = parseErrorPayload(response.body)
                    val message = if (errorPayload != null) errorPayload.second else response.body.ifBlank { null }
                    throw FeedbackException.RateLimited(message, requestId)
                }
                if (response.code == 500 && mapImageUploadErrors) {
                    val errorPayload = parseErrorPayload(response.body)
                    val code = errorPayload?.first
                    val message = errorPayload?.second
                    val rawOrMessage = message ?: response.body.ifBlank { null }
                    if (FeedbackException.ImageUploadFailed.isImageUploadFailed(code, rawOrMessage)) {
                        throw FeedbackException.ImageUploadFailed(
                            serverMessage = message,
                            serverCode = code ?: FeedbackException.ImageUploadFailed.CODE_IMAGE_UPLOAD_FAILED,
                            requestId = requestId
                        )
                    }
                }
                // Generic fallback: a 400 `Validation failed` body with the documented zod
                // `details` envelope surfaces as typed ValidationFailed; anything else keeps
                // the existing UnexpectedStatus behavior. Specific mappers above have already
                // had the chance to claim dedicated rejection codes.
                if (response.code == 400) {
                    FeedbackException.ValidationFailed.fromErrorBody(response.body, requestId)?.let { throw it }
                }
                throw FeedbackException.UnexpectedStatus(response.code, response.body.ifBlank { "Unknown error" }, requestId)
            }
            try {
                if (response.body.isBlank()) JSONObject() else JSONObject(response.body)
            } catch (error: Exception) {
                throw FeedbackException.InvalidResponse(error)
            }
        }

    private fun parseErrorPayload(body: String): Pair<String?, String?>? {
        if (body.isBlank()) return null
        return try {
            val json = JSONObject(body)
            val code = if (json.isNull("code")) null else json.optString("code").takeIf { it.isNotBlank() && it != "null" }
            val error = if (json.isNull("error")) null else json.optString("error").takeIf { it.isNotBlank() && it != "null" }
            val message = if (json.isNull("message")) null else json.optString("message").takeIf { it.isNotBlank() && it != "null" }
            Pair(code, error ?: message)
        } catch (_: Exception) {
            null
        }
    }

    private fun iso8601Now(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date())
    }
}
