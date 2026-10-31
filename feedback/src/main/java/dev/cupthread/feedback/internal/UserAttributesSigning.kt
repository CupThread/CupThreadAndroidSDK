package dev.cupthread.feedback.internal

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC request signing for `PUT /api/v1/public/apps/{appKey}/user`.
 *
 * The server rejects payment attributes (`isPaying`, `mrr`, `plan`) and `currency` unless the
 * request carries a valid `signature` + `timestamp` pair produced with the app's SDK signing
 * secret (422 `payment_attributes_require_signature`). The canonical string is versioned with
 * the `cpt-user-attrs-v1` prefix so the format can evolve without ambiguity.
 */
internal const val USER_ATTRS_SIGNATURE_PREFIX = "cpt-user-attrs-v1"

/**
 * Renders [mrr] using the server's `canonicalNumber` rules: exactly two fraction digits with
 * round-half-even applied to the exact IEEE 754 double value (`toFixed(2)` semantics), then
 * trailing zeros and a trailing decimal point are stripped (`1200.00` → `"1200"`, `99.50` →
 * `"99.5"`). [BigDecimal(Double)] keeps the exact binary expansion of the double, so values
 * such as `1.015` (whose binary form sits just below the decimal tie) round exactly the way
 * an IEEE 754 double formatter rounds.
 */
internal fun canonicalMrr(mrr: Double): String {
    val scaled = BigDecimal(mrr).setScale(2, RoundingMode.HALF_EVEN).toPlainString()
    return scaled.trimEnd('0').trimEnd('.')
}

/**
 * Builds the newline-joined canonical string (no trailing newline) that is HMAC-SHA256 signed:
 *
 * ```
 * cpt-user-attrs-v1
 * <appKey>
 * <userToken>
 * <isPaying: true|false|unset>
 * <plan: value|null|unset>
 * <mrr: canonicalNumber|null|unset>
 * <currency: valueAsSent|unset>
 * <timestamp: epochSeconds>
 * ```
 *
 * Absent optional fields sign as `unset`; values are signed exactly as sent in the JSON body
 * (`currency` before any server-side normalization, `userToken` as carried by `X-User-Token`).
 */
internal fun userAttributesCanonicalString(
    appKey: String,
    userToken: String,
    isPaying: Boolean?,
    plan: String?,
    mrr: Double?,
    currency: String?,
    timestampSeconds: Long
): String = listOf(
    USER_ATTRS_SIGNATURE_PREFIX,
    appKey,
    userToken,
    isPaying?.toString() ?: "unset",
    plan ?: "unset",
    mrr?.let(::canonicalMrr) ?: "unset",
    currency ?: "unset",
    timestampSeconds.toString()
).joinToString("\n")

/**
 * Computes the lowercase hex HMAC-SHA256 of [message] keyed with [secret].
 */
internal fun hmacSha256Hex(secret: String, message: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(message.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(Locale.US, it) }
}
