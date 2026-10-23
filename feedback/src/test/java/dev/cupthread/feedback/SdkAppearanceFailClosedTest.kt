package dev.cupthread.feedback

import dev.cupthread.feedback.internal.HttpResponse
import dev.cupthread.feedback.internal.SdkAppearanceCache
import dev.cupthread.feedback.ui.SdkAppearanceState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Verifies that remote SDK appearance and feature gates fail closed when remote configuration
 * cannot be retrieved, preventing console kill-switch regressions (issue #22).
 */
class SdkAppearanceFailClosedTest {

    private val appKey = "app_test_fail_closed"
    private val clientConfig = FeedbackClientConfig(baseUrl = "https://api.example.com", appKey = appKey)

    @Before
    @After
    fun cleanCache() {
        SdkAppearanceCache.clear()
    }

    @Test
    fun sdkFeaturesAllDisabledHasAllFlagsFalse() {
        val disabled = SdkFeatures.allDisabled
        assertFalse(disabled.feedback)
        assertFalse(disabled.featureRequests)
        assertFalse(disabled.roadmap)
        assertFalse(disabled.changelog)

        assertFalse(disabled.isEnabled(SdkFeature.FEEDBACK))
        assertFalse(disabled.isEnabled(SdkFeature.FEATURE_REQUESTS))
        assertFalse(disabled.isEnabled(SdkFeature.ROADMAP))
        assertFalse(disabled.isEnabled(SdkFeature.CHANGELOG))
    }

    @Test
    fun sdkAppearanceFailClosedHasAllFeaturesDisabled() {
        val appearance = SdkAppearance.failClosed
        assertFalse(appearance.features.isEnabled(SdkFeature.FEEDBACK))
        assertFalse(appearance.features.isEnabled(SdkFeature.FEATURE_REQUESTS))
        assertFalse(appearance.features.isEnabled(SdkFeature.ROADMAP))
        assertFalse(appearance.features.isEnabled(SdkFeature.CHANGELOG))
    }

    @Test
    fun sdkAppearanceStateErrorWithoutCacheResolvesToFailClosed() {
        val errorState = SdkAppearanceState.Error(
            error = RuntimeException("Connection failed"),
            cachedAppearance = null
        )
        val appearance = errorState.appearance
        assertEquals(SdkAppearance.failClosed, appearance)
        assertFalse(appearance.features.isEnabled(SdkFeature.FEEDBACK))
        assertFalse(appearance.features.isEnabled(SdkFeature.FEATURE_REQUESTS))
        assertFalse(appearance.features.isEnabled(SdkFeature.ROADMAP))
        assertFalse(appearance.features.isEnabled(SdkFeature.CHANGELOG))
    }

    @Test
    fun sdkAppearanceStateErrorWithCachePreservesLastGoodAppearance() {
        val lastGood = SdkAppearance(
            theme = SdkTheme.OCEAN,
            features = SdkFeatures(feedback = true, featureRequests = false, roadmap = true, changelog = false)
        )
        val errorState = SdkAppearanceState.Error(
            error = RuntimeException("503 Service Unavailable"),
            cachedAppearance = lastGood
        )
        val appearance = errorState.appearance
        assertEquals(lastGood, appearance)
        assertEquals(SdkTheme.OCEAN, appearance.theme)
        assertTrue(appearance.features.isEnabled(SdkFeature.FEEDBACK))
        assertFalse(appearance.features.isEnabled(SdkFeature.FEATURE_REQUESTS))
        assertTrue(appearance.features.isEnabled(SdkFeature.ROADMAP))
        assertFalse(appearance.features.isEnabled(SdkFeature.CHANGELOG))
    }

    @Test
    fun sdkAppearanceStateLoadingWithoutCacheResolvesToFailClosed() {
        val loadingState = SdkAppearanceState.Loading(cachedAppearance = null)
        val appearance = loadingState.appearance
        assertEquals(SdkAppearance.failClosed, appearance)
        assertFalse(appearance.features.isEnabled(SdkFeature.FEEDBACK))
    }

    @Test
    fun sdkAppearanceStateLoadingWithCachePreservesCachedAppearance() {
        val cached = SdkAppearance(
            theme = SdkTheme.SUNSET,
            features = SdkFeatures(feedback = true, featureRequests = true, roadmap = false, changelog = true)
        )
        val loadingState = SdkAppearanceState.Loading(cachedAppearance = cached)
        val appearance = loadingState.appearance
        assertEquals(cached, appearance)
        assertEquals(SdkTheme.SUNSET, appearance.theme)
        assertFalse(appearance.features.isEnabled(SdkFeature.ROADMAP))
    }

    @Test
    fun sdkAppearanceCacheStoresAndRetrievesAppearancePerAppKey() {
        assertNull(SdkAppearanceCache.get(appKey))

        val customAppearance = SdkAppearance(
            theme = SdkTheme.FOREST,
            features = SdkFeatures(feedback = false, featureRequests = false, roadmap = false, changelog = true)
        )
        SdkAppearanceCache.put(appKey, customAppearance)
        assertEquals(customAppearance, SdkAppearanceCache.get(appKey))

        // Different app key does not collide
        assertNull(SdkAppearanceCache.get("other_app_key"))

        SdkAppearanceCache.remove(appKey)
        assertNull(SdkAppearanceCache.get(appKey))
    }

    @Test
    fun fetchAppConfigAutomaticallyPopulatesSdkAppearanceCache() = runTest {
        val json = """
            {
              "appId": "app_1",
              "appKey": "$appKey",
              "slug": "sample",
              "name": "Sample App",
              "allowAnonymousFeedback": true,
              "allowAnonymousVote": true,
              "maxAttachmentBytes": 10485760,
              "sdk": {
                "theme": "ocean",
                "features": {
                  "feedback": true,
                  "featureRequests": false,
                  "roadmap": true,
                  "changelog": false
                },
                "changelogOverlay": {
                  "title": "Latest Updates",
                  "subtitle": "Discover what is new",
                  "entryCount": 5
                }
              }
            }
        """.trimIndent()

        val client = FeedbackClient(clientConfig, transport = { HttpResponse(200, json) })
        assertNull(SdkAppearanceCache.get(appKey))

        val config = client.fetchAppConfig()
        val cached = SdkAppearanceCache.get(appKey)
        assertNotNull(cached)
        assertEquals(config.sdk, cached)
        assertEquals(SdkTheme.OCEAN, cached?.theme)
        assertTrue(cached?.features?.feedback == true)
        assertFalse(cached?.features?.featureRequests == true)
        assertTrue(cached?.features?.roadmap == true)
        assertFalse(cached?.features?.changelog == true)
        assertEquals("Latest Updates", cached?.changelogOverlay?.title)
    }

    @Test
    fun fetchAppConfigFailureKeepsPriorCacheIntact() = runTest {
        val priorAppearance = SdkAppearance(
            theme = SdkTheme.MIDNIGHT,
            features = SdkFeatures(feedback = false, featureRequests = true, roadmap = false, changelog = false)
        )
        SdkAppearanceCache.put(appKey, priorAppearance)

        val failingClient = FeedbackClient(
            clientConfig,
            transport = { HttpResponse(500, """{"error":"Internal server error"}""") }
        )

        try {
            failingClient.fetchAppConfig()
            fail("Expected fetchAppConfig to throw")
        } catch (_: FeedbackException.UnexpectedStatus) {
            // Failure should not clear prior cache
            val cached = SdkAppearanceCache.get(appKey)
            assertEquals(priorAppearance, cached)
            assertFalse(cached?.features?.isEnabled(SdkFeature.FEEDBACK) == true)
            assertTrue(cached?.features?.isEnabled(SdkFeature.FEATURE_REQUESTS) == true)
        }
    }
}
