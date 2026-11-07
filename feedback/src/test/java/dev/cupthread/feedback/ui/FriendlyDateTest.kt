package dev.cupthread.feedback.ui

import dev.cupthread.feedback.internal.parseChangelogTimestamp
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Unit tests for [friendlyDate]'s timestamp parsing policy (issue #202): UI date rendering must
 * go through the same [parseChangelogTimestamp] helper as the data layer, because bare
 * `Instant.parse` rejects numeric-offset timestamps (`2026-01-01T23:00:00+05:00`) on the older
 * runtimes this SDK supports, silently degrading the rendered date to a raw string prefix.
 *
 * The fixed zone/locale keeps `DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)` output
 * deterministic: `Locale.US` MEDIUM dates render as `MMM d, yyyy` (e.g. `Jan 1, 2026`).
 */
class FriendlyDateTest {

    private val zone = ZoneId.of("America/New_York")
    private val locale = Locale.US

    @Test
    fun numericOffsetTimestampsRenderTheLocalizedDateOfTheParsedInstant() {
        // 2026-01-01T23:00:00+05:00 == 2026-01-01T18:00:00Z == Jan 1, 13:00 in New York.
        // Before the fix this degraded to the raw prefix "2026-01-01" on older runtimes.
        assertEquals("Jan 1, 2026", friendlyDate("2026-01-01T23:00:00+05:00", zone, locale))
    }

    @Test
    fun numericOffsetAndZSuffixFormsOfTheSameInstantRenderIdentically() {
        val numericOffset = "2026-01-01T23:00:00+05:00"
        val zSuffix = "2026-01-01T18:00:00Z"
        assertEquals(parseChangelogTimestamp(zSuffix), parseChangelogTimestamp(numericOffset))
        assertEquals(friendlyDate(zSuffix, zone, locale), friendlyDate(numericOffset, zone, locale))
    }

    @Test
    fun numericOffsetTimestampsRespectTheRenderZone() {
        // 18:00Z is Jan 2, 07:00 in Auckland (UTC+13 in January) — the device-timezone date,
        // which the old `iso.take(10)` fallback could never express.
        assertEquals(
            "Jan 2, 2026",
            friendlyDate("2026-01-01T23:00:00+05:00", ZoneId.of("Pacific/Auckland"), locale)
        )
    }

    @Test
    fun zSuffixTimestampsKeepParsingAndFormatting() {
        assertEquals("Jan 1, 2026", friendlyDate("2026-01-01T18:00:00Z", zone, locale))
        assertEquals("Jan 1, 2026", friendlyDate("2026-01-01T18:00:00.000Z", zone, locale))
    }

    @Test
    fun parsingMatchesTheDataLayerPolicyForTheSameFieldValue() {
        // The wire format demonstrably includes numeric offsets (ChangelogPagingTest pins one as
        // a server-emitted publishedAt); the UI must resolve the identical Instant.
        assertEquals(
            Instant.parse("2026-01-01T18:00:00Z"),
            parseChangelogTimestamp("2026-01-01T23:00:00+05:00")
        )
    }

    @Test
    fun unparseableInputFallsBackToTheRawStringPrefix() {
        assertEquals("not-a-date", friendlyDate("not-a-date", zone, locale))
        assertEquals("2026-13-45", friendlyDate("2026-13-45T99:00:00+05:00", zone, locale))
        assertEquals("2026-01-01", friendlyDate("2026-01-01", zone, locale))
        assertEquals("", friendlyDate("", zone, locale))
    }
}
