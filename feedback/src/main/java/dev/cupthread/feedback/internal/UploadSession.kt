package dev.cupthread.feedback.internal

import org.json.JSONObject

/**
 * A pre-allocated file slot inside an upload session created via `POST /api/v1/uploads/sessions`.
 *
 * @property clientFileId The caller-assigned identifier echoed back by the server.
 * @property uploadId The server-assigned id (`upl_…`) referenced by feedback submissions.
 * @property maxSizeBytes The per-file byte budget enforced by `PUT /api/v1/uploads/{uploadId}`.
 */
internal data class UploadSessionSlot(
    val clientFileId: String,
    val uploadId: String,
    val maxSizeBytes: Long
)

/**
 * The server-issued credentials and the matching file slot for a single-file upload session.
 *
 * @property sessionToken Bearer token (`cpt_up_…`, ~1 hour TTL) authorizing `PUT /api/v1/uploads/{uploadId}`.
 * @property slot The pre-allocated slot holding the target [UploadSessionSlot.uploadId].
 */
internal data class UploadSessionReservation(
    val sessionToken: String,
    val slot: UploadSessionSlot
)

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
 * @throws org.json.JSONException if the body does not match the documented session schema.
 * @throws NoSuchElementException if no slot in the session matches [clientFileId].
 */
internal fun parseUploadSession(json: JSONObject, clientFileId: String): UploadSessionReservation {
    val sessionToken = json.getJSONObject("session").getString("sessionToken")
    val files = json.getJSONArray("files")
    for (i in 0 until files.length()) {
        val item = files.getJSONObject(i)
        if (item.optString("clientFileId") == clientFileId) {
            return UploadSessionReservation(
                sessionToken = sessionToken,
                slot = UploadSessionSlot(
                    clientFileId = clientFileId,
                    uploadId = item.getString("uploadId"),
                    maxSizeBytes = item.getLong("maxSizeBytes")
                )
            )
        }
    }
    throw NoSuchElementException("Upload session response has no slot for clientFileId $clientFileId")
}

/**
 * Parses the `200` body of `PUT /api/v1/uploads/{uploadId}`.
 *
 * @throws org.json.JSONException if the body does not match the documented uploaded-file schema
 *   or if the returned [UploadedSessionFile.uploadId] is blank.
 */
internal fun parseUploadedSessionFile(json: JSONObject): UploadedSessionFile {
    val uploadId = json.getString("uploadId").trim().ifEmpty {
        throw org.json.JSONException("uploaded session file response has a blank uploadId")
    }
    return UploadedSessionFile(
        uploadId = uploadId,
        filename = json.stringOrNull("filename"),
        contentType = json.stringOrNull("contentType"),
        sizeBytes = if (!json.has("sizeBytes") || json.isNull("sizeBytes")) null else json.getLong("sizeBytes")
    )
}
