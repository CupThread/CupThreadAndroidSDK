package dev.cupthread.feedback.internal

import dev.cupthread.feedback.SdkAppearance
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory cache for remote [SdkAppearance] keyed by base URL + app key (issue #145).
 *
 * [FeedbackClientConfig.baseUrl] is part of client identity, so two deployments sharing one
 * app key (staging/production) must never see each other's feature gates or theme. Entries
 * preserve the last successfully fetched appearance configuration per `(base URL, app key)`
 * pair so that temporary network dropouts or subsequent screen navigations do not fail
 * closed or drop remote feature flags.
 */
internal object SdkAppearanceCache {
    private val cache = ConcurrentHashMap<String, SdkAppearance>()

    /**
     * Cache key for a client identity: normalized base URL + app key.
     *
     * Trailing slashes and case differences in the host are normalized so
     * `https://api.example.com/` and `https://API.example.com` share one entry.
     */
    internal fun cacheKey(baseUrl: String, appKey: String): String =
        baseUrl.trim().trimEnd('/').lowercase() + '|' + appKey

    fun get(baseUrl: String, appKey: String): SdkAppearance? = cache[cacheKey(baseUrl, appKey)]

    fun put(baseUrl: String, appKey: String, appearance: SdkAppearance) {
        cache[cacheKey(baseUrl, appKey)] = appearance
    }

    fun remove(baseUrl: String, appKey: String): SdkAppearance? =
        cache.remove(cacheKey(baseUrl, appKey))

    fun clear() {
        cache.clear()
    }
}
