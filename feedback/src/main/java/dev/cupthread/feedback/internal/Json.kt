package dev.cupthread.feedback.internal

import dev.cupthread.feedback.AppVersion
import dev.cupthread.feedback.BoardColumn
import dev.cupthread.feedback.CommentDraft
import dev.cupthread.feedback.FeatureRequestComment
import dev.cupthread.feedback.PublicAppSummary
import dev.cupthread.feedback.PublicUserProfile
import dev.cupthread.feedback.PublicUserProfileResult
import dev.cupthread.feedback.RecentCommenter
import dev.cupthread.feedback.UserProfileComment
import dev.cupthread.feedback.BoardColumnKind
import dev.cupthread.feedback.ChangelogEntry
import dev.cupthread.feedback.ChangelogLinkedRequest
import dev.cupthread.feedback.ChangelogSubscriptionResult
import dev.cupthread.feedback.ChangelogUnsubscribeResult
import dev.cupthread.feedback.FeatureRequestItem
import dev.cupthread.feedback.FeatureRequestSubmissionResult
import dev.cupthread.feedback.FeedbackAttachment
import dev.cupthread.feedback.FeedbackPlatform
import dev.cupthread.feedback.FeedbackSubmissionResult
import dev.cupthread.feedback.ListFeatureRequestsResult
import dev.cupthread.feedback.ChangelogOverlayConfig
import dev.cupthread.feedback.PublicAppConfig
import dev.cupthread.feedback.SdkAppearance
import dev.cupthread.feedback.SdkFeatures
import dev.cupthread.feedback.SdkTheme
import dev.cupthread.feedback.UserAttributesUpdateResult
import dev.cupthread.feedback.VoteResult
import org.json.JSONArray
import org.json.JSONObject

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
    return PublicAppConfig(
        appId = json.getString("appId"),
        appKey = json.getString("appKey"),
        slug = json.getString("slug"),
        name = json.getString("name"),
        storeUrl = json.stringOrNull("storeUrl"),
        storeKind = json.stringOrNull("storeKind"),
        iconUrl = json.stringOrNull("iconUrl"),
        allowPublic = json.booleanOr("allowPublic", true),
        allowedPlatforms = allowed,
        maxAttachmentBytes = json.longOr("maxAttachmentBytes", 20_000_000L),
        allowAnonymousRoadmap = json.booleanOr("allowAnonymousRoadmap", true),
        allowAnonymousVote = json.booleanOr("allowAnonymousVote", true),
        allowAnonymousFeedback = json.booleanOr("allowAnonymousFeedback", true),
        allowAnonymousChangelog = json.booleanOr("allowAnonymousChangelog", true),
        sdk = parseSdkAppearance(json.optJSONObject("sdk"))
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
    updatedAt = json.optString("updatedAt", "")
)

internal fun parseListFeatureRequests(json: JSONObject): ListFeatureRequestsResult {
    val array = json.optJSONArray("requests") ?: JSONArray()
    val requests = buildList {
        for (i in 0 until array.length()) {
            add(parseFeatureRequestItem(array.getJSONObject(i)))
        }
    }
    return ListFeatureRequestsResult(requests = requests, total = json.intOr("total", requests.size))
}

internal fun parseFeedbackSubmission(json: JSONObject): FeedbackSubmissionResult =
    FeedbackSubmissionResult(
        submissionId = json.getString("submissionId"),
        forwardedToGithub = json.booleanOr("forwardedToGithub", false),
        githubDiscussionId = json.stringOrNull("githubDiscussionId"),
        githubDiscussionUrl = json.stringOrNull("githubDiscussionUrl"),
        warning = json.stringOrNull("warning")
    )

internal fun parseAttachment(json: JSONObject): FeedbackAttachment {
    val kind = FeedbackAttachment.Kind.fromWire(json.getString("kind"))
        ?: throw IllegalArgumentException("unknown attachment kind")
    return FeedbackAttachment(
        kind = kind,
        key = json.getString("key"),
        url = json.getString("url"),
        filename = json.stringOrNull("filename"),
        mimeType = json.stringOrNull("mimeType"),
        size = if (!json.has("size") || json.isNull("size")) null else json.getLong("size")
    )
}

internal fun parseFeatureRequestSubmission(json: JSONObject): FeatureRequestSubmissionResult =
    FeatureRequestSubmissionResult(
        featureRequestId = json.getString("featureRequestId"),
        pending = json.booleanOr("pending", true)
    )

internal fun parseVoteResult(json: JSONObject): VoteResult = VoteResult(
    voted = json.getBoolean("voted"),
    voteCount = json.intOr("voteCount", 0)
)

internal fun parseChangelogEntry(json: JSONObject): ChangelogEntry {
    val linked = json.optJSONArray("linkedRequests") ?: JSONArray()
    val requests = buildList {
        for (i in 0 until linked.length()) {
            val item = linked.getJSONObject(i)
            add(ChangelogLinkedRequest(id = item.getString("id"), title = item.getString("title")))
        }
    }
    return ChangelogEntry(
        id = json.getString("id"),
        title = json.getString("title"),
        body = json.optString("body", ""),
        versionLabel = json.stringOrNull("versionLabel"),
        publishedAt = json.getString("publishedAt"),
        linkedRequests = requests
    )
}

internal fun parseChangelogSubscription(json: JSONObject): ChangelogSubscriptionResult =
    ChangelogSubscriptionResult(
        subscribed = json.booleanOr("subscribed", true),
        alreadySubscribed = json.booleanOr("alreadySubscribed", false)
    )

internal fun parseChangelogUnsubscribe(json: JSONObject): ChangelogUnsubscribeResult =
    ChangelogUnsubscribeResult(unsubscribed = json.booleanOr("unsubscribed", true))

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

internal fun parseFeatureRequestComment(json: JSONObject): FeatureRequestComment =
    FeatureRequestComment(
        id = json.getString("id"),
        featureRequestId = json.getString("featureRequestId"),
        authorName = json.stringOrNull("authorName"),
        authorEmail = json.stringOrNull("authorEmail"),
        authorAvatarUrl = json.stringOrNull("authorAvatarUrl"),
        authorClerkId = json.stringOrNull("authorClerkId"),
        body = json.getString("body"),
        parentId = json.stringOrNull("parentId"),
        replyToClerkId = json.stringOrNull("replyToClerkId"),
        replyToAuthorName = json.stringOrNull("replyToAuthorName"),
        isHidden = json.booleanOr("isHidden", false),
        createdAt = json.optString("createdAt", "")
    )

internal fun parseCommentList(json: JSONObject): List<FeatureRequestComment> {
    val array = json.optJSONArray("comments") ?: JSONArray()
    return buildList {
        for (i in 0 until array.length()) {
            add(parseFeatureRequestComment(array.getJSONObject(i)))
        }
    }
}

internal fun parsePublicUserProfile(json: JSONObject): PublicUserProfile =
    PublicUserProfile(
        clerkUserId = json.getString("clerkUserId"),
        displayName = json.stringOrNull("displayName"),
        avatarUrl = json.stringOrNull("avatarUrl"),
        bio = json.stringOrNull("bio"),
        websiteUrl = json.stringOrNull("websiteUrl"),
        hideComments = json.booleanOr("hideComments", false),
        createdAt = json.optString("createdAt", ""),
        updatedAt = json.optString("updatedAt", "")
    )

internal fun parsePublicAppSummary(json: JSONObject): PublicAppSummary =
    PublicAppSummary(
        id = json.getString("id"),
        name = json.getString("name"),
        slug = json.getString("slug"),
        iconUrl = json.stringOrNull("iconUrl"),
        description = json.stringOrNull("description"),
        requestCount = json.intOr("requestCount", 0)
    )

internal fun parseUserProfileComment(json: JSONObject): UserProfileComment =
    UserProfileComment(
        id = json.getString("id"),
        body = json.getString("body"),
        createdAt = json.optString("createdAt", ""),
        featureRequestId = json.getString("featureRequestId"),
        featureRequestTitle = json.getString("featureRequestTitle"),
        appId = json.getString("appId"),
        appName = json.getString("appName")
    )

internal fun parsePublicUserProfileResult(json: JSONObject): PublicUserProfileResult {
    val profileJson = json.getJSONObject("profile")
    val appsArray = json.optJSONArray("apps") ?: JSONArray()
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

internal fun JSONObject.putOptString(name: String, value: String?) {
    if (value != null) put(name, value)
}
