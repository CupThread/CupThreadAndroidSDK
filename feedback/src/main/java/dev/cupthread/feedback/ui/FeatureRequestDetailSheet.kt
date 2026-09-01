package dev.cupthread.feedback.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.cupthread.feedback.CommentDraft
import dev.cupthread.feedback.FeatureRequestComment
import dev.cupthread.feedback.FeatureRequestItem
import dev.cupthread.feedback.FeedbackClient
import dev.cupthread.feedback.R
import kotlinx.coroutines.launch

/**
 * Bottom sheet displaying full feature request details, comments thread,
 * author avatars, and flat `@reply` functionality.
 *
 * @param client Shared API client.
 * @param item The feature request being viewed.
 * @param userToken Stable anonymous user token for posting comments and voting.
 * @param onDismiss Callback to dismiss the detail sheet.
 * @param onVote Callback when the user toggles their vote.
 * @param voting Whether a vote request is currently in flight for this item.
 * @param onOpenProfile Callback when a user avatar/name is tapped with a valid clerkUserId.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FeatureRequestDetailSheet(
    client: FeedbackClient,
    item: FeatureRequestItem,
    userToken: String,
    onDismiss: () -> Unit,
    onVote: () -> Unit,
    voting: Boolean = false,
    onOpenProfile: (String) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var comments by remember { mutableStateOf<List<FeatureRequestComment>>(emptyList()) }
    var loadingComments by remember { mutableStateOf(true) }
    var commentsError by remember { mutableStateOf<String?>(null) }
    var replyTarget by remember { mutableStateOf<FeatureRequestComment?>(null) }
    var commentBody by remember { mutableStateOf("") }
    var authorName by remember { mutableStateOf("") }
    var posting by remember { mutableStateOf(false) }
    var postError by remember { mutableStateOf<String?>(null) }

    val anonymousLabel = stringResource(R.string.cupthread_features_anonymous)
    val stage = stageStyleForRequest(item)
    val defaultLoadError = stringResource(R.string.cupthread_comments_load_failed)
    val defaultPostError = stringResource(R.string.cupthread_comments_post_failed)

    suspend fun loadComments() {
        loadingComments = true
        commentsError = null
        try {
            comments = client.fetchComments(item.id).filter { !it.isHidden }
        } catch (ex: Exception) {
            commentsError = ex.message ?: defaultLoadError
        } finally {
            loadingComments = false
        }
    }

    LaunchedEffect(item.id) {
        loadComments()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header Row: Badges and Vote
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                FlowRow(
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CapsuleBadge(item.stageName, stage.tint, stage.icon)
                    if (item.isOwnRequest && !item.approved) {
                        CapsuleBadge(
                            stringResource(R.string.cupthread_features_pending_review),
                            MaterialTheme.colorScheme.tertiary,
                            Icons.Outlined.Schedule
                        )
                    }
                    item.versionLabel?.let {
                        CapsuleBadge(it, MaterialTheme.colorScheme.onSurfaceVariant, Icons.Outlined.Sell)
                    }
                }
                VotePill(
                    voteCount = item.voteCount,
                    hasVoted = item.hasVoted,
                    inFlight = voting,
                    enabled = !item.isOwnRequest,
                    onClick = onVote
                )
            }

            // Title
            Text(item.title, style = MaterialTheme.typography.titleLarge)

            // Requester Info Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 2.dp)
            ) {
                UserAvatar(
                    url = item.requesterAvatarUrl,
                    name = item.requesterName,
                    size = 28.dp,
                    onClick = item.requesterClerkId?.let { clerkId -> { onOpenProfile(clerkId) } }
                )
                Text(
                    text = item.requesterName?.ifBlank { null } ?: anonymousLabel,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (item.requesterClerkId != null) FontWeight.Medium else FontWeight.Normal,
                        color = if (item.requesterClerkId != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = if (item.requesterClerkId != null) {
                        Modifier.clickable { onOpenProfile(item.requesterClerkId) }
                    } else Modifier
                )
                Text(
                    "·",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    relativeOrDate(item.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Description
            if (item.description.isNotBlank()) {
                MarkdownText(
                    content = item.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Comments Section Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.cupthread_comments_title) + if (comments.isNotEmpty()) " (${comments.size})" else "",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            // Comments List
            Box(modifier = Modifier.weight(1f, fill = false).fillMaxWidth()) {
                when {
                    loadingComments -> {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                    commentsError != null -> {
                        LoadErrorState(commentsError!!) { scope.launch { loadComments() } }
                    }
                    comments.isEmpty() -> {
                        EmptyState(
                            title = stringResource(R.string.cupthread_comments_empty_title),
                            body = stringResource(R.string.cupthread_comments_empty_body)
                        )
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(comments, key = { it.id }) { comment ->
                                CommentItemRow(
                                    comment = comment,
                                    onReply = { replyTarget = comment },
                                    onOpenProfile = onOpenProfile
                                )
                            }
                        }
                    }
                }
            }

            // Reply Target Banner (if active)
            if (replyTarget != null) {
                val targetName = replyTarget?.authorName?.ifBlank { null } ?: anonymousLabel
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.Reply,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            stringResource(R.string.cupthread_comments_replying_to, targetName),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(
                        onClick = { replyTarget = null },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.cupthread_comments_cancel_reply),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            // Comment Composer Input
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (postError != null) {
                    Text(
                        postError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = authorName,
                        onValueChange = { authorName = it },
                        placeholder = { Text(stringResource(R.string.cupthread_comments_name_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.weight(0.4f),
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = commentBody,
                        onValueChange = { commentBody = it },
                        placeholder = { Text(stringResource(R.string.cupthread_comments_add_placeholder)) },
                        singleLine = false,
                        maxLines = 3,
                        modifier = Modifier.weight(0.6f),
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                    IconButton(
                        onClick = {
                            if (commentBody.trim().isEmpty() || posting) return@IconButton
                            scope.launch {
                                posting = true
                                postError = null
                                try {
                                    val draft = CommentDraft(
                                        body = commentBody.trim(),
                                        authorName = authorName.trim().ifEmpty { null },
                                        parentId = replyTarget?.id ?: replyTarget?.parentId,
                                        replyToClerkId = replyTarget?.authorClerkId,
                                        replyToAuthorName = replyTarget?.authorName
                                    )
                                    client.postComment(item.id, draft, userToken)
                                    commentBody = ""
                                    replyTarget = null
                                    loadComments()
                                } catch (ex: Exception) {
                                    postError = ex.message ?: defaultPostError
                                } finally {
                                    posting = false
                                }
                            }
                        },
                        enabled = commentBody.trim().isNotEmpty() && !posting
                    ) {
                        if (posting) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.AutoMirrored.Outlined.Send,
                                contentDescription = stringResource(R.string.cupthread_comments_submit),
                                tint = if (commentBody.trim().isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentItemRow(
    comment: FeatureRequestComment,
    onReply: () -> Unit,
    onOpenProfile: (String) -> Unit
) {
    val anonymousLabel = stringResource(R.string.cupthread_features_anonymous)
    val author = comment.authorName?.ifBlank { null } ?: anonymousLabel

    RequestCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Author & Date Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    UserAvatar(
                        url = comment.authorAvatarUrl,
                        name = comment.authorName,
                        size = 24.dp,
                        onClick = comment.authorClerkId?.let { clerkId -> { onOpenProfile(clerkId) } }
                    )
                    Text(
                        text = author,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = if (comment.authorClerkId != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = if (comment.authorClerkId != null) {
                            Modifier.clickable { onOpenProfile(comment.authorClerkId) }
                        } else Modifier
                    )
                    Text(
                        "·",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        relativeOrDate(comment.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                TextButton(
                    onClick = onReply,
                    modifier = Modifier.height(28.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Reply,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        stringResource(R.string.cupthread_comments_reply),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            // Reply-To Indicator Badge
            if (!comment.replyToAuthorName.isNullOrBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Reply,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        stringResource(R.string.cupthread_comments_replying_to, comment.replyToAuthorName),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Comment Body
            MarkdownText(
                content = comment.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
