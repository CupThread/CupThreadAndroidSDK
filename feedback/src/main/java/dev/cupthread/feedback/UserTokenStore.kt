package dev.cupthread.feedback

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * Persists a stable anonymous user token across launches.
 * Used to track vote state and own pending requests without requiring sign-in.
 */
class UserTokenStore internal constructor(private val prefs: SharedPreferences) {
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

        @JvmStatic
        fun create(context: Context): UserTokenStore =
            UserTokenStore(
                context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            )
    }
}
