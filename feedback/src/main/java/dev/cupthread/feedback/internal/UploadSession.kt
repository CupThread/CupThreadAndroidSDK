package dev.cupthread.feedback.internal

import org.json.JSONObject

/**
 * A pre-allocated file slot inside an upload session created via `POST /api/v1/uploads/sessions`.
 *
 * @property clientFileId The caller-assigned identifier echoed back by the server.
 * @property uploadId The server-assigned id (`upl_…`) referenced by feedback submissions.
 * @property maxSizeBytes The per-file byte budget enforced by `PUT /api/v1/uploads/{uploadId}`.
 * @property filename The original filename, when provided in the slot descriptor.
 * @property mimeType The MIME type, when provided in the slot descriptor.
 * @property uploadUrl Direct upload URL, when provided in legacy mirror slot descriptors.
 */
internal data class UploadSessionSlot(
    val clientFileId: String,
    val uploadId: String,
    val maxSizeBytes: Long,
    val filename: String? = null,
    val mimeType: String? = null,
    val uploadUrl: String? = null
) {
    /**
     * Preserves binary backward compatibility with callers compiled against earlier signatures.
     */
    constructor(
        clientFileId: String,
        uploadId: String,
        maxSizeBytes: Long
    ) : this(
        clientFileId = clientFileId,
        uploadId = uploadId,
        maxSizeBytes = maxSizeBytes,
        filename = null,
        mimeType = null,
        uploadUrl = null
    )
}

/**
 * The server-issued credentials and the matching file slot for a single-file upload session.
 *
 * @property sessionToken Bearer token (`cpt_up_…`, ~1 hour TTL) authorizing `PUT /api/v1/uploads/{uploadId}`.
 * @property slot The pre-allocated slot holding the target [UploadSessionSlot.uploadId].
 * @property sessionId The server-assigned upload session ID, when present.
 * @property expiresAt ISO 8601 timestamp when the upload session token expires, when present.
 */
internal data class UploadSessionReservation(
    val sessionToken: String,
    val slot: UploadSessionSlot,
    val sessionId: String? = null,
    val expiresAt: String? = null
) {
    /**
     * Preserves binary backward compatibility with callers compiled against earlier signatures.
     */
    constructor(
        sessionToken: String,
        slot: UploadSessionSlot
    ) : this(
        sessionToken = sessionToken,
        slot = slot,
        sessionId = null,
        expiresAt = null
    )
}

/**
 * The stored-file descriptor returned by `PUT /api/v1/uploads/{uploadId}`.
 */
internal data class UploadedSessionFile(
    val uploadId: String,
    val filename: String?,
    val contentType: String?,
    val sizeBytes: Long?
)

/**
 * Parses the `201` body of `POST /api/v1/uploads/sessions` and extracts the session token plus
 * the slot reserved for [clientFileId].
 *
 * Accepts both the modern nested schema (`session` object + `files` array) and the legacy flat
 * mirror schema (`sessionId`, `sessionToken`, `expiresAt`, and `uploads` array with `maxBytes`).
 *
 * @throws org.json.JSONException if the body does not match the documented session schema.
 * @throws NoSuchElementException if no slot in the session matches [clientFileId].
 */
internal fun parseUploadSession(json: JSONObject, clientFileId: String): UploadSessionReservation {
    val sessionObj = if (json.has("session") && !json.isNull("session")) json.optJSONObject("session") else null
    val sessionToken = sessionObj?.optString("sessionToken")?.takeIf { it.isNotBlank() && it != "null" }
        ?: json.optString("sessionToken")?.takeIf { it.isNotBlank() && it != "null" }
        ?: throw org.json.JSONException("Upload session response missing sessionToken")

    val sessionId = sessionObj?.optString("sessionId")?.takeIf { it.isNotBlank() && it != "null" }
        ?: json.optString("sessionId")?.takeIf { it.isNotBlank() && it != "null" }

    val expiresAt = sessionObj?.optString("expiresAt")?.takeIf { it.isNotBlank() && it != "null" }
        ?: json.optString("expiresAt")?.takeIf { it.isNotBlank() && it != "null" }

    val fileArrays = listOfNotNull(
        if (json.has("files") && !json.isNull("files")) json.optJSONArray("files") else null,
        if (json.has("uploads") && !json.isNull("uploads")) json.optJSONArray("uploads") else null
    )

    if (fileArrays.isEmpty()) {
        throw org.json.JSONException("Upload session response missing files or uploads array")
    }

    for (files in fileArrays) {
        for (i in 0 until files.length()) {
            val item = files.getJSONObject(i)
            val itemClientFileId = if (item.has("clientFileId") && !item.isNull("clientFileId")) {
                item.optString("clientFileId").takeIf { it.isNotBlank() && it != "null" }
            } else null

            if (itemClientFileId == clientFileId || (itemClientFileId == null && files.length() == 1)) {
                val uploadId = item.optString("uploadId").takeIf { it.isNotBlank() && it != "null" }
                    ?: throw org.json.JSONException("Upload slot missing uploadId")

                val maxSizeBytes = if (item.has("maxSizeBytes") && !item.isNull("maxSizeBytes")) {
                    item.getLong("maxSizeBytes")
                } else if (item.has("maxBytes") && !item.isNull("maxBytes")) {
                    item.getLong("maxBytes")
                } else {
                    throw org.json.JSONException("Upload slot missing maxSizeBytes or maxBytes")
                }

                val filename = item.optString("filename").takeIf { it.isNotBlank() && it != "null" }
                val mimeType = item.optString("mimeType").takeIf { it.isNotBlank() && it != "null" }
                    ?: item.optString("contentType").takeIf { it.isNotBlank() && it != "null" }
                val uploadUrl = item.optString("uploadUrl").takeIf { it.isNotBlank() && it != "null" }

                return UploadSessionReservation(
                    sessionToken = sessionToken,
                    slot = UploadSessionSlot(
                        clientFileId = itemClientFileId ?: clientFileId,
                        uploadId = uploadId,
                        maxSizeBytes = maxSizeBytes,
                        filename = filename,
                        mimeType = mimeType,
                        uploadUrl = uploadUrl
                    ),
                    sessionId = sessionId,
                    expiresAt = expiresAt
                )
            }
        }
    }

    throw NoSuchElementException("Upload session response has no slot for clientFileId $clientFileId")
}

/**
 * Parses the `200` body of `PUT /api/v1/uploads/{uploadId}`.
 *
 * @throws org.json.JSONException if the body does not match the documented uploaded-file schema
 *   or if the returned [UploadedSessionFile.uploadId] is missing, null, or blank.
 */
internal fun parseUploadedSessionFile(json: JSONObject): UploadedSessionFile {
    val uploadId = json.stringOrNull("uploadId")?.trim()?.ifEmpty { null }
        ?: throw org.json.JSONException("uploaded session file response has a missing or blank uploadId")
    val sizeBytes = if (json.has("sizeBytes") && !json.isNull("sizeBytes")) {
        json.getLong("sizeBytes")
    } else if (json.has("maxBytes") && !json.isNull("maxBytes")) {
        json.getLong("maxBytes")
    } else null
    return UploadedSessionFile(
        uploadId = uploadId,
        filename = json.stringOrNull("filename"),
        contentType = json.stringOrNull("contentType") ?: json.stringOrNull("mimeType"),
        sizeBytes = sizeBytes
    )
}
