package dev.cupthread.demo

import dev.cupthread.feedback.FeedbackClient
import dev.cupthread.feedback.FeedbackClientConfig
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest

/**
 * Mock data fixtures and a local HTTP dispatcher for automated UI screenshot tests.
 */
object DemoMockData {

    fun createMockClient(baseUrl: String): FeedbackClient {
        return FeedbackClient(
            config = FeedbackClientConfig(
                baseUrl = baseUrl,
                appKey = "app_demo_123"
            )
        )
    }

    const val APP_CONFIG_JSON = """
    {
      "appId": "app_demo_1",
      "appKey": "app_demo_placeholder",
      "slug": "cupthread-demo",
      "name": "CupThread Demo",
      "storeUrl": "https://play.google.com",
      "storeKind": "google_play",
      "iconUrl": "https://cupthread.com/icon.png",
      "allowPublic": true,
      "allowedPlatforms": ["android", "ios", "macos", "web"],
      "maxAttachmentBytes": 20000000,
      "allowAnonymousRoadmap": true,
      "allowAnonymousVote": true,
      "allowAnonymousFeedback": true,
      "allowAnonymousChangelog": true,
      "sdk": {
        "theme": "system",
        "features": {
          "roadmap": true,
          "featureRequests": true,
          "changelog": true,
          "feedback": true
        },
        "changelogOverlay": {
          "title": "What's New in v2.4.0",
          "subtitle": "Discover the latest improvements and features in CupThread.",
          "primaryButton": "Got It",
          "closeButton": "Close",
          "entryCount": 3
        }
      }
    }
    """

    const val COLUMNS_JSON = """
    {
      "columns": [
        {
          "id": "col_planned",
          "appId": "app_demo_1",
          "name": "Planned",
          "slug": "planned",
          "position": 1,
          "isVisible": true,
          "isSystem": false,
          "kind": "normal",
          "createdAt": "2026-01-01T00:00:00Z",
          "updatedAt": "2026-01-01T00:00:00Z"
        },
        {
          "id": "col_in_progress",
          "appId": "app_demo_1",
          "name": "In Progress",
          "slug": "in-progress",
          "position": 2,
          "isVisible": true,
          "isSystem": false,
          "kind": "normal",
          "createdAt": "2026-01-01T00:00:00Z",
          "updatedAt": "2026-01-01T00:00:00Z"
        },
        {
          "id": "col_completed",
          "appId": "app_demo_1",
          "name": "Completed",
          "slug": "completed",
          "position": 3,
          "isVisible": true,
          "isSystem": true,
          "kind": "done",
          "createdAt": "2026-01-01T00:00:00Z",
          "updatedAt": "2026-01-01T00:00:00Z"
        }
      ]
    }
    """

    const val VERSIONS_JSON = """
    {
      "versions": [
        {
          "id": "ver_2_4_0",
          "appId": "app_demo_1",
          "label": "v2.4.0",
          "position": 1,
          "released": true,
          "releasedAt": "2026-08-20T10:00:00Z",
          "description": "Material 3 design updates and performance optimizations",
          "createdAt": "2026-08-01T00:00:00Z",
          "updatedAt": "2026-08-20T10:00:00Z"
        },
        {
          "id": "ver_2_5_0",
          "appId": "app_demo_1",
          "label": "v2.5.0",
          "position": 2,
          "released": false,
          "description": "Home screen widgets and offline draft synchronization",
          "createdAt": "2026-08-15T00:00:00Z",
          "updatedAt": "2026-08-15T00:00:00Z"
        }
      ]
    }
    """

    const val FEATURE_REQUESTS_JSON = """
    {
      "requests": [
        {
          "id": "req_1",
          "appId": "app_demo_1",
          "title": "Interactive Lock & Home Screen Widgets",
          "description": "Add Android Glance/AppWidget support to display roadmap progression and upvote proposals directly from home screen.",
          "status": "in-progress",
          "columnId": "col_in_progress",
          "columnSlug": "in-progress",
          "columnName": "In Progress",
          "versionId": "ver_2_5_0",
          "versionLabel": "v2.5.0",
          "requesterName": "Sarah Connor",
          "requesterAvatarUrl": "https://images.unsplash.com/photo-1494790108377-be9c29b29330?w=128&h=128&fit=crop",
          "recentCommenters": [
            { "authorName": "David Miller", "avatarUrl": "https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=128&h=128&fit=crop" },
            { "authorName": "Elena Rostova", "avatarUrl": "https://images.unsplash.com/photo-1438761681033-6461ffad8d80?w=128&h=128&fit=crop" }
          ],
          "hasMoreCommenters": true,
          "approved": true,
          "voteCount": 142,
          "hasVoted": true,
          "isOwnRequest": false,
          "createdAt": "2026-08-15T08:30:00Z",
          "updatedAt": "2026-08-25T14:20:00Z"
        },
        {
          "id": "req_2",
          "appId": "app_demo_1",
          "title": "Offline Draft Caching & Automatic Sync",
          "description": "Allow drafting feedback offline with background synchronization once network is restored.",
          "status": "in-progress",
          "columnId": "col_in_progress",
          "columnSlug": "in-progress",
          "columnName": "In Progress",
          "versionId": "ver_2_5_0",
          "versionLabel": "v2.5.0",
          "requesterName": "Marcus Vance",
          "requesterAvatarUrl": "https://images.unsplash.com/photo-1500648767791-00dcc994a43e?w=128&h=128&fit=crop",
          "recentCommenters": [],
          "hasMoreCommenters": false,
          "approved": true,
          "voteCount": 89,
          "hasVoted": false,
          "isOwnRequest": false,
          "createdAt": "2026-08-16T11:00:00Z",
          "updatedAt": "2026-08-24T09:15:00Z"
        },
        {
          "id": "req_3",
          "appId": "app_demo_1",
          "title": "Dark Mode & Dynamic Material You Accent",
          "description": "Support Monet theme palettes based on Android 12+ wallpaper accents.",
          "status": "planned",
          "columnId": "col_planned",
          "columnSlug": "planned",
          "columnName": "Planned",
          "versionId": "ver_2_4_0",
          "versionLabel": "v2.4.0",
          "requesterName": "Claire Redfield",
          "requesterAvatarUrl": "https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=128&h=128&fit=crop",
          "recentCommenters": [
            { "authorName": "Chris Redfield", "avatarUrl": "https://images.unsplash.com/photo-1522075469751-3a6694fb2f61?w=128&h=128&fit=crop" }
          ],
          "hasMoreCommenters": false,
          "approved": true,
          "voteCount": 96,
          "hasVoted": true,
          "isOwnRequest": false,
          "createdAt": "2026-08-10T15:20:00Z",
          "updatedAt": "2026-08-20T10:00:00Z"
        }
      ]
    }
    """

    const val CHANGELOG_JSON = """
    {
      "entries": [
        {
          "id": "cl_240",
          "appId": "app_demo_1",
          "versionLabel": "v2.4.0",
          "title": "Material 3 Design & Speed Improvements",
          "body": "## What's New\n- **Dynamic Colors**: Support Android 12+ wallpaper Monet theme palettes.\n- **Optimized Compose Pipeline**: Smoother 120Hz scrolling and reduced jank.\n- **Attachment Previews**: Inline image thumbnails and PhotoPicker integration.\n\n```kotlin\nCupThreadTheme(client) { ... }\n```",
          "publishedAt": "2026-08-20T10:00:00Z",
          "pinned": true,
          "isDraft": false,
          "sendNotification": true,
          "targetPlatforms": ["android"],
          "linkedRequests": [
            {
              "id": "req_3",
              "title": "Dark Mode & Dynamic Material You Accent",
              "voteCount": 96,
              "columnName": "Completed"
            }
          ]
        }
      ]
    }
    """
    fun dispatcher(): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            return when {
                path.contains("/api/v1/public/config/") -> json(APP_CONFIG_JSON)
                path.contains("/api/v1/public/columns/") -> json(COLUMNS_JSON)
                path.contains("/api/v1/public/versions/") -> json(VERSIONS_JSON)
                path.contains("/vote") -> json("""{"id":"req_1","voted":true,"voteCount":143}""")
                path.contains("/api/v1/feature-requests") -> {
                    if (request.method == "POST") {
                        json("""{"id":"req_new","title":"New Request","status":"planned","voteCount":1,"hasVoted":true,"approved":true}""", 201)
                    } else {
                        json(FEATURE_REQUESTS_JSON)
                    }
                }
                path.contains("/changelog/subscribe") -> json("""{"ok":true}""")
                path.contains("/changelog/unsubscribe") -> json("""{"ok":true}""")
                path.contains("/changelog") -> json(CHANGELOG_JSON)
                path.contains("/api/v1/feedback") -> json("""{"submissionId":"sub_123","createdAt":"2026-08-20T10:00:00Z","status":"received"}""")
                path.contains("/api/v1/uploads/") -> json("""{"key":"img_123","filename":"screenshot.png","size":1240000,"mimeType":"image/png","url":"https://cdn.cupthread.com/img_123"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private fun json(body: String, code: Int = 200): MockResponse = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}
