package dev.cupthread.feedback

/**
 * Platform value the API accepts. The backend distinguishes
 * `ios` / `macos` / `android` / `universal`.
 */
enum class FeedbackPlatform(val wireValue: String) {
    IOS("ios"),
    MACOS("macos"),
    ANDROID("android"),
    UNIVERSAL("universal");

    companion object {
        /**
         * Platform reported for this SDK: always [ANDROID]. Exposed so shared
         * code can pick the right value per platform SDK.
         */
        val current: FeedbackPlatform = ANDROID

        /**
         * Parses a wire value such as `android`, or returns `null` when unknown.
         */
        fun fromWire(value: String): FeedbackPlatform? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * Immutable configuration for [FeedbackClient].
 *
 * @property baseUrl API root, such as `https://api.cupthread.com`. Endpoint
 *   paths are appended directly, so it must not contain path segments.
 * @property appKey App key from the CupThread developer console (`app_...`).
 * @property defaultPlatform Platform reported on feedback drafts when the
 *   caller does not set one; defaults to [FeedbackPlatform.current].
 */
data class FeedbackClientConfig(
    val baseUrl: String,
    val appKey: String,
    val defaultPlatform: FeedbackPlatform = FeedbackPlatform.current
)

/**
 * An uploaded attachment, as returned by [FeedbackClient.uploadAttachment].
 * Attach instances to a [FeedbackDraft.attachments] before submitting.
 *
 * @property kind Storage backend the file was uploaded to.
 * @property key Server-side storage key.
 * @property url Public URL for rendering or downloading the file.
 * @property filename Original file name, when provided at upload time.
 * @property mimeType MIME type, when provided at upload time.
 * @property size File size in bytes, when reported by the server.
 */
data class FeedbackAttachment(
    val kind: Kind,
    val key: String,
    val url: String,
    val filename: String? = null,
    val mimeType: String? = null,
    val size: Long? = null
) {
    /**
     * Storage backend for an attachment: `image` files land in image storage,
     * everything else in generic R2 object storage.
     */
    enum class Kind(val wireValue: String) {
        /** Generic object storage (Cloudflare R2) for non-image files. */
        R2("r2"),

        /** Image storage for screenshots and other media. */
        IMAGE("image");

        companion object {
            /**
             * Parses a wire value such as `image`, or returns `null` when unknown.
             */
            fun fromWire(value: String): Kind? = entries.firstOrNull { it.wireValue == value }
        }
    }
}

/**
 * Editable feedback content for [FeedbackClient.submit].
 *
 * Optional fields are omitted from the request when blank. Use
 * [FeedbackDraft.autofilled] to pre-fill the version fields from the host app;
 * the bundled [dev.cupthread.feedback.ui.FeedbackComposer] does this for you.
 *
 * @property title Short summary. The composer requires at least 3 characters.
 * @property description What happened and what was expected. The composer
 *   requires at least 5 characters.
 * @property reporterName Optional display name of the reporter.
 * @property reporterEmail Optional contact address for follow-ups.
 * @property platform Platform the feedback was captured on.
 * @property appVersion Host app version name, such as `1.2.0`.
 * @property buildNumber Host app version code, such as `42`.
 * @property metadata Free-form string metadata. The SDK appends its own
 *   `sdk`, `platform`, and `submittedAt` entries on submit.
 * @property attachments Files uploaded beforehand via
 *   [FeedbackClient.uploadAttachment].
 */
data class FeedbackDraft(
    val title: String = "",
    val description: String = "",
    val reporterName: String = "",
    val reporterEmail: String = "",
    val platform: FeedbackPlatform,
    val appVersion: String = "",
    val buildNumber: String = "",
    val metadata: Map<String, String> = emptyMap(),
    val attachments: List<FeedbackAttachment> = emptyList()
) {
    companion object {
        /**
         * Pre-fills platform, versionName, and versionCode from the host package
         * so end users never type them. Priority is a developer-side field.
         */
        fun autofilled(
            platform: FeedbackPlatform = FeedbackPlatform.current,
            versionName: String = "",
            versionCode: String = ""
        ): FeedbackDraft = FeedbackDraft(
            platform = platform,
            appVersion = versionName,
            buildNumber = versionCode
        )
    }
}

/**
 * Result of [FeedbackClient.submit].
 *
 * @property submissionId Server-assigned id for the submission.
 * @property forwardedToGithub Whether the platform mirrored the feedback to a
 *   connected GitHub discussion.
 * @property githubDiscussionId GitHub discussion id, when forwarded.
 * @property githubDiscussionUrl Public discussion URL, when forwarded.
 * @property warning Non-fatal notice from the server to show the reporter,
 *   such as a partially rejected attachment.
 */
data class FeedbackSubmissionResult(
    val submissionId: String,
    val forwardedToGithub: Boolean,
    val githubDiscussionId: String? = null,
    val githubDiscussionUrl: String? = null,
    val warning: String? = null
)

/**
 * Color themes selectable in the CupThread console and applied to every SDK
 * surface by [dev.cupthread.feedback.ui.CupThreadTheme].
 */
enum class SdkTheme(val wireValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    MIDNIGHT("midnight"),
    OCEAN("ocean"),
    FOREST("forest"),
    SUNSET("sunset"),
    CANDY("candy");

    companion object {
        /**
         * Parses a wire value, falling back to [SYSTEM] for missing or
         * unknown values.
         */
        fun fromWire(value: String?): SdkTheme =
            entries.firstOrNull { it.wireValue == value } ?: SYSTEM
    }
}

/**
 * A user-facing SDK surface that can be enabled or disabled from the console.
 */
enum class SdkFeature {
    FEEDBACK,
    FEATURE_REQUESTS,
    ROADMAP,
    CHANGELOG
}

/**
 * Which [SdkFeature]s are enabled for the app, as configured in the console.
 *
 * @property feedback Whether the feedback composer is available.
 * @property featureRequests Whether the feature-request list is available.
 * @property roadmap Whether the roadmap board is available.
 * @property changelog Whether the What's-New surfaces are available.
 */
data class SdkFeatures(
    val feedback: Boolean = true,
    val featureRequests: Boolean = true,
    val roadmap: Boolean = true,
    val changelog: Boolean = true
) {
    /**
     * Returns whether [feature] is enabled for the app.
     */
    fun isEnabled(feature: SdkFeature): Boolean = when (feature) {
        SdkFeature.FEEDBACK -> feedback
        SdkFeature.FEATURE_REQUESTS -> featureRequests
        SdkFeature.ROADMAP -> roadmap
        SdkFeature.CHANGELOG -> changelog
    }

    companion object {
        /** Configuration with every surface enabled; the default. */
        val allEnabled = SdkFeatures()
    }
}

/**
 * Copy and layout options for the What's-New overlay, configured in the
 * console and honored by [dev.cupthread.feedback.ui.ChangelogOverlay].
 *
 * @property title Sheet headline.
 * @property subtitle Optional line under the headline.
 * @property entryCount Requested number of entries; [clampedEntryCount]
 *   coerces this to the supported 1–10 range.
 * @property primaryButton Label of the primary button.
 * @property closeButton Label of the secondary (close) button.
 */
data class ChangelogOverlayConfig(
    val title: String = "What's New",
    val subtitle: String = "",
    val entryCount: Int = 3,
    val primaryButton: String = "Continue",
    val closeButton: String = "Close"
) {
    /** Entry count coerced into the supported 1–10 range. */
    val clampedEntryCount: Int get() = entryCount.coerceIn(1, 10)
}

/**
 * Console-configured look and feel applied to every SDK surface: color theme,
 * enabled features, and What's-New overlay copy.
 */
data class SdkAppearance(
    val theme: SdkTheme = SdkTheme.SYSTEM,
    val features: SdkFeatures = SdkFeatures.allEnabled,
    val changelogOverlay: ChangelogOverlayConfig = ChangelogOverlayConfig()
) {
    companion object {
        /** Sensible defaults used when the console config is unavailable. */
        val defaults = SdkAppearance()
    }
}

/**
 * Public configuration for an app, served by
 * `GET /api/v1/public/config/{appKey}`.
 *
 * The `allowAnonymous*` flags mirror the console privacy settings; check them
 * before presenting surfaces to users who have no user token. Surfaces of
 * this SDK read this config themselves, so most apps never need to use the
 * flags directly.
 *
 * @property appId Stable platform id of the app.
 * @property appKey App key used to fetch this config.
 * @property slug URL-friendly app name.
 * @property name Display name.
 * @property storeUrl App store URL, when configured.
 * @property storeKind Store identifier such as `app_store` or `play_store`.
 * @property iconUrl App icon URL, when configured.
 * @property allowPublic Whether the app is publicly reachable at all.
 * @property allowedPlatforms Platforms the app accepts feedback for.
 * @property maxAttachmentBytes Maximum accepted attachment size in bytes.
 * @property allowAnonymousRoadmap Whether the roadmap can be viewed without a
 *   user token.
 * @property allowAnonymousVote Whether voting requires a user token.
 * @property allowAnonymousFeedback Whether feedback can be sent without a
 *   user token.
 * @property allowAnonymousChangelog Whether the changelog requires a user token.
 * @property sdk Console-configured appearance for SDK surfaces.
 */
data class PublicAppConfig(
    val appId: String,
    val appKey: String,
    val slug: String,
    val name: String,
    val storeUrl: String?,
    val storeKind: String?,
    val iconUrl: String?,
    val allowPublic: Boolean,
    val allowedPlatforms: List<FeedbackPlatform>,
    val maxAttachmentBytes: Long,
    val allowAnonymousRoadmap: Boolean,
    val allowAnonymousVote: Boolean,
    val allowAnonymousFeedback: Boolean,
    val allowAnonymousChangelog: Boolean,
    val sdk: SdkAppearance = SdkAppearance.defaults
)

/**
 * Semantic kind of a roadmap [BoardColumn].
 */
enum class BoardColumnKind(val wireValue: String) {
    /** Requests awaiting moderator approval are filed here. */
    PENDING_REVIEW("pending_review"),

    /** Ordinary board stage. */
    NORMAL("normal"),

    /** Terminal column for shipped or closed requests. */
    DONE("done");

    companion object {
        /**
         * Parses a wire value, falling back to [NORMAL] for missing or
         * unknown values.
         */
        fun fromWire(value: String): BoardColumnKind =
            entries.firstOrNull { it.wireValue == value } ?: NORMAL
    }
}

/**
 * A roadmap kanban column, as shown by
 * [dev.cupthread.feedback.ui.RoadmapBoardScreen].
 *
 * @property id Column id referenced by [FeatureRequestItem.columnId].
 * @property appId Id of the owning app.
 * @property name Display name, such as `In Progress`.
 * @property slug URL-friendly name; also used to derive stage styling.
 * @property position Sort position, lowest first.
 * @property isVisible Whether the column is shown on the public board.
 * @property isSystem Whether the column is a system column, such as
 *   pending-review intake.
 * @property kind Semantic kind of the column.
 * @property color Column accent color as a hex string, when configured.
 * @property createdAt Creation timestamp.
 * @property updatedAt Last-change timestamp.
 */
data class BoardColumn(
    val id: String,
    val appId: String,
    val name: String,
    val slug: String,
    val position: Int,
    val isVisible: Boolean,
    val isSystem: Boolean,
    val kind: BoardColumnKind,
    val color: String? = null,
    val createdAt: String,
    val updatedAt: String
)

/**
 * A release version of the app, used to filter
 * [FeatureRequestItem]s by the version they ship in.
 *
 * @property id Version id used with [FeedbackClient.fetchFeatureRequests].
 * @property appId Id of the owning app.
 * @property label Display label such as `1.2.0`.
 * @property position Sort position, lowest first.
 * @property released Whether the version has shipped.
 * @property releasedAt Release timestamp, when shipped.
 * @property description Optional release description.
 * @property createdAt Creation timestamp.
 * @property updatedAt Last-change timestamp.
 */
data class AppVersion(
    val id: String,
    val appId: String,
    val label: String,
    val position: Int,
    val released: Boolean,
    val releasedAt: String?,
    val description: String?,
    val createdAt: String,
    val updatedAt: String
)

/**
 * A feature request as returned by [FeedbackClient.fetchFeatureRequests].
 *
 * Vote fields ([hasVoted], [voteCount]) are resolved against the user token
 * used for the fetch; use [withVoteState] to apply optimistic updates.
 *
 * @property id Request id; the target of [FeedbackClient.toggleVote].
 * @property appId Id of the owning app.
 * @property title Short summary.
 * @property description Longer rationale; may contain the inline Markdown
 *   subset rendered by [dev.cupthread.feedback.ui.MarkdownText].
 * @property status Workflow status slug, such as `backlog`.
 * @property columnId Roadmap column the request sits in, when on the board.
 * @property columnSlug Slug of that column.
 * @property columnName Display name of that column.
 * @property columnColor Accent color of that column, when configured.
 * @property versionId Version the request ships in, when assigned.
 * @property versionLabel Display label of that version.
 * @property releasedVersion Version label once the request has shipped.
 * @property requesterName Display name of the requester, when given.
 * @property requesterAvatarUrl Avatar image URL of the requester, when available.
 * @property requesterClerkId Clerk user id of the requester, when signed in.
 * @property approved Whether a moderator approved the request.
 * @property voteCount Total number of votes.
 * @property hasVoted Whether the fetching user has voted.
 * @property isOwnRequest Whether the fetching user created the request; own
 *   requests cannot be voted on.
 * @property recentCommenters Up to 3 most recent commenters, displayed as an avatar stack on the card.
 * @property hasMoreCommenters Whether there are more commenters beyond [recentCommenters].
 * @property createdAt Creation timestamp.
 * @property updatedAt Last-change timestamp.
 */
data class FeatureRequestItem(
    val id: String,
    val appId: String,
    val title: String,
    val description: String,
    val status: String,
    val columnId: String?,
    val columnSlug: String?,
    val columnName: String?,
    val columnColor: String? = null,
    val versionId: String?,
    val versionLabel: String?,
    val releasedVersion: String?,
    val requesterName: String?,
    val requesterAvatarUrl: String? = null,
    val requesterClerkId: String? = null,
    val approved: Boolean,
    val voteCount: Int,
    val hasVoted: Boolean,
    val isOwnRequest: Boolean,
    val recentCommenters: List<RecentCommenter> = emptyList(),
    val hasMoreCommenters: Boolean = false,
    val createdAt: String,
    val updatedAt: String
) {
    /**
     * Display name of the roadmap stage: the column name when the request is
     * on the board, otherwise the raw [status].
     */
    val stageName: String get() = columnName ?: status

    /**
     * Returns a copy with an updated vote state — used to apply optimistic
     * updates and server corrections after [FeedbackClient.toggleVote].
     */
    fun withVoteState(voted: Boolean, count: Int): FeatureRequestItem =
        copy(hasVoted = voted, voteCount = count)
}

/**
 * New feature request content for [FeedbackClient.submitFeatureRequest].
 *
 * @property title Short summary; at least 3 characters in the composer.
 * @property description Longer rationale; at least 5 characters in the composer.
 * @property requesterName Optional display name shown with the request.
 */
data class FeatureRequestDraft(
    val title: String = "",
    val description: String = "",
    val requesterName: String = ""
)

/**
 * A recent commenter on a feature request, shown in the avatar stack
 * on [FeatureRequestItem] cards.
 *
 * @property authorName Display name of the commenter, when given.
 * @property clerkUserId Clerk user id, when the commenter is a signed-in user.
 * @property avatarUrl Avatar image URL, when available.
 */
data class RecentCommenter(
    val authorName: String? = null,
    val clerkUserId: String? = null,
    val avatarUrl: String? = null
)

/**
 * A comment on a feature request, as returned by
 * [FeedbackClient.fetchComments].
 *
 * @property id Comment id.
 * @property featureRequestId Id of the parent feature request.
 * @property authorName Display name of the comment author, when given.
 * @property authorEmail Author email, when given.
 * @property authorAvatarUrl Avatar image URL, when available.
 * @property authorClerkId Clerk user id, when the author is signed in.
 * @property body Comment text; may contain inline Markdown.
 * @property parentId Id of the parent comment this is a reply to, when applicable.
 * @property replyToClerkId Clerk user id of the user being replied to, when applicable.
 * @property replyToAuthorName Display name of the user being replied to, when applicable.
 * @property isHidden Whether the comment has been hidden by a moderator.
 * @property createdAt Creation timestamp (ISO 8601).
 */
data class FeatureRequestComment(
    val id: String,
    val featureRequestId: String,
    val authorName: String? = null,
    val authorEmail: String? = null,
    val authorAvatarUrl: String? = null,
    val authorClerkId: String? = null,
    val body: String,
    val parentId: String? = null,
    val replyToClerkId: String? = null,
    val replyToAuthorName: String? = null,
    val isHidden: Boolean = false,
    val createdAt: String
)

/**
 * New comment content for [FeedbackClient.postComment].
 *
 * @property body Comment text; at least 1 character.
 * @property authorName Optional display name shown with the comment.
 * @property authorEmail Optional contact address for follow-ups.
 * @property authorAvatarUrl Optional avatar URL for the comment author.
 * @property parentId Id of the parent comment, when replying.
 * @property replyToClerkId Clerk user id of the user being replied to.
 * @property replyToAuthorName Display name of the user being replied to.
 */
data class CommentDraft(
    val body: String = "",
    val authorName: String? = null,
    val authorEmail: String? = null,
    val authorAvatarUrl: String? = null,
    val parentId: String? = null,
    val replyToClerkId: String? = null,
    val replyToAuthorName: String? = null
)

/**
 * A public user profile, as returned by [FeedbackClient.fetchUserProfile].
 *
 * @property clerkUserId Clerk user id.
 * @property displayName Display name, when set.
 * @property avatarUrl Avatar image URL, when available.
 * @property bio Short biography, when set.
 * @property websiteUrl Personal website URL, when set.
 * @property hideComments Whether the user has opted to hide their comments.
 * @property createdAt Profile creation timestamp.
 * @property updatedAt Profile last-update timestamp.
 */
data class PublicUserProfile(
    val clerkUserId: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val bio: String? = null,
    val websiteUrl: String? = null,
    val hideComments: Boolean = false,
    val createdAt: String = "",
    val updatedAt: String = ""
)

/**
 * Summary of a public app, as shown on a user's profile.
 *
 * @property id App id.
 * @property name Display name.
 * @property slug URL-friendly app name.
 * @property iconUrl App icon URL, when configured.
 * @property description App description, when set.
 * @property requestCount Number of public feature requests.
 */
data class PublicAppSummary(
    val id: String,
    val name: String,
    val slug: String,
    val iconUrl: String? = null,
    val description: String? = null,
    val requestCount: Int = 0
)

/**
 * A comment shown on a user's public profile page.
 *
 * @property id Comment id.
 * @property body Comment text.
 * @property createdAt Creation timestamp.
 * @property featureRequestId Feature request the comment belongs to.
 * @property featureRequestTitle Title of the feature request.
 * @property appId App the comment belongs to.
 * @property appName Display name of the app.
 */
data class UserProfileComment(
    val id: String,
    val body: String,
    val createdAt: String,
    val featureRequestId: String,
    val featureRequestTitle: String,
    val appId: String,
    val appName: String
)

/**
 * Result of [FeedbackClient.fetchUserProfile].
 *
 * @property profile The user's public profile.
 * @property apps Public apps owned by the user.
 * @property recentComments Recent public comments by the user.
 * @property hideComments Whether the user has opted to hide comments.
 */
data class PublicUserProfileResult(
    val profile: PublicUserProfile,
    val apps: List<PublicAppSummary> = emptyList(),
    val recentComments: List<UserProfileComment> = emptyList(),
    val hideComments: Boolean = false
)

/**
 * Result of [FeedbackClient.submitFeatureRequest].
 *
 * @property featureRequestId Server-assigned request id.
 * @property pending `true` while the request awaits moderator approval and is
 *   not yet visible on the public board.
 */
data class FeatureRequestSubmissionResult(
    val featureRequestId: String,
    val pending: Boolean
)

/**
 * Result of [FeedbackClient.toggleVote].
 *
 * @property voted Vote state after the toggle.
 * @property voteCount Total votes on the request after the toggle.
 */
data class VoteResult(
    val voted: Boolean,
    val voteCount: Int
)

/**
 * Page of [FeatureRequestItem]s returned by
 * [FeedbackClient.fetchFeatureRequests].
 *
 * @property requests The page of requests.
 * @property total Total number of matching requests, independent of paging.
 */
data class ListFeatureRequestsResult(
    val requests: List<FeatureRequestItem>,
    val total: Int
)

/**
 * Feature request linked from a [ChangelogEntry].
 *
 * @property id Linked request id.
 * @property title Linked request title.
 */
data class ChangelogLinkedRequest(
    val id: String,
    val title: String
)

/**
 * A published changelog entry, as returned by [FeedbackClient.fetchChangelog].
 *
 * @property id Entry id.
 * @property title Headline.
 * @property body Release notes; may contain the inline Markdown subset
 *   rendered by [dev.cupthread.feedback.ui.MarkdownText].
 * @property versionLabel Version the entry ships with, when set.
 * @property publishedAt Publication timestamp (ISO 8601); the sort key for
 *   [FeedbackClient.fetchChangelog].
 * @property linkedRequests Feature requests shipped with this entry.
 */
data class ChangelogEntry(
    val id: String,
    val title: String,
    val body: String,
    val versionLabel: String?,
    val publishedAt: String,
    val linkedRequests: List<ChangelogLinkedRequest>
)

/**
 * Result of [FeedbackClient.subscribeToChangelog].
 *
 * @property subscribed Whether the address is subscribed after the call.
 * @property alreadySubscribed Whether the address was already on the list.
 */
data class ChangelogSubscriptionResult(
    val subscribed: Boolean,
    val alreadySubscribed: Boolean
)

/**
 * Result of [FeedbackClient.unsubscribeFromChangelog].
 *
 * @property unsubscribed Whether the address was unsubscribed.
 */
data class ChangelogUnsubscribeResult(
    val unsubscribed: Boolean
)

/**
 * Result of [FeedbackClient.updateUserAttributes].
 *
 * @property ok Whether the update was applied.
 * @property updatedAt Server timestamp of the update.
 */
data class UserAttributesUpdateResult(
    val ok: Boolean,
    val updatedAt: String
)
