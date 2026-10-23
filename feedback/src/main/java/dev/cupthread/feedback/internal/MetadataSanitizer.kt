package dev.cupthread.feedback.internal

/**
 * Sanitizes feedback metadata according to the CupThread feedback intake contract.
 *
 * Enforces:
 * - Valid key names conforming to `^[A-Za-z0-9_.:-]{1,64}$`
 * - Replacement of credential-looking keys (token, secret, password, apiKey, authorization, cookie, session, etc.) with `"[redacted]"`
 * - Truncation of values to a maximum of 512 characters
 * - Capping total keys at 24, sorted deterministically
 * - Capping total serialized bytes to 8 KB (8192 bytes) by dropping trailing keys in sorted order
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

    fun sanitize(metadata: Map<String, String>): Map<String, String> {
        if (metadata.isEmpty()) return emptyMap()

        val sanitizedEntries = mutableListOf<Pair<String, String>>()
        for ((key, value) in metadata) {
            val trimmedKey = key.trim()
            if (!KEY_REGEX.matches(trimmedKey)) continue

            val sanitizedValue = if (SENSITIVE_KEY_REGEX.matches(trimmedKey)) {
                REDACTED
            } else {
                value.take(MAX_VALUE_LENGTH)
            }
            sanitizedEntries.add(trimmedKey to sanitizedValue)
        }

        // Sort deterministically and cap at max 24 keys
        val sortedEntries = sanitizedEntries
            .sortedBy { it.first }
            .take(MAX_KEYS)
            .toMutableList()

        // Enforce 8 KB total size
        fun totalByteSize(entries: List<Pair<String, String>>): Int {
            var sum = 0
            for ((k, v) in entries) {
                sum += k.toByteArray(Charsets.UTF_8).size + v.toByteArray(Charsets.UTF_8).size
            }
            return sum
        }

        while (sortedEntries.isNotEmpty() && totalByteSize(sortedEntries) > MAX_TOTAL_BYTES) {
            sortedEntries.removeAt(sortedEntries.lastIndex)
        }

        return sortedEntries.toMap()
    }
}
