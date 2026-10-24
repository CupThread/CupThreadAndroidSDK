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
     */
    class AuthenticationRequired :
        FeedbackException("These updates are only available to signed-in users.")

    /**
     * Raised when an attachment upload HTTP request is rejected because the file media type
     * is unsupported (HTTP 415 `unsupported_media_type`), such as SVG images or files whose
     * content does not match the accepted image formats (PNG, JPEG, WebP, GIF).
     *
     * @property serverMessage Raw error message text returned by the server, if any.
     */
    class UnsupportedMediaType(val serverMessage: String? = null) :
        FeedbackException(
            serverMessage?.ifBlank { null }
                ?: "This image format is not supported. Please upload a PNG, JPEG, WebP, or GIF image."
        )

    /**
     * Raised when the server responds with HTTP 402 `tier_limit_submissions`, indicating
     * that the application's workspace has reached its monthly submission quota.
     *
     * @property serverMessage Raw error message text returned by the server, if any.
     */
    class SubmissionQuotaExceeded(val serverMessage: String? = null) :
        FeedbackException(
            serverMessage?.ifBlank { null }
                ?: "This app has reached its submission limit for this month. Please try again later."
        )

    /**
     * Raised when the server responds with HTTP 402 `subscription_inactive`, indicating
     * that the application's workspace subscription is inactive or canceled.
     *
     * @property serverMessage Raw error message text returned by the server, if any.
     */
    class SubscriptionInactive(val serverMessage: String? = null) :
        FeedbackException(
            serverMessage?.ifBlank { null }
                ?: "Submissions are unavailable for this app right now. Please try again later."
        )

    /**
     * Raised when feedback submission is rejected because an attachment failed backend content inspection
     * (HTTP 422 `scan_rejected`).
     *
     * @property serverMessage Raw error message text returned by the server, if any.
     */
    class AttachmentScanRejected(val serverMessage: String? = null) :
        FeedbackException(
            serverMessage?.ifBlank { null }
                ?: "An attachment could not be uploaded due to content inspection rejection."
        )

    /**
     * Raised when the server responds with HTTP 429 `too_many_requests`, indicating that the client
     * has exceeded a rate limit window (such as voting too frequently on feature requests).
     *
     * @property serverMessage Raw error message text returned by the server, if any.
     */
    class RateLimited(val serverMessage: String? = null) :
        FeedbackException(
            serverMessage?.ifBlank { null }
                ?: "You're voting too fast. Please try again shortly."
        )

    /**
     * Raised when the server responds with an HTTP status code outside the accepted success range
     * for the specific endpoint.
     *
     * @property code The HTTP status code received from the server (e.g. 400, 403, 404, 429, 500).
     * @property serverMessage Raw error message text extracted from the response body.
     */
    class UnexpectedStatus(val code: Int, val serverMessage: String) :
        FeedbackException("The feedback request failed ($code): $serverMessage")
}
