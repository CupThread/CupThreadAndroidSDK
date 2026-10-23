package dev.cupthread.feedback.internal

import org.json.JSONObject

/**
 * Sanitizes feedback metadata according to the CupThread feedback intake contract.
 *
 * Enforces:
 * - Valid key names conforming to `^[A-Za-z0-9_.:-]{1,64}$`
 * - Replacement of credential-looking keys (token, secret, password, apiKey, authorization, cookie, session, etc.) with `"[redacted]"`
 * - Truncation of values to a maximum of 512 characters
 * - Capping total keys at 24 (including default metadata entries like sdk, platform, submittedAt), sorted deterministically
 * - Capping total serialized bytes to 8 KB (8192 bytes) of the fully serialized final JSONObject by dropping trailing custom keys in sorted order
 */
internal object MetadataSanitizer {
    val KEY_REGEX = Regex("^[A-Za-z0-9_.:-]{1,64}$")
    val SENSITIVE_KEY_REGEX = Regex(
        "(?i).*(token|secret|password|api[_-]?key|authorization|cookie|session|bearer|auth|credential).*"
    )
    const val MAX_KEYS = 24
    const val MAX_VALUE_LENGTH = 512
    const val MAX_TOTAL_BYTES = 8192
    const val REDACTED = "[redacted]"

    fun sanitize(
        metadata: Map<String, String>,
        defaults: Map<String, String> = emptyMap()
    ): Map<String, String> {
        val reservedSlots = defaults.size
        val maxCustomKeys = (MAX_KEYS - reservedSlots).coerceAtLeast(0)

        val sanitizedEntries = mutableListOf<Pair<String, String>>()
        for ((key, value) in metadata) {
            val trimmedKey = key.trim()
            if (defaults.containsKey(trimmedKey)) continue
            if (!KEY_REGEX.matches(trimmedKey)) continue

            val sanitizedValue = if (SENSITIVE_KEY_REGEX.matches(trimmedKey)) {
                REDACTED
            } else {
                value.take(MAX_VALUE_LENGTH)
            }
            sanitizedEntries.add(trimmedKey to sanitizedValue)
        }

        // Sort deterministically and cap at available slots (accounting for defaults)
        val sortedCustomEntries = sanitizedEntries
            .sortedBy { it.first }
            .take(maxCustomKeys)
            .toMutableList()

        // Enforce 8 KB total size on the fully serialized final JSONObject
        fun totalJsonByteSize(custom: List<Pair<String, String>>): Int {
            val json = JSONObject()
            for ((k, v) in custom) {
                json.put(k, v)
            }
            for ((k, v) in defaults) {
                json.put(k, v)
            }
            return json.toString().toByteArray(Charsets.UTF_8).size
        }

        while (sortedCustomEntries.isNotEmpty() && totalJsonByteSize(sortedCustomEntries) > MAX_TOTAL_BYTES) {
            sortedCustomEntries.removeAt(sortedCustomEntries.lastIndex)
        }

        val result = sortedCustomEntries.toMap().toMutableMap()
        result.putAll(defaults)
        return result
    }
}
