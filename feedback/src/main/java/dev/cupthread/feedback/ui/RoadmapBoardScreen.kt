package dev.cupthread.feedback.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
 * One roadmap board column with its requests, as rendered by
 * [RoadmapBoardScreen].
 *
 * @property column The backing kanban column, or `null` for the synthetic
 *   "Other" group that collects requests without a column.
 * @property requests Requests currently in [column].
 */
data class RoadmapGroup(
    val column: BoardColumn?,
    val requests: List<FeatureRequestItem>
) {
    val id: String get() = column?.id ?: "uncategorized"
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
 * Roadmap kanban board: horizontally paged columns with chip navigation,
 * free-text search, and pull-to-refresh.
 *
 * Columns and their order come from the console; requests are grouped into
 * [RoadmapGroup]s per column. The screen applies the console-configured
 * theme itself.
 *
 * @param client Shared API client.
 * @param userToken Stable anonymous token from
 *   [dev.cupthread.feedback.UserTokenStore] used to load requests.
 * @param modifier Modifier applied to the root [Scaffold].
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
                    else -> PagedBoard(groups = visible, query = search)
                }
            }
        }
    }
}

@Composable
private fun BoxPad(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(Modifier.padding(16.dp)) { content() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PagedBoard(groups: List<RoadmapGroup>, query: String) {
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
                    RoadmapCard(item, query)
                }
            }
        }
    }
}

@Composable
private fun RoadmapCard(item: FeatureRequestItem, query: String) {
    RequestCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HighlightedText(item.title, query, MaterialTheme.typography.titleSmall, maxLines = 2)
            if (item.description.isNotBlank()) {
                if (query.isBlank()) MarkdownText(item.description, maxLines = 3)
                else HighlightedText(item.description, query, MaterialTheme.typography.bodySmall, maxLines = 3)
            }
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item.versionLabel?.let { CapsuleBadge(it, MaterialTheme.colorScheme.onSurfaceVariant, Icons.Outlined.Sell) }
                Text(
                    stringResource(R.string.cupthread_roadmap_votes_format, item.voteCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
