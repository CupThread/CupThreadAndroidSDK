package dev.cupthread.feedback.internal

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

internal data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val contentType: String? = null
)

internal data class HttpResponse(
    val code: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap()
)

internal const val REQUEST_ID_HEADER = "X-Request-Id"

internal val REQUEST_ID_PATTERN = Regex("^[A-Za-z0-9._-]{8,64}$")

internal fun interface HttpTransport {
    fun execute(request: HttpRequest): HttpResponse
}

/**
 * True when a request carries a bearer credential (`X-User-Token` or `Authorization` header).
 * Two policies key on this: redirect-following is disabled so a credential is never replayed
 * to a redirect target, and the destination scheme must be HTTPS so the credential is never
 * transmitted in cleartext (issue #199).
 */
internal fun HttpRequest.carriesCredentials(): Boolean =
    headers.any { (key, value) ->
        (key.equals("X-User-Token", ignoreCase = true) || key.equals("Authorization", ignoreCase = true)) &&
            value.isNotBlank()
    }

internal fun HttpRequest.shouldFollowRedirects(): Boolean = !carriesCredentials()

internal class UrlConnectionTransport : HttpTransport {
    override fun execute(request: HttpRequest): HttpResponse {
        val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = request.method
            connectTimeout = 15_000
            readTimeout = 20_000
            doInput = true
            instanceFollowRedirects = request.shouldFollowRedirects()
            request.headers.forEach { (key, value) -> setRequestProperty(key, value) }
            if (request.contentType != null) {
                setRequestProperty("Content-Type", request.contentType)
            }
            if (request.body != null) {
                doOutput = true
                outputStream.use { it.write(request.body) }
            }
        }
        return try {
            val stream = try {
                connection.inputStream
            } catch (_: IOException) {
                connection.errorStream
            }
            val text = stream?.bufferedReader(StandardCharsets.UTF_8).use { it?.readText().orEmpty() }
            HttpResponse(connection.responseCode, text, responseHeaders(connection))
        } finally {
            connection.disconnect()
        }
    }

    private fun responseHeaders(connection: HttpURLConnection): Map<String, String> = buildMap {
        connection.headerFields?.forEach { (name, values) ->
            if (name != null) {
                this[name] = values?.firstOrNull().orEmpty()
            }
        }
    }
}

internal fun joinUrl(baseUrl: String, path: String): String {
    val base = baseUrl.trimEnd('/')
    val suffix = if (path.startsWith("/")) path else "/$path"
    return base + suffix
}

internal fun encodeQuery(params: List<Pair<String, String>>): String {
    if (params.isEmpty()) return ""
    return params.joinToString("&") { (key, value) ->
        "${encodeComponent(key)}=${encodeComponent(value)}"
    }
}

internal fun encodeComponent(value: String): String =
    java.net.URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

/**
 * Encodes a host-supplied value interpolated into a request **path** segment (#196). `/`
 * becomes `%2F`, so the segment can neither escape its position nor inject `?`/`#` query or
 * fragment syntax; the `+`-to-`%20` rewrite already happens inside [encodeComponent].
 *
 * Dot-only ids are rejected: `URLEncoder` leaves `.` unreserved, so `"."`/`".."` would
 * survive as a literal dot path segment, and Android's URL parser normalizes those away —
 * `/api/v1/feature-requests/../vote` would target `/api/v1/vote`.
 */
internal fun encodePathSegment(value: String): String {
    val encoded = encodeComponent(value)
    require(encoded != "." && encoded != "..") {
        "path segment cannot be a dot segment: \"$value\""
    }
    return encoded
}

// A caller-supplied credential becomes an HTTP header value (`X-User-Token` / `Authorization`)
// and, on several endpoints, a JSON body field the server stores for ownership comparisons.
// CR/LF or any other C0 control character written verbatim into a header would terminate the
// header line early — turning a host-side storage bug into a request-splitting primitive —
// and a raw value in the body beside a trimmed value in the header would attribute one caller
// to two identities (issue #212).
private val CREDENTIAL_CONTROL_CHARS = Regex("[\\x00-\\x1F\\x7F]")

/**
 * Normalizes a caller-supplied credential once at the transport boundary (issue #212): trims
 * surrounding whitespace, maps a blank value to `null`, and rejects CR/LF or any other C0
 * control character that survives the trim with an `IllegalArgumentException` naming
 * [parameterName]. `buildHeaders` routes `X-User-Token` and `Authorization` through this
 * helper, and every JSON body writer applies it before writing `userToken`/`requesterToken`,
 * so the header identity and the body identity of one request can never diverge.
 */
internal fun normalizeUserToken(value: String?, parameterName: String = "userToken"): String? {
    val trimmed = value?.trim()
    if (trimmed.isNullOrEmpty()) return null
    require(!CREDENTIAL_CONTROL_CHARS.containsMatchIn(trimmed)) {
        "$parameterName cannot contain CR/LF or other control characters"
    }
    return trimmed
}

// A caller-supplied filename is interpolated into the raw multipart header block. CR/LF (and any
// other C0 control character) written verbatim would let a crafted name terminate the header
// section early and smuggle additional header lines or multipart parts into the request (#162).
internal val MULTIPART_FILENAME_CONTROL_CHARS = Regex("[\\x00-\\x1F\\x7F]")

/**
 * Rewrites control characters (including CR/LF) in a caller-supplied multipart filename to spaces
 * so the name stays a single line inside its quoted `Content-Disposition` attribute.
 */
internal fun sanitizeMultipartFilename(filename: String): String =
    filename.replace(MULTIPART_FILENAME_CONTROL_CHARS, " ")

internal fun multipartBody(
    boundary: String,
    appKey: String,
    filename: String,
    mimeType: String,
    fileData: ByteArray
): ByteArray {
    val output = ByteArrayOutputStream()
    fun write(text: String) = output.write(text.toByteArray(StandardCharsets.UTF_8))
    val safeName = sanitizeMultipartFilename(filename).replace("\\", "\\\\").replace("\"", "\\\"")
    write("--$boundary\r\n")
    write("Content-Disposition: form-data; name=\"appKey\"\r\n\r\n")
    write("$appKey\r\n")
    write("--$boundary\r\n")
    write("Content-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\r\n")
    write("Content-Type: $mimeType\r\n\r\n")
    output.write(fileData)
    write("\r\n--$boundary--\r\n")
    return output.toByteArray()
}
