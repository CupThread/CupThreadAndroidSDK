package dev.cupthread.feedback

/**
 * Platform identifier recognized by the CupThread REST API.
 *
 * The backend distinguishes between Apple platforms (`ios`, `macos`), Android (`android`),
 * and cross-platform or web projects (`universal`).
 *
 * Example usage:
 * ```kotlin
 * val platform = FeedbackPlatform.fromWire("android") ?: FeedbackPlatform.current
 * println("Target platform: ${platform.wireValue}")
 * ```
 *
 * @property wireValue The raw string token transmitted across the network in API requests and responses.
 */
enum class FeedbackPlatform(val wireValue: String) {
    /** Apple iOS platform (`ios`). */
    IOS("ios"),

    /** Apple macOS desktop platform (`macos`). */
    MACOS("macos"),

    /** Google Android platform (`android`). */
    ANDROID("android"),

    /** Universal / cross-platform target (`universal`). */
    UNIVERSAL("universal");

    companion object {
        /**
         * Platform reported for this SDK: always [ANDROID].
         *
         * Exposed so shared cross-platform logic can select the correct platform identifier
         * dynamically per SDK runtime.
         */
        val current: FeedbackPlatform = ANDROID

        /**
         * Parses a wire value such as `"android"` into its corresponding [FeedbackPlatform] enum entry.
         *
         * @param value The raw string representation from the API or configuration.
         * @return The matched [FeedbackPlatform], or `null` if the wire value is unrecognized.
         *
         * Example:
         * ```kotlin
         * val platform = FeedbackPlatform.fromWire("android") // FeedbackPlatform.ANDROID
         * val unknown = FeedbackPlatform.fromWire("windows") // null
         * ```
         */
        fun fromWire(value: String): FeedbackPlatform? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * Immutable configuration for [FeedbackClient].
 *
 * Contains server connectivity settings, your app's public API credentials from the CupThread
 * developer console, and default device metadata defaults.
 *
 * Example:
 * ```kotlin
 * val config = FeedbackClientConfig(
 *     baseUrl = "https://api.cupthread.com",
 *     appKey = "app_live_sampleAppKey123",
 *     defaultPlatform = FeedbackPlatform.ANDROID
 * )
 * val client = FeedbackClient(config)
 * ```
 *
 * @property baseUrl API root URL (e.g. `https://api.cupthread.com`). Endpoint paths are
 *   appended directly, so this URL should not contain trailing slashes or subpaths.
 * @property appKey Public app key generated in the CupThread developer dashboard (e.g., `app_live_...`).
 * @property defaultPlatform Default platform reported on feedback drafts when none is explicitly provided;
 *   defaults to [FeedbackPlatform.current] ([FeedbackPlatform.ANDROID]).
 */
data class FeedbackClientConfig(
    val baseUrl: String,
    val appKey: String,
    val defaultPlatform: FeedbackPlatform = FeedbackPlatform.current
)

/**
 * An uploaded attachment descriptor, as returned by [FeedbackClient.uploadAttachment].
 *
 * Attach one or more instances to [FeedbackDraft.attachments] before calling [FeedbackClient.submit].
 *
 * Example:
 * ```kotlin
 * val attachment = client.uploadAttachment(
 *     data = imageBytes,
 *     filename = "bug_screenshot.png",
 *     mimeType = "image/png"
 * )
 * val draft = FeedbackDraft(
 *     title = "UI Glitch on Pixel 8",
 *     description = "Navigation bar overlaps bottom buttons.",
 *     platform = FeedbackPlatform.ANDROID,
 *     attachments = listOf(attachment)
 * )
 * ```
 *
 * @property kind Storage backend where the file is hosted (see [FeedbackAttachment.Kind]).
 * @property key Unique storage key assigned by the server.
 * @property url Publicly accessible CDN URL for rendering or downloading the attachment.
 * @property filename Original file name provided during upload (e.g., `"screenshot.png"`), or `null`.
 * @property mimeType Standard MIME type provided during upload (e.g., `"image/png"`), or `null`.
 * @property size File size in bytes as reported by the storage backend, or `null`.
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
     * Storage backend category for an attachment.
     *
     * @property wireValue The string token used in JSON serialization.
     */
    enum class Kind(val wireValue: String) {
        /** Generic Cloudflare R2 object storage for logs, diagnostics, and non-image files (`r2`). */
        R2("r2"),

        /** Optimized image storage for screenshots and visual attachments (`image`). */
        IMAGE("image");

        companion object {
            /**
             * Parses a raw wire string (e.g. `"image"`, `"r2"`) into its [Kind] enum entry.
             *
             * @param value The wire string representation.
             * @return The corresponding [Kind], or `null` if unrecognized.
             */
            fun fromWire(value: String): Kind? = entries.firstOrNull { it.wireValue == value }
        }
    }
}

/**
 * Editable feedback submission content passed to [FeedbackClient.submit].
 *
 * Optional fields left empty or blank are automatically omitted from the network payload.
 * Use [FeedbackDraft.autofilled] to automatically populate version and platform fields
 * from the host application.
 *
 * Example:
 * ```kotlin
 * val draft = FeedbackDraft.autofilled(
 *     versionName = "2.1.0",
 *     versionCode = "104"
 * ).copy(
 *     title = "Dark mode contrast issue",
 *     description = "Secondary text in settings is hard to read in dark mode.",
 *     reporterEmail = "user@example.com",
 *     metadata = mapOf("device_model" to "Pixel 8 Pro", "os_version" to "Android 14")
 * )
 * val result = client.submit(draft, userToken = "user_anon_token_123")
 * ```
 *
 * @property title Brief summary of the feedback (minimum 3 characters required by composer UI).
 * @property description Detailed explanation of what happened or what is requested (minimum 5 characters).
 * @property reporterName Optional display name of the user providing feedback.
 * @property reporterEmail Optional email address of the reporter for follow-up communications.
 * @property platform Operating system platform the feedback was captured on.
 * @property appVersion Semantic version name of the host application (e.g., `"1.2.0"`).
 * @property buildNumber Internal build code of the host application (e.g., `"42"`).
 * @property metadata Arbitrary key-value diagnostic metadata. The SDK automatically appends
 *   `sdk`, `platform`, and `submittedAt` timestamps upon submission.
 * @property attachments Pre-uploaded files and screenshots returned by [FeedbackClient.uploadAttachment].
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
         * Convenience factory that pre-populates platform, version name, and version code
         * from the host application package so end users never have to enter them manually.
         *
         * @param platform Target platform; defaults to [FeedbackPlatform.current].
         * @param versionName Host app version name (e.g., `BuildConfig.VERSION_NAME`).
         * @param versionCode Host app version code (e.g., `BuildConfig.VERSION_CODE.toString()`).
         * @return A newly initialized [FeedbackDraft] ready for user input.
         *
         * Example:
         * ```kotlin
         * val draft = FeedbackDraft.autofilled(
         *     versionName = "1.0.0",
         *     versionCode = "1"
         * )
         * ```
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
 * Result returned by [FeedbackClient.submit] upon successful delivery of a feedback draft.
 *
 * Example:
 * ```kotlin
 * val result = client.submit(draft)
 * if (result.forwardedToGithub) {
 *     println("Discussion created: ${result.githubDiscussionUrl}")
 * }
 * result.warning?.let { println("Notice: $it") }
 * ```
 *
 * @property submissionId Unique server-assigned tracking identifier for the feedback submission.
 * @property forwardedToGithub Whether the platform automatically mirrored the feedback to a connected GitHub Discussion.
 * @property githubDiscussionId Numerical ID of the mirrored GitHub Discussion, or `null` if not forwarded.
 * @property githubDiscussionUrl Web URL of the mirrored GitHub Discussion, or `null` if not forwarded.
 * @property warning Non-fatal informational notice from the server (e.g., if an attachment was stripped due to size limits).
 */
data class FeedbackSubmissionResult(
    val submissionId: String,
    val forwardedToGithub: Boolean,
    val githubDiscussionId: String? = null,
    val githubDiscussionUrl: String? = null,
    val warning: String? = null
)

/**
 * Visual color themes selectable in the CupThread developer console and applied across SDK composables
 * by [dev.cupthread.feedback.ui.CupThreadTheme].
 *
 * Each theme defines specific accent colors and surface backgrounds for both light and dark modes.
 *
 * @property wireValue Raw string identifier matching console configuration settings.
 */
enum class SdkTheme(val wireValue: String) {
    /** Follows system appearance using standard blue accent (`#2563EB`). */
    SYSTEM("system"),

    /** Forces light mode with blue accent (`#2563EB`) and white backgrounds. */
    LIGHT("light"),

    /** Forces dark mode with blue accent (`#60A5FA`) and slate dark backgrounds (`#0F172A`). */
    DARK("dark"),

    /** Pitch dark theme with violet accent (`#818CF8`) and near-black backgrounds (`#09090B`). */
    MIDNIGHT("midnight"),

    /** Teal palette (`#0D9488`) with soothing aquatic tones. */
    OCEAN("ocean"),

    /** Emerald green palette (`#16A34A`) with natural forest accents. */
    FOREST("forest"),

    /** Vibrant warm orange palette (`#EA580C`) with warm amber undertones. */
    SUNSET("sunset"),

    /** Playful deep pink palette (`#DB2777`) with rose accents. */
    CANDY("candy");

    companion object {
        /**
         * Parses a theme wire value, gracefully falling back to [SYSTEM] when missing or unrecognized.
         *
         * @param value The wire string representation from public app config (e.g., `"ocean"`).
         * @return The matched [SdkTheme] or [SYSTEM] as default.
         *
         * Example:
         * ```kotlin
         * val theme = SdkTheme.fromWire("midnight") // SdkTheme.MIDNIGHT
         * val fallback = SdkTheme.fromWire(null) // SdkTheme.SYSTEM
         * ```
         */
        fun fromWire(value: String?): SdkTheme =
            entries.firstOrNull { it.wireValue == value } ?: SYSTEM
    }
}

/**
 * Functional SDK surface areas that can be toggled on or off remotely from the CupThread console.
 */
enum class SdkFeature {
    /** General user feedback submission form ([dev.cupthread.feedback.ui.FeedbackComposer]). */
    FEEDBACK,

    /** Public feature request listing and voting list ([dev.cupthread.feedback.ui.FeatureRequestsScreen]). */
    FEATURE_REQUESTS,

    /** Kanban roadmap board ([dev.cupthread.feedback.ui.RoadmapBoardScreen]). */
    ROADMAP,

    /** Release notes and What's New changelog stream ([dev.cupthread.feedback.ui.WhatsNewScreen]). */
    CHANGELOG
}

/**
 * Feature availability switches configured remotely in the CupThread developer console.
 *
 * Example:
 * ```kotlin
 * val features = SdkFeatures(feedback = true, roadmap = false)
 * if (features.isEnabled(SdkFeature.ROADMAP)) {
 *     // Render roadmap tab
 * }
 * ```
 *
 * @property feedback Whether the feedback composer surface is enabled.
 * @property featureRequests Whether the feature request board and voting are enabled.
 * @property roadmap Whether the public roadmap kanban board is enabled.
 * @property changelog Whether the What's-New changelog screens and overlays are enabled.
 */
data class SdkFeatures(
    val feedback: Boolean = true,
    val featureRequests: Boolean = true,
    val roadmap: Boolean = true,
    val changelog: Boolean = true
) {
    /**
     * Checks if the given [feature] is enabled for this app.
     *
     * @param feature The [SdkFeature] surface to query.
     * @return `true` if enabled in the console, `false` otherwise.
     */
    fun isEnabled(feature: SdkFeature): Boolean = when (feature) {
        SdkFeature.FEEDBACK -> feedback
        SdkFeature.FEATURE_REQUESTS -> featureRequests
        SdkFeature.ROADMAP -> roadmap
        SdkFeature.CHANGELOG -> changelog
    }

    companion object {
        /** Default configuration with all SDK surfaces enabled. */
        val allEnabled = SdkFeatures()
    }
}

/**
 * Copy, button label, and display count settings for the What's-New modal overlay
 * ([dev.cupthread.feedback.ui.ChangelogOverlay]).
 *
 * @property title Header title displayed at the top of the What's-New sheet (e.g., `"What's New"`).
 * @property subtitle Optional secondary description displayed under the header.
 * @property entryCount Desired number of changelog entries to present (coerced between 1 and 10).
 * @property primaryButton Label for the main action button (e.g., `"Continue"`).
 * @property closeButton Label for the secondary dismissal button (e.g., `"Close"`).
 */
data class ChangelogOverlayConfig(
    val title: String = "What's New",
    val subtitle: String = "",
    val entryCount: Int = 3,
    val primaryButton: String = "Continue",
    val closeButton: String = "Close"
) {
    /**
     * Number of changelog entries guaranteed to be within the valid 1..10 range.
     */
    val clampedEntryCount: Int get() = entryCount.coerceIn(1, 10)
}

/**
 * Complete remote appearance package configured in the CupThread developer console:
 * color palette theme, feature switches, and changelog presentation options.
 *
 * @property theme Primary color theme ([SdkTheme]).
 * @property features Enabled feature flags ([SdkFeatures]).
 * @property changelogOverlay Layout and copy settings for What's-New sheets ([ChangelogOverlayConfig]).
 */
data class SdkAppearance(
    val theme: SdkTheme = SdkTheme.SYSTEM,
    val features: SdkFeatures = SdkFeatures.allEnabled,
    val changelogOverlay: ChangelogOverlayConfig = ChangelogOverlayConfig()
) {
    companion object {
        /** Default appearance parameters applied when network configuration is unavailable. */
        val defaults = SdkAppearance()
    }
}

/**
 * Public metadata and configuration for an application, fetched from `GET /api/v1/public/config/{appKey}`.
 *
 * Contains remote access policies (such as anonymous voting or roadmap visibility), attachment size limits,
 * app store links, and SDK visual styling.
 *
 * Example:
 * ```kotlin
 * val config = client.fetchAppConfig()
 * if (config.allowAnonymousFeedback) {
 *     // Anonymous users can submit feedback without an explicit user session
 * }
 * println("Max upload: ${config.maxAttachmentBytes / (1024 * 1024)} MB")
 * ```
 *
 * @property appId Globally unique ID of the application.
 * @property appKey Public app key used to authenticate SDK calls.
 * @property slug URL-friendly slug identifier for the application.
 * @property name Human-readable display name of the application.
 * @property storeUrl Direct URL to the app on Google Play or Apple App Store, or `null`.
 * @property storeKind App store ecosystem (e.g. `"play_store"`, `"app_store"`), or `null`.
 * @property iconUrl Hosted icon image URL, or `null`.
 * @property allowPublic Whether the application's public portal is globally enabled.
 * @property allowedPlatforms List of operating system platforms accepted for feedback.
 * @property maxAttachmentBytes Maximum allowed upload size per attachment in bytes (e.g., `10485760` for 10MB).
 * @property allowAnonymousRoadmap Whether the roadmap board can be viewed without a valid user token.
 * @property allowAnonymousVote Whether feature requests can be upvoted by anonymous users.
 * @property allowAnonymousFeedback Whether feedback submissions can be sent anonymously.
 * @property allowAnonymousChangelog Whether changelog release notes can be viewed anonymously.
 * @property sdk Remote theme and surface appearance bundle ([SdkAppearance]).
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
 * Semantic classification of a kanban [BoardColumn].
 *
 * @property wireValue String identifier used in the REST API.
 */
enum class BoardColumnKind(val wireValue: String) {
    /** Column for newly submitted requests awaiting moderator approval (`pending_review`). */
    PENDING_REVIEW("pending_review"),

    /** Standard active development or planning stage (`normal`). */
    NORMAL("normal"),

    /** Terminal status column for completed or closed items (`done`). */
    DONE("done");

    companion object {
        /**
         * Parses a wire string into its corresponding [BoardColumnKind], defaulting to [NORMAL] on mismatch.
         *
         * @param value The wire string representation.
         * @return The matched [BoardColumnKind].
         */
        fun fromWire(value: String): BoardColumnKind =
            entries.firstOrNull { it.wireValue == value } ?: NORMAL
    }
}

/**
 * A column on the public roadmap kanban board, as displayed in [dev.cupthread.feedback.ui.RoadmapBoardScreen].
 *
 * Example:
 * ```kotlin
 * val columns = client.fetchColumns()
 * columns.forEach { col ->
 *     println("Column: ${col.name} (position ${col.position})")
 * }
 * ```
 *
 * @property id Unique column identifier referenced by [FeatureRequestItem.columnId].
 * @property appId Identifier of the owning application.
 * @property name Display name of the column (e.g., `"In Progress"`, `"Planned"`, `"Completed"`).
 * @property slug URL-friendly slug used for stage style heuristics (e.g., `"in-progress"`).
 * @property position Ordering index for display sorting (ascending).
 * @property isVisible Whether this column is visible on the public board.
 * @property isSystem Whether this is an internal system column (such as pending review queue).
 * @property kind Semantic categorization of the column ([BoardColumnKind]).
 * @property color Optional hex color string for column accent tinting (e.g., `"#16A34A"`).
 * @property createdAt ISO 8601 creation timestamp.
 * @property updatedAt ISO 8601 last update timestamp.
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
 * A release milestone or version tag of the app, used to categorize and filter [FeatureRequestItem]s.
 *
 * Example:
 * ```kotlin
 * val versions = client.fetchVersions()
 * val latestVersion = versions.firstOrNull { it.released }
 * println("Latest released version: ${latestVersion?.label}")
 * ```
 *
 * @property id Version identifier used in [FeedbackClient.fetchFeatureRequests].
 * @property appId Identifier of the owning application.
 * @property label Display version string (e.g., `"2.1.0"`).
 * @property position Ordering position (ascending).
 * @property released Whether this version has officially shipped to users.
 * @property releasedAt ISO 8601 release timestamp, or `null` if unreleased.
 * @property description Optional release summary or milestone goal.
 * @property createdAt ISO 8601 creation timestamp.
 * @property updatedAt ISO 8601 last update timestamp.
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
 * A public feature request, as returned by [FeedbackClient.fetchFeatureRequests].
 *
 * Includes live vote counts, the current user's vote state, roadmap column placement,
 * moderation status, and recent commenter avatars.
 *
 * Example:
 * ```kotlin
 * val result = client.fetchFeatureRequests(userToken = token)
 * result.requests.forEach { item ->
 *     println("${item.title} - ${item.voteCount} votes (Voted: ${item.hasVoted})")
 * }
 * ```
 *
 * @property id Unique identifier for the feature request; used in [FeedbackClient.toggleVote] and comments.
 * @property appId Identifier of the owning application.
 * @property title Brief summary of the proposed feature.
 * @property description Detailed description; may contain inline Markdown formatting rendered by [dev.cupthread.feedback.ui.MarkdownText].
 * @property status Workflow status slug (e.g., `"backlog"`, `"planned"`, `"completed"`).
 * @property columnId Associated roadmap [BoardColumn] ID, or `null` if uncategorized.
 * @property columnSlug Slug of the associated roadmap column, or `null`.
 * @property columnName Display name of the associated roadmap column, or `null`.
 * @property columnColor Optional accent color of the associated column.
 * @property versionId Target [AppVersion] ID where this feature is scheduled to ship, or `null`.
 * @property versionLabel Display label of the target release version, or `null`.
 * @property releasedVersion Actual release version label once shipped, or `null`.
 * @property requesterName Display name of the user who submitted the request, or `null`.
 * @property requesterAvatarUrl Hosted avatar image URL of the requester, or `null`.
 * @property requesterClerkId User account ID of the requester if signed in, or `null`.
 * @property approved Whether a moderator has approved this request for public display.
 * @property voteCount Total number of upvotes.
 * @property hasVoted Whether the current user (identified by `userToken`) has upvoted this request.
 * @property isOwnRequest Whether the current user created this request (users cannot vote on their own requests).
 * @property recentCommenters Up to 3 most recent commenters for rendering avatar stacks.
 * @property hasMoreCommenters Whether additional commenters exist beyond [recentCommenters].
 * @property createdAt ISO 8601 creation timestamp.
 * @property updatedAt ISO 8601 last update timestamp.
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
     * Display name of the roadmap stage: returns [columnName] when on the board,
     * otherwise falls back to the raw [status] slug.
     */
    val stageName: String get() = columnName ?: status

    /**
     * Creates a copy of this item with updated vote state.
     *
     * Useful for performing optimistic UI updates before network confirmation
     * and reconciling server state after [FeedbackClient.toggleVote].
     *
     * @param voted Whether the user has voted.
     * @param count The new total vote count.
     * @return An updated [FeatureRequestItem] instance.
     *
     * Example:
     * ```kotlin
     * val optimistic = item.withVoteState(voted = true, count = item.voteCount + 1)
     * ```
     */
    fun withVoteState(voted: Boolean, count: Int): FeatureRequestItem =
        copy(hasVoted = voted, voteCount = count)
}

/**
 * Draft content for submitting a new feature request via [FeedbackClient.submitFeatureRequest].
 *
 * Example:
 * ```kotlin
 * val draft = FeatureRequestDraft(
 *     title = "Add Biometric Authentication",
 *     description = "Support Fingerprint and Face Unlock on login.",
 *     requesterName = "Alex"
 * )
 * val result = client.submitFeatureRequest(draft, userToken = token)
 * ```
 *
 * @property title Concise feature title (minimum 3 characters).
 * @property description Comprehensive rationale and use case (minimum 5 characters).
 * @property requesterName Optional author name to display alongside the request.
 */
data class FeatureRequestDraft(
    val title: String = "",
    val description: String = "",
    val requesterName: String = ""
)

/**
 * Summary of a recent commenter on a [FeatureRequestItem], used in avatar stacks.
 *
 * @property authorName Display name of the commenter, or `null`.
 * @property clerkUserId Unique user ID if the commenter is signed in, or `null`.
 * @property avatarUrl Hosted avatar image URL, or `null`.
 */
data class RecentCommenter(
    val authorName: String? = null,
    val clerkUserId: String? = null,
    val avatarUrl: String? = null
)

/**
 * A comment or reply on a feature request, returned by [FeedbackClient.fetchComments].
 *
 * Example:
 * ```kotlin
 * val comments = client.fetchComments(featureRequestId = "req_123")
 * comments.forEach { comment ->
 *     println("${comment.authorName ?: "Anon"}: ${comment.body}")
 * }
 * ```
 *
 * @property id Unique comment identifier.
 * @property featureRequestId Parent [FeatureRequestItem.id] this comment belongs to.
 * @property authorName Display name of the commenter, or `null`.
 * @property authorEmail Email address of the author, or `null`.
 * @property authorAvatarUrl Hosted avatar image URL, or `null`.
 * @property authorClerkId Unique user ID if signed in, or `null`.
 * @property body Comment content; supports inline Markdown.
 * @property parentId Identifier of the parent comment if this is a reply, or `null`.
 * @property replyToClerkId User ID of the participant being replied to, or `null`.
 * @property replyToAuthorName Name of the participant being replied to, or `null`.
 * @property isHidden Whether this comment was moderated and hidden from public view.
 * @property createdAt ISO 8601 creation timestamp.
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
 * Draft content for creating a new comment or threaded `@reply` via [FeedbackClient.postComment].
 *
 * Example:
 * ```kotlin
 * val comment = CommentDraft(
 *     body = "I would love to see this implemented in the next release!",
 *     authorName = "Jordan",
 *     parentId = parentComment.id,
 *     replyToAuthorName = parentComment.authorName
 * )
 * val created = client.postComment(featureRequestId = "req_123", draft = comment, userToken = token)
 * ```
 *
 * @property body Text content of the comment (minimum 1 character).
 * @property authorName Optional display name of the comment author.
 * @property authorEmail Optional contact email address.
 * @property authorAvatarUrl Optional avatar picture URL.
 * @property parentId ID of the parent comment when creating a reply, or `null` for top-level comments.
 * @property replyToClerkId User ID of the person being replied to, or `null`.
 * @property replyToAuthorName Display name of the person being replied to, or `null`.
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
 * Public user profile information, as returned by [FeedbackClient.fetchUserProfile].
 *
 * @property clerkUserId Unique user identifier.
 * @property displayName User's public display name, or `null`.
 * @property avatarUrl Hosted profile picture URL, or `null`.
 * @property bio Biography or tagline, or `null`.
 * @property websiteUrl Personal website or portfolio link, or `null`.
 * @property hideComments Whether the user has chosen to hide their public comment history.
 * @property createdAt ISO 8601 account creation timestamp.
 * @property updatedAt ISO 8601 last update timestamp.
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
 * Summary of an application owned or associated with a user profile.
 *
 * @property id Unique app identifier.
 * @property name Application display name.
 * @property slug URL-friendly slug.
 * @property iconUrl Hosted app icon URL, or `null`.
 * @property description Short description of the application, or `null`.
 * @property requestCount Total number of public feature requests in this app.
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
 * A user comment displayed on their public profile activity feed.
 *
 * @property id Unique comment ID.
 * @property body Text content of the comment.
 * @property createdAt ISO 8601 creation timestamp.
 * @property featureRequestId ID of the feature request where the comment was posted.
 * @property featureRequestTitle Title of the feature request.
 * @property appId ID of the application owning the feature request.
 * @property appName Display name of the application.
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
 * Combined public profile result returned by [FeedbackClient.fetchUserProfile].
 *
 * @property profile Core user profile metadata ([PublicUserProfile]).
 * @property apps List of public apps associated with the user ([PublicAppSummary]).
 * @property recentComments Recent comments posted by the user across public requests ([UserProfileComment]).
 * @property hideComments Whether comment history is obscured due to user privacy settings.
 */
data class PublicUserProfileResult(
    val profile: PublicUserProfile,
    val apps: List<PublicAppSummary> = emptyList(),
    val recentComments: List<UserProfileComment> = emptyList(),
    val hideComments: Boolean = false
)

/**
 * Result of submitting a feature request via [FeedbackClient.submitFeatureRequest].
 *
 * @property featureRequestId Server-assigned ID for the created feature request.
 * @property pending `true` if the request was held for moderation and is not yet visible on the public board.
 */
data class FeatureRequestSubmissionResult(
    val featureRequestId: String,
    val pending: Boolean
)

/**
 * Result of toggling an upvote on a feature request via [FeedbackClient.toggleVote].
 *
 * @property voted Whether the user is currently upvoting the request after the toggle.
 * @property voteCount The reconciled total vote count on the server after the toggle.
 */
data class VoteResult(
    val voted: Boolean,
    val voteCount: Int
)

/**
 * Paginated list of feature requests returned by [FeedbackClient.fetchFeatureRequests].
 *
 * @property requests The current page of [FeatureRequestItem] objects.
 * @property total Total number of requests matching the query across all pages.
 */
data class ListFeatureRequestsResult(
    val requests: List<FeatureRequestItem>,
    val total: Int
)

/**
 * Feature request referenced by a [ChangelogEntry], highlighting resolved requests.
 *
 * @property id Unique ID of the linked feature request.
 * @property title Headline of the linked feature request.
 */
data class ChangelogLinkedRequest(
    val id: String,
    val title: String
)

/**
 * A published changelog entry or release note, returned by [FeedbackClient.fetchChangelog].
 *
 * Example:
 * ```kotlin
 * val entries = client.fetchChangelog()
 * entries.forEach { entry ->
 *     println("${entry.versionLabel ?: "Update"}: ${entry.title}")
 * }
 * ```
 *
 * @property id Unique changelog entry ID.
 * @property title Headline of the release note.
 * @property body Markdown body containing release highlights and details.
 * @property versionLabel Associated version name (e.g., `"v2.0.0"`), or `null`.
 * @property publishedAt ISO 8601 publication timestamp (entries are sorted newest first).
 * @property linkedRequests List of feature requests closed or shipped as part of this release.
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
 * Result of subscribing an email to product updates via [FeedbackClient.subscribeToChangelog].
 *
 * @property subscribed Whether the email is active in the subscriber list.
 * @property alreadySubscribed Whether the email was already registered previously.
 */
data class ChangelogSubscriptionResult(
    val subscribed: Boolean,
    val alreadySubscribed: Boolean
)

/**
 * Result of removing an email from product update emails via [FeedbackClient.unsubscribeFromChangelog].
 *
 * @property unsubscribed Whether the email was successfully removed from the newsletter list.
 */
data class ChangelogUnsubscribeResult(
    val unsubscribed: Boolean
)

/**
 * Result of updating user telemetry attributes via [FeedbackClient.updateUserAttributes].
 *
 * @property ok Whether the attribute update was accepted by the server.
 * @property updatedAt ISO 8601 timestamp when the attributes were saved.
 */
data class UserAttributesUpdateResult(
    val ok: Boolean,
    val updatedAt: String
)

