package dev.cupthread.feedback.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for search-result highlighting (issue #183): the scan must stay in the original
 * string's coordinates. Characters whose full Unicode lowercase mapping expands in length
 * (Turkish `İ`, U+0130 → "i\u0307") used to desynchronize the lowercased-copy scan from the
 * sliced original, crashing composition with `StringIndexOutOfBoundsException` or silently
 * highlighting the wrong characters.
 */
class SearchHighlightTest {

    private val highlightColor = Color(0xFF3366FF)

    private fun highlight(text: String, query: String): AnnotatedString =
        highlightedSearchText(text, query, highlightColor)

    private fun highlightedRanges(annotated: AnnotatedString): List<Pair<Int, Int>> =
        annotated.spanStyles.map { it.start to it.end }

    private fun highlightedRuns(annotated: AnnotatedString): List<String> =
        annotated.spanStyles.map { annotated.text.substring(it.start, it.end) }

    @Test
    fun expandingLowercaseCharacterDoesNotCrashAndHighlightsExactMatch() {
        // `İ`.lowercase() is "i\u0307" — two characters. The old lowercased-copy scan found the
        // match at index 2 in the copy and sliced the 2-character original at [2, 3), which
        // threw StringIndexOutOfBoundsException during composition.
        val annotated = highlight("İa", "a")

        assertEquals("İa", annotated.text)
        assertEquals(listOf(1 to 2), highlightedRanges(annotated))
        assertEquals(listOf("a"), highlightedRuns(annotated))
    }

    @Test
    fun expansionMisalignmentHighlightsTheMatchedCharacterNotTheNeighbor() {
        // In the lowercased copy "i̇bcd" the match sits at index 2, which pointed at "c" in the
        // original — the old code highlighted "c" instead of "b".
        val annotated = highlight("İbcd", "b")

        assertEquals("İbcd", annotated.text)
        assertEquals(listOf(1 to 2), highlightedRanges(annotated))
        assertEquals(listOf("b"), highlightedRuns(annotated))
    }

    @Test
    fun bothOccurrencesAroundMidStringExpansionAreHighlighted() {
        // "aİa" lowercases to the 4-character "ai̇a"; the second match's copy index (3) fell
        // past the end of the 3-character original and crashed.
        val annotated = highlight("aİa", "a")

        assertEquals("aİa", annotated.text)
        assertEquals(listOf(0 to 1, 2 to 3), highlightedRanges(annotated))
        assertEquals(listOf("a", "a"), highlightedRuns(annotated))
    }

    @Test
    fun plainAsciiHighlightingIsUnchanged() {
        val annotated = highlight("Hello World", "world")

        assertEquals("Hello World", annotated.text)
        assertEquals(listOf(6 to 11), highlightedRanges(annotated))
        // The highlighted run is the original substring, original casing preserved.
        assertEquals(listOf("World"), highlightedRuns(annotated))

        val repeated = highlight("abcabc", "b")
        assertEquals(listOf(1 to 2, 4 to 5), highlightedRanges(repeated))
    }

    @Test
    fun adjacentMatchesDoNotOverlapAndCoverEveryOccurrence() {
        // The greedy forward scan advances past each match; overlapping occurrences of the
        // same needle can never be re-matched inside a highlighted run.
        val annotated = highlight("aaa", "a")

        assertEquals(listOf(0 to 1, 1 to 2, 2 to 3), highlightedRanges(annotated))
        assertEquals(listOf("a", "a", "a"), highlightedRuns(annotated))
    }

    @Test
    fun blankQueryAndNoMatchRenderTheTextUnstyled() {
        for (query in listOf("", "   ", "zz")) {
            val annotated = highlight("abc", query)
            assertEquals("abc", annotated.text)
            assertTrue("query='$query' produced styles", annotated.spanStyles.isEmpty())
        }
    }

    @Test
    fun highlightedRunCarriesTheInjectedStyle() {
        val annotated = highlight("İa", "a")

        assertEquals(1, annotated.spanStyles.size)
        val span = annotated.spanStyles.single()
        assertEquals(FontWeight.Bold, span.item.fontWeight)
        assertEquals(highlightColor, span.item.color)
    }

    @Test
    fun queryIsTrimmedBeforeMatching() {
        val annotated = highlight("İbcd", " B ")

        assertEquals("İbcd", annotated.text)
        assertEquals(listOf(1 to 2), highlightedRanges(annotated))
        assertEquals(listOf("b"), highlightedRuns(annotated))
    }
}
