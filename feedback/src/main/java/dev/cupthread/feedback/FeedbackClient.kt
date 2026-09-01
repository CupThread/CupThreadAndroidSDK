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
 * Entry point for the CupThread public API: feedback, feature requests,
 * roadmap, and changelog.
 *
 * Every method is a [suspend] function that performs its network call on
 * `Dispatchers.IO`; call it from a coroutine scope. All failures are raised
 * as [FeedbackException] subclasses, so catching that type is enough to
 * handle API errors uniformly.
 *
 * Requests use a 15-second connection timeout and a 20-second read timeout.
 * The client holds no mutable state, so a single instance can be shared by
 * concurrent coroutines — create one per app process and pass it around.
 *
 * Typical programmatic usage (for ready-made UI, see the screens in
 * [dev.cupthread.feedback.ui]):
 *
 * ```kotlin
 * val client = FeedbackClient(
 *     FeedbackClientConfig(
 *         baseUrl = "https://api.cupthread.com",
 *         appKey = "app_live_yourAppKey", // from the CupThread console
 *     )
 * )
 * val userToken = UserTokenStore.create(context).token
 *
 * scope.launch {
 *     val submission = client.submit(
 *         FeedbackDraft(
 *             title = "Keyboard covers the send button",
 *             description = "On the login screen the keyboard hides the button.",
 *         ),
 *         userToken = userToken,
 *     )
 *     Log.d("Feedback", "Submitted as ${submission.submissionId}")
 * }
 * ```
 *
 * @param config Immutable client configuration; see [FeedbackClientConfig].
 * @param transport HTTP transport used for every request. Defaults to a
 *   `java.net.HttpURLConnection`-based transport; inject a fake in unit tests.
 */
class FeedbackClient internal constructor(
    val config: FeedbackClientConfig,
    internal val transport: HttpTransport
) {
    constructor(config: FeedbackClientConfig) : this(config, UrlConnectionTransport())

    /**
     * Submits a feedback draft to `POST /api/v1/feedback`.
     *
     * Title and description are trimmed and blank optional fields are omitted.
     * The SDK appends `sdk`, `platform`, and `submittedAt` entries to
     * [FeedbackDraft.metadata] before sending. Attachments referenced by the
     * draft must already be uploaded with [uploadAttachment].
     *
     * @param draft Feedback content, reporter info, and uploaded attachments.
     * @param userToken Stable anonymous token from [UserTokenStore], sent as
     *   the `X-User-Token` header. Optional when the app allows anonymous
     *   feedback.
     * @return Server-assigned submission id and GitHub discussion link, when
     *   the platform mirrored the feedback to GitHub.
     * @throws FeedbackException.AuthenticationRequired if the app requires a
     *   user token (HTTP 401).
     * @throws FeedbackException.UnexpectedStatus on any other rejected status.
     * @throws FeedbackException.InvalidResponse if the request fails at the
     *   transport level or the response cannot be parsed.
     *
     * Example:
     * ```kotlin
     * val draft = FeedbackDraft.autofilled(
     *     versionName = "1.2.0",
     *     versionCode = "42",
     * ).copy(
     *     title = "Crash when syncing offline queue",
     *     description = "App crashes right after the network reconnects.",
     * )
     * val result = client.submit(draft, userToken)
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
     * Uploads a binary attachment and returns the descriptor to attach to a
     * [FeedbackDraft].
     *
     * Image MIME types upload to `POST /api/v1/uploads/images`; everything
     * else goes to `POST /api/v1/uploads/r2` object storage.
     *
     * @param data Raw file contents.
     * @param filename Original file name, echoed back on the result.
     * @param mimeType MIME type such as `image/png`; also decides the upload
     *   route unless [preferredKind] is given.
     * @param preferredKind Forces a storage backend, overriding the
     *   MIME-type-based choice.
     * @return Attachment descriptor (kind, storage key, URL, size).
     * @throws FeedbackException.UnreadableUploadResponse if the upload
     *   succeeded but the response could not be parsed.
     * @throws FeedbackException.UnexpectedStatus on rejected uploads, for
     *   example payloads above the app's `maxAttachmentBytes` limit.
     *
     * Example — upload a screenshot, then attach it to a draft:
     * ```kotlin
     * val screenshot = client.uploadAttachment(
     *     data = bitmapBytes,
     *     filename = "screenshot.png",
     *     mimeType = "image/png",
     * )
     * val draft = baseDraft.copy(attachments = baseDraft.attachments + screenshot)
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
     * Fetches the public app configuration from
     * `GET /api/v1/public/config/{appKey}`: display metadata, anonymous-access
     * switches, attachment size limit, and the console-configured
     * [PublicAppConfig.sdk] appearance.
     */
    suspend fun fetchAppConfig(): PublicAppConfig =
        parsePublicAppConfig(get("/api/v1/public/config/${config.appKey}"))

    /**
     * Fetches the roadmap kanban columns for the app from
     * `GET /api/v1/public/columns/{appKey}`, sorted by display position.
     */
    suspend fun fetchColumns(): List<BoardColumn> {
        val json = get("/api/v1/public/columns/${config.appKey}")
        val array = json.optJSONArray("columns") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) add(parseBoardColumn(array.getJSONObject(i)))
        }.sortedBy { it.position }
    }

    /**
     * Fetches the app's release versions from
     * `GET /api/v1/public/versions/{appKey}`, sorted by display position.
     * Version ids from this list can be used to filter [fetchFeatureRequests].
     */
    suspend fun fetchVersions(): List<AppVersion> {
        val json = get("/api/v1/public/versions/${config.appKey}")
        val array = json.optJSONArray("versions") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) add(parseAppVersion(array.getJSONObject(i)))
        }.sortedBy { it.position }
    }

    /**
     * Fetches a page of feature requests from `GET /api/v1/feature-requests`.
     *
     * Each item carries the caller's vote state (`hasVoted`) and ownership
     * flag (`isOwnRequest`), both derived from [userToken].
     *
     * @param userToken Stable anonymous token from [UserTokenStore].
     * @param limit Page size; the server caps and defaults this (50).
     * @param offset Number of requests to skip, for pagination.
     * @param versionId Restricts results to a version from [fetchVersions].
     * @param query Free-text search across title and description.
     * @return Page of requests plus the total number of matches.
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
     * Submits a new feature request to `POST /api/v1/feature-requests`.
     *
     * New requests may be held for moderation; check
     * [FeatureRequestSubmissionResult.pending] to know whether the request is
     * visible on the public board yet.
     *
     * @param draft Title, description, and optional display name.
     * @param userToken Stable anonymous token that owns the request.
     * @throws FeedbackException.AuthenticationRequired when the app disallows
     *   anonymous requests (HTTP 401).
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
     * Toggles the caller's vote on a feature request via
     * `POST /api/v1/feature-requests/{id}/vote`.
     *
     * @param featureRequestId Id of the request to vote on, taken from
     *   [FeatureRequestItem.id].
     * @param userToken Stable anonymous token; a token can cast at most one
     *   vote per request.
     * @return Vote state and total count after the toggle — use these to
     *   correct optimistic UI updates.
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
     * Fetches comments on a feature request from
     * `GET /api/v1/feature-requests/{id}/comments`.
     *
     * Returns a flat list of comments including author avatars and
     * `@reply` metadata. Hidden comments are included in the response
     * but flagged with [FeatureRequestComment.isHidden].
     *
     * @param featureRequestId Id of the feature request.
     * @return List of comments, newest last.
     */
    suspend fun fetchComments(featureRequestId: String): List<FeatureRequestComment> =
        parseCommentList(get("/api/v1/feature-requests/$featureRequestId/comments"))

    /**
     * Posts a comment or `@reply` on a feature request via
     * `POST /api/v1/feature-requests/{id}/comments`.
     *
     * @param featureRequestId Id of the feature request to comment on.
     * @param draft Comment content and optional reply metadata.
     * @param userToken Stable anonymous token from [UserTokenStore], sent
     *   as the `X-User-Token` header.
     * @return The newly created comment with server-assigned id and
     *   timestamp.
     * @throws FeedbackException.AuthenticationRequired if the app
     *   requires a user token (HTTP 401).
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
     * Fetches published changelog entries from
     * `GET /api/v1/public/apps/{appKey}/changelog`, newest first by
     * [ChangelogEntry.publishedAt].
     */
    suspend fun fetchChangelog(): List<ChangelogEntry> {
        val json = get("/api/v1/public/apps/${config.appKey}/changelog")
        val array = json.optJSONArray("entries") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) add(parseChangelogEntry(array.getJSONObject(i)))
        }.sortedByDescending { it.publishedAt }
    }

    /**
     * Loads console-configured overlay copy and the newest published entries
     * for [dev.cupthread.feedback.ui.ChangelogOverlay] and
     * [dev.cupthread.feedback.ui.presentLatestChangelog].
     *
     * The number of returned entries follows the console's
     * `changelogOverlay.entryCount` setting, clamped to 1–10 (see
     * [ChangelogOverlayConfig.clampedEntryCount]).
     *
     * @return Entries plus the appearance to render them with, or `null` when
     *   the changelog feature is hidden in the console or nothing has shipped.
     */
    suspend fun prepareChangelogOverlay(): Pair<List<ChangelogEntry>, SdkAppearance>? {
        val appearance = fetchAppConfig().sdk
        if (!appearance.features.changelog) return null
        val entries = fetchChangelog().take(appearance.changelogOverlay.clampedEntryCount)
        if (entries.isEmpty()) return null
        return entries to appearance
    }

    /**
     * Subscribes an email address to the app's changelog updates via
     * `POST /api/v1/public/apps/{appKey}/changelog/subscribe`.
     *
     * @param email Address to subscribe.
     * @param userToken Stable anonymous token, sent as the `X-User-Token`
     *   header.
     * @return Whether a new subscription was created; see
     *   [ChangelogSubscriptionResult.alreadySubscribed] for repeat calls.
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
     * Removes an email address from the app's changelog updates via
     * `POST /api/v1/public/apps/{appKey}/changelog/unsubscribe`.
     *
     * @param email Address to unsubscribe.
     * @return Whether the address was unsubscribed.
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
     * Reports self-declared user attributes via
     * `PUT /api/v1/public/apps/{appKey}/user`.
     *
     * Paying signals are self-declared only — never payment details.
     * Omitted (null) parameters are left unchanged server-side.
     *
     * @param userToken Stable anonymous token identifying the user.
     * @param isPaying Whether the user reports being a paying customer.
     * @param plan Subscription plan name, such as `pro`.
     * @param mrr Monthly recurring revenue attributed to the user.
     * @param currency ISO 4217 code for [mrr], such as `USD`.
     * @return Whether the update was applied, and when.
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
     * Fetches a public user profile from
     * `GET /api/v1/users/{userId}/profile`.
     *
     * Returns the user's public profile, their public apps, and recent
     * public comments (respecting the user's privacy settings).
     *
     * @param userId Clerk user id of the profile to fetch.
     * @return Profile, public apps, and recent comments.
     * @throws FeedbackException.UnexpectedStatus on HTTP 404 when the
     *   user does not exist.
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
