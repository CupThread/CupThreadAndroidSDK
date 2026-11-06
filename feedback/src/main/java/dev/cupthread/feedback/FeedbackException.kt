package dev.cupthread.feedback

import org.json.JSONObject

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
 *     showErrorSnackbar(e.message ?: "You're doing that too fast. Please try again shortly.")
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
     * Raised when a changelog subscription is rejected because the submitted address is not the
     * verified address of the caller's signed-in session (HTTP 403 `email_not_verified`).
     *
     * On apps that require sign-in for the changelog surface, the server binds the subscription
     * to the session's verified email address; a body `email` that differs from it is refused and
     * nothing is subscribed. Prompt the user to subscribe with their signed-in account address
     * instead of retrying the same value.
     *
     * @property serverMessage Raw error message text returned by the server, if any.
     * @property serverCode Machine-readable rejection code from the server, if any
     *   (the server sends [CODE_EMAIL_NOT_VERIFIED]).
     * @property requestId Server correlation identifier from the `X-Request-Id` response header
     *   (see [FeedbackException.requestId]).
     */
    class EmailNotVerified @JvmOverloads constructor(
        val serverMessage: String? = null,
        val serverCode: String? = null,
        override val requestId: String? = null
    ) : FeedbackException(
        serverMessage?.ifBlank { null }
            ?: "Subscribe with the email address of the account you're signed in with."
    ) {
        companion object {
            /** Standard machine-readable code returned with HTTP 403 for this rejection. */
            const val CODE_EMAIL_NOT_VERIFIED: String = VoteWarning.CODE_EMAIL_NOT_VERIFIED
        }
    }

    /**
     * Raised when a feature request's comment thread is not visible (HTTP 404) — for example
     * because the request has not been approved yet, was removed, or the app's roadmap is not
     * accessible at all.
     *
     * The request itself may still exist: treat this as "comments are not available right now"
     * rather than as proof that the feature request was never created.
     *
     * @property serverMessage Raw error message text returned by the server, if any.
     * @property requestId Server correlation identifier from the `X-Request-Id` response header
     *   (see [FeedbackException.requestId]).
     */
    class CommentsUnavailable @JvmOverloads constructor(
        val serverMessage: String? = null,
        override val requestId: String? = null
    ) : FeedbackException(
        serverMessage?.ifBlank { null }
            ?: "Comments aren't available for this feature request yet."
    )

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
     * Raised when an image attachment upload HTTP request fails due to an unexpected server-side
     * storage failure (HTTP 500 `image_upload_failed`).
     *
     * In contrast to client-side format or size errors (HTTP 415 [UnsupportedMediaType] or HTTP 413),
     * this indicates an unexpected backend failure (R2 / Cloudflare Images / D1). Clients can inspect
     * [serverCode] to offer a retry option instead of treating the failure as fatal.
     *
     * @property serverMessage Raw error message text returned by the server, if any.
     * @property serverCode Machine-readable rejection code from the server (defaults to [CODE_IMAGE_UPLOAD_FAILED]).
     * @property requestId Server correlation identifier from the `X-Request-Id` response header (see [FeedbackException.requestId]).
     */
    class ImageUploadFailed @JvmOverloads constructor(
        val serverMessage: String? = null,
        val serverCode: String? = CODE_IMAGE_UPLOAD_FAILED,
        override val requestId: String? = null
    ) : FeedbackException(
        serverMessage?.ifBlank { null }
            ?: "Image upload failed. Please try again."
    ) {
        companion object {
            /** Standard machine-readable code returned by `POST /api/v1/uploads/images` on storage failure. */
            const val CODE_IMAGE_UPLOAD_FAILED = "image_upload_failed"

            private val RECOGNIZED_LEGACY_MESSAGES = setOf(
                "image upload failed. please try again.",
                "image upload failed. please try again",
                "image upload failed",
                "image upload failed.",
                "image upload failed: internal storage error"
            )

            /**
             * Determines whether the given [code] or [message] represents an image-upload-failed rejection.
             *
             * Matches when [code] is [CODE_IMAGE_UPLOAD_FAILED], or when no code was returned and
             * [message] matches one of the observed complete legacy image-upload error messages.
             * Unrecognized messages or near-misses fail closed to prevent false positive retry prompts.
             */
            internal fun isImageUploadFailed(code: String?, message: String?): Boolean {
                if (code == CODE_IMAGE_UPLOAD_FAILED) return true
                if (code != null) return false
                val normalized = message?.trim()?.lowercase() ?: return false
                return normalized in RECOGNIZED_LEGACY_MESSAGES
            }
        }
    }

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
     * Raised when feedback submission via [FeedbackClient.submit] is rejected because an attachment
     * was already finalized into another submission by a concurrent duplicate submit (HTTP 409
     * `already_finalized`).
     *
     * The duplicate submission creates nothing on the backend and consumes no monthly submission
     * quota. Callers should stop retrying the same payload with the consumed attachment and
     * surface an "already submitted" notice, or resubmit without the consumed attachment.
     *
     * @property serverMessage Raw error message text returned by the server, if any.
     * @property serverCode Machine-readable rejection code from the server (defaults to [CODE_ALREADY_FINALIZED]).
     * @property requestId Server correlation identifier from the `X-Request-Id` response header (see [FeedbackException.requestId]).
     */
    class AttachmentAlreadyFinalized @JvmOverloads constructor(
        val serverMessage: String? = null,
        val serverCode: String? = CODE_ALREADY_FINALIZED,
        override val requestId: String? = null
    ) : FeedbackException(
        serverMessage?.ifBlank { null }
            ?: "This attachment has already been submitted."
    ) {
        companion object {
            /** Standard machine-readable code returned by `POST /api/v1/feedback`. */
            const val CODE_ALREADY_FINALIZED = "already_finalized"

            /** Legacy/concurrent code variant for duplicate attachment finalization. */
            const val CODE_UPLOAD_FINALIZED_CONCURRENTLY = "upload_finalized_concurrently"

            /**
              * Determines whether the given [code] or [message] represents an already-finalized attachment error.
              */
            internal fun isAlreadyFinalized(code: String?, message: String?): Boolean =
                code == CODE_ALREADY_FINALIZED ||
                    code == CODE_UPLOAD_FINALIZED_CONCURRENTLY ||
                    (code == null && message?.contains("already been finalized", ignoreCase = true) == true)
        }
    }

    /**
     * Raised when an upload session creation or feedback attachment submission is rejected
     * because an uploader identity is required (HTTP 400 `uploader_identity_required`).
     *
     * Anonymous callers must provide a valid `X-User-Token` UUID to create upload sessions.
     * Submissions referencing upload-session attachments also require a matching `userToken`.
     *
     * @property serverMessage Raw error message text returned by the server, if any.
     * @property serverCode Machine-readable rejection code from the server (defaults to [CODE_UPLOADER_IDENTITY_REQUIRED]).
     * @property requestId Server correlation identifier from the `X-Request-Id` response header (see [FeedbackException.requestId]).
     */
    class UploaderIdentityRequired @JvmOverloads constructor(
        val serverMessage: String? = null,
        val serverCode: String? = CODE_UPLOADER_IDENTITY_REQUIRED,
        override val requestId: String? = null
    ) : FeedbackException(
        serverMessage?.ifBlank { null }
            ?: "A valid user token is required to create an upload session."
    ) {
        companion object {
            /** Standard machine-readable code returned by `POST /api/v1/uploads/sessions` and `POST /api/v1/feedback`. */
            const val CODE_UPLOADER_IDENTITY_REQUIRED = "uploader_identity_required"

            /**
             * Determines whether the given [code] or [message] represents an uploader-identity-required error.
             */
            internal fun isUploaderIdentityRequired(code: String?, message: String?): Boolean =
                code == CODE_UPLOADER_IDENTITY_REQUIRED
        }
    }

    /**
     * Raised when feedback submission via [FeedbackClient.submit] is rejected because an attachment
     * upload session was created by a different uploader identity (HTTP 400 `uploader_mismatch`).
     *
     * Submissions referencing upload-session attachments must present the same identity (`X-User-Token`
     * or Clerk session) used when reserving the session. The referenced file remains unfinalized.
     *
     * @property serverMessage Raw error message text returned by the server, if any.
     * @property serverCode Machine-readable rejection code from the server (defaults to [CODE_UPLOADER_MISMATCH]).
     * @property requestId Server correlation identifier from the `X-Request-Id` response header (see [FeedbackException.requestId]).
     */
    class UploaderMismatch @JvmOverloads constructor(
        val serverMessage: String? = null,
        val serverCode: String? = CODE_UPLOADER_MISMATCH,
        override val requestId: String? = null
    ) : FeedbackException(
        serverMessage?.ifBlank { null }
            ?: "Upload session was created by a different uploader."
    ) {
        companion object {
            /** Standard machine-readable code returned by `POST /api/v1/feedback`. */
            const val CODE_UPLOADER_MISMATCH = "uploader_mismatch"

            /**
             * Determines whether the given [code] or [message] represents an uploader-mismatch error.
             */
            internal fun isUploaderMismatch(code: String?, message: String?): Boolean =
                code == CODE_UPLOADER_MISMATCH
        }
    }

    /**
     * Raised when posting a feature request comment via [FeedbackClient.postComment] is rejected
     * because [CommentDraft.parentId] does not resolve to a visible comment on the target
     * feature request (HTTP 400 `invalid_parent`).
     *
     * @property serverMessage Raw error message text returned by the server, if any.
     * @property serverCode Machine-readable rejection code from the server (defaults to [CODE_INVALID_PARENT]).
     * @property requestId Server correlation identifier from the `X-Request-Id` response header (see [FeedbackException.requestId]).
     */
    class InvalidParentComment @JvmOverloads constructor(
        val serverMessage: String? = null,
        val serverCode: String? = CODE_INVALID_PARENT,
        override val requestId: String? = null
    ) : FeedbackException(
        serverMessage?.ifBlank { null }
            ?: "The comment being replied to does not exist or is no longer available."
    ) {
        companion object {
            /** Standard machine-readable code returned by `POST /api/v1/feature-requests/{id}/comments`. */
            const val CODE_INVALID_PARENT = "invalid_parent"

            /**
             * Determines whether the given [code] represents an invalid parent comment rejection.
             */
            internal fun isInvalidParent(code: String?): Boolean =
                code == CODE_INVALID_PARENT
        }
    }

    /**
     * Raised when the server responds with HTTP 429 `too_many_requests`, indicating that the client
     * has exceeded a rate limit window (such as voting too frequently, or syncing user attributes
     * for many users behind one shared IP).
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
                ?: "You're doing that too fast. Please try again shortly."
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
     * Raised when user attribute synchronization via [FeedbackClient.updateUserAttributes] is rejected
     * because [plan] uses the reserved sentinel strings `"unset"` or `"null"` (HTTP 400 `plan_reserved_sentinel`).
     *
     * In the `cpt-user-attrs-v1` canonical signature protocol, `"unset"` denotes an omitted field and
     * `"null"` denotes clearing a field. These literals are reserved whole-string byte-exact identifiers.
     * Callers should omit the argument to leave it unchanged, or use a valid
     * plan name (e.g., `"free"`, `"pro"`, `"enterprise"`).
     *
     * @property serverMessage Raw error message text returned by the server, if any.
     * @property serverCode Machine-readable rejection code from the server (defaults to [CODE_PLAN_RESERVED_SENTINEL]).
     * @property requestId Server correlation identifier from the `X-Request-Id` response header (see [FeedbackException.requestId]).
     */
    class ReservedPlanSentinel @JvmOverloads constructor(
        val serverMessage: String? = null,
        val serverCode: String? = CODE_PLAN_RESERVED_SENTINEL,
        override val requestId: String? = null
    ) : FeedbackException(
        serverMessage?.ifBlank { null }
            ?: "The plan value is reserved ('unset' and 'null' are protocol sentinels)."
    ) {
        companion object {
            /** Standard machine-readable code returned in `details.fieldErrors.plan` on `PUT /api/v1/public/apps/{appKey}/user`. */
            const val CODE_PLAN_RESERVED_SENTINEL = "plan_reserved_sentinel"

            /**
             * Determines whether the given error response represents a plan-reserved-sentinel rejection.
             */
            internal fun isPlanReservedSentinel(code: String?, message: String?, body: String): Boolean {
                if (code == CODE_PLAN_RESERVED_SENTINEL) return true
                if (!body.contains(CODE_PLAN_RESERVED_SENTINEL)) return false
                return try {
                    val json = JSONObject(body)
                    val details = json.optJSONObject("details")
                    val fieldErrors = details?.optJSONObject("fieldErrors")
                    val planErrors = fieldErrors?.optJSONArray("plan")
                    if (planErrors != null) {
                        (0 until planErrors.length()).any { planErrors.optString(it) == CODE_PLAN_RESERVED_SENTINEL }
                    } else {
                        false
                    }
                } catch (_: Exception) {
                    false
                }
            }
        }
    }

    /**
     * Raised when request validation fails on an intake endpoint (HTTP 400 with
     * `"error": "Validation failed"` and a `details` object), replacing a generic
     * [UnexpectedStatus] so callers can present precise, field-level messages.
     *
     * The CupThread API validates every request with zod and answers 400 with the zod
     * `flatten()` output:
     *
     * ```json
     * {
     *   "error": "Validation failed",
     *   "details": {
     *     "formErrors": [],
     *     "fieldErrors": { "appKey": ["Required"] }
     *   }
     * }
     * ```
     *
     * `formErrors` carries whole-payload problems and `fieldErrors` maps each rejected field to
     * its message strings; both are empty/absent when unused. Specific 400 mappers (such as
     * [InvalidParentComment], [ReservedPlanSentinel], and [UploaderIdentityRequired]) take
     * precedence: when the server code or field messages match a dedicated rejection, that typed
     * exception is thrown instead and no [ValidationFailed] is raised.
     *
     * @property serverMessage Raw top-level error text (typically `"Validation failed"`).
     * @property serverCode Machine-readable rejection code from the server, when present.
     * @property formErrors Whole-payload validation messages; empty when the failure is field-only.
     * @property fieldErrors Map of rejected field name to its validation messages; empty when the
     *   failure is form-level only.
     * @property requestId Server correlation identifier from the `X-Request-Id` response header (see [FeedbackException.requestId]).
     */
    class ValidationFailed @JvmOverloads constructor(
        val serverMessage: String? = null,
        val serverCode: String? = null,
        val formErrors: List<String> = emptyList(),
        val fieldErrors: Map<String, List<String>> = emptyMap(),
        override val requestId: String? = null
    ) : FeedbackException(
        buildString {
            append(serverMessage?.ifBlank { null } ?: "The request was rejected by validation.")
            val firstField = fieldErrors.entries.firstOrNull { it.value.isNotEmpty() }
            if (firstField != null) {
                append(" (${firstField.key}: ${firstField.value.first()})")
            } else {
                formErrors.firstOrNull()?.let { append(" ($it)") }
            }
        }
    ) {
        companion object {
            /** The fixed top-level `error` value the server sends on validation failures. */
            const val ERROR_VALIDATION_FAILED = "Validation failed"

            /**
             * Parses the zod `flatten()` `details` envelope of a `Validation failed` body.
             *
             * Returns `null` when [body] is not a JSON object, the `error` field differs from
             * [ERROR_VALIDATION_FAILED], or `details` is missing/blank — callers should fall back
             * to [UnexpectedStatus] in that case. Malformed entries inside a present `details`
             * object are skipped rather than failing the whole parse.
             */
            internal fun fromErrorBody(body: String, requestId: String? = null): ValidationFailed? {
                val json = try {
                    JSONObject(body)
                } catch (_: Exception) {
                    return null
                }
                val error = json.optString("error").takeIf { it.isNotBlank() }
                if (error != ERROR_VALIDATION_FAILED) return null
                val details = json.optJSONObject("details") ?: return null
                val formErrors = details.optJSONArray("formErrors")?.stringList().orEmpty()
                val fieldErrors = details.optJSONObject("fieldErrors")?.let { fields ->
                    buildMap {
                        fields.keys().forEach { field ->
                            val messages = fields.optJSONArray(field)?.stringList()
                            if (!messages.isNullOrEmpty()) put(field, messages)
                        }
                    }
                }.orEmpty()
                if (formErrors.isEmpty() && fieldErrors.isEmpty()) return null
                return ValidationFailed(
                    serverMessage = error,
                    serverCode = json.optString("code").takeIf { it.isNotBlank() && it != "null" },
                    formErrors = formErrors,
                    fieldErrors = fieldErrors,
                    requestId = requestId
                )
            }

            private fun org.json.JSONArray.stringList(): List<String> =
                buildList {
                    for (i in 0 until length()) {
                        (opt(i) as? String)?.takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
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
