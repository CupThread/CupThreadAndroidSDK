package dev.cupthread.feedback

import dev.cupthread.feedback.internal.HttpRequest
import dev.cupthread.feedback.internal.HttpTransport
import dev.cupthread.feedback.internal.UrlConnectionTransport
import dev.cupthread.feedback.internal.encodeQuery
import dev.cupthread.feedback.internal.joinUrl
import dev.cupthread.feedback.internal.multipartBody
import dev.cupthread.feedback.internal.parseAppVersion
import dev.cupthread.feedback.internal.parseAttachment
import dev.cupthread.feedback.internal.parseBoardColumn
import dev.cupthread.feedback.internal.parseChangelogEntry
import dev.cupthread.feedback.internal.parseChangelogSubscription
import dev.cupthread.feedback.internal.parseChangelogUnsubscribe
import dev.cupthread.feedback.internal.parseCommentList
import dev.cupthread.feedback.internal.parseFeatureRequestComment
import dev.cupthread.feedback.internal.parseFeatureRequestSubmission
import dev.cupthread.feedback.internal.parseFeedbackSubmission
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
                accepted = setOf(200, 201, 202)
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
     * @param data Raw binary bytes of the file.
     * @param filename Suggested original filename (e.g. `"screenshot.png"`, `"logcat.txt"`).
     * @param mimeType Standard MIME type string (e.g. `"image/png"`, `"text/plain"`).
     * @param preferredKind Optional storage destination override ([FeedbackAttachment.Kind]).
     * @return [FeedbackAttachment] containing the CDN URL and storage key.
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
     *     mimeType = "image/png"
     * )
     * val draft = baseDraft.copy(attachments = listOf(attachment))
     * client.submit(draft, userToken)
     * ```
     */
    suspend fun uploadAttachment(
        data: ByteArray,
        filename: String,
        mimeType: String,
        preferredKind: FeedbackAttachment.Kind? = null
    ): FeedbackAttachment {
        val kind = preferredKind ?: if (mimeType.startsWith("image/")) {
            FeedbackAttachment.Kind.IMAGE
        } else {
            FeedbackAttachment.Kind.R2
        }
        val path = if (kind == FeedbackAttachment.Kind.IMAGE) {
            "/api/v1/uploads/images"
        } else {
            "/api/v1/uploads/r2"
        }
        val boundary = "Boundary-${UUID.randomUUID()}"
        val body = multipartBody(boundary, config.appKey, filename, mimeType, data)
        val json = send(
            HttpRequest(
                method = "POST",
                url = joinUrl(config.baseUrl, path),
                contentType = "multipart/form-data; boundary=$boundary",
                body = body
            ),
            accepted = setOf(200)
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
    suspend fun fetchAppConfig(): PublicAppConfig =
        parsePublicAppConfig(get("/api/v1/public/config/${config.appKey}"))

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
     * @param userToken Stable anonymous user token from [UserTokenStore].
     * @param limit Maximum number of requests to return per page; default is 50.
     * @param offset Number of requests to skip for pagination; default is 0.
     * @param versionId Optional [AppVersion.id] to restrict results to a specific release target.
     * @param query Optional free-text search query matching against titles and descriptions.
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
        query: String? = null
    ): ListFeatureRequestsResult {
        val params = mutableListOf(
            "appKey" to config.appKey,
            "userToken" to userToken,
            "limit" to limit.toString(),
            "offset" to offset.toString()
        )
        if (!versionId.isNullOrBlank()) params += "versionId" to versionId
        if (!query.isNullOrBlank()) params += "q" to query
        return parseListFeatureRequests(get("/api/v1/feature-requests?${encodeQuery(params)}"))
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
            sendJson("POST", "/api/v1/feature-requests", payload, userToken = null, accepted = setOf(200, 201))
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
     * Fetches published changelog entries and release notes from
     * `GET /api/v1/public/apps/{appKey}/changelog`.
     *
     * Entries are returned in descending chronological order by [ChangelogEntry.publishedAt] (newest first).
     *
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
    suspend fun fetchChangelog(): List<ChangelogEntry> {
        val json = get("/api/v1/public/apps/${config.appKey}/changelog")
        val array = json.optJSONArray("entries") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) add(parseChangelogEntry(array.getJSONObject(i)))
        }.sortedByDescending { it.publishedAt }
    }

    /**
     * Prepares changelog entries and appearance styling for What's-New presentation overlays
     * ([dev.cupthread.feedback.ui.ChangelogOverlay] and [dev.cupthread.feedback.ui.presentLatestChangelog]).
     *
     * Automatically honors the console-configured `changelogOverlay.entryCount` setting,
     * clamped between 1 and 10 entries.
     *
     * @return A [Pair] containing the list of entries and the active [SdkAppearance],
     *   or `null` if the changelog feature is disabled in the console or no entries have been published.
     * @throws FeedbackException on network or parsing failure.
     *
     * Example:
     * ```kotlin
     * val overlayData = client.prepareChangelogOverlay()
     * if (overlayData != null) {
     *     val (entries, appearance) = overlayData
     *     println("Displaying ${entries.size} new updates with title '${appearance.changelogOverlay.title}'")
     * }
     * ```
     */
    suspend fun prepareChangelogOverlay(): Pair<List<ChangelogEntry>, SdkAppearance>? {
        val appearance = fetchAppConfig().sdk
        if (!appearance.features.changelog) return null
        val entries = fetchChangelog().take(appearance.changelogOverlay.clampedEntryCount)
        if (entries.isEmpty()) return null
        return entries to appearance
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
     * Fetches a public user profile from `GET /api/v1/users/{userId}/profile`.
     *
     * Returns the user's public biographical details, public applications, and recent public comments.
     *
     * @param userId Unique user identifier (e.g., Clerk user account ID).
     * @return [PublicUserProfileResult] containing the user profile, associated apps, and comments.
     * @throws FeedbackException.UnexpectedStatus on HTTP 404 if the user profile does not exist.
     * @throws FeedbackException.InvalidResponse on network or parsing failure.
     *
     * Example:
     * ```kotlin
     * val userProfile = client.fetchUserProfile("user_clerk_123")
     * println("User: ${userProfile.profile.displayName}")
     * println("Bio: ${userProfile.profile.bio}")
     * println("Apps count: ${userProfile.apps.size}")
     * ```
     */
    suspend fun fetchUserProfile(userId: String): PublicUserProfileResult =
        parsePublicUserProfileResult(get("/api/v1/users/$userId/profile"))


    private fun defaultMetadata(draft: FeedbackDraft): Map<String, String> {
        val submittedAt = iso8601Now()
        return draft.metadata + mapOf(
            "sdk" to "cupthread-android",
            "platform" to draft.platform.wireValue,
            "submittedAt" to submittedAt
        )
    }

    private suspend fun get(path: String): JSONObject = sendJson("GET", path, body = null)

    private suspend fun sendJson(
        method: String,
        path: String,
        body: JSONObject?,
        userToken: String? = null,
        accepted: Set<Int> = setOf(200)
    ): JSONObject {
        val headers = buildMap {
            if (body != null) put("Content-Type", "application/json")
            if (!userToken.isNullOrBlank()) put("X-User-Token", userToken)
        }
        return send(
            HttpRequest(
                method = method,
                url = joinUrl(config.baseUrl, path),
                headers = headers,
                contentType = if (body != null) "application/json" else null,
                body = body?.toString()?.toByteArray(Charsets.UTF_8)
            ),
            accepted
        )
    }

    private suspend fun send(request: HttpRequest, accepted: Set<Int>): JSONObject =
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
                throw FeedbackException.UnexpectedStatus(response.code, response.body.ifBlank { "Unknown error" })
            }
            try {
                if (response.body.isBlank()) JSONObject() else JSONObject(response.body)
            } catch (error: Exception) {
                throw FeedbackException.InvalidResponse(error)
            }
        }

    private fun iso8601Now(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date())
    }
}
