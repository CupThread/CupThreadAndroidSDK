package dev.cupthread.feedback

/**
 * Base sealed exception class for all errors produced by [FeedbackClient].
 *
 * Every method in [FeedbackClient] encapsulates network transport failures, HTTP errors,
 * authentication rejections, and serialization anomalies into one of the specialized subclasses of
 * [FeedbackException]. This enables concise, uniform error handling across UI layers:
 *
 * ### Exhaustive Error Handling Example
 * ```kotlin
 * try {
 *     val result = client.submit(draft, userToken)
 *     showSuccessToast("Submitted: ${result.submissionId}")
 * } catch (e: FeedbackException.AuthenticationRequired) {
 *     showLoginPrompt("Please sign in to submit feedback.")
 * } catch (e: FeedbackException.UnsupportedMediaType) {
 *     showErrorSnackbar("Unsupported file format: ${e.message}")
 * } catch (e: FeedbackException.RateLimited) {
 *     showErrorSnackbar(e.message ?: "You're voting too fast. Please try again shortly.")
 * } catch (e: FeedbackException.UnexpectedStatus) {
 *     Log.e("Feedback", "Server error code: ${e.code}, message: ${e.serverMessage}")
 *     supportTicket.referenceId = e.requestId // server X-Request-Id for support conversations
 *     showErrorSnackbar("Submission failed: ${e.serverMessage}")
 * } catch (e: FeedbackException.InvalidResponse) {
 *     Log.e("Feedback", "Network connection or parsing failed", e.cause)
 *     showErrorSnackbar("Unable to reach the server. Please check your internet connection.")
 * } catch (e: FeedbackException) {
 *     showErrorSnackbar("An unexpected error occurred.")
 * }
 * ```
 *
 * @param message Human-readable error description.
 * @param cause Underlying root cause exception, when available.
 */
sealed class FeedbackException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /**
     * Server-assigned correlation identifier echoed in the `X-Request-Id` response header,
     * or the caller-supplied `X-Request-Id` request header accepted by the server. Attach it
     * to bug reports and support conversations so CupThread can locate the exact request in
     * server logs. `null` when the server did not echo the header (for example on transport
     * failures, where no response was received).
     */
    open val requestId: String? = null
    /**
     * Raised when a network transport failure occurs (such as connection timeouts, DNS resolution errors,
     * or SSL handshake issues) or when the server returns a malformed response body that cannot be parsed.
     *
     * @param cause Underlying I/O exception or JSON parsing exception.
     */
    class InvalidResponse(cause: Throwable? = null) :
        FeedbackException("The feedback server returned an invalid response.", cause)

    /**
     * Raised when an attachment upload HTTP request returned status 200, but the resulting JSON payload
     * could not be decoded into a valid [FeedbackAttachment] descriptor.
     */
    class UnreadableUploadResponse :
        FeedbackException("The feedback server returned an unreadable upload response.")

    /**
     * Raised when attempting to perform an action that requires a registered or permitted user token
     * on an app configured with anonymous restrictions (HTTP 401 `authentication_required`).
     *
     * @property requestId Server correlation identifier from the `X-Request-Id` response header (see [FeedbackException.requestId]).
     */
    class AuthenticationRequired @JvmOverloads constructor(
        override val requestId: String? = null
    ) : FeedbackException("These updates are only available to signed-in users.")

    /**
     * Raised when an attachment upload HTTP request is rejected because the file media type
     * is unsupported (HTTP 415 `unsupported_media_type`), such as SVG images or files whose
     * content does not match the accepted image formats (PNG, JPEG, WebP, GIF).
     *
     * @property serverMessage Raw error message text returned by the server, if any.
     * @property requestId Server correlation identifier from the `X-Request-Id` response header (see [FeedbackException.requestId]).
     */
    class UnsupportedMediaType @JvmOverloads constructor(
        val serverMessage: String? = null,
        override val requestId: String? = null
    ) :
        FeedbackException(
            serverMessage?.ifBlank { null }
                ?: "This image format is not supported. Please upload a PNG, JPEG, WebP, or GIF image."
        )

    /**
     * Raised when the server responds with HTTP 402 `tier_limit_submissions`, indicating
     * that the application's workspace has reached its monthly submission quota.
     *
     * @property serverMessage Raw error message text returned by the server, if any.
     * @property requestId Server correlation identifier from the `X-Request-Id` response header (see [FeedbackException.requestId]).
     */
    class SubmissionQuotaExceeded @JvmOverloads constructor(
        val serverMessage: String? = null,
        override val requestId: String? = null
    ) :
        FeedbackException(
            serverMessage?.ifBlank { null }
                ?: "This app has reached its submission limit for this month. Please try again later."
        )

    /**
     * Raised when the server responds with HTTP 402 `subscription_inactive`, indicating
     * that the application's workspace subscription is inactive or canceled.
     *
     * @property serverMessage Raw error message text returned by the server, if any.
     * @property requestId Server correlation identifier from the `X-Request-Id` response header (see [FeedbackException.requestId]).
     */
    class SubscriptionInactive @JvmOverloads constructor(
        val serverMessage: String? = null,
        override val requestId: String? = null
    ) :
        FeedbackException(
            serverMessage?.ifBlank { null }
                ?: "Submissions are unavailable for this app right now. Please try again later."
        )

    /**
     * Raised when feedback submission is rejected because an attachment failed backend content inspection
     * (HTTP 422 `scan_rejected`).
     *
     * @property serverMessage Raw error message text returned by the server, if any.
     * @property requestId Server correlation identifier from the `X-Request-Id` response header (see [FeedbackException.requestId]).
     */
    class AttachmentScanRejected @JvmOverloads constructor(
        val serverMessage: String? = null,
        override val requestId: String? = null
    ) :
        FeedbackException(
            serverMessage?.ifBlank { null }
                ?: "An attachment could not be uploaded due to content inspection rejection."
        )

    /**
     * Raised when the server responds with HTTP 429 `too_many_requests`, indicating that the client
     * has exceeded a rate limit window (such as voting too frequently on feature requests).
     *
     * @property serverMessage Raw error message text returned by the server, if any.
     * @property requestId Server correlation identifier from the `X-Request-Id` response header (see [FeedbackException.requestId]).
     */
    class RateLimited @JvmOverloads constructor(
        val serverMessage: String? = null,
        override val requestId: String? = null
    ) :
        FeedbackException(
            serverMessage?.ifBlank { null }
                ?: "You're voting too fast. Please try again shortly."
        )

    /**
     * Raised when the server's human-verification (Cloudflare Turnstile) gate rejects a gated
     * intake call — `POST /api/v1/feedback`, `POST /api/v1/feature-requests`, or
     * `POST /api/v1/uploads/sessions` — with HTTP 403, replacing the raw server body previously
     * surfaced through [UnexpectedStatus].
     *
     * When [FeedbackClientConfig.turnstileTokenProvider] is configured and the rejection is
     * retryable ([canRetryWithFreshToken]), the client has already retried once with a freshly
     * minted token before this error is thrown — surface retry guidance to the user, or mint
     * another token through a subsequent call.
     *
     * @property serverCode Machine-readable rejection code: `turnstile_required` when the request
     *   carried no token, `turnstile_verification_failed` when siteverify rejected it (replay,
     *   expiry, or action/cdata mismatch), `turnstile_not_configured` when the deployment requires
     *   a secret it does not have, or `null` on legacy deployments that answered a message-only 403.
     * @property serverMessage Raw error message text returned by the server, if any.
     * @property requestId Server correlation identifier from the `X-Request-Id` response header (see [FeedbackException.requestId]).
     */
    class VerificationRequired @JvmOverloads constructor(
        val serverCode: String? = null,
        val serverMessage: String? = null,
        override val requestId: String? = null
    ) : FeedbackException(
        serverMessage?.ifBlank { null }
            ?: "Please complete human verification and try again."
    ) {
        /**
         * Whether minting a fresh token and retrying can succeed. Only
         * [CODE_NOT_CONFIGURED] is futile — no client token can pass a deployment
         * whose server lacks its verification secret.
         */
        val canRetryWithFreshToken: Boolean
            get() = serverCode != CODE_NOT_CONFIGURED

        companion object {
            /** The request carried no `turnstileToken`. */
            const val CODE_REQUIRED = "turnstile_required"

            /** siteverify rejected the token (replay, expiry, or action/cdata mismatch). */
            const val CODE_VERIFICATION_FAILED = "turnstile_verification_failed"

            /** The deployment enforces verification but its server has no secret configured. */
            const val CODE_NOT_CONFIGURED = "turnstile_not_configured"

            /**
             * Whether a rejection body represents the server's Turnstile human-verification gate.
             *
             * The machine-readable `code` wins when present. Releases from before the codes were
             * introduced carried no `code` on intake, so the message matches as a fallback —
             * a `null` code with a non-verification message is left for [UnexpectedStatus].
             */
            internal fun isTurnstileRejection(code: String?, message: String?): Boolean =
                code == CODE_REQUIRED ||
                    code == CODE_VERIFICATION_FAILED ||
                    code == CODE_NOT_CONFIGURED ||
                    (code == null && (
                        message?.contains("turnstile", ignoreCase = true) == true ||
                            message?.contains("human verification", ignoreCase = true) == true
                    ))
        }
    }

    /**
     * Raised when the server responds with an HTTP status code outside the accepted success range
     * for the specific endpoint.
     *
     * @property code The HTTP status code received from the server (e.g. 400, 403, 404, 429, 500).
     * @property serverMessage Raw error message text extracted from the response body.
     * @property requestId Server correlation identifier from the `X-Request-Id` response header (see [FeedbackException.requestId]).
     */
    class UnexpectedStatus @JvmOverloads constructor(
        val code: Int,
        val serverMessage: String,
        override val requestId: String? = null
    ) : FeedbackException("The feedback request failed ($code): $serverMessage")
}
