package dev.cupthread.feedback.internal

import dev.cupthread.feedback.AppVersion
import dev.cupthread.feedback.BoardColumn
import dev.cupthread.feedback.CommentDraft
import dev.cupthread.feedback.FeatureRequestComment
import dev.cupthread.feedback.FeedbackException
import dev.cupthread.feedback.PublicAppSummary
import dev.cupthread.feedback.PublicUserProfile
import dev.cupthread.feedback.PublicUserProfileResult
import dev.cupthread.feedback.RecentCommenter
import dev.cupthread.feedback.WeeklyDigestUnsubscribeResult
import dev.cupthread.feedback.UserProfileComment
import dev.cupthread.feedback.BoardColumnKind
import dev.cupthread.feedback.ChangelogEntry
import dev.cupthread.feedback.ChangelogLinkedRequest
import dev.cupthread.feedback.ChangelogSubscriptionResult
import dev.cupthread.feedback.ChangelogConfirmationResult
import dev.cupthread.feedback.ChangelogUnsubscribeResult
import dev.cupthread.feedback.EndUserEraseResult
import dev.cupthread.feedback.FeatureRequestItem
import dev.cupthread.feedback.FeatureRequestSubmissionResult
import dev.cupthread.feedback.FeedbackAttachment
import dev.cupthread.feedback.FeedbackPlatform
import dev.cupthread.feedback.FeedbackSubmissionResult
import dev.cupthread.feedback.ListChangelogResult
import dev.cupthread.feedback.ListCommentsResult
import dev.cupthread.feedback.ListFeatureRequestsResult
import dev.cupthread.feedback.ChangelogOverlayConfig
import dev.cupthread.feedback.EndUserLinkResult
import dev.cupthread.feedback.PublicAppConfig
import dev.cupthread.feedback.SdkAppearance
import dev.cupthread.feedback.SdkFeatures
import dev.cupthread.feedback.SdkTheme
import dev.cupthread.feedback.TurnstileConfig
import dev.cupthread.feedback.UserAttributesUpdateResult
import dev.cupthread.feedback.VoteResult
import dev.cupthread.feedback.VoteWarning
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

internal fun JSONObject.stringOrNull(name: String): String? =
    if (!has(name) || isNull(name)) null else getString(name)

internal fun JSONObject.booleanOr(name: String, default: Boolean): Boolean =
    if (!has(name) || isNull(name)) default else getBoolean(name)

internal fun JSONObject.intOr(name: String, default: Int): Int =
    if (!has(name) || isNull(name)) default else getInt(name)

internal fun JSONObject.longOr(name: String, default: Long): Long =
    if (!has(name) || isNull(name)) default else getLong(name)

internal fun parsePublicAppConfig(json: JSONObject): PublicAppConfig {
    val platforms = json.optJSONArray("allowedPlatforms") ?: JSONArray()
    val allowed = buildList {
        for (i in 0 until platforms.length()) {
            FeedbackPlatform.fromWire(platforms.getString(i))?.let(::add)
        }
    }
    val embedOrigins = json.optJSONArray("allowedEmbedOrigins")?.let { array ->
        buildList {
            for (i in 0 until array.length()) {
                val origin = array.optString(i)?.trim()?.takeIf { it.isNotEmpty() }
                if (origin != null) {
                    add(origin)
                }
            }
        }.takeIf { it.isNotEmpty() }
    }
    return PublicAppConfig(
        appId = json.getString("appId"),
        appKey = json.getString("appKey"),
        slug = json.getString("slug"),
        name = json.getString("name"),
        storeUrl = json.stringOrNull("storeUrl"),
        storeKind = json.stringOrNull("storeKind"),
        iconUrl = json.stringOrNull("iconUrl"),
        websiteUrl = json.stringOrNull("websiteUrl"),
        hideSiteBranding = json.booleanOr("hideSiteBranding", false),
        allowPublic = json.booleanOr("allowPublic", true),
        allowedPlatforms = allowed,
        maxAttachmentBytes = json.longOr("maxAttachmentBytes", 20_000_000L),
        allowAnonymousRoadmap = json.booleanOr("allowAnonymousRoadmap", true),
        allowAnonymousVote = json.booleanOr("allowAnonymousVote", true),
        allowAnonymousFeedback = json.booleanOr("allowAnonymousFeedback", true),
        allowAnonymousChangelog = json.booleanOr("allowAnonymousChangelog", true),
        requireAnonymousVoteTurnstile = json.booleanOr("requireAnonymousVoteTurnstile", false),
        turnstile = parseTurnstileConfig(json.optJSONObject("turnstile")),
        sdk = parseSdkAppearance(json.optJSONObject("sdk")),
        allowedEmbedOrigins = embedOrigins
    )
}

/**
 * Decodes the optional `turnstile` object. Absent on deployments that publish no site key, in
 * which case `null` signals that intake needs a host-supplied token provider instead. Values of
 * an unexpected JSON type degrade to `null` rather than failing the whole config parse.
 */
internal fun parseTurnstileConfig(json: JSONObject?): TurnstileConfig? {
    if (json == null) return null
    return TurnstileConfig(
        siteKey = (json.opt("siteKey") as? String)?.trim()?.takeIf { it.isNotEmpty() }
    )
}

internal fun parseSdkAppearance(json: JSONObject?): SdkAppearance {
    if (json == null) return SdkAppearance.defaults
    val features = json.optJSONObject("features")
    val overlay = json.optJSONObject("changelogOverlay")
    return SdkAppearance(
        theme = SdkTheme.fromWire(json.stringOrNull("theme")),
        features = SdkFeatures(
            feedback = features?.booleanOr("feedback", true) ?: true,
            featureRequests = features?.booleanOr("featureRequests", true) ?: true,
            roadmap = features?.booleanOr("roadmap", true) ?: true,
            changelog = features?.booleanOr("changelog", true) ?: true
        ),
        changelogOverlay = ChangelogOverlayConfig(
            title = overlay?.optString("title").orEmpty().ifBlank { "What's New" },
            subtitle = overlay?.optString("subtitle").orEmpty(),
            entryCount = overlay?.intOr("entryCount", 3) ?: 3,
            primaryButton = overlay?.optString("primaryButton").orEmpty().ifBlank { "Continue" },
            closeButton = overlay?.optString("closeButton").orEmpty().ifBlank { "Close" }
        )
    )
}

internal fun parseBoardColumn(json: JSONObject): BoardColumn = BoardColumn(
    id = json.getString("id"),
    appId = json.getString("appId"),
    name = json.getString("name"),
    slug = json.getString("slug"),
    position = json.intOr("position", 0),
    isVisible = json.booleanOr("isVisible", true),
    isSystem = json.booleanOr("isSystem", false),
    kind = BoardColumnKind.fromWire(json.optString("kind", "normal")),
    color = json.stringOrNull("color"),
    createdAt = json.optString("createdAt", ""),
    updatedAt = json.optString("updatedAt", "")
)

internal fun parseAppVersion(json: JSONObject): AppVersion = AppVersion(
    id = json.getString("id"),
    appId = json.getString("appId"),
    label = json.getString("label"),
    position = json.intOr("position", 0),
    released = json.booleanOr("released", false),
    releasedAt = json.stringOrNull("releasedAt"),
    description = json.stringOrNull("description"),
    createdAt = json.optString("createdAt", ""),
    updatedAt = json.optString("updatedAt", "")
)

internal fun parseFeatureRequestItem(json: JSONObject): FeatureRequestItem = FeatureRequestItem(
    id = json.getString("id"),
    appId = json.getString("appId"),
    title = json.getString("title"),
    description = json.optString("description", ""),
    status = json.optString("status", ""),
    columnId = json.stringOrNull("columnId"),
    columnSlug = json.stringOrNull("columnSlug"),
    columnName = json.stringOrNull("columnName"),
    columnColor = json.stringOrNull("columnColor"),
    versionId = json.stringOrNull("versionId"),
    versionLabel = json.stringOrNull("versionLabel"),
    releasedVersion = json.stringOrNull("releasedVersion"),
    requesterName = json.stringOrNull("requesterName"),
    requesterAvatarUrl = json.stringOrNull("requesterAvatarUrl"),
    requesterClerkId = json.stringOrNull("requesterClerkId"),
    approved = json.booleanOr("approved", false),
    voteCount = json.intOr("voteCount", 0),
    hasVoted = json.booleanOr("hasVoted", false),
    isOwnRequest = json.booleanOr("isOwnRequest", false),
    recentCommenters = parseRecentCommenters(json.optJSONArray("recentCommenters")),
    hasMoreCommenters = json.booleanOr("hasMoreCommenters", false),
    createdAt = json.optString("createdAt", ""),
    updatedAt = json.optString("updatedAt", ""),
    importedVotes = json.intOr("importedVotes", 0),
    commentCount = json.intOr("commentCount", 0)
)

internal fun parseListFeatureRequests(json: JSONObject, offset: Int = 0): ListFeatureRequestsResult {
    val array = json.optJSONArray("requests") ?: JSONArray()
    val requests = buildList {
        for (i in 0 until array.length()) {
            add(parseFeatureRequestItem(array.getJSONObject(i)))
        }
    }
    val total = json.intOr("total", requests.size)
    // Older deployments answer {requests,total} only; derive hasMore from the page offset then.
    val hasMore = if (json.has("hasMore") && !json.isNull("hasMore")) {
        json.getBoolean("hasMore")
    } else {
        offset + requests.size < total
    }
    return ListFeatureRequestsResult(
        requests = requests,
        total = total,
        hasMore = hasMore,
        nextCursor = json.stringOrNull("nextCursor")
    )
}

internal fun parseFeedbackSubmission(json: JSONObject): FeedbackSubmissionResult =
    FeedbackSubmissionResult(
        submissionId = json.getString("submissionId"),
        forwardedToGithub = json.booleanOr("forwardedToGithub", false),
        githubDiscussionId = json.stringOrNull("githubDiscussionId"),
        githubDiscussionUrl = json.stringOrNull("githubDiscussionUrl"),
        warning = json.stringOrNull("warning"),
        warningCode = json.stringOrNull("warningCode")
    )

internal fun parseAttachment(json: JSONObject): FeedbackAttachment {
    val kind = FeedbackAttachment.Kind.fromWire(json.getString("kind"))
        ?: throw IllegalArgumentException("unknown attachment kind")
    val uploadId = json.stringOrNull("uploadId")?.trim()?.ifEmpty { null }
    val key = json.stringOrNull("key")?.trim()?.ifEmpty { null }
    if (uploadId == null && key == null) {
        throw IllegalArgumentException("attachment response must contain an uploadId or key")
    }
    return FeedbackAttachment(
        kind = kind,
        key = key,
        url = json.stringOrNull("url"),
        filename = json.stringOrNull("filename"),
        mimeType = json.stringOrNull("mimeType"),
        size = if (!json.has("size") || json.isNull("size")) null else json.getLong("size"),
        uploadId = uploadId,
        userToken = json.stringOrNull("userToken")?.trim()?.ifEmpty { null }
    )
}

internal fun parseFeatureRequestSubmission(json: JSONObject): FeatureRequestSubmissionResult =
    FeatureRequestSubmissionResult(
        featureRequestId = json.getString("featureRequestId"),
        pending = json.booleanOr("pending", true)
    )

internal fun parseVoteResult(json: JSONObject): VoteResult = VoteResult(
    voted = json.getBoolean("voted"),
    voteCount = json.intOr("voteCount", 0),
    warning = parseVoteWarning(json.optJSONObject("warning"))
)

/**
 * Decodes the optional `warning` object of a vote response.
 *
 * A warning without a usable `code` carries no machine-readable meaning, so it is dropped rather
 * than failing the whole vote result. Unknown codes are kept verbatim for forward compatibility.
 */
private fun parseVoteWarning(json: JSONObject?): VoteWarning? {
    if (json == null) return null
    val code = (json.opt("code") as? String)?.takeIf { it.isNotBlank() } ?: return null
    val error = (json.opt("error") as? String)?.ifBlank { null }
    return VoteWarning(code = code, error = error)
}

internal fun parseChangelogEntry(json: JSONObject): ChangelogEntry {
    val linked = json.optJSONArray("linkedRequests") ?: JSONArray()
    val requests = buildList {
        for (i in 0 until linked.length()) {
            val item = linked.getJSONObject(i)
            add(ChangelogLinkedRequest(id = item.getString("id"), title = item.getString("title")))
        }
    }
    val body = if (json.has("body")) {
        json.optString("body", "")
    } else {
        json.optString("content", "")
    }
    val versionLabel = json.stringOrNull("versionLabel") ?: json.stringOrNull("version")
    return ChangelogEntry(
        id = json.getString("id"),
        title = json.getString("title"),
        body = body,
        versionLabel = versionLabel,
        publishedAt = json.getString("publishedAt"),
        linkedRequests = requests
    )
}

/**
 * Parses an ISO-8601 timestamp into the instant it denotes, accepting `Z` and numeric-offset
 * representations plus any sub-second precision. Lexicographic comparison of the raw strings is
 * not chronological (issue #166): `'.'` sorts before `'Z'` (mixed fractional precision) and an
 * offset form sorts by its local clock reading, so entries must be ordered by instant instead.
 *
 * Returns `null` for strings no parser accepts; callers sort those deterministically last rather
 * than failing the whole page.
 */
internal fun parseChangelogTimestamp(iso: String): Instant? =
    try {
        Instant.parse(iso)
    } catch (_: DateTimeParseException) {
        try {
            // Older runtimes' ISO_INSTANT parsing rejects numeric offsets; OffsetDateTime
            // accepts them on every java.time implementation since its first release.
            OffsetDateTime.parse(iso).toInstant()
        } catch (_: DateTimeParseException) {
            null
        }
    }

internal fun parseListChangelog(json: JSONObject): ListChangelogResult {
    val array = json.optJSONArray("entries") ?: JSONArray()
    val entries = buildList {
        for (i in 0 until array.length()) {
            add(parseChangelogEntry(array.getJSONObject(i)))
        }
    }.sortedByDescending { parseChangelogTimestamp(it.publishedAt) ?: Instant.MIN }
    // Older deployments answer {entries, nextCursor} only; derive hasMore from the cursor then
    // (same defect class as parseListComments, fixed in #153). An explicit hasMore stays authoritative.
    val nextCursor = json.stringOrNull("nextCursor")?.takeIf { it.isNotEmpty() }
    val hasMore = if (json.has("hasMore") && !json.isNull("hasMore")) {
        json.getBoolean("hasMore")
    } else {
        entries.isNotEmpty() && !nextCursor.isNullOrBlank()
    }
    return ListChangelogResult(
        entries = entries,
        hasMore = hasMore,
        nextCursor = nextCursor
    )
}

internal fun parseChangelogSubscription(json: JSONObject): ChangelogSubscriptionResult =
    ChangelogSubscriptionResult(
        subscribed = json.booleanOr("subscribed", true),
        alreadySubscribed = json.booleanOr("alreadySubscribed", false)
    )

internal fun parseChangelogUnsubscribe(json: JSONObject): ChangelogUnsubscribeResult =
    ChangelogUnsubscribeResult(unsubscribed = json.booleanOr("unsubscribed", true))

internal fun parseWeeklyDigestUnsubscribe(json: JSONObject): WeeklyDigestUnsubscribeResult {
    // Strict by contract (issue #36 review): a conforming 200 always carries the boolean
    // `unsubscribed` confirmation, so a missing field is a non-conforming response rather
    // than a silent success — reporting `true` there would tell the user they are
    // unsubscribed when the deployment answered something else. Malformed values wrap into
    // the documented FeedbackException.InvalidResponse instead of a raw JSONException.
    if (!json.has("unsubscribed") || json.isNull("unsubscribed")) {
        throw FeedbackException.InvalidResponse(
            IllegalStateException("Digest unsubscribe response is missing the \"unsubscribed\" confirmation: $json")
        )
    }
    val unsubscribed = try {
        json.getBoolean("unsubscribed")
    } catch (error: Exception) {
        throw FeedbackException.InvalidResponse(error)
    }
    return WeeklyDigestUnsubscribeResult(unsubscribed = unsubscribed)
}

internal fun parseChangelogConfirmationResult(json: JSONObject): ChangelogConfirmationResult =
    ChangelogConfirmationResult(confirmed = json.booleanOr("confirmed", true))

internal fun parseUserAttributesUpdate(json: JSONObject): UserAttributesUpdateResult =
    UserAttributesUpdateResult(
        ok = json.booleanOr("ok", true),
        updatedAt = json.optString("updatedAt", "")
    )

internal fun parseRecentCommenters(array: JSONArray?): List<RecentCommenter> {
    if (array == null) return emptyList()
    return buildList {
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            add(
                RecentCommenter(
                    authorName = item.stringOrNull("authorName"),
                    clerkUserId = item.stringOrNull("clerkUserId"),
                    avatarUrl = item.stringOrNull("avatarUrl")
                )
            )
        }
    }
}

internal fun parseFeatureRequestComment(json: JSONObject): FeatureRequestComment {
    val record = json.optJSONObject("comment") ?: json
    return FeatureRequestComment(
        id = record.getString("id"),
        featureRequestId = record.stringOrNull("featureRequestId") ?: record.optString("featureRequestId", ""),
        authorName = record.stringOrNull("authorName"),
        authorEmail = record.stringOrNull("authorEmail"),
        authorAvatarUrl = record.stringOrNull("authorAvatarUrl"),
        authorClerkId = record.stringOrNull("authorClerkId"),
        body = record.getString("body"),
        parentId = record.stringOrNull("parentId"),
        replyToClerkId = record.stringOrNull("replyToClerkId"),
        replyToAuthorName = record.stringOrNull("replyToAuthorName"),
        isHidden = record.booleanOr("isHidden", false),
        createdAt = record.stringOrNull("createdAt") ?: record.optString("createdAt", ""),
        appId = record.stringOrNull("appId"),
        updatedAt = record.stringOrNull("updatedAt")
    )
}

internal fun parseCommentList(json: JSONObject): List<FeatureRequestComment> {
    val array = json.optJSONArray("comments") ?: JSONArray()
    return buildList {
        for (i in 0 until array.length()) {
            add(parseFeatureRequestComment(array.getJSONObject(i)))
        }
    }
}

internal fun parseListComments(json: JSONObject): ListCommentsResult {
    val comments = parseCommentList(json)
    val total = json.optInt("total", comments.size)
    val nextCursor = json.stringOrNull("nextCursor")
    // Legacy deployments answer {comments,total[,nextCursor]} without hasMore; derive it
    // from the payload instead of silently truncating paginated fetches.
    val hasMore = if (json.has("hasMore") && !json.isNull("hasMore")) {
        json.getBoolean("hasMore")
    } else {
        comments.isNotEmpty() && (comments.size < total || !nextCursor.isNullOrBlank())
    }
    return ListCommentsResult(
        comments = comments,
        total = total,
        hasMore = hasMore,
        nextCursor = nextCursor
    )
}

internal fun parsePublicUserProfile(json: JSONObject): PublicUserProfile =
    PublicUserProfile(
        clerkUserId = json.stringOrNull("clerkUserId") ?: "",
        displayName = json.stringOrNull("displayName"),
        avatarUrl = json.stringOrNull("avatarUrl"),
        bio = json.stringOrNull("bio"),
        websiteUrl = json.stringOrNull("websiteUrl"),
        hideComments = json.booleanOr("hideComments", false),
        createdAt = json.stringOrNull("createdAt") ?: "",
        updatedAt = json.stringOrNull("updatedAt") ?: ""
    )

internal fun parsePublicAppSummary(json: JSONObject): PublicAppSummary =
    PublicAppSummary(
        id = json.getString("id"),
        name = json.getString("name"),
        slug = json.stringOrNull("appSlug").orEmpty().ifBlank { json.stringOrNull("slug").orEmpty() },
        iconUrl = json.stringOrNull("iconUrl"),
        description = json.stringOrNull("description"),
        requestCount = json.intOr("requestCount", 0)
    )

internal fun parseUserProfileComment(json: JSONObject): UserProfileComment =
    UserProfileComment(
        id = json.getString("id"),
        body = json.getString("body"),
        createdAt = json.stringOrNull("createdAt") ?: "",
        featureRequestId = json.getString("featureRequestId"),
        featureRequestTitle = json.getString("featureRequestTitle"),
        appId = json.stringOrNull("appId") ?: "",
        appName = json.getString("appName")
    )

internal fun parsePublicUserProfileResult(json: JSONObject): PublicUserProfileResult {
    val profileJson = json.getJSONObject("profile")
    val appsArray = json.optJSONArray("publicApps") ?: json.optJSONArray("apps") ?: JSONArray()
    val commentsArray = json.optJSONArray("recentComments") ?: JSONArray()
    return PublicUserProfileResult(
        profile = parsePublicUserProfile(profileJson),
        apps = buildList {
            for (i in 0 until appsArray.length()) add(parsePublicAppSummary(appsArray.getJSONObject(i)))
        },
        recentComments = buildList {
            for (i in 0 until commentsArray.length()) add(parseUserProfileComment(commentsArray.getJSONObject(i)))
        },
        hideComments = json.booleanOr("hideComments", false)
    )
}

internal fun parseEndUserEraseResult(json: JSONObject): EndUserEraseResult {
    return EndUserEraseResult(
        erased = json.getBoolean("erased"),
        endUserId = json.stringOrNull("endUserId")?.takeIf { it.isNotEmpty() }
            ?: json.stringOrNull("id")?.takeIf { it.isNotEmpty() }
    )
}

internal fun parseEndUserLinkResult(json: JSONObject): EndUserLinkResult {
    val endUser = json.optJSONObject("endUser")
    if (endUser != null) {
        val id = endUser.stringOrNull("id")?.takeIf { it.isNotEmpty() }
            ?: endUser.stringOrNull("endUserId")?.takeIf { it.isNotEmpty() }
        val clerkUserId = endUser.stringOrNull("clerkUserId")?.takeIf { it.isNotEmpty() }
        val confirmed = if (endUser.has("identityConfirmed") && !endUser.isNull("identityConfirmed")) {
            endUser.getBoolean("identityConfirmed")
        } else {
            true
        }
        return EndUserLinkResult(
            linked = confirmed,
            endUserId = id,
            clerkUserId = clerkUserId
        )
    }
    val linked = json.booleanOr("linked", true)
    val endUserId = json.stringOrNull("endUserId")?.takeIf { it.isNotEmpty() }
        ?: json.stringOrNull("id")?.takeIf { it.isNotEmpty() }
    val clerkUserId = json.stringOrNull("clerkUserId")?.takeIf { it.isNotEmpty() }
    return EndUserLinkResult(
        linked = linked,
        endUserId = endUserId,
        clerkUserId = clerkUserId
    )
}

internal fun JSONObject.putOptString(name: String, value: String?) {
    if (value != null) put(name, value)
}
