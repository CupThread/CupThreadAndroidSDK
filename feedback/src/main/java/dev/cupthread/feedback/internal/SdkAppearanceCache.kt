package dev.cupthread.feedback.internal

import dev.cupthread.feedback.SdkAppearance
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory cache for remote [SdkAppearance] keyed by app key.
 *
 * Preserves the last successfully fetched appearance configuration per app key so that
 * temporary network dropouts or subsequent screen navigations do not fail closed or drop
 * remote feature flags.
 */
internal object SdkAppearanceCache {
    private val cache = ConcurrentHashMap<String, SdkAppearance>()

    fun get(appKey: String): SdkAppearance? = cache[appKey]

    fun put(appKey: String, appearance: SdkAppearance) {
        cache[appKey] = appearance
    }

    fun remove(appKey: String): SdkAppearance? = cache.remove(appKey)

    fun clear() {
        cache.clear()
    }
}
