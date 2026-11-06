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

    // Guards the read-modify-write cycles on the seen set. Callers may arrive from the UI
    // (auto-mark on overlay presentation) and any host thread (public FeedbackClient API)
    // concurrently; without the lock, two stale snapshots write back disjoint full
    // replacements and one mark is silently lost.
    private val lock = Any()

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
     * Safe to call concurrently: the read-modify-write cycle is serialized.
     *
     * @param versionOrId Version label (e.g. `"2.1.0"`, `"v1.0"`) or unique changelog entry ID.
     */
    fun markChangelogSeen(versionOrId: String) {
        if (versionOrId.isBlank()) return
        synchronized(lock) {
            val current = HashSet(prefs.getStringSet(KEY_SEEN, emptySet()))
            current.add(versionOrId.trim())
            prefs.edit().putStringSet(KEY_SEEN, current).apply()
        }
    }

    /**
     * Marks the changelog entry identified by [versionOrId] as unseen.
     *
     * Safe to call concurrently: the read-modify-write cycle is serialized.
     *
     * @param versionOrId Version label or entry ID to remove from the seen set.
     */
    fun markChangelogUnseen(versionOrId: String) {
        if (versionOrId.isBlank()) return
        synchronized(lock) {
            val current = HashSet(prefs.getStringSet(KEY_SEEN, emptySet()))
            if (current.remove(versionOrId.trim())) {
                prefs.edit().putStringSet(KEY_SEEN, current).apply()
            }
        }
    }

    /**
     * Retrieves a snapshot of the version labels and entry IDs marked as seen.
     *
     * The returned set is a defensive copy: mutating it does not affect the store.
     */
    fun getSeenChangelogs(): Set<String> =
        HashSet(prefs.getStringSet(KEY_SEEN, emptySet()))

    /**
     * Clears all seen changelog records, causing all versions to be considered unseen again.
     *
     * Safe to call concurrently: the mutation is serialized with the other mutators.
     */
    fun clearSeenChangelogs() {
        synchronized(lock) {
            prefs.edit().remove(KEY_SEEN).apply()
        }
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
