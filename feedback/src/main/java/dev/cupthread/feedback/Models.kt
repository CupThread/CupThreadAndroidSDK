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
        val current: FeedbackPlatform = ANDROID

        fun fromWire(value: String): FeedbackPlatform? =
            entries.firstOrNull { it.wireValue == value }
    }
}

data class FeedbackClientConfig(
    val baseUrl: String,
    val appKey: String,
    val defaultPlatform: FeedbackPlatform = FeedbackPlatform.current
)

data class FeedbackAttachment(
    val kind: Kind,
    val key: String,
    val url: String,
    val filename: String? = null,
    val mimeType: String? = null,
    val size: Long? = null
) {
    enum class Kind(val wireValue: String) {
        R2("r2"),
        IMAGE("image");

        companion object {
            fun fromWire(value: String): Kind? = entries.firstOrNull { it.wireValue == value }
        }
    }
}

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

data class FeedbackSubmissionResult(
    val submissionId: String,
    val forwardedToGithub: Boolean,
    val githubDiscussionId: String? = null,
    val githubDiscussionUrl: String? = null,
    val warning: String? = null
)

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
        fun fromWire(value: String?): SdkTheme =
            entries.firstOrNull { it.wireValue == value } ?: SYSTEM
    }
}

enum class SdkFeature {
    FEEDBACK,
    FEATURE_REQUESTS,
    ROADMAP,
    CHANGELOG
}

data class SdkFeatures(
    val feedback: Boolean = true,
    val featureRequests: Boolean = true,
    val roadmap: Boolean = true,
    val changelog: Boolean = true
) {
    fun isEnabled(feature: SdkFeature): Boolean = when (feature) {
        SdkFeature.FEEDBACK -> feedback
        SdkFeature.FEATURE_REQUESTS -> featureRequests
        SdkFeature.ROADMAP -> roadmap
        SdkFeature.CHANGELOG -> changelog
    }

    companion object {
        val allEnabled = SdkFeatures()
    }
}

data class ChangelogOverlayConfig(
    val title: String = "What's New",
    val subtitle: String = "",
    val entryCount: Int = 3,
    val primaryButton: String = "Continue",
    val closeButton: String = "Close"
) {
    val clampedEntryCount: Int get() = entryCount.coerceIn(1, 10)
}

data class SdkAppearance(
    val theme: SdkTheme = SdkTheme.SYSTEM,
    val features: SdkFeatures = SdkFeatures.allEnabled,
    val changelogOverlay: ChangelogOverlayConfig = ChangelogOverlayConfig()
) {
    companion object {
        val defaults = SdkAppearance()
    }
}

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

enum class BoardColumnKind(val wireValue: String) {
    PENDING_REVIEW("pending_review"),
    NORMAL("normal"),
    DONE("done");

    companion object {
        fun fromWire(value: String): BoardColumnKind =
            entries.firstOrNull { it.wireValue == value } ?: NORMAL
    }
}

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
    val approved: Boolean,
    val voteCount: Int,
    val hasVoted: Boolean,
    val isOwnRequest: Boolean,
    val createdAt: String,
    val updatedAt: String
) {
    val stageName: String get() = columnName ?: status

    fun withVoteState(voted: Boolean, count: Int): FeatureRequestItem =
        copy(hasVoted = voted, voteCount = count)
}

data class FeatureRequestDraft(
    val title: String = "",
    val description: String = "",
    val requesterName: String = ""
)

data class FeatureRequestSubmissionResult(
    val featureRequestId: String,
    val pending: Boolean
)

data class VoteResult(
    val voted: Boolean,
    val voteCount: Int
)

data class ListFeatureRequestsResult(
    val requests: List<FeatureRequestItem>,
    val total: Int
)

data class ChangelogLinkedRequest(
    val id: String,
    val title: String
)

data class ChangelogEntry(
    val id: String,
    val title: String,
    val body: String,
    val versionLabel: String?,
    val publishedAt: String,
    val linkedRequests: List<ChangelogLinkedRequest>
)

data class ChangelogSubscriptionResult(
    val subscribed: Boolean,
    val alreadySubscribed: Boolean
)

data class ChangelogUnsubscribeResult(
    val unsubscribed: Boolean
)

data class UserAttributesUpdateResult(
    val ok: Boolean,
    val updatedAt: String
)
