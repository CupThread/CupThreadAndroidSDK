package dev.cupthread.feedback.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle

/**
 * Inline-only Markdown (bold, italic, code, links). Block syntax is flattened
 * so parent line-clamps still work, matching the Apple SDK / web previews.
 *
 * This is the renderer used for [dev.cupthread.feedback.ChangelogEntry.body]
 * and [dev.cupthread.feedback.FeatureRequestItem.description]; reuse it to
 * display those fields in custom surfaces:
 *
 * ```kotlin
 * MarkdownText(entry.body, maxLines = 3)
 * ```
 *
 * @param content Markdown source; unrecognized syntax is rendered as-is.
 * @param style Typography for the rendered text.
 * @param color Text color; links are drawn in `MaterialTheme.colorScheme.primary`.
 * @param maxLines Maximum lines before ellipsizing.
 */
@Composable
fun MarkdownText(
    content: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines: Int = Int.MAX_VALUE
) {
    Text(
        text = inlineMarkdown(content, MaterialTheme.colorScheme.primary),
        modifier = modifier,
        style = style.copy(color = color),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}

internal fun inlineMarkdown(source: String, linkColor: Color) = buildAnnotatedString {
    val flattened = source
        .replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")
        .replace(Regex("^[-*]\\s+", RegexOption.MULTILINE), "")
        .replace(Regex("^\\d+\\.\\s+", RegexOption.MULTILINE), "")
    var i = 0
    while (i < flattened.length) {
        when {
            flattened.startsWith("**", i) -> {
                val end = flattened.indexOf("**", i + 2)
                if (end > i) {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        append(flattened.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    append(flattened[i]); i++
                }
            }
            flattened.startsWith("`", i) -> {
                val end = flattened.indexOf('`', i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                        append(flattened.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(flattened[i]); i++
                }
            }
            flattened.startsWith("[", i) -> {
                val close = flattened.indexOf("](", i)
                val end = if (close > i) flattened.indexOf(')', close + 2) else -1
                if (close > i && end > close) {
                    withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                        append(flattened.substring(i + 1, close))
                    }
                    i = end + 1
                } else {
                    append(flattened[i]); i++
                }
            }
            flattened.startsWith("*", i) && !flattened.startsWith("**", i) -> {
                val end = flattened.indexOf('*', i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(flattened.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(flattened[i]); i++
                }
            }
            else -> {
                append(flattened[i]); i++
            }
        }
    }
}
