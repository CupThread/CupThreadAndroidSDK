package dev.cupthread.feedback.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.cupthread.feedback.BoardColumn
import dev.cupthread.feedback.BoardColumnKind
import dev.cupthread.feedback.FeatureRequestItem
import dev.cupthread.feedback.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal data class StageStyle(val tint: Color, val icon: ImageVector)

internal fun stageStyleForColumn(column: BoardColumn?): StageStyle {
    if (column == null) return StageStyle(Color(0xFF6750A4), Icons.Filled.CheckCircle)
    return when (column.kind) {
        BoardColumnKind.DONE -> stageStyleForSlug("done")
        BoardColumnKind.PENDING_REVIEW -> stageStyleForSlug("review")
        BoardColumnKind.NORMAL -> stageStyleForSlug(column.slug)
    }
}

internal fun stageStyleForRequest(item: FeatureRequestItem): StageStyle =
    stageStyleForSlug((item.columnSlug ?: item.status).lowercase())

private fun stageStyleForSlug(slug: String): StageStyle = when {
    slug.contains("done") || slug.contains("shipped") || slug.contains("released") ->
        StageStyle(Color(0xFF2E7D32), Icons.Filled.CheckCircle)
    slug.contains("progress") || slug.contains("doing") || slug.contains("building") ->
        StageStyle(Color(0xFFEF6C00), Icons.Outlined.Build)
    slug.contains("review") || slug.contains("consider") ->
        StageStyle(Color(0xFF1565C0), Icons.Outlined.Visibility)
    slug.contains("planned") || slug.contains("next") || slug.contains("upcoming") || slug.contains("backlog") ->
        StageStyle(Color(0xFF1565C0), Icons.Outlined.CalendarMonth)
    else -> StageStyle(Color(0xFF6750A4), Icons.Filled.CheckCircle)
}

@Composable
internal fun CapsuleBadge(
    text: String,
    tint: Color,
    icon: ImageVector? = null
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(tint.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(12.dp))
        }
        Text(
            text = text,
            color = tint,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun VotePill(
    voteCount: Int,
    hasVoted: Boolean,
    inFlight: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val tint = if (hasVoted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val scale by animateFloatAsState(if (hasVoted) 1.06f else 1f, label = "voteScale")
    val voteDesc = stringResource(R.string.cupthread_features_vote_desc, voteCount)
    val removeVoteDesc = stringResource(R.string.cupthread_features_remove_vote_desc, voteCount)
    Column(
        modifier = Modifier
            .width(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(
                1.dp,
                if (hasVoted) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(12.dp)
            )
            .background(
                if (hasVoted) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                else MaterialTheme.colorScheme.surface
            )
            .clickable(enabled = enabled && !inFlight, role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp)
            .semantics {
                contentDescription = if (hasVoted) {
                    removeVoteDesc
                } else {
                    voteDesc
                }
            }
            .scale(scale),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (inFlight) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Text(
            text = voteCount.toString(),
            style = MaterialTheme.typography.labelLarge.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = tint
            )
        )
    }
}

@Composable
internal fun RequestCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Box(Modifier.padding(14.dp)) { content() }
    }
}

@Composable
internal fun HighlightedText(
    text: String,
    query: String,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    maxLines: Int = Int.MAX_VALUE
) {
    val needle = query.trim()
    if (needle.isEmpty()) {
        Text(text = text, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        return
    }
    val annotated = buildAnnotatedString {
        val lower = text.lowercase()
        val q = needle.lowercase()
        var start = 0
        while (start <= text.length) {
            val index = lower.indexOf(q, start)
            if (index < 0) {
                append(text.substring(start))
                break
            }
            append(text.substring(start, index))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)) {
                append(text.substring(index, index + needle.length))
            }
            start = index + needle.length
        }
    }
    Text(text = annotated, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun LoadErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.cupthread_common_could_not_load), style = MaterialTheme.typography.titleMedium)
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(onClick = onRetry) {
            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.cupthread_common_retry))
        }
    }
}

@Composable
internal fun EmptyState(title: String, body: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
internal fun SkeletonCardList() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(4) { SkeletonCard() }
    }
}

@Composable
internal fun SkeletonCard() {
    val wash = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    RequestCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth(0.7f).height(16.dp).clip(RoundedCornerShape(4.dp)).background(wash))
            Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(4.dp)).background(wash))
            Box(Modifier.fillMaxWidth(0.4f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(wash))
        }
    }
}

@Composable
internal fun ColumnChip(
    name: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            )
            .then(
                if (selected) Modifier.border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), CircleShape)
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .semantics {
                contentDescription = "Column $name, $count items"
                this.selected = selected
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = tint
            )
        )
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = FontFamily.Monospace,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
    }
}

internal fun friendlyDate(iso: String): String {
    return try {
        val instant = Instant.parse(iso)
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .withZone(ZoneId.systemDefault())
            .format(instant)
    } catch (_: Exception) {
        iso.take(10)
    }
}

internal fun relativeOrDate(iso: String): String = friendlyDate(iso)
