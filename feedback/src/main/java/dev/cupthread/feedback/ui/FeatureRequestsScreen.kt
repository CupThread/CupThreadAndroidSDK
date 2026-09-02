package dev.cupthread.feedback.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.cupthread.feedback.AppVersion
import dev.cupthread.feedback.FeatureRequestDraft
import dev.cupthread.feedback.FeatureRequestItem
import dev.cupthread.feedback.FeedbackClient
import dev.cupthread.feedback.R
import dev.cupthread.feedback.SdkFeature
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Full-screen interactive Feature Requests board and proposal submission screen.
 *
 * Renders a searchable, filterable list of community feature proposals with live vote counters,
 * status badges, author avatars, and an interactive Floating Action Button to draft new requests.
 *
 * ### Key Behaviors & Architecture
 * - **Debounced Search**: Free-text search matching titles and descriptions, debounced at ~350 ms.
 * - **Version Filter**: Top app bar dropdown menu that filters requests by milestone release ([AppVersion]).
 * - **Optimistic Upvoting**: Vote pill immediately animates count increment/decrement and automatically
 *   reconciles with server response or reverts on failure. Users cannot vote on their own requests ([FeatureRequestItem.isOwnRequest]).
 * - **New Request Bottom Sheet**: Bottom sheet modal allowing users to draft and submit new proposals
 *   ([FeatureRequestDraft]). Handles moderation feedback ([FeatureRequestSubmissionResult.pending]).
 * - **Detail & Profile Sheets**: Tapping a card opens [FeatureRequestDetailSheet] for discussions and replies,
 *   while tapping user avatars opens [UserProfileSheet].
 * - **Remote Theming & Feature Gating**: Automatically wrapped in [SdkSurface] with [SdkFeature.FEATURE_REQUESTS].
 *
 * ### Example Integration
 * ```kotlin
 * @Composable
 * fun CommunityFeedbackTab(
 *     client: FeedbackClient,
 *     userTokenStore: UserTokenStore
 * ) {
 *     FeatureRequestsScreen(
 *         client = client,
 *         userToken = userTokenStore.token,
 *         modifier = Modifier.fillMaxSize()
 *     )
 * }
 * ```
 *
 * @param client Shared [FeedbackClient] instance used for network queries and mutations.
 * @param userToken Stable anonymous user token from [UserTokenStore] used to track vote state and ownership.
 * @param modifier Optional [Modifier] applied to the root [Scaffold] container.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FeatureRequestsScreen(
    client: FeedbackClient,
    userToken: String,
    modifier: Modifier = Modifier
) {
    SdkSurface(client, SdkFeature.FEATURE_REQUESTS) {
        FeatureRequestsScreenContent(client, userToken, modifier)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FeatureRequestsScreenContent(
    client: FeedbackClient,
    userToken: String,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<FeatureRequestItem>>(emptyList()) }
    var versions by remember { mutableStateOf<List<AppVersion>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var hasLoaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var versionId by remember { mutableStateOf<String?>(null) }
    var votingIds by remember { mutableStateOf(setOf<String>()) }
    var composeOpen by remember { mutableStateOf(false) }
    var submittedBanner by remember { mutableStateOf(false) }
    var filterOpen by remember { mutableStateOf(false) }
    var selectedItem by remember { mutableStateOf<FeatureRequestItem?>(null) }
    var selectedProfileUserId by remember { mutableStateOf<String?>(null) }
    val defaultLoadError = stringResource(R.string.cupthread_features_load_failed)

    suspend fun load() {
        loading = true
        error = null
        try {
            items = client.fetchFeatureRequests(
                userToken = userToken,
                versionId = versionId,
                query = search.trim().ifEmpty { null }
            ).requests
        } catch (ex: Exception) {
            error = ex.message ?: defaultLoadError
        } finally {
            loading = false
            hasLoaded = true
        }
    }

    LaunchedEffect(client) {
        versions = runCatching { client.fetchVersions() }.getOrDefault(emptyList())
    }

    LaunchedEffect(versionId) {
        snapshotFlow { search }
            .distinctUntilChanged()
            .collectLatest { query ->
                if (query.isNotEmpty()) delay(350)
                load()
            }
    }

    LaunchedEffect(submittedBanner) {
        if (submittedBanner) {
            delay(4_000)
            submittedBanner = false
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cupthread_features_title)) },
                actions = {
                    Box {
                        IconButton(onClick = { filterOpen = true }, enabled = versions.isNotEmpty()) {
                            Icon(Icons.Outlined.FilterList, contentDescription = stringResource(R.string.cupthread_features_filter_by_version))
                        }
                        DropdownMenu(expanded = filterOpen, onDismissRequest = { filterOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.cupthread_features_all_versions)) },
                                onClick = {
                                    versionId = null
                                    filterOpen = false
                                }
                            )
                            versions.forEach { version ->
                                DropdownMenuItem(
                                    text = { Text(version.label) },
                                    onClick = {
                                        versionId = version.id
                                        filterOpen = false
                                    }
                                )
                            }
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { composeOpen = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.cupthread_features_request_feature))
            }
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = loading && hasLoaded,
            onRefresh = { scope.launch { load() } },
            modifier = Modifier.padding(padding).fillMaxSize()
        ) {
            Column(Modifier.fillMaxSize()) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.cupthread_features_search_placeholder)) }
                )
                when {
                    loading && !hasLoaded -> Box(Modifier.padding(16.dp)) { SkeletonCardList() }
                    error != null -> LoadErrorState(error!!) { scope.launch { load() } }
                    items.isEmpty() -> EmptyState(
                        title = if (search.isBlank()) stringResource(R.string.cupthread_features_empty_title) else stringResource(R.string.cupthread_features_empty_search_title),
                        body = if (search.isBlank()) stringResource(R.string.cupthread_features_empty_body)
                        else stringResource(R.string.cupthread_features_empty_search_body, search),
                        actionLabel = if (search.isBlank()) stringResource(R.string.cupthread_features_request_feature) else null,
                        onAction = if (search.isBlank()) ({ composeOpen = true }) else null
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (submittedBanner) {
                            item {
                                Text(
                                    stringResource(R.string.cupthread_features_submitted_banner),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                        }
                        items(items, key = { it.id }) { item ->
                            FeatureRequestRow(
                                item = item,
                                query = search,
                                voting = votingIds.contains(item.id),
                                onClick = { selectedItem = item },
                                onOpenProfile = { selectedProfileUserId = it },
                                onVote = {
                                    scope.launch {
                                        if (item.isOwnRequest || votingIds.contains(item.id)) return@launch
                                        votingIds = votingIds + item.id
                                        val previous = item
                                        items = items.map {
                                            if (it.id == item.id) {
                                                it.withVoteState(!it.hasVoted, if (it.hasVoted) it.voteCount - 1 else it.voteCount + 1)
                                            } else it
                                        }
                                        selectedItem = if (selectedItem?.id == item.id) {
                                            selectedItem?.withVoteState(!item.hasVoted, if (item.hasVoted) item.voteCount - 1 else item.voteCount + 1)
                                        } else selectedItem
                                        try {
                                            val result = client.toggleVote(item.id, userToken)
                                            items = items.map {
                                                if (it.id == item.id) it.withVoteState(result.voted, result.voteCount) else it
                                            }
                                            if (selectedItem?.id == item.id) {
                                                selectedItem = selectedItem?.withVoteState(result.voted, result.voteCount)
                                            }
                                        } catch (_: Exception) {
                                            items = items.map { if (it.id == item.id) previous else it }
                                            if (selectedItem?.id == item.id) {
                                                selectedItem = previous
                                            }
                                        } finally {
                                            votingIds = votingIds - item.id
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (composeOpen) {
        FeatureRequestComposeSheet(
            client = client,
            userToken = userToken,
            onDismiss = { composeOpen = false },
            onSubmitted = {
                composeOpen = false
                submittedBanner = true
                scope.launch { load() }
            }
        )
    }

    selectedItem?.let { currentItem ->
        FeatureRequestDetailSheet(
            client = client,
            item = currentItem,
            userToken = userToken,
            onDismiss = { selectedItem = null },
            voting = votingIds.contains(currentItem.id),
            onOpenProfile = { selectedProfileUserId = it },
            onVote = {
                scope.launch {
                    if (currentItem.isOwnRequest || votingIds.contains(currentItem.id)) return@launch
                    votingIds = votingIds + currentItem.id
                    val previous = currentItem
                    val newVoted = !currentItem.hasVoted
                    val newCount = if (currentItem.hasVoted) currentItem.voteCount - 1 else currentItem.voteCount + 1
                    items = items.map { if (it.id == currentItem.id) it.withVoteState(newVoted, newCount) else it }
                    selectedItem = currentItem.withVoteState(newVoted, newCount)
                    try {
                        val result = client.toggleVote(currentItem.id, userToken)
                        items = items.map { if (it.id == currentItem.id) it.withVoteState(result.voted, result.voteCount) else it }
                        selectedItem = currentItem.withVoteState(result.voted, result.voteCount)
                    } catch (_: Exception) {
                        items = items.map { if (it.id == currentItem.id) previous else it }
                        selectedItem = previous
                    } finally {
                        votingIds = votingIds - currentItem.id
                    }
                }
            }
        )
    }

    selectedProfileUserId?.let { userId ->
        UserProfileSheet(
            client = client,
            userId = userId,
            onDismiss = { selectedProfileUserId = null }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeatureRequestRow(
    item: FeatureRequestItem,
    query: String,
    voting: Boolean,
    onClick: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onVote: () -> Unit
) {
    val stage = stageStyleForRequest(item)
    val anonymousLabel = stringResource(R.string.cupthread_features_anonymous)
    RequestCard(
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HighlightedText(item.title, query, MaterialTheme.typography.titleSmall, maxLines = 2)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CapsuleBadge(item.stageName, stage.tint, stage.icon)
                    if (item.isOwnRequest && !item.approved) {
                        CapsuleBadge(stringResource(R.string.cupthread_features_pending_review), MaterialTheme.colorScheme.tertiary, Icons.Outlined.Schedule)
                    }
                    item.versionLabel?.let { CapsuleBadge(it, MaterialTheme.colorScheme.onSurfaceVariant, Icons.Outlined.Sell) }
                }
                if (item.description.isNotBlank()) {
                    if (query.isBlank()) {
                        MarkdownText(item.description, maxLines = 3)
                    } else {
                        HighlightedText(item.description, query, MaterialTheme.typography.bodySmall, maxLines = 3)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        UserAvatar(
                            url = item.requesterAvatarUrl,
                            name = item.requesterName,
                            size = 18.dp,
                            onClick = item.requesterClerkId?.let { clerkId -> { onOpenProfile(clerkId) } }
                        )
                        Text(
                            text = item.requesterName?.ifBlank { null } ?: anonymousLabel,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = if (item.requesterClerkId != null) FontWeight.Medium else FontWeight.Normal,
                                color = if (item.requesterClerkId != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
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

                    if (item.recentCommenters.isNotEmpty() || item.hasMoreCommenters) {
                        AvatarStack(
                            commenters = item.recentCommenters,
                            hasMore = item.hasMoreCommenters,
                            onCommenterClick = { it.clerkUserId?.let(onOpenProfile) }
                        )
                    }
                }
            }
            VotePill(item.voteCount, item.hasVoted, voting, enabled = !item.isOwnRequest, onClick = onVote)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeatureRequestComposeSheet(
    client: FeedbackClient,
    userToken: String,
    onDismiss: () -> Unit,
    onSubmitted: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val canSubmit = title.trim().length >= 3 && description.trim().length >= 5
    val defaultSubmitError = stringResource(R.string.cupthread_features_submit_failed)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.cupthread_features_compose_title), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.cupthread_feedback_title_label)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(description, { description = it }, label = { Text(stringResource(R.string.cupthread_feedback_desc_label)) }, modifier = Modifier.fillMaxWidth(), minLines = 4)
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.cupthread_features_your_name_optional)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            if (error != null) {
                Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(
                onClick = {
                    scope.launch {
                        submitting = true
                        error = null
                        try {
                            client.submitFeatureRequest(
                                FeatureRequestDraft(title, description, name),
                                userToken
                            )
                            onSubmitted()
                        } catch (ex: Exception) {
                            error = ex.message ?: defaultSubmitError
                        } finally {
                            submitting = false
                        }
                    }
                },
                enabled = canSubmit && !submitting,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(if (submitting) stringResource(R.string.cupthread_features_compose_sending) else stringResource(R.string.cupthread_features_compose_submit))
            }
        }
    }
}
