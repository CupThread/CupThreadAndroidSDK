package dev.cupthread.feedback

import dev.cupthread.feedback.internal.MetadataSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataSanitizerTest {

    @Test
    fun returnsEmptyMapWhenInputIsEmpty() {
        val result = MetadataSanitizer.sanitize(emptyMap())
        assertTrue(result.isEmpty())
    }

    @Test
    fun dropsKeysWithInvalidCharactersOrLength() {
        val input = mapOf(
            "valid_key" to "value1",
            "also.valid:123-abc" to "value2",
            "invalid key with spaces" to "drop_me",
            "invalid@char$" to "drop_me",
            "" to "empty_key_drop",
            "a".repeat(65) to "too_long_key_drop"
        )
        val result = MetadataSanitizer.sanitize(input)
        assertEquals(2, result.size)
        assertTrue(result.containsKey("valid_key"))
        assertTrue(result.containsKey("also.valid:123-abc"))
        assertFalse(result.containsKey("invalid key with spaces"))
        assertFalse(result.containsKey("invalid@char$"))
    }

    @Test
    fun redactsSensitiveCredentialLookingKeys() {
        val sensitiveKeys = listOf(
            "user_token",
            "authToken",
            "api_key",
            "client_secret",
            "user_password",
            "session_id",
            "bearer_token",
            "auth_credential",
            "set-cookie",
            "AUTHORIZATION"
        )
        val input = sensitiveKeys.associateWith { "super_secret_value_123" } + mapOf(
            "device_model" to "Pixel 8 Pro",
            "app_version" to "1.2.3"
        )

        val result = MetadataSanitizer.sanitize(input)

        for (k in sensitiveKeys) {
            assertEquals("[redacted]", result[k])
        }
        assertEquals("Pixel 8 Pro", result["device_model"])
        assertEquals("1.2.3", result["app_version"])
    }

    @Test
    fun truncatesValuesExceeding512Characters() {
        val longValue = "A".repeat(600)
        val input = mapOf("long_data" to longValue)

        val result = MetadataSanitizer.sanitize(input)
        assertEquals(512, result["long_data"]?.length)
        assertEquals("A".repeat(512), result["long_data"])
    }

    @Test
    fun capsAt24KeysSortedAlphabetically() {
        val input = (1..30).associate { i ->
            String.format("key_%02d", i) to "val_$i"
        }

        val result = MetadataSanitizer.sanitize(input)
        assertEquals(24, result.size)
        assertEquals("val_1", result["key_01"])
        assertEquals("val_24", result["key_24"])
        assertNull(result["key_25"])
        assertNull(result["key_30"])
    }

    @Test
    fun capsTotalSerializedSizeAt8Kilobytes() {
        // Create 20 keys with 500-char values (~10 KB total)
        val input = (1..20).associate { i ->
            String.format("big_entry_%02d", i) to "X".repeat(500)
        }

        val result = MetadataSanitizer.sanitize(input)
        var totalBytes = 0
        for ((k, v) in result) {
            totalBytes += k.toByteArray(Charsets.UTF_8).size + v.toByteArray(Charsets.UTF_8).size
        }

        assertTrue("Total bytes must be <= 8192, but was $totalBytes", totalBytes <= 8192)
        assertTrue("Result should keep as many sorted entries as fit within 8 KB", result.isNotEmpty())
    }
}
