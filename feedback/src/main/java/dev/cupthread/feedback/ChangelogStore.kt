package dev.cupthread.feedback

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent store for tracking seen changelog and release notes versions backed by [SharedPreferences].
 *
 * Used by [FeedbackClient.prepareChangelogOverlay], [dev.cupthread.feedback.ui.presentLatestChangelog],
 * and [dev.cupthread.feedback.ui.ChangelogOverlay] to filter out release notes that the user
 * has already viewed on this device.
 *
 * ### Example Usage
 * ```kotlin
 * val store = ChangelogStore.create(context)
 * if (!store.hasSeenChangelog("2.1.0")) {
 *     // Show changelog banner or modal
 *     store.markChangelogSeen("2.1.0")
 * }
 * ```
 *
 * @param prefs [SharedPreferences] instance where seen version tokens are stored.
 */
class ChangelogStore internal constructor(private val prefs: SharedPreferences) {

    /**
     * Checks whether the user has already seen a changelog identified by [versionOrId].
     *
     * @param versionOrId Version label (e.g. `"2.1.0"`, `"v1.0"`) or unique changelog entry ID.
     * @return `true` if previously marked as seen, `false` otherwise.
     */
    fun hasSeenChangelog(versionOrId: String): Boolean {
        if (versionOrId.isBlank()) return false
        val set = prefs.getStringSet(KEY_SEEN, emptySet()) ?: emptySet()
        return set.contains(versionOrId.trim())
    }

    /**
     * Marks the changelog entry identified by [versionOrId] as seen.
     *
     * @param versionOrId Version label (e.g. `"2.1.0"`, `"v1.0"`) or unique changelog entry ID.
     */
    fun markChangelogSeen(versionOrId: String) {
        if (versionOrId.isBlank()) return
        val current = prefs.getStringSet(KEY_SEEN, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(versionOrId.trim())
        prefs.edit().putStringSet(KEY_SEEN, current).apply()
    }

    /**
     * Marks the changelog entry identified by [versionOrId] as unseen.
     *
     * @param versionOrId Version label or entry ID to remove from the seen set.
     */
    fun markChangelogUnseen(versionOrId: String) {
        if (versionOrId.isBlank()) return
        val current = prefs.getStringSet(KEY_SEEN, emptySet())?.toMutableSet() ?: return
        if (current.remove(versionOrId.trim())) {
            prefs.edit().putStringSet(KEY_SEEN, current).apply()
        }
    }

    /**
     * Retrieves the complete set of version labels and entry IDs marked as seen.
     */
    fun getSeenChangelogs(): Set<String> =
        prefs.getStringSet(KEY_SEEN, emptySet()) ?: emptySet()

    /**
     * Clears all seen changelog records, causing all versions to be considered unseen again.
     */
    fun clearSeenChangelogs() {
        prefs.edit().remove(KEY_SEEN).apply()
    }

    companion object {
        private const val PREFS = "cupthread_feedback"
        private const val KEY_SEEN = "seen_changelogs"

        /**
         * Factory method to create a [ChangelogStore] using application context private preferences.
         *
         * @param context An Android [Context] (application context is automatically extracted).
         * @return A configured [ChangelogStore] instance.
         */
        @JvmStatic
        fun create(context: Context): ChangelogStore =
            ChangelogStore(
                context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            )
    }
}
