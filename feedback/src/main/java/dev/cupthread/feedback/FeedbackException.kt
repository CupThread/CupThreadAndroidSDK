package dev.cupthread.feedback

/**
 * Errors raised by [FeedbackClient]. Mirrors `FeedbackClientError` on Apple.
 */
sealed class FeedbackException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidResponse(cause: Throwable? = null) :
        FeedbackException("The feedback server returned an invalid response.", cause)

    class UnreadableUploadResponse :
        FeedbackException("The feedback server returned an unreadable upload response.")

    /** The endpoint requires a signed-in user (HTTP 401 `authentication_required`). */
    class AuthenticationRequired :
        FeedbackException("These updates are only available to signed-in users.")

    class UnexpectedStatus(val code: Int, val serverMessage: String) :
        FeedbackException("The feedback request failed ($code): $serverMessage")
}
