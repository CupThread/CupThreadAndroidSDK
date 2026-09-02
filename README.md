# CupThread Android SDK

Native Kotlin + Jetpack Compose SDK for Android 8+ (minSdk 26, Material 3).

Part of the [CupThread.com](https://cupthread.com) platform.

## 🤖 Recommended: Install via AI Agent (Agentic Coding)

Instead of manually editing Gradle build files and writing boilerplate by hand, install the official **CupThread Android AI Skill** into your workspace with [`npx skills`](https://github.com/skills-directory/skills) and let your AI assistant (Claude Code, Cursor, Copilot, Android Studio, Windsurf, Codex, Antigravity) integrate and customize it for you:

```sh
npx skills add CupThread/CupThreadAgenticCoding --skill cupthread-android-sdk
```

Once installed, simply copy and paste this prompt to your AI coding agent:

```text
Integrate the CupThread Android SDK (roadmap, changelog, and feedback screens) into this Compose app. Scaffold a dedicated configuration helper with a placeholder for the App Key, and at the end, remind me with step-by-step instructions on how to set my App Key safely (e.g. via local.properties).
```

---

## CupThread Ecosystem
- 🌐 [CupThread.com](https://cupthread.com) — Feedback SaaS platform, developer console, and API.
- 🍏 [CupThread/CupThreadSwiftSDK](https://github.com/CupThread/CupThreadSwiftSDK) — Apple platform SDK (SwiftUI / SPM / XCFramework).
- 🤖 [CupThread/CupThreadAndroidSDK](https://github.com/CupThread/CupThreadAndroidSDK) — Android SDK (Jetpack Compose / Maven).
- ⚛️ [CupThread/CupThreadReactNativeSDK](https://github.com/CupThread/CupThreadReactNativeSDK) — React Native & Expo SDK (TypeScript).
- 💙 [CupThread/CupThreadFlutterSDK](https://github.com/CupThread/CupThreadFlutterSDK) — Flutter SDK (Dart).
- 🧠 [CupThread/CupThreadAgenticCoding](https://github.com/CupThread/CupThreadAgenticCoding) — AI-friendly CLI & Skills for pair programming.

---

## Manual Installation

Add the CupThread Maven repository and the dependency:

```kotlin
// settings.gradle.kts (or root build.gradle.kts)
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            name = "CupThreadCDN"
            url = uri("https://cdn.cupthread.com/maven")
        }
    }
}

// app/build.gradle.kts
dependencies {
    implementation("dev.cupthread:feedback:0.1.0")
}
```

---

## Quick Start

```kotlin
val client = FeedbackClient(
    FeedbackClientConfig(
        baseUrl = "https://api.cupthread.com",
        appKey = "app_xxx" // from the developer console
    )
)
val userToken = UserTokenStore.create(context).token
```

`FeedbackPlatform.current` is `ANDROID`. The composer pre-fills version name and version code from the host package automatically.

---

## API Reference

The full KDoc-generated API reference is published automatically on every push to `main`:

**[https://cupthread.github.io/CupThreadAndroidSDK/](https://cupthread.github.io/CupThreadAndroidSDK/)**

To generate the docs locally (output in `feedback/build/dokka/html`):

```sh
./gradlew :feedback:dokkaGenerateHtml
```

---

## Ready-Made Compose Screens

Wrap your UI tree in `CupThreadTheme(client)` so console skin settings apply:

- `FeedbackComposer(client, userToken, onSubmit)` / `FeedbackComposerSheet(client, userToken, onDismiss, onSubmit)` — Structured feedback form with attachment uploads and system PhotoPicker.
- `FeatureRequestsScreen(client, userToken)` — Browse, vote (optimistic), and submit requests.
- `RoadmapBoardScreen(client, userToken)` — Column chips + paged lists grouped by public roadmap columns.
- `WhatsNewScreen(client, userToken)` — Changelog list with email subscribe / unsubscribe.
- `ChangelogOverlay(client, visible, onlyIfUnseen, onDismiss)` / `client.presentLatestChangelog(activity, onlyIfUnseen)` — What's-New modal with built-in seen state persistence.

```kotlin
CupThreadTheme(client) {
    RoadmapBoardScreen(client = client, userToken = userToken)
}

// Present latest changelog only if unseen:
lifecycleScope.launch {
    client.presentLatestChangelog(activity, onlyIfUnseen = true)
}
```

---

## API Surface

| Method | Endpoint / Description |
| ------ | ---------------------- |
| `submit(draft, userToken)` | `POST /api/v1/feedback` (sends `X-User-Token`) |
| `uploadAttachment(data, filename, mimeType, preferredKind)` | `POST /api/v1/uploads/{images,r2}` |
| `fetchAppConfig()` | `GET /api/v1/public/config/{appKey}` |
| `prepareChangelogOverlay(context, onlyIfUnseen)` | Config + newest changelog entries with seen filtering |
| `presentLatestChangelog(activity, onlyIfUnseen)` | Presents overlay sheet with automatic seen persistence |
| `fetchFeatureRequests(userToken, limit, offset, versionId, query)` | `GET /api/v1/feature-requests` |
| `submitFeatureRequest(draft, userToken)` | `POST /api/v1/feature-requests` |
| `toggleVote(featureRequestId, userToken)` | `POST /api/v1/feature-requests/{id}/vote` |
| `fetchComments(featureRequestId)` | `GET /api/v1/feature-requests/{id}/comments` |
| `postComment(featureRequestId, draft, userToken)` | `POST /api/v1/feature-requests/{id}/comments` |
| `fetchUserProfile(userId)` | `GET /api/v1/users/{userId}/profile` |
| `fetchColumns()` | `GET /api/v1/public/columns/{appKey}` |
| `fetchVersions()` | `GET /api/v1/public/versions/{appKey}` |
| `fetchChangelog()` | `GET /api/v1/public/apps/{appKey}/changelog` |
| `subscribeToChangelog(email, userToken)` | `POST /api/v1/public/apps/{appKey}/changelog/subscribe` |
| `unsubscribeFromChangelog(email)` | `POST /api/v1/public/apps/{appKey}/changelog/unsubscribe` |
| `updateUserAttributes(userToken, isPaying, plan, mrr, currency)` | `PUT /api/v1/public/apps/{appKey}/user` |
| `ChangelogStore.create(context)` | Persistent store for tracking seen changelog versions |

---

## Development & Testing

```sh
# Run unit tests
./gradlew :feedback:testDebugUnitTest

# Assemble demo app
./gradlew :demo:assembleDebug

# Release a new version (publishes Maven layout, computes sha256, tags & creates release)
node scripts/release.mjs --version 0.1.1
```

## License
MIT
