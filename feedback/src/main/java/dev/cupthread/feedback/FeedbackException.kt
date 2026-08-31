package dev.cupthread.feedback

/**
 * Errors raised by [FeedbackClient]. Mirrors `FeedbackClientError` on Apple.
 *
 * Every client method throws one of these subclasses; catch
 * [FeedbackException] to handle all API failures uniformly.
 */
sealed class FeedbackException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /**
     * The request failed at the transport level, or the server response could
     * not be parsed.
     */
    class InvalidResponse(cause: Throwable? = null) :
        FeedbackException("The feedback server returned an invalid response.", cause)

    /**
     * An attachment upload succeeded, but its response could not be parsed
     * into a [FeedbackAttachment].
     */
    class UnreadableUploadResponse :
        FeedbackException("The feedback server returned an unreadable upload response.")

    /** The endpoint requires a signed-in user (HTTP 401 `authentication_required`). */
    class AuthenticationRequired :
        FeedbackException("These updates are only available to signed-in users.")

    /**
     * The server returned a status outside the accepted set for the endpoint.
     *
     * @property code HTTP status code that was rejected.
     * @property serverMessage Error text from the response body, when present.
     */
    class UnexpectedStatus(val code: Int, val serverMessage: String) :
        FeedbackException("The feedback request failed ($code): $serverMessage")
}
