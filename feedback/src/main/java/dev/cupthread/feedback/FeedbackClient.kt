package dev.cupthread.feedback

import dev.cupthread.feedback.internal.HttpRequest
import dev.cupthread.feedback.internal.HttpTransport
import dev.cupthread.feedback.internal.UrlConnectionTransport
import dev.cupthread.feedback.internal.encodeQuery
import dev.cupthread.feedback.internal.joinUrl
import dev.cupthread.feedback.internal.multipartBody
import dev.cupthread.feedback.internal.parseAppVersion
import dev.cupthread.feedback.internal.parseAttachment
import dev.cupthread.feedback.internal.parseBoardColumn
import dev.cupthread.feedback.internal.parseChangelogEntry
import dev.cupthread.feedback.internal.parseChangelogSubscription
import dev.cupthread.feedback.internal.parseChangelogUnsubscribe
import dev.cupthread.feedback.internal.parseFeatureRequestSubmission
import dev.cupthread.feedback.internal.parseFeedbackSubmission
import dev.cupthread.feedback.internal.parseListFeatureRequests
import dev.cupthread.feedback.internal.parsePublicAppConfig
import dev.cupthread.feedback.internal.parseUserAttributesUpdate
import dev.cupthread.feedback.internal.parseVoteResult
import dev.cupthread.feedback.internal.putOptString
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class FeedbackClient internal constructor(
    val config: FeedbackClientConfig,
    internal val transport: HttpTransport
) {
    constructor(config: FeedbackClientConfig) : this(config, UrlConnectionTransport())

    suspend fun submit(draft: FeedbackDraft, userToken: String? = null): FeedbackSubmissionResult {
        val payload = JSONObject().apply {
            put("appKey", config.appKey)
            put("title", draft.title.trim())
            put("description", draft.description.trim())
            putOptString("reporterName", draft.reporterName.trim().ifEmpty { null })
            putOptString("reporterEmail", draft.reporterEmail.trim().ifEmpty { null })
            put("platform", draft.platform.wireValue)
            putOptString("appVersion", draft.appVersion.trim().ifEmpty { null })
            putOptString("buildNumber", draft.buildNumber.trim().ifEmpty { null })
            put("metadata", JSONObject(defaultMetadata(draft)))
            val attachments = JSONArray()
            for (attachment in draft.attachments) {
                attachments.put(
                    JSONObject().apply {
                        put("kind", attachment.kind.wireValue)
                        put("key", attachment.key)
                        put("url", attachment.url)
                        putOptString("filename", attachment.filename)
                        putOptString("mimeType", attachment.mimeType)
                        if (attachment.size != null) put("size", attachment.size)
                    }
                )
            }
            put("attachments", attachments)
        }
        return parseFeedbackSubmission(
            sendJson(
                method = "POST",
                path = "/api/v1/feedback",
                body = payload,
                userToken = userToken,
                accepted = setOf(200, 201, 202)
            )
        )
    }

    suspend fun uploadAttachment(
        data: ByteArray,
        filename: String,
        mimeType: String,
        preferredKind: FeedbackAttachment.Kind? = null
    ): FeedbackAttachment {
        val kind = preferredKind ?: if (mimeType.startsWith("image/")) {
            FeedbackAttachment.Kind.IMAGE
        } else {
            FeedbackAttachment.Kind.R2
        }
        val path = if (kind == FeedbackAttachment.Kind.IMAGE) {
            "/api/v1/uploads/images"
        } else {
            "/api/v1/uploads/r2"
        }
        val boundary = "Boundary-${UUID.randomUUID()}"
        val body = multipartBody(boundary, config.appKey, filename, mimeType, data)
        val json = send(
            HttpRequest(
                method = "POST",
                url = joinUrl(config.baseUrl, path),
                contentType = "multipart/form-data; boundary=$boundary",
                body = body
            ),
            accepted = setOf(200)
        )
        return try {
            parseAttachment(json)
        } catch (_: Exception) {
            throw FeedbackException.UnreadableUploadResponse()
        }
    }

    suspend fun fetchAppConfig(): PublicAppConfig =
        parsePublicAppConfig(get("/api/v1/public/config/${config.appKey}"))

    suspend fun fetchColumns(): List<BoardColumn> {
        val json = get("/api/v1/public/columns/${config.appKey}")
        val array = json.optJSONArray("columns") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) add(parseBoardColumn(array.getJSONObject(i)))
        }.sortedBy { it.position }
    }

    suspend fun fetchVersions(): List<AppVersion> {
        val json = get("/api/v1/public/versions/${config.appKey}")
        val array = json.optJSONArray("versions") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) add(parseAppVersion(array.getJSONObject(i)))
        }.sortedBy { it.position }
    }

    suspend fun fetchFeatureRequests(
        userToken: String,
        limit: Int = 50,
        offset: Int = 0,
        versionId: String? = null,
        query: String? = null
    ): ListFeatureRequestsResult {
        val params = mutableListOf(
            "appKey" to config.appKey,
            "userToken" to userToken,
            "limit" to limit.toString(),
            "offset" to offset.toString()
        )
        if (!versionId.isNullOrBlank()) params += "versionId" to versionId
        if (!query.isNullOrBlank()) params += "q" to query
        return parseListFeatureRequests(get("/api/v1/feature-requests?${encodeQuery(params)}"))
    }

    suspend fun submitFeatureRequest(
        draft: FeatureRequestDraft,
        userToken: String
    ): FeatureRequestSubmissionResult {
        val payload = JSONObject().apply {
            put("appKey", config.appKey)
            put("title", draft.title.trim())
            put("description", draft.description.trim())
            putOptString("requesterName", draft.requesterName.trim().ifEmpty { null })
            put("requesterToken", userToken)
        }
        return parseFeatureRequestSubmission(
            sendJson("POST", "/api/v1/feature-requests", payload, userToken = null, accepted = setOf(200, 201))
        )
    }

    suspend fun toggleVote(featureRequestId: String, userToken: String): VoteResult {
        val payload = JSONObject().apply {
            put("appKey", config.appKey)
            put("userToken", userToken)
        }
        return parseVoteResult(
            sendJson("POST", "/api/v1/feature-requests/$featureRequestId/vote", payload, accepted = setOf(200))
        )
    }

    suspend fun fetchChangelog(): List<ChangelogEntry> {
        val json = get("/api/v1/public/apps/${config.appKey}/changelog")
        val array = json.optJSONArray("entries") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) add(parseChangelogEntry(array.getJSONObject(i)))
        }.sortedByDescending { it.publishedAt }
    }

    /**
     * Loads overlay copy and the newest published entries.
     * Returns `null` when changelog is hidden in the console or nothing has shipped.
     */
    suspend fun prepareChangelogOverlay(): Pair<List<ChangelogEntry>, SdkAppearance>? {
        val appearance = fetchAppConfig().sdk
        if (!appearance.features.changelog) return null
        val entries = fetchChangelog().take(appearance.changelogOverlay.clampedEntryCount)
        if (entries.isEmpty()) return null
        return entries to appearance
    }

    suspend fun subscribeToChangelog(email: String, userToken: String): ChangelogSubscriptionResult {
        val payload = JSONObject().put("email", email.trim())
        return parseChangelogSubscription(
            sendJson(
                "POST",
                "/api/v1/public/apps/${config.appKey}/changelog/subscribe",
                payload,
                userToken = userToken,
                accepted = setOf(200, 201)
            )
        )
    }

    suspend fun unsubscribeFromChangelog(email: String): ChangelogUnsubscribeResult {
        val payload = JSONObject().put("email", email.trim())
        return parseChangelogUnsubscribe(
            sendJson(
                "POST",
                "/api/v1/public/apps/${config.appKey}/changelog/unsubscribe",
                payload,
                accepted = setOf(200)
            )
        )
    }

    /**
     * Self-declared paying signals only — never payment details.
     * Omitted parameters are left unchanged server-side.
     */
    suspend fun updateUserAttributes(
        userToken: String,
        isPaying: Boolean? = null,
        plan: String? = null,
        mrr: Double? = null,
        currency: String? = null
    ): UserAttributesUpdateResult {
        val payload = JSONObject().apply {
            if (isPaying != null) put("isPaying", isPaying)
            putOptString("plan", plan)
            if (mrr != null) put("mrr", mrr)
            putOptString("currency", currency)
        }
        return parseUserAttributesUpdate(
            sendJson(
                "PUT",
                "/api/v1/public/apps/${config.appKey}/user",
                payload,
                userToken = userToken,
                accepted = setOf(200)
            )
        )
    }

    private fun defaultMetadata(draft: FeedbackDraft): Map<String, String> {
        val submittedAt = iso8601Now()
        return draft.metadata + mapOf(
            "sdk" to "cupthread-android",
            "platform" to draft.platform.wireValue,
            "submittedAt" to submittedAt
        )
    }

    private suspend fun get(path: String): JSONObject = sendJson("GET", path, body = null)

    private suspend fun sendJson(
        method: String,
        path: String,
        body: JSONObject?,
        userToken: String? = null,
        accepted: Set<Int> = setOf(200)
    ): JSONObject {
        val headers = buildMap {
            if (body != null) put("Content-Type", "application/json")
            if (!userToken.isNullOrBlank()) put("X-User-Token", userToken)
        }
        return send(
            HttpRequest(
                method = method,
                url = joinUrl(config.baseUrl, path),
                headers = headers,
                contentType = if (body != null) "application/json" else null,
                body = body?.toString()?.toByteArray(Charsets.UTF_8)
            ),
            accepted
        )
    }

    private suspend fun send(request: HttpRequest, accepted: Set<Int>): JSONObject =
        withContext(Dispatchers.IO) {
            val response = try {
                transport.execute(request)
            } catch (error: FeedbackException) {
                throw error
            } catch (error: Exception) {
                throw FeedbackException.InvalidResponse(error)
            }
            if (response.code !in accepted) {
                if (response.code == 401) throw FeedbackException.AuthenticationRequired()
                throw FeedbackException.UnexpectedStatus(response.code, response.body.ifBlank { "Unknown error" })
            }
            try {
                if (response.body.isBlank()) JSONObject() else JSONObject(response.body)
            } catch (error: Exception) {
                throw FeedbackException.InvalidResponse(error)
            }
        }

    private fun iso8601Now(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date())
    }
}
