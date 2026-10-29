package dev.cupthread.feedback

/**
 * Server-aligned Cloudflare Turnstile `action` values for the Turnstile-gated intake endpoints.
 *
 * Render the widget (or mint a token server-side) with one of these actions and the app key as
 * `cdata` — e.g. `turnstile.render({ action: "feedback", cdata: appKey })`. Tokens minted without
 * a binding, or under a different action, are rejected by the server's verification gate, so a
 * `feature-request`-minted token cannot be spent on feedback or upload intake, and vice versa.
 */
object TurnstileAction {
    /**
     * Binding shared by `POST /api/v1/feedback` and `POST /api/v1/uploads/sessions`.
     *
     * Upload sessions intentionally reuse the feedback action: the server pins the upload-session
     * gate to the same `(action, cdata)` binding as feedback intake, so one binding covers a
     * submission's attachments and its intake alike.
     */
    const val FEEDBACK: String = "feedback"

    /** Binding for `POST /api/v1/feature-requests`. */
    const val FEATURE_REQUEST: String = "feature-request"

    /** Binding for `POST /api/v1/feature-requests/{id}/vote`. */
    const val VOTE: String = "vote"

    /**
     * Binding for the ship-notification opt-in carried on
     * `POST /api/v1/feature-requests/{id}/vote` with `shipNotifyEmail`.
     */
    const val SHIP_NOTIFY: String = "ship-notify"
}

/**
 * The Turnstile widget binding `(action, cdata)` a gated intake endpoint validates tokens against.
 *
 * Every Turnstile siteverify call is bound to the `(action, cdata)` pair the widget was rendered
 * with, and a token minted under a different binding — or with no binding at all — fails
 * verification. [FeedbackClient] passes one of these to the configured
 * [FeedbackClientConfig.turnstileTokenProvider] on every consultation, so the host can render (or
 * server-mint) a token under the exact binding the endpoint validates:
 *
 * | Endpoint | `action` | `cdata` |
 * | --- | --- | --- |
 * | `POST /api/v1/feedback` | [TurnstileAction.FEEDBACK] | app key |
 * | `POST /api/v1/uploads/sessions` | [TurnstileAction.FEEDBACK] | app key |
 * | `POST /api/v1/feature-requests` | [TurnstileAction.FEATURE_REQUEST] | app key |
 *
 * Tokens are single-use with a short validity window, so every gated call presents its own freshly
 * minted token; [TurnstileTokenProvider] implementations must mint (or render a challenge for) a
 * new token on every invocation.
 *
 * @property action The Cloudflare widget `action` the server binds verification to.
 * @property cdata The Cloudflare widget `cdata` the server binds verification to — always the client's app key.
 */
data class TurnstileChallenge(
    val action: String,
    val cdata: String
) {
    /**
     * The binding for feedback intake (`POST /api/v1/feedback`) and attachment upload-session
     * creation (`POST /api/v1/uploads/sessions`).
     */
    companion object {
        /**
         * The binding for feedback intake (`POST /api/v1/feedback`) and attachment upload-session
         * creation (`POST /api/v1/uploads/sessions`).
         */
        fun feedback(appKey: String): TurnstileChallenge =
            TurnstileChallenge(TurnstileAction.FEEDBACK, appKey)

        /** The binding for feature-request submission (`POST /api/v1/feature-requests`). */
        fun featureRequest(appKey: String): TurnstileChallenge =
            TurnstileChallenge(TurnstileAction.FEATURE_REQUEST, appKey)
    }
}

/**
 * Host-supplied factory the SDK consults for a Cloudflare Turnstile token before a gated intake
 * call (`POST /api/v1/feedback`, `POST /api/v1/feature-requests`, `POST /api/v1/uploads/sessions`).
 *
 * Production deployments with `TURNSTILE_SECRET_KEY` configured reject these calls with HTTP 403
 * unless the request carries a valid `turnstileToken`. Install a provider on
 * [FeedbackClientConfig.turnstileTokenProvider] so submissions succeed:
 *
 * ```kotlin
 * val config = FeedbackClientConfig(
 *     baseUrl = "https://api.cupthread.com",
 *     appKey = "app_live_yourConsoleAppKey",
 *     turnstileTokenProvider = TurnstileTokenProvider { challenge ->
 *         myTurnstileRuntime.solve(challenge.action, challenge.cdata)
 *     }
 * )
 * ```
 *
 * The returned token may be `null` (or blank) when no token can be minted right now — the SDK
 * then sends the request without one, which the server answers with
 * [FeedbackException.VerificationRequired] while the gate is active. Implementations should mint
 * a fresh token per invocation: tokens are single-use and the SDK consults the provider again
 * when the server rejects the first attempt and it retries once.
 */
fun interface TurnstileTokenProvider {
    /**
     * Resolves a Cloudflare Turnstile token for the gated call described by [challenge].
     *
     * @param challenge The `(action, cdata)` widget binding the endpoint validates against.
     * @return A freshly minted Turnstile token, or `null` when none is available.
     */
    suspend fun token(challenge: TurnstileChallenge): String?
}
