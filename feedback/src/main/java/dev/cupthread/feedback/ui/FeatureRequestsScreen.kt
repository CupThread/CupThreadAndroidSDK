package dev.cupthread.feedback.ui

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
 * Feature-request list with free-text search, version filter, optimistic
 * voting, and a bottom sheet for submitting new requests.
 *
 * Behavior details:
 * - Search is debounced (~350 ms) and matches titles and descriptions.
 * - Voting is optimistic: the vote pill updates immediately and reverts if
 *   the server rejects the toggle. Own requests cannot be voted on.
 * - Pull to refresh reloads the current view; new requests appear in the
 *   list only after moderation approval
 *   ([dev.cupthread.feedback.FeatureRequestSubmissionResult.pending]).
 *
 * The screen applies the console-configured theme itself and shows its own
 * top app bar and compose button:
 *
 * ```kotlin
 * FeatureRequestsScreen(client = client, userToken = userToken)
 * ```
 *
 * @param client Shared API client.
 * @param userToken Stable anonymous token from
 *   [dev.cupthread.feedback.UserTokenStore]; required so vote state and
 *   own-request flags resolve correctly.
 * @param modifier Modifier applied to the root [Scaffold].
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
                                        try {
                                            val result = client.toggleVote(item.id, userToken)
                                            items = items.map {
                                                if (it.id == item.id) it.withVoteState(result.voted, result.voteCount) else it
                                            }
                                        } catch (_: Exception) {
                                            items = items.map { if (it.id == item.id) previous else it }
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
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeatureRequestRow(
    item: FeatureRequestItem,
    query: String,
    voting: Boolean,
    onVote: () -> Unit
) {
    val stage = stageStyleForRequest(item)
    val anonymousLabel = stringResource(R.string.cupthread_features_anonymous)
    RequestCard {
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
                Text(
                    listOfNotNull(item.requesterName?.ifBlank { null } ?: anonymousLabel, relativeOrDate(item.createdAt)).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
