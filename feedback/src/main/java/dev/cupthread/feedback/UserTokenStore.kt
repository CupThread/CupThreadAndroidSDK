package dev.cupthread.feedback

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * Persistent anonymous device token store backed by Android [SharedPreferences].
 *
 * Provides a stable, pseudonymous UUID token for the lifetime of an app installation.
 * This token is used across CupThread SDK endpoints to:
 * - Persist upvote state across app launches ([FeedbackClient.toggleVote])
 * - Identify and highlight feature requests created by the user ([FeedbackClient.submitFeatureRequest], [FeatureRequestItem.isOwnRequest])
 * - Authorize anonymous comments and subscription actions without requiring third-party authentication
 *
 * ### Lifecycle and Storage
 * - Generates a new random RFC 4122 UUID v4 on first access and immediately persists it into private `SharedPreferences`.
 * - Subsequent reads reuse the cached token, surviving activity recreations, process death, and app updates.
 * - The token resets only when the user clears app storage or uninstalls the application.
 *
 * ### Recommended Usage
 * Create a single instance per application process (e.g. via dependency injection or in your `Application` / `ComponentActivity`):
 *
 * ```kotlin
 * class MainActivity : ComponentActivity() {
 *     private val userTokenStore by lazy { UserTokenStore.create(this) }
 *     private val feedbackClient by lazy {
 *         FeedbackClient(FeedbackClientConfig(baseUrl = "https://api.cupthread.com", appKey = "app_live_sample"))
 *     }
 *
 *     override fun onCreate(savedInstanceState: Bundle?) {
 *         super.onCreate(savedInstanceState)
 *         val token = userTokenStore.token
 *         setContent {
 *             FeatureRequestsScreen(client = feedbackClient, userToken = token)
 *         }
 *     }
 * }
 * ```
 *
 * @param prefs [SharedPreferences] instance where the token key is stored.
 */
class UserTokenStore internal constructor(private val prefs: SharedPreferences) {
    /**
     * The stable anonymous user token.
     *
     * Lazily generates a unique UUID string on the first access, stores it to disk, and returns
     * the persisted value on all subsequent accesses.
     *
     * Example:
     * ```kotlin
     * val store = UserTokenStore.create(context)
     * val userToken = store.token // e.g. "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"
     * ```
     */
    val token: String
        get() {
            val existing = prefs.getString(KEY, null)
            if (!existing.isNullOrBlank()) return existing
            val created = UUID.randomUUID().toString()
            prefs.edit().putString(KEY, created).apply()
            return created
        }

    companion object {
        private const val PREFS = "cupthread_feedback"
        private const val KEY = "user_token"

        /**
         * Factory method to create a [UserTokenStore] using the application context's private preferences.
         *
         * @param context An Android [Context] (e.g., [android.app.Application] or [android.app.Activity]).
         *   The application context is automatically extracted to prevent memory leaks.
         * @return A configured [UserTokenStore] instance.
         *
         * Example:
         * ```kotlin
         * val store = UserTokenStore.create(applicationContext)
         * ```
         */
        @JvmStatic
        fun create(context: Context): UserTokenStore =
            UserTokenStore(
                context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            )
    }
}
