package dev.cupthread.feedback

import android.content.Context
import dev.cupthread.feedback.internal.HttpRequest
import dev.cupthread.feedback.internal.HttpTransport
import dev.cupthread.feedback.internal.SdkAppearanceCache
import dev.cupthread.feedback.internal.UrlConnectionTransport
import dev.cupthread.feedback.internal.AttachmentLimits
import dev.cupthread.feedback.internal.encodeQuery
import dev.cupthread.feedback.internal.joinUrl
import dev.cupthread.feedback.internal.multipartBody
import dev.cupthread.feedback.internal.MetadataSanitizer
import dev.cupthread.feedback.internal.parseAppVersion
import dev.cupthread.feedback.internal.parseAttachment
import dev.cupthread.feedback.internal.parseBoardColumn
import dev.cupthread.feedback.internal.parseChangelogEntry
import dev.cupthread.feedback.internal.parseChangelogSubscription
import dev.cupthread.feedback.internal.parseChangelogUnsubscribe
import dev.cupthread.feedback.internal.parseCommentList
import dev.cupthread.feedback.internal.parseEndUserEraseResult
import dev.cupthread.feedback.internal.parseEndUserLinkResult
import dev.cupthread.feedback.internal.parseFeatureRequestComment
import dev.cupthread.feedback.internal.parseFeatureRequestSubmission
import dev.cupthread.feedback.internal.parseFeedbackSubmission
import dev.cupthread.feedback.internal.parseListChangelog
import dev.cupthread.feedback.internal.parseListFeatureRequests
import dev.cupthread.feedback.internal.parsePublicAppConfig
import dev.cupthread.feedback.internal.parsePublicUserProfileResult
import dev.cupthread.feedback.internal.parseUserAttributesUpdate
import dev.cupthread.feedback.internal.parseVoteResult
import dev.cupthread.feedback.internal.putOptString
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
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
 * - Feature request submissions, filtering, and voting ([fetchFeatureRequests], [submitFeatureRequest], [toggleVote])
 * - Threaded feature request comments and replies ([fetchComments], [postComment])
 * - What's-New changelog streams and email subscriptions ([fetchChangelog], [subscribeToChangelog], [unsubscribeFromChangelog])
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
 * - [FeedbackException.UnsupportedMediaType]: File format or image media type is unsupported (HTTP 415 `unsupported_media_type`).
 * - [FeedbackException.UnexpectedStatus]: Server returned an unexpected HTTP error code.
 * - [FeedbackException.InvalidResponse]: Network transport failure or malformed JSON payload.
 * - [FeedbackException.UnreadableUploadResponse]: Upload succeeded but server response descriptor was unreadable.
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
     * @param draft The feedback content, reporter details, and uploaded attachments ([FeedbackDraft]).
     * @param userToken Optional stable anonymous user token from [UserTokenStore]. Sent as the `X-User-Token`
     *   header to attribute submissions.
     * @return [FeedbackSubmissionResult] containing the server-assigned submission ID and optional GitHub discussion URL.
     * @throws FeedbackException.AuthenticationRequired if anonymous feedback is disabled and token is missing (HTTP 401).
     * @throws FeedbackException.SubmissionQuotaExceeded if the workspace has reached its monthly submission limit (HTTP 402 `tier_limit_submissions`).
     * @throws FeedbackException.SubscriptionInactive if the workspace subscription is inactive (HTTP 402 `subscription_inactive`).
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
    suspend fun submit(draft: FeedbackDraft, userToken: String? = null): FeedbackSubmissionResult {
        val payload = JSONObject().apply {
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
            for (attachment in draft.attachments) {
                attachments.put(
                    JSONObject().apply {
                        put("kind", attachment.kind.wireValue)
                        put("key", attachment.key)
                        put("url", attachment.url)
                        putOptString("filename", attachment.filename)
                        putOptString("mimeType", attachment.mimeType)
                        if (attachment.size != null) put("size", attachment.size)
                    }
                )
            }
            put("attachments", attachments)
        }
        return parseFeedbackSubmission(
            sendJson(
                method = "POST",
                path = "/api/v1/feedback",
                body = payload,
                userToken = userToken,
                accepted = setOf(200, 201, 202),
                mapSubmissionQuotaErrors = true
            )
        )
    }

    /**
     * Uploads a binary file or screenshot attachment to storage and returns a descriptor ready
     * to be attached to a [FeedbackDraft].
     *
     * - Image MIME types (e.g., `image/png`, `image/jpeg`) are routed to `POST /api/v1/uploads/images`.
     * - All other files (e.g. logs, crashes, text) are routed to `POST /api/v1/uploads/r2` object storage.
     * - Use [preferredKind] to explicitly override the destination storage backend.
     *
     * Accepts HTTP 200 OK and 201 Created responses. If [userToken] is provided, it is forwarded
     * as the `X-User-Token` header to associate the upload with the anonymous user identity.
     *
     * @param data Binary payload to upload.
     * @param filename Display file name including extension (e.g. `screenshot.png`).
     * @param mimeType MIME content type (e.g. `image/png`, `application/pdf`).
     * @param preferredKind Optional explicit [FeedbackAttachment.Kind], overriding MIME-based auto-detection.
     * @param userToken Optional stable user token (from [UserTokenStore]) to attribute the upload.
     * @return A populated [FeedbackAttachment] containing the persisted URL and storage key.
     * @throws FeedbackException.AuthenticationRequired on HTTP 401.
     * @throws FeedbackException.UnsupportedMediaType on HTTP 415 or when an unsupported image format (e.g. SVG) is uploaded.
     * @throws FeedbackException.UnreadableUploadResponse if the upload succeeded on the server but the response could not be parsed.
     * @throws FeedbackException.UnexpectedStatus if the file upload is rejected (e.g. exceeded `maxAttachmentBytes`).
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
        userToken: String? = null
    ): FeedbackAttachment {
        val kind = preferredKind ?: if (mimeType.startsWith("image/")) {
            FeedbackAttachment.Kind.IMAGE
        } else {
            FeedbackAttachment.Kind.R2
        }
        if (kind == FeedbackAttachment.Kind.IMAGE) {
            AttachmentLimits.validateImageAttachment(data, filename, mimeType)
        }
        val path = if (kind == FeedbackAttachment.Kind.IMAGE) {
            "/api/v1/uploads/images"
        } else {
            "/api/v1/uploads/r2"
        }
        val boundary = "Boundary-${UUID.randomUUID()}"
        val body = multipartBody(boundary, config.appKey, filename, mimeType, data)
        val isImageUpload = (kind == FeedbackAttachment.Kind.IMAGE)
        val json = sendMultipart(
            path = path,
            boundary = boundary,
            body = body,
            userToken = userToken,
            accepted = setOf(200, 201),
            mapUnsupportedMediaType = isImageUpload
        )
        return try {
            parseAttachment(json)
        } catch (_: Exception) {
            throw FeedbackException.UnreadableUploadResponse()
        }
    }

    /**
     * Fetches public app configuration from `GET /api/v1/public/config/{appKey}`.
     *
     * Returns app metadata, anonymous permission flags ([PublicAppConfig.allowAnonymousFeedback],
     * [PublicAppConfig.allowAnonymousVote], etc.), attachment size limits, and the console-configured
     * SDK appearance theme ([PublicAppConfig.sdk]).
     *
     * @return The [PublicAppConfig] for the configured application.
     * @throws FeedbackException.UnexpectedStatus on HTTP errors (such as invalid app key).
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
        SdkAppearanceCache.put(config.appKey, appConfig.sdk)
        return appConfig
    }

    /**
     * Fetches the roadmap kanban columns from `GET /api/v1/public/columns/{appKey}`.
     *
     * Columns are returned sorted in ascending order by their [BoardColumn.position].
     *
     * @return List of [BoardColumn] instances configured for the app.
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
        val json = get("/api/v1/public/columns/${config.appKey}")
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
        val json = get("/api/v1/public/versions/${config.appKey}")
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
     * @throws FeedbackException.UnexpectedStatus on HTTP errors.
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
            get("/api/v1/feature-requests?${encodeQuery(params)}", userToken = userToken),
            offset
        )
    }

    /**
     * Submits a new feature request proposal to `POST /api/v1/feature-requests`.
     *
     * Depending on the console's moderation settings, new requests may be immediately visible
     * or placed into moderation review (indicated by [FeatureRequestSubmissionResult.pending]).
     *
     * @param draft The proposed title, description, and optional requester display name ([FeatureRequestDraft]).
     * @param userToken Stable anonymous user token from [UserTokenStore] that owns the request.
     * @return [FeatureRequestSubmissionResult] with the assigned ID and moderation state.
     * @throws FeedbackException.AuthenticationRequired if anonymous requests are disallowed (HTTP 401).
     * @throws FeedbackException.SubmissionQuotaExceeded if the workspace has reached its monthly submission limit (HTTP 402 `tier_limit_submissions`).
     * @throws FeedbackException.SubscriptionInactive if the workspace subscription is inactive (HTTP 402 `subscription_inactive`).
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
        userToken: String
    ): FeatureRequestSubmissionResult {
        val payload = JSONObject().apply {
            put("appKey", config.appKey)
            put("title", draft.title.trim())
            put("description", draft.description.trim())
            putOptString("requesterName", draft.requesterName.trim().ifEmpty { null })
            put("requesterToken", userToken)
        }
        return parseFeatureRequestSubmission(
            sendJson(
                method = "POST",
                path = "/api/v1/feature-requests",
                body = payload,
                userToken = null,
                accepted = setOf(200, 201),
                mapSubmissionQuotaErrors = true
            )
        )
    }

    /**
     * Toggles an upvote on a feature request via `POST /api/v1/feature-requests/{id}/vote`.
     *
     * If the user has not voted yet, a vote is added; if they have already voted, their vote is removed.
     * Returns the reconciled server vote state for optimistic UI synchronization.
     *
     * @param featureRequestId Unique ID of the target [FeatureRequestItem].
     * @param userToken Stable anonymous user token from [UserTokenStore].
     * @return [VoteResult] containing the updated vote state (`voted`) and current total count (`voteCount`).
     * @throws FeedbackException.AuthenticationRequired if anonymous voting is disallowed (HTTP 401).
     * @throws FeedbackException.UnexpectedStatus on HTTP rejection.
     * @throws FeedbackException.InvalidResponse on network or parsing failure.
     *
     * Example:
     * ```kotlin
     * val result = client.toggleVote(featureRequestId = "req_123", userToken = userToken)
     * println("Voted: ${result.voted}, New total: ${result.voteCount}")
     * ```
     */
    suspend fun toggleVote(featureRequestId: String, userToken: String): VoteResult {
        val payload = JSONObject().apply {
            put("appKey", config.appKey)
            put("userToken", userToken)
        }
        return parseVoteResult(
            sendJson("POST", "/api/v1/feature-requests/$featureRequestId/vote", payload, accepted = setOf(200))
        )
    }

    /**
     * Fetches public comments on a feature request from `GET /api/v1/feature-requests/{id}/comments`.
     *
     * Returns a flat list of comments including avatar URLs and threaded `@reply` references.
     *
     * @param featureRequestId Target [FeatureRequestItem.id].
     * @return List of [FeatureRequestComment] instances, sorted chronologically.
     * @throws FeedbackException.UnexpectedStatus on HTTP errors.
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
    suspend fun fetchComments(featureRequestId: String): List<FeatureRequestComment> =
        parseCommentList(get("/api/v1/feature-requests/$featureRequestId/comments"))

    /**
     * Posts a new comment or threaded `@reply` on a feature request via
     * `POST /api/v1/feature-requests/{id}/comments`.
     *
     * @param featureRequestId ID of the feature request being commented on.
     * @param draft Comment text and optional author/reply metadata ([CommentDraft]).
     * @param userToken Stable anonymous token from [UserTokenStore], sent as the `X-User-Token` header.
     * @return The created [FeatureRequestComment] with server-assigned ID and timestamp.
     * @throws FeedbackException.AuthenticationRequired if commenting requires sign-in (HTTP 401).
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
                accepted = setOf(200, 201)
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
        val json = get("/api/v1/public/apps/${config.appKey}/changelog?${encodeQuery(params)}")
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
     * @param email Valid email address to subscribe.
     * @param userToken Stable anonymous token from [UserTokenStore].
     * @return [ChangelogSubscriptionResult] indicating subscription success and whether the address was already registered.
     * @throws FeedbackException.UnexpectedStatus on HTTP errors.
     * @throws FeedbackException.InvalidResponse on network or parsing errors.
     *
     * Example:
     * ```kotlin
     * val result = client.subscribeToChangelog("user@example.com", userToken = userToken)
     * if (result.alreadySubscribed) {
     *     println("Already subscribed!")
     * } else {
     *     println("Successfully subscribed to update emails.")
     * }
     * ```
     */
    suspend fun subscribeToChangelog(email: String, userToken: String): ChangelogSubscriptionResult {
        val payload = JSONObject().put("email", email.trim())
        return parseChangelogSubscription(
            sendJson(
                "POST",
                "/api/v1/public/apps/${config.appKey}/changelog/subscribe",
                payload,
                userToken = userToken,
                accepted = setOf(200, 201)
            )
        )
    }

    /**
     * Unsubscribes an email address from product update emails via
     * `POST /api/v1/public/apps/{appKey}/changelog/unsubscribe`.
     *
     * @param email Email address to remove from the subscriber list.
     * @return [ChangelogUnsubscribeResult] indicating whether the address was removed.
     * @throws FeedbackException.UnexpectedStatus on HTTP errors.
     * @throws FeedbackException.InvalidResponse on network or parsing errors.
     *
     * Example:
     * ```kotlin
     * val result = client.unsubscribeFromChangelog("user@example.com")
     * println("Unsubscribed: ${result.unsubscribed}")
     * ```
     */
    suspend fun unsubscribeFromChangelog(email: String): ChangelogUnsubscribeResult {
        val payload = JSONObject().put("email", email.trim())
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
     * Reports self-declared user segmentation attributes and telemetry via
     * `PUT /api/v1/public/apps/{appKey}/user`.
     *
     * Parameters provided as `null` are left untouched on the server, allowing partial updates.
     *
     * > [!NOTE]
     * > Financial values such as [mrr] and [isPaying] are self-declared signals used for feedback prioritization
     * > and feature triage, never actual payment transaction details.
     *
     * @param userToken Stable anonymous token identifying the user ([UserTokenStore]).
     * @param isPaying Whether the user is on a paid subscription tier.
     * @param plan Identifier or name of the subscription plan (e.g. `"pro"`, `"enterprise"`).
     * @param mrr Monthly recurring revenue associated with the user account.
     * @param currency ISO 4217 currency code for [mrr] (e.g., `"USD"`, `"EUR"`).
     * @return [UserAttributesUpdateResult] indicating whether the update was saved.
     * @throws FeedbackException.UnexpectedStatus on HTTP rejection.
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
        val payload = JSONObject().apply {
            if (isPaying != null) put("isPaying", isPaying)
            putOptString("plan", plan)
            if (mrr != null) put("mrr", mrr)
            putOptString("currency", currency)
        }
        return parseUserAttributesUpdate(
            sendJson(
                "PUT",
                "/api/v1/public/apps/${config.appKey}/user",
                payload,
                userToken = userToken,
                accepted = setOf(200)
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
        val query = encodeQuery(listOf("appKey" to config.appKey))
        return parsePublicUserProfileResult(get("/api/v1/users/$userId/profile?$query"))
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
     * @throws FeedbackException.UnexpectedStatus on HTTP 400, 404, 409 (conflict), or other server rejections.
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
        bearerToken: String? = null
    ): JSONObject = sendJson("GET", path, body = null, userToken = userToken, bearerToken = bearerToken)

    private fun buildHeaders(
        contentType: String?,
        userToken: String?,
        bearerToken: String? = null
    ): Map<String, String> = buildMap {
        if (contentType != null) put("Content-Type", contentType)
        if (!userToken.isNullOrBlank()) put("X-User-Token", userToken)
        if (!bearerToken.isNullOrBlank()) put("Authorization", "Bearer $bearerToken")
    }

    private suspend fun sendJson(
        method: String,
        path: String,
        body: JSONObject?,
        userToken: String? = null,
        bearerToken: String? = null,
        accepted: Set<Int> = setOf(200),
        mapSubmissionQuotaErrors: Boolean = false
    ): JSONObject {
        val contentType = if (body != null) "application/json" else null
        return send(
            HttpRequest(
                method = method,
                url = joinUrl(config.baseUrl, path),
                headers = buildHeaders(contentType, userToken, bearerToken),
                contentType = contentType,
                body = body?.toString()?.toByteArray(Charsets.UTF_8)
            ),
            accepted,
            mapSubmissionQuotaErrors = mapSubmissionQuotaErrors
        )
    }

    private suspend fun sendMultipart(
        path: String,
        boundary: String,
        body: ByteArray,
        userToken: String? = null,
        bearerToken: String? = null,
        accepted: Set<Int> = setOf(200, 201),
        mapUnsupportedMediaType: Boolean = false
    ): JSONObject {
        val contentType = "multipart/form-data; boundary=$boundary"
        return send(
            HttpRequest(
                method = "POST",
                url = joinUrl(config.baseUrl, path),
                headers = buildHeaders(contentType, userToken, bearerToken),
                contentType = contentType,
                body = body
            ),
            accepted,
            mapUnsupportedMediaType = mapUnsupportedMediaType
        )
    }

    private suspend fun send(
        request: HttpRequest,
        accepted: Set<Int>,
        mapSubmissionQuotaErrors: Boolean = false,
        mapUnsupportedMediaType: Boolean = false
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
                if (response.code == 401) throw FeedbackException.AuthenticationRequired()
                if (response.code == 402 && mapSubmissionQuotaErrors) {
                    val errorPayload = parseErrorPayload(response.body)
                    when (errorPayload?.first) {
                        "tier_limit_submissions" -> throw FeedbackException.SubmissionQuotaExceeded(errorPayload.second)
                        "subscription_inactive" -> throw FeedbackException.SubscriptionInactive(errorPayload.second)
                    }
                }
                if (response.code == 415 && mapUnsupportedMediaType) {
                    val errorPayload = parseErrorPayload(response.body)
                    throw FeedbackException.UnsupportedMediaType(errorPayload?.second ?: response.body.ifBlank { null })
                }
                throw FeedbackException.UnexpectedStatus(response.code, response.body.ifBlank { "Unknown error" })
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
            val code = json.optString("code").takeIf { it.isNotBlank() }
            val error = json.optString("error").takeIf { it.isNotBlank() }
            Pair(code, error)
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
