package dev.cupthread.feedback

import dev.cupthread.feedback.internal.multipartBody
import dev.cupthread.feedback.internal.sanitizeMultipartFilename
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpMultipartTest {
    private val boundary = "Boundary-TestBoundary123"
    private val appKey = "app_testkey123456"
    private val fileData = "png-bytes".toByteArray(StandardCharsets.US_ASCII)

    @Test
    fun filenameWithCrLfIsNeutralized() {
        // #162: CR/LF in a caller-supplied filename must not be written verbatim into the
        // multipart header block, where a crafted name could terminate the header section early
        // and smuggle additional headers or parts into the request.
        val hostileName = "shot.png\r\nX-Evil: injected\r\n\r\n" +
            "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"appKey\"\r\n\r\n" +
            "other-app\r\n" +
            "--$boundary--"
        val body = String(
            multipartBody(boundary, appKey, hostileName, "image/png", fileData),
            StandardCharsets.UTF_8
        )

        // Every CR/LF in the name is rewritten to a space, so the injected text stays on the
        // single quoted Content-Disposition line instead of forming new headers or boundaries.
        val lines = body.split("\r\n")
        val dispositionLine = lines.first { it.contains("name=\"file\"") }
        assertTrue(
            dispositionLine.startsWith("Content-Disposition: form-data; name=\"file\"; filename=\"")
        )
        assertTrue(dispositionLine.contains("shot.png  X-Evil: injected"))
        assertFalse(lines.any { it.startsWith("X-Evil:") })
        // The legitimate appKey part header appears exactly once — no smuggled duplicate.
        assertEquals(1, lines.count { it == "Content-Disposition: form-data; name=\"appKey\"" })

        // Exactly the two legitimate part separators and one final terminator survive on the wire.
        assertEquals(1, body.split("\r\n--$boundary\r\n").size - 1)
        assertEquals(1, body.split("\r\n--$boundary--\r\n").size - 1)
        assertTrue(body.endsWith("\r\n--$boundary--\r\n"))
    }

    @Test
    fun filenameWithQuotesAndBackslashesStaysEscaped() {
        // Regression guard for the pre-existing escaping behavior.
        val body = String(
            multipartBody(boundary, appKey, "a\\b\"c.png", "image/png", fileData),
            StandardCharsets.UTF_8
        )
        assertTrue(body.contains("filename=\"a\\\\b\\\"c.png\""))
    }

    @Test
    fun benignFilenameProducesUnchangedBody() {
        // Sane inputs keep a byte-identical body to the pre-fix output.
        val body = String(
            multipartBody(boundary, appKey, "screenshot_42.png", "image/png", fileData),
            StandardCharsets.UTF_8
        )
        val expected = buildString {
            append("--$boundary\r\n")
            append("Content-Disposition: form-data; name=\"appKey\"\r\n\r\n")
            append("$appKey\r\n")
            append("--$boundary\r\n")
            append("Content-Disposition: form-data; name=\"file\"; filename=\"screenshot_42.png\"\r\n")
            append("Content-Type: image/png\r\n\r\n")
            append("png-bytes")
            append("\r\n--$boundary--\r\n")
        }
        assertEquals(expected, body)
    }

    @Test
    fun sanitizeMultipartFilenameReplacesControlCharactersWithSpaces() {
        assertEquals("a b c d e", sanitizeMultipartFilename("a\rb\nc\u0000d\u0007e"))
        assertEquals("tab too", sanitizeMultipartFilename("tab\ttoo"))
        assertEquals("shot.png", sanitizeMultipartFilename("shot.png"))
    }
}
