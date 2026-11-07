package dev.cupthread.feedback.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the inline Markdown parser backing [MarkdownText] (issue #158):
 * `[title](url)` links must carry a `LinkAnnotation.Url` tap target gated by the
 * `WebUrlPolicy`, while bold/code parsing stays unchanged.
 */
class MarkdownTextTest {

    private val linkColor = Color(0xFF3366FF)

    private fun linkAnnotations(source: String): AnnotatedString {
        val text = inlineMarkdown(source, linkColor)
        return text
    }

    private fun urlLinks(text: AnnotatedString) =
        text.getLinkAnnotations(0, text.length)
            .map { it.item as LinkAnnotation.Url }
            .toList()

    @Test
    fun linkProducesTappableUrlAnnotation() {
        val text = linkAnnotations("[CupThread](https://cupthread.com)")
        assertEquals("CupThread", text.text)

        val links = urlLinks(text)
        assertEquals(1, links.size)
        val link = links.single()
        assertEquals("https://cupthread.com", link.url)

        // The annotation covers exactly the rendered label range.
        val range = text.getLinkAnnotations(0, text.length).single()
        assertEquals(0, range.start)
        assertEquals("CupThread".length, range.end)

        // The label keeps the link styling (primary color + underline).
        assertEquals(linkColor, link.styles?.style?.color)
        assertEquals(TextDecoration.Underline, link.styles?.style?.textDecoration)
    }

    @Test
    fun unsafeSchemeRendersAsStyledTextWithoutTapTarget() {
        // Note: URLs containing ')' are truncated by the simple parser before the
        // policy check; javascript:void keeps the test focused on the policy gate.
        val text = linkAnnotations("[evil](javascript:void)")
        assertEquals("evil", text.text)
        assertEquals(0, urlLinks(text).size)

        // Still styled like a link, just not tappable.
        val styleRange = text.spanStyles.single()
        assertEquals(TextDecoration.Underline, styleRange.item.textDecoration)
    }

    @Test
    fun urlWithEmbeddedCredentialsIsNotLinked() {
        val text = linkAnnotations("[phish](https://user:pass@cupthread.com)")
        assertEquals("phish", text.text)
        assertEquals(0, urlLinks(text).size)
    }

    @Test
    fun schemeLessUrlIsNormalizedToHttps() {
        val text = linkAnnotations("[Blog](cupthread.com/blog)")
        assertEquals("Blog", text.text)

        val links = urlLinks(text)
        assertEquals(1, links.size)
        assertEquals("https://cupthread.com/blog", links.single().url)
    }

    @Test
    fun boldAndCodeParsingUnchanged() {
        val text = linkAnnotations("**bold** and `code`")
        assertEquals("bold and code", text.text)
        assertEquals(0, urlLinks(text).size)

        val bold = text.spanStyles.first { it.item.fontWeight == FontWeight.SemiBold }
        assertEquals(0, bold.start)
        assertEquals("bold".length, bold.end)

        val code = text.spanStyles.first { it.item.fontFamily == FontFamily.Monospace }
        assertEquals("bold and ".length, code.start)
        assertEquals("bold and code".length, code.end)
    }

    @Test
    fun incompleteLinkSyntaxStaysLiteral() {
        val text = linkAnnotations("[not a link]")
        assertEquals("[not a link]", text.text)
        assertEquals(0, urlLinks(text).size)
    }
}
