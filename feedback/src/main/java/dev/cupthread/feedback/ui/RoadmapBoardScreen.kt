package dev.cupthread.feedback.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import dev.cupthread.feedback.BoardColumn
import dev.cupthread.feedback.FeatureRequestItem
import dev.cupthread.feedback.FeedbackClient
import dev.cupthread.feedback.R
import dev.cupthread.feedback.SdkFeature
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Represents a grouped roadmap category consisting of a kanban [BoardColumn] and its active [FeatureRequestItem]s.
 *
 * Rendered as an individual horizontal page and filter chip inside [RoadmapBoardScreen].
 *
 * @property column The backing roadmap [BoardColumn], or `null` for the synthetic "Other" fallback group
 *   that aggregates requests with no assigned column.
 * @property requests List of [FeatureRequestItem]s categorized under [column].
 */
data class RoadmapGroup(
    val column: BoardColumn?,
    val requests: List<FeatureRequestItem>
) {
    /** Unique identifier for the group: returns [BoardColumn.id] or `"uncategorized"` when [column] is `null`. */
    val id: String get() = column?.id ?: "uncategorized"

    /** Display name of the group: returns [BoardColumn.name] or `"Other"` when [column] is `null`. */
    val name: String get() = column?.name ?: "Other"
}

private fun makeGroups(columns: List<BoardColumn>, requests: List<FeatureRequestItem>): List<RoadmapGroup> {
    val byColumn = requests.groupBy { it.columnId }
    val groups = columns.map { column ->
        RoadmapGroup(column, byColumn[column.id].orEmpty())
    }
    val uncategorized = byColumn[null].orEmpty()
    return if (uncategorized.isEmpty()) groups else groups + RoadmapGroup(null, uncategorized)
}

/**
 * Full-screen interactive Roadmap Kanban Board composable.
 *
 * Renders a horizontally paged kanban board displaying live product feature requests organized
 * across milestone stages (such as *Planned*, *In Progress*, *Completed*), as configured in the
 * CupThread developer console.
 *
 * ### Key Features
 * - **Horizontal Pager & Synchronized Chip Bar**: Swipe smoothly between columns or tap chips with badge counts.
 * - **Debounced Search**: Filters requests in real-time (~350 ms debounce). During search, empty columns are hidden.
 * - **Pull-to-Refresh**: Refreshes both columns and feature requests concurrently.
 * - **Optimistic Voting & Detail Sheet**: Tapping a request opens the interactive [FeatureRequestDetailSheet]
 *   supporting comments, author profiles, and real-time upvoting.
 * - **Remote Theming & Feature Gating**: Automatically wrapped in [SdkSurface] with [SdkFeature.ROADMAP].
 *
 * ### Example Integration
 * ```kotlin
 * @Composable
 * fun ProductRoadmapDestination(
 *     client: FeedbackClient,
 *     userTokenStore: UserTokenStore
 * ) {
 *     RoadmapBoardScreen(
 *         client = client,
 *         userToken = userTokenStore.token,
 *         modifier = Modifier.fillMaxSize()
 *     )
 * }
 * ```
 *
 * @param client Shared [FeedbackClient] used for network communication.
 * @param userToken Stable anonymous user token from [UserTokenStore] used to track vote state.
 * @param modifier Optional [Modifier] applied to the root [Scaffold] layout.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RoadmapBoardScreen(
    client: FeedbackClient,
    userToken: String,
    modifier: Modifier = Modifier
) {
    SdkSurface(client, SdkFeature.ROADMAP) {
        RoadmapBoardScreenContent(client, userToken, modifier)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun RoadmapBoardScreenContent(
    client: FeedbackClient,
    userToken: String,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var groups by remember { mutableStateOf<List<RoadmapGroup>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var hasLoaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var selectedItem by remember { mutableStateOf<FeatureRequestItem?>(null) }
    var selectedProfileUserId by remember { mutableStateOf<String?>(null) }
    var votingIds by remember { mutableStateOf(setOf<String>()) }
    val defaultLoadError = stringResource(R.string.cupthread_roadmap_load_failed)

    suspend fun load() {
        loading = true
        error = null
        try {
            val columns = client.fetchColumns()
            val requests = client.fetchFeatureRequests(
                userToken = userToken,
                query = search.trim().ifEmpty { null }
            ).requests
            groups = makeGroups(columns, requests)
        } catch (ex: Exception) {
            error = ex.message ?: defaultLoadError
        } finally {
            loading = false
            hasLoaded = true
        }
    }

    LaunchedEffect(client, userToken) {
        snapshotFlow { search }
            .distinctUntilChanged()
            .collectLatest { query ->
                if (query.isNotEmpty()) delay(350)
                load()
            }
    }

    val visible = if (search.isBlank()) groups else groups.filter { it.requests.isNotEmpty() }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.cupthread_roadmap_title)) }) }
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
                    placeholder = { Text(stringResource(R.string.cupthread_roadmap_search_placeholder)) }
                )
                when {
                    loading && !hasLoaded -> BoxPad { SkeletonCardList() }
                    error != null -> LoadErrorState(error!!) { scope.launch { load() } }
                    visible.isEmpty() -> EmptyState(
                        title = if (search.isBlank()) stringResource(R.string.cupthread_roadmap_empty_title) else stringResource(R.string.cupthread_features_empty_search_title),
                        body = if (search.isBlank()) stringResource(R.string.cupthread_roadmap_empty_body)
                        else stringResource(R.string.cupthread_features_empty_search_body, search)
                    )
                    else -> PagedBoard(
                        groups = visible,
                        query = search,
                        onCardClick = { selectedItem = it },
                        onOpenProfile = { selectedProfileUserId = it }
                    )
                }
            }
        }
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
                    groups = groups.map { grp ->
                        grp.copy(requests = grp.requests.map { if (it.id == currentItem.id) it.withVoteState(newVoted, newCount) else it })
                    }
                    selectedItem = currentItem.withVoteState(newVoted, newCount)
                    try {
                        val result = client.toggleVote(currentItem.id, userToken)
                        groups = groups.map { grp ->
                            grp.copy(requests = grp.requests.map { if (it.id == currentItem.id) it.withVoteState(result.voted, result.voteCount) else it })
                        }
                        selectedItem = currentItem.withVoteState(result.voted, result.voteCount)
                    } catch (_: Exception) {
                        groups = groups.map { grp ->
                            grp.copy(requests = grp.requests.map { if (it.id == currentItem.id) previous else it })
                        }
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

@Composable
private fun BoxPad(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(Modifier.padding(16.dp)) { content() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PagedBoard(
    groups: List<RoadmapGroup>,
    query: String,
    onCardClick: (FeatureRequestItem) -> Unit,
    onOpenProfile: (String) -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { groups.size })
    val chipState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val otherLabel = stringResource(R.string.cupthread_roadmap_column_other)

    LaunchedEffect(pagerState.currentPage, groups) {
        if (groups.isNotEmpty()) {
            chipState.animateScrollToItem(pagerState.currentPage.coerceIn(0, groups.lastIndex))
        }
    }

    LazyRow(
        state = chipState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(groups.size) { index ->
            val group = groups[index]
            ColumnChip(
                name = group.column?.name ?: otherLabel,
                count = group.requests.size,
                selected = pagerState.currentPage == index,
                onClick = { scope.launch { pagerState.animateScrollToPage(index) } }
            )
        }
    }

    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
        val group = groups[page]
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            if (group.requests.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.cupthread_roadmap_empty_column),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            } else {
                items(group.requests, key = { it.id }) { item ->
                    RoadmapCard(
                        item = item,
                        query = query,
                        onClick = { onCardClick(item) },
                        onOpenProfile = onOpenProfile
                    )
                }
            }
        }
    }
}

@Composable
private fun RoadmapCard(
    item: FeatureRequestItem,
    query: String,
    onClick: () -> Unit,
    onOpenProfile: (String) -> Unit
) {
    val anonymousLabel = stringResource(R.string.cupthread_features_anonymous)
    RequestCard(
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HighlightedText(item.title, query, MaterialTheme.typography.titleSmall, maxLines = 2)
            if (item.description.isNotBlank()) {
                if (query.isBlank()) MarkdownText(item.description, maxLines = 3)
                else HighlightedText(item.description, query, MaterialTheme.typography.bodySmall, maxLines = 3)
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
                    item.versionLabel?.let { CapsuleBadge(it, MaterialTheme.colorScheme.onSurfaceVariant, Icons.Outlined.Sell) }
                    Text(
                        stringResource(R.string.cupthread_roadmap_votes_format, item.voteCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

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
                    if (item.recentCommenters.isNotEmpty() || item.hasMoreCommenters) {
                        AvatarStack(
                            commenters = item.recentCommenters,
                            hasMore = item.hasMoreCommenters,
                            onCommenterClick = { it.clerkUserId?.let(onOpenProfile) }
                        )
                    }
                }
            }
        }
    }
}
