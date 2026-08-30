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
    val body: String
)

internal fun interface HttpTransport {
    fun execute(request: HttpRequest): HttpResponse
}

internal class UrlConnectionTransport : HttpTransport {
    override fun execute(request: HttpRequest): HttpResponse {
        val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = request.method
            connectTimeout = 15_000
            readTimeout = 20_000
            doInput = true
            instanceFollowRedirects = true
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
            HttpResponse(connection.responseCode, text)
        } finally {
            connection.disconnect()
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

internal fun multipartBody(
    boundary: String,
    appKey: String,
    filename: String,
    mimeType: String,
    fileData: ByteArray
): ByteArray {
    val output = ByteArrayOutputStream()
    fun write(text: String) = output.write(text.toByteArray(StandardCharsets.UTF_8))
    val safeName = filename.replace("\\", "\\\\").replace("\"", "\\\"")
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
