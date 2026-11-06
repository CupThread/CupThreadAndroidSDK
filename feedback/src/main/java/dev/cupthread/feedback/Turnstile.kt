package dev.cupthread.feedback

import android.webkit.JavascriptInterface
import java.net.URI
import java.net.URLEncoder
import org.json.JSONObject

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
 * | `POST /api/v1/feature-requests/{id}/vote` | [TurnstileAction.VOTE] | app key |
 * | `POST /api/v1/feature-requests/{id}/vote` (with `shipNotifyEmail`) | [TurnstileAction.SHIP_NOTIFY] | app key |
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
     * Builds the hosted Turnstile challenge page URL for this challenge and the given [siteKey].
     *
     * @param siteKey The public Turnstile widget site key from [PublicAppConfig.turnstile].
     * @param theme Optional widget color theme: `"light"`, `"dark"`, or `"auto"`.
     * @param locale Optional locale tag (e.g. `"en"`, `"zh-CN"`, `"ja"`).
     * @param portalBaseUrl The base URL of the portal; defaults to [TurnstileHostedChallenge.DEFAULT_PORTAL_BASE_URL].
     * @return Formatted challenge page URL ready to be loaded by a WebView.
     */
    fun hostedUrl(
        siteKey: String,
        theme: String? = null,
        locale: String? = null,
        portalBaseUrl: String = TurnstileHostedChallenge.DEFAULT_PORTAL_BASE_URL
    ): String = TurnstileHostedChallenge.buildChallengeUrl(
        siteKey = siteKey,
        challenge = this,
        theme = theme,
        locale = locale,
        portalBaseUrl = portalBaseUrl
    )

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

        /** The binding for feature-request voting (`POST /api/v1/feature-requests/{id}/vote`). */
        fun vote(appKey: String): TurnstileChallenge =
            TurnstileChallenge(TurnstileAction.VOTE, appKey)

        /**
         * The binding for the ship-notification opt-in on
         * `POST /api/v1/feature-requests/{id}/vote` with `shipNotifyEmail`.
         */
        fun shipNotify(appKey: String): TurnstileChallenge =
            TurnstileChallenge(TurnstileAction.SHIP_NOTIFY, appKey)
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

/**
 * Messages dispatched by the hosted Turnstile challenge page (`/turnstile/verify`)
 * across the native bridge (`window.AndroidTurnstileBridge.postMessage`).
 *
 * The cross-SDK contract specifies three message shapes:
 * - `{status: "success", token: "..."}`: The challenge succeeded; token is single-use (~300 s validity).
 * - `{status: "error", code: "..."}`: The widget failed client-side (e.g. domain not allowlisted `110200`).
 * - `{status: "expired"}`: The minted token expired before submission.
 */
sealed interface TurnstileBridgeMessage {
    /**
     * The widget minted a valid, single-use Cloudflare Turnstile token.
     *
     * @property token The raw Turnstile verification token string.
     */
    data class Success(val token: String) : TurnstileBridgeMessage

    /**
     * The widget failed client-side or encountered an error.
     *
     * @property code Raw client-side widget error code (e.g. `"110200"` for domain allowlist failure)
     *   or synthetic error indicator, or `null` if omitted.
     */
    data class Error(val code: String? = null) : TurnstileBridgeMessage

    /**
     * A previously minted challenge token expired before it was consumed.
     */
    object Expired : TurnstileBridgeMessage

    companion object {
        /**
         * Parses a JSON string dispatched by the challenge page into a [TurnstileBridgeMessage].
         *
         * Returns `null` if [payloadJson] is null, blank, not valid JSON, or missing a recognized `status`.
         */
        fun parse(payloadJson: String?): TurnstileBridgeMessage? {
            if (payloadJson.isNullOrBlank()) return null
            return try {
                val json = JSONObject(payloadJson)
                when (json.optString("status")) {
                    "success" -> {
                        val token = json.optString("token").trim().ifEmpty { null }
                        if (token != null) Success(token) else null
                    }
                    "error" -> {
                        val code = if (json.has("code") && !json.isNull("code")) {
                            json.opt("code")?.toString()?.trim()?.ifEmpty { null }
                        } else null
                        Error(code)
                    }
                    "expired" -> Expired
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * JavaScript interface exposed to the WebView under the name `"AndroidTurnstileBridge"`
 * (`window.AndroidTurnstileBridge.postMessage(JSON)` and `window.AndroidTurnstileBridge.onToken(token)`).
 *
 * Implements the cross-SDK native bridge contract for hosted Turnstile challenge pages
 * (issue #71 / SaaS #1086).
 *
 * Register it on an Android [android.webkit.WebView] via:
 * ```kotlin
 * webView.settings.javaScriptEnabled = true
 * webView.addJavascriptInterface(
 *     AndroidTurnstileBridge { message ->
 *         when (message) {
 *             is TurnstileBridgeMessage.Success -> handleToken(message.token)
 *             is TurnstileBridgeMessage.Error -> handleWidgetError(message.code)
 *             is TurnstileBridgeMessage.Expired -> reloadChallenge()
 *         }
 *     },
 *     AndroidTurnstileBridge.BRIDGE_NAME
 * )
 * ```
 *
 * @param onMessage Callback invoked when a message is received from the page.
 */
class AndroidTurnstileBridge(
    private val onMessage: (TurnstileBridgeMessage) -> Unit
) {
    companion object {
        /**
         * The standard window property name under which this bridge must be registered in the WebView.
         * The hosted challenge page accesses `window.AndroidTurnstileBridge.postMessage(...)`.
         */
        const val BRIDGE_NAME: String = "AndroidTurnstileBridge"
    }

    /**
     * Primary message entry point invoked by the hosted challenge page:
     * `window.AndroidTurnstileBridge.postMessage(JSON.stringify(payload))`.
     *
     * @param jsonString Serialized JSON string matching `{status: "success" | "error" | "expired", ...}`.
     */
    @JavascriptInterface
    fun postMessage(jsonString: String?) {
        val message = TurnstileBridgeMessage.parse(jsonString)
        if (message != null) {
            onMessage(message)
        }
    }

    /**
     * Legacy callback invoked by older challenge pages that call:
     * `window.AndroidTurnstileBridge.onToken(token)`.
     *
     * @param token The minted Turnstile token string.
     */
    @JavascriptInterface
    fun onToken(token: String?) {
        val cleanToken = token?.trim()?.ifEmpty { null }
        if (cleanToken != null) {
            onMessage(TurnstileBridgeMessage.Success(cleanToken))
        }
    }
}

/**
 * Utility for assembling and parsing hosted Cloudflare Turnstile challenge page interactions.
 *
 * When a native WebView must mint a token, open:
 * `https://app.cupthread.com/turnstile/verify?siteKey=...&action=...&cdata=...&theme=light|dark|auto&locale=...`
 */
object TurnstileHostedChallenge {
    /** Default public portal origin for CupThread cloud deployments. */
    const val DEFAULT_PORTAL_BASE_URL: String = "https://app.cupthread.com"

    /** Path to the hosted challenge page on the portal origin. */
    const val VERIFY_PATH: String = "/turnstile/verify"

    /**
     * Derives the hosted Turnstile challenge URL from an API base URL:
     * - Replaces a leading `api.` host prefix with `app.` (e.g. `https://api.cupthread.com` -> `https://app.cupthread.com`).
     * - Retains any other host intact (e.g. `https://custom-portal.example.com` -> `https://custom-portal.example.com`).
     *
     * @param apiBaseUrl Base API URL (e.g. `https://api.cupthread.com`).
     * @return Portal origin base URL (e.g. `https://app.cupthread.com`).
     */
    fun derivePortalBaseUrl(apiBaseUrl: String): String {
        val trimmed = apiBaseUrl.trim().trimEnd('/')
        return try {
            val uri = URI(trimmed)
            val host = uri.host ?: return trimmed
            if (host.startsWith("api.", ignoreCase = true)) {
                val newHost = "app." + host.substring(4)
                URI(uri.scheme, uri.userInfo, newHost, uri.port, null, null, null).toString().trimEnd('/')
            } else {
                trimmed
            }
        } catch (_: Exception) {
            trimmed
        }
    }

    /**
     * Assembles the full hosted challenge page URL for the specified [challenge] and [siteKey].
     *
     * @param siteKey The public Turnstile widget site key (e.g. `0x4AAAAAA...`).
     * @param challenge The widget `(action, cdata)` binding.
     * @param theme Optional widget color theme: `"light"`, `"dark"`, or `"auto"`.
     * @param locale Optional locale language tag (e.g. `"en"`, `"zh-CN"`, `"ja"`).
     * @param portalBaseUrl The base URL of the portal; defaults to [DEFAULT_PORTAL_BASE_URL].
     * @return Formatted URL string ready to be loaded by a WebView.
     */
    fun buildChallengeUrl(
        siteKey: String,
        challenge: TurnstileChallenge,
        theme: String? = null,
        locale: String? = null,
        portalBaseUrl: String = DEFAULT_PORTAL_BASE_URL
    ): String {
        require(siteKey.isNotBlank()) { "siteKey cannot be blank" }
        require(challenge.action.isNotBlank()) { "challenge action cannot be blank" }
        require(challenge.cdata.isNotBlank()) { "challenge cdata cannot be blank" }

        val base = portalBaseUrl.trim().trimEnd('/')
        val params = mutableListOf(
            "siteKey" to siteKey.trim(),
            "action" to challenge.action.trim(),
            "cdata" to challenge.cdata.trim()
        )
        theme?.trim()?.takeIf { it.isNotEmpty() }?.let {
            params += "theme" to it
        }
        locale?.trim()?.takeIf { it.isNotEmpty() }?.let {
            params += "locale" to it
        }

        val queryString = params.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
        }
        return "$base$VERIFY_PATH?$queryString"
    }
}
