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
     * Raised when the server responds with an HTTP status code outside the accepted success range
     * for the specific endpoint.
     *
     * @property code The HTTP status code received from the server (e.g. 400, 403, 404, 429, 500).
     * @property serverMessage Raw error message text extracted from the response body.
     */
    class UnexpectedStatus(val code: Int, val serverMessage: String) :
        FeedbackException("The feedback request failed ($code): $serverMessage")
}
