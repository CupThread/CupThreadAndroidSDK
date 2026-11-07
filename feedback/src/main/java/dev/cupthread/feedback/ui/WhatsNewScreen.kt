package dev.cupthread.feedback.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.ExperimentalMaterial3Api
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
import dev.cupthread.feedback.ChangelogEntry
import dev.cupthread.feedback.ChangelogLinkedRequest
import dev.cupthread.feedback.FeedbackClient
import dev.cupthread.feedback.FeedbackException
import dev.cupthread.feedback.UserTokenStore
import dev.cupthread.feedback.R
import dev.cupthread.feedback.SdkFeature
import dev.cupthread.feedback.internal.ChangelogPager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Full-screen "What's New" and Product Changelog Screen composable.
 *
 * Renders a reverse-chronological timeline of published release notes and product announcements
 * with inline Markdown rendering, version badges, linked feature requests, pull-to-refresh,
 * and an integrated email newsletter subscription sheet.
 *
 * ### Key Features & Architecture
 * - **Chronological Release Timeline**: Fetches published [ChangelogEntry] items via [FeedbackClient.fetchChangelog]
 *   and displays formatted release titles, publication dates, and rich Markdown content.
 * - **Linked Feature Badges**: Highlights completed user requests associated with each release via [ChangelogLinkedRequest].
 * - **Email Subscription Modal**: The top bar mail icon and footer card open an interactive modal bottom sheet
 *   allowing users to subscribe to product update emails (or unsubscribe) directly from the device.
 * - **Remote Theming & Feature Gating**: Automatically wrapped in [SdkSurface] with [SdkFeature.CHANGELOG].
 *
 * ### Example Integration
 * ```kotlin
 * @Composable
 * fun ProductUpdatesTab(
 *     client: FeedbackClient,
 *     userTokenStore: UserTokenStore
 * ) {
 *     WhatsNewScreen(
 *         client = client,
 *         userToken = userTokenStore.token,
 *         modifier = Modifier.fillMaxSize()
 *     )
 * }
 * ```
 *
 * ### Screenshot
 * <img src="https://raw.githubusercontent.com/CupThread/CupThreadAndroidSDK/main/docs/images/whats_new.jpg" width="320" alt="What's New Screen" />
 *
 * @param client Shared [FeedbackClient] instance used to load changelog entries and manage email subscriptions.
 * @param userToken Stable anonymous user token from [UserTokenStore] used to authenticate subscription actions.
 * @param modifier Optional [Modifier] applied to the root [Scaffold] layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewScreen(
    client: FeedbackClient,
    userToken: String,
    modifier: Modifier = Modifier
) {
    SdkSurface(client, SdkFeature.CHANGELOG) {
        WhatsNewScreenContent(client, userToken, modifier)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WhatsNewScreenContent(
    client: FeedbackClient,
    userToken: String,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val pager = remember(client) {
        ChangelogPager { limit, cursor -> client.fetchChangelogPage(limit, cursor) }
    }
    var entries by remember { mutableStateOf<List<ChangelogEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    // Identifies which load() owns `loading`; a superseded refresh must not hide the
    // indicator while a newer reload is still running.
    var loadGeneration by remember { mutableStateOf(0) }
    var hasLoaded by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var loadMoreFailed by remember { mutableStateOf(false) }
    // Non-null while the last refresh of already-rendered entries failed: the list stays
    // mounted (issue #163) and the value is the failure message shown by the inline retry banner.
    var refreshFailed by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var subscribeOpen by remember { mutableStateOf(false) }
    val defaultLoadError = stringResource(R.string.cupthread_whatsnew_empty_body)

    suspend fun load() {
        val generation = ++loadGeneration
        loading = true
        error = null
        loadMoreFailed = false
        refreshFailed = null
        try {
            // When the pager discards this page because a newer load superseded it, keep the
            // newer load's entries on screen instead of overwriting them.
            val page = pager.loadFirstPage() ?: return
            entries = page.entries
            hasMore = pager.hasMore
            // Set on success only (like the other bundled screens): a failed first load must
            // not make later attempts look like refreshes of preserved content.
            hasLoaded = true
        } catch (ex: FeedbackException.AuthenticationRequired) {
            error = ex.message
        } catch (ex: Exception) {
            if (hasLoaded) {
                // Keep the last successful entries on screen: a failed refresh must not wipe
                // already-rendered content (issue #163). The pager already reset its paging
                // position, so paging must not continue into the retained entries.
                hasMore = false
                refreshFailed = ex.message ?: defaultLoadError
            } else {
                error = ex.message ?: defaultLoadError
            }
        } finally {
            if (generation == loadGeneration) loading = false
        }
    }

    suspend fun loadMore() {
        if (loading || loadingMore || !hasMore) return
        loadingMore = true
        loadMoreFailed = false
        try {
            val page = pager.loadNextPage() ?: return
            entries = ChangelogPager.append(entries, page.entries)
            hasMore = pager.hasMore
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            loadMoreFailed = true
        } finally {
            loadingMore = false
        }
    }

    LaunchedEffect(client) { load() }

    LaunchedEffect(listState, hasMore, entries.size) {
        if (!hasMore) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { lastVisible ->
                if (lastVisible >= entries.size - 3) loadMore()
            }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cupthread_whatsnew_title)) },
                actions = {
                    IconButton(onClick = { subscribeOpen = true }) {
                        Icon(Icons.Outlined.MailOutline, contentDescription = stringResource(R.string.cupthread_whatsnew_subscribe))
                    }
                }
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = loading && hasLoaded,
            onRefresh = { scope.launch { load() } },
            modifier = Modifier.padding(padding).fillMaxSize()
        ) {
            when {
                loading && !hasLoaded -> androidx.compose.foundation.layout.Box(Modifier.padding(16.dp)) { SkeletonCardList() }
                error != null -> LoadErrorState(error!!) { scope.launch { load() } }
                else -> Column(Modifier.fillMaxSize()) {
                    // A failed refresh keeps the rendered entries and shows this fixed,
                    // always-visible retry banner instead of the full-screen error state
                    // (issue #163). It sits outside the LazyColumn so the key-anchored list
                    // can never scroll it above the viewport.
                    refreshFailed?.let { message ->
                        RefreshFailedBanner(
                            message = message,
                            onRetry = { scope.launch { load() } }
                        )
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp)
                    ) {
                        if (entries.isEmpty()) {
                            item {
                                EmptyState(
                                    title = stringResource(R.string.cupthread_whatsnew_empty_title),
                                    body = stringResource(R.string.cupthread_whatsnew_empty_body)
                                )
                            }
                        }
                        items(entries, key = { it.id }) { entry ->
                            ChangelogCard(entry)
                        }
                        if (hasMore || loadingMore || loadMoreFailed) {
                            item(key = "paging-footer") {
                                PagingFooter(
                                    loading = loadingMore,
                                    failed = loadMoreFailed,
                                    onLoadMore = { scope.launch { loadMore() } }
                                )
                            }
                        }
                        item {
                            SubscribeFooter { subscribeOpen = true }
                        }
                    }
                }
            }
        }
    }

    if (subscribeOpen) {
        SubscribeSheet(client = client, userToken = userToken, onDismiss = { subscribeOpen = false })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChangelogCard(entry: ChangelogEntry) {
    RequestCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                entry.versionLabel?.let { CapsuleBadge(it, MaterialTheme.colorScheme.primary, Icons.Outlined.Sell) }
                Spacer(Modifier.weight(1f))
                Text(
                    friendlyDate(entry.publishedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(entry.title, style = MaterialTheme.typography.titleSmall)
            if (entry.body.isNotBlank()) {
                MarkdownText(entry.body)
            }
            if (entry.linkedRequests.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    entry.linkedRequests.forEach { request ->
                        CapsuleBadge(request.title, MaterialTheme.colorScheme.tertiary, Icons.Outlined.CheckCircle)
                    }
                }
            }
        }
    }
}

@Composable
private fun SubscribeFooter(onClick: () -> Unit) {
    RequestCard(modifier = Modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Outlined.MailOutline, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.cupthread_whatsnew_get_update_emails), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.cupthread_whatsnew_be_notified),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onClick) { Text(stringResource(R.string.cupthread_whatsnew_subscribe)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubscribeSheet(
    client: FeedbackClient,
    userToken: String,
    onDismiss: () -> Unit
) {
    var email by remember { mutableStateOf("") }
    var phase by remember { mutableStateOf("form") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val valid = email.contains("@") && email.contains(".") && !email.contains(" ")
    val genericError = stringResource(R.string.cupthread_feedback_generic_error)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.cupthread_whatsnew_updates_by_email), style = MaterialTheme.typography.titleLarge)
            when (phase) {
                "form" -> {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text(stringResource(R.string.cupthread_whatsnew_email_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        stringResource(R.string.cupthread_whatsnew_email_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Subscription is double opt-in: the address is pending until the emailed
                // single-use link is confirmed, so no changelog emails flow before that.
                "subscribed" -> {
                    Text(stringResource(R.string.cupthread_whatsnew_subscribed))
                    Text(stringResource(R.string.cupthread_whatsnew_subscribed_sub, email.trim()), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (error != null) {
                Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = {
                    scope.launch {
                        working = true
                        error = null
                        try {
                            when (phase) {
                                "form" -> {
                                    client.subscribeToChangelog(email, userToken)
                                    phase = "subscribed"
                                }
                                else -> onDismiss()
                            }
                        } catch (ex: Exception) {
                            error = ex.message ?: genericError
                        } finally {
                            working = false
                        }
                    }
                },
                enabled = !working && (phase != "form" || valid),
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(
                    when {
                        working && phase == "form" -> stringResource(R.string.cupthread_whatsnew_subscribing)
                        phase == "form" -> stringResource(R.string.cupthread_whatsnew_subscribe)
                        else -> stringResource(R.string.cupthread_whatsnew_done)
                    }
                )
            }
        }
    }
}
