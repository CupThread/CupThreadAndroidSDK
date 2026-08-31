package dev.cupthread.feedback

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * Persists a stable anonymous user token across launches.
 * Used to track vote state and own pending requests without requiring sign-in.
 *
 * Create one per app process via [create] and pass [token] wherever the SDK
 * asks for a `userToken`. Reading [token] on the very first call writes to
 * `SharedPreferences`; from then on it returns the cached value.
 *
 * ```kotlin
 * val userToken = UserTokenStore.create(context).token
 * FeatureRequestsScreen(client = client, userToken = userToken)
 * ```
 */
class UserTokenStore internal constructor(private val prefs: SharedPreferences) {
    /**
     * The stable anonymous token. Creates and persists a random UUID on first
     * access, then keeps returning the same value for the lifetime of the
     * app installation.
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
         * Creates a store backed by the application's default
         * [SharedPreferences], so the token survives process death.
         */
        @JvmStatic
        fun create(context: Context): UserTokenStore =
            UserTokenStore(
                context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            )
    }
}
