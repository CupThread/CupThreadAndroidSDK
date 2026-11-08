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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.rememberUpdatedState
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
import dev.cupthread.feedback.FeatureRequestSubmissionResult
import dev.cupthread.feedback.FeedbackClient
import dev.cupthread.feedback.FeedbackException
import dev.cupthread.feedback.UserTokenStore
import dev.cupthread.feedback.R
import dev.cupthread.feedback.SdkFeature
import dev.cupthread.feedback.VoteResult
import dev.cupthread.feedback.internal.FeatureRequestPager
import dev.cupthread.feedback.internal.SearchThrottler
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
 * - **Debounced Search**: Free-text search matching titles and descriptions, debounced at ~350 ms
 *   and throttled to at most one request every 2 s so typing stays within the backend's shared
 *   per-IP search budget; an HTTP 429 shows a banner and keeps the loaded list intact.
 * - **Incremental Paging**: Requests the next page as the list approaches its end, appending
 *   without duplicate ids. Search and version changes restart from the first page.
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
 * ### Screenshots
 * | Feature Requests | Submit a Request |
 * | :---: | :---: |
 * | <img src="https://raw.githubusercontent.com/CupThread/CupThreadAndroidSDK/main/docs/images/feature_requests.jpg" width="280" alt="Feature Requests Screen" /> | <img src="https://raw.githubusercontent.com/CupThread/CupThreadAndroidSDK/main/docs/images/submit_request.jpg" width="280" alt="Submit Request Sheet" /> |
 *
 * @param client Shared [FeedbackClient] instance used for network queries and mutations.
 * @param userToken Stable anonymous user token from [UserTokenStore] used to track vote state and ownership.
 *   When the host supplies a new token (for example after [UserTokenStore.rotateToken] or
 *   [UserTokenStore.clearToken] following erasure), the screen re-fetches the first page and every
 *   later page with the new identity.
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
    val listState = rememberLazyListState()
    var items by remember { mutableStateOf<List<FeatureRequestItem>>(emptyList()) }
    var versions by remember { mutableStateOf<List<AppVersion>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    // Identifies which load() owns `loading`; a superseded refresh must not hide the
    // indicator while a newer token or search reload is still running.
    var loadGeneration by remember { mutableStateOf(0) }
    var hasLoaded by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var loadMoreFailed by remember { mutableStateOf(false) }
    // Non-null while the last refresh of already-rendered rows failed: the rows stay mounted
    // (issue #163) and the value is the failure message shown by the inline retry banner.
    var refreshFailed by remember { mutableStateOf<String?>(null) }
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
    val searchRateLimitedMessage = stringResource(R.string.cupthread_search_rate_limited)
    val snackbarHostState = remember { SnackbarHostState() }

    // Coalesces repeated feedback: SnackbarHostState serializes every showSnackbar
    // call, so identical messages from repeated failures (vote taps or retries during
    // a 429) would otherwise queue up and outlast the tapping. Both the currently
    // displayed message and the messages already queued behind it are suppressed; a
    // queued entry leaves `pendingSnackbarMessages` when its snackbar finishes, so a
    // later identical failure can surface again. All callers run on the main
    // dispatcher, so the plain set needs no synchronization.
    val pendingSnackbarMessages = remember { mutableSetOf<String>() }
    fun showFeedbackSnackbar(message: String) {
        if (snackbarHostState.currentSnackbarData?.visuals?.message == message) return
        if (!pendingSnackbarMessages.add(message)) return
        // Dispatchers.Main.immediate runs the launch inline when already on the main
        // thread — every caller is — so the snackbar data is published without an extra
        // dispatcher hop. The plain composition-scope launch queues the coroutine on the
        // ui dispatcher's frame-driven trampoline, which Robolectric tests observed
        // starving indefinitely once the tree went quiescent (the CI flake).
        scope.launch(Dispatchers.Main.immediate) {
            try {
                snackbarHostState.showSnackbar(message)
            } finally {
                pendingSnackbarMessages.remove(message)
            }
        }
    }

    // Searches share a strict per-IP budget with every other client (30/min); the throttler
    // keeps ordinary typing inside it (issue #23).
    val searchThrottler = remember { SearchThrottler() }

    // Reads the current token, search, and version filter on every request, so one pager serves
    // all filters and survives token rotation or erasure (the token must not be captured by value).
    val currentUserToken by rememberUpdatedState(userToken)
    val pager = remember(client) {
        FeatureRequestPager { request ->
            client.fetchFeatureRequests(
                userToken = currentUserToken,
                limit = request.limit,
                offset = request.offset,
                versionId = versionId,
                query = search.trim().ifEmpty { null },
                cursor = request.cursor
            )
        }
    }

    suspend fun load() {
        val generation = ++loadGeneration
        loading = true
        error = null
        loadMoreFailed = false
        refreshFailed = null
        try {
            // A pull-to-refresh launch outlives a token rotation (the search collector restarts
            // instead). When the pager discards this page because a newer load superseded it,
            // keep the newer identity's rows on screen instead of overwriting them.
            val page = pager.loadFirstPage() ?: return
            items = page.requests
            hasMore = pager.hasMore
            hasLoaded = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: FeedbackException.RateLimited) {
            searchThrottler.penalize()
            if (hasLoaded) {
                // Keep the last successful results on screen: a search rate limit must not
                // wipe already-rendered content (issue #23). The pager already reset its
                // paging position, so paging must not continue into the retained results.
                hasMore = false
                refreshFailed = searchRateLimitedMessage
                showFeedbackSnackbar(searchRateLimitedMessage)
            } else {
                error = e.message
            }
        } catch (ex: Exception) {
            if (hasLoaded) {
                // Keep the last successful results on screen: a failed refresh must not wipe
                // already-rendered content (issue #163), matching the 429 handling above. The
                // pager already reset its paging position, so paging must not continue into
                // the retained results.
                hasMore = false
                refreshFailed = ex.message ?: defaultLoadError
                scope.launch { snackbarHostState.showSnackbar(ex.message ?: defaultLoadError) }
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
            items = FeatureRequestPager.append(items, page.requests)
            hasMore = pager.hasMore
        } catch (e: CancellationException) {
            throw e
        } catch (e: FeedbackException.RateLimited) {
            // The failed page keeps its cursor inside the pager, so the retry footer stays
            // and paging can resume where it stopped; cool down before further auto loads.
            searchThrottler.penalize()
            loadMoreFailed = true
            // Fire-and-forget: loadMore must release `loadingMore` (unblocking the retry
            // footer) without waiting for the snackbar to go away.
            showFeedbackSnackbar(searchRateLimitedMessage)
        } catch (_: Exception) {
            loadMoreFailed = true
        } finally {
            loadingMore = false
        }
    }

    LaunchedEffect(listState, hasMore, items.size, loadMoreFailed) {
        if (!hasMore || loadMoreFailed) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { lastVisible ->
                if (lastVisible >= items.size - 3) loadMore()
            }
    }

    LaunchedEffect(client) {
        // Versions only power the filter menu: a failed fetch leaves the board usable with the
        // filter disabled. A cancelled fetch (the client was swapped, or the screen left the
        // composition) must still propagate — swallowing it here would clobber versions a
        // completed fetch already rendered (issue #206).
        try {
            versions = client.fetchVersions()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            versions = emptyList()
        }
    }

    LaunchedEffect(versionId, userToken) {
        // The first emission of every (re)started collector is user-initiated — the initial
        // load, a version-filter change, or a new end-user token; later emissions are genuine
        // query changes. Restarting on the token re-fetches the first page for the new identity
        // so vote/ownership flags are not left over from the previous token (issue #142).
        var userInitiated = true
        snapshotFlow { search }
            .distinctUntilChanged()
            .collectLatest { query ->
                // The previous query's paging state is obsolete the moment the query changes:
                // reset before the throttled wait so a scroll-triggered loadMore cannot fetch
                // with the old cursor while the pager callback reads the new query.
                pager.reset()
                hasMore = false
                searchThrottler.awaitSearchTurn(query, bypassThrottle = userInitiated)
                userInitiated = false
                load()
            }
    }

    LaunchedEffect(submittedBanner) {
        if (submittedBanner) {
            delay(4_000)
            submittedBanner = false
        }
    }

    val rateLimitedMessage = stringResource(R.string.cupthread_features_vote_rate_limited)
    // With a turnstile token provider installed the host renders the challenge, so "complete
    // the step" is an actionable ask (the client already retried once with a fresh token
    // before surfacing the rejection). Without one the gate can never be satisfied from this
    // UI: telling the user to complete a step that does not exist would send them retrying
    // into the same 403, so the message says verification is unavailable instead (issue #184).
    val voteVerificationMessage = if (client.config.turnstileTokenProvider != null) {
        stringResource(R.string.cupthread_feedback_verification_required)
    } else {
        stringResource(R.string.cupthread_features_vote_verification_unavailable)
    }
    val signInRequiredMessage = stringResource(R.string.cupthread_features_vote_sign_in_required)

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                // A failed refresh keeps the rendered rows and shows this fixed, always-visible
                // retry banner instead of the full-screen error state (issue #163). It sits above
                // every content branch (rows, empty state, paging footer) and outside the
                // key-anchored LazyColumn, so it can never be scrolled out of view; `error` and
                // `refreshFailed` are mutually exclusive, and a new load clears the banner.
                refreshFailed?.let { message ->
                    RefreshFailedBanner(
                        message = message,
                        onRetry = { scope.launch { load() } }
                    )
                }
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
                        state = listState,
                        modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
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
                                        try {
                                            applyOptimisticVote(
                                                readRows = { items },
                                                commitRows = { items = it },
                                                readSelection = { selectedItem },
                                                commitSelection = { selectedItem = it },
                                                target = item,
                                                rateLimitedMessage = rateLimitedMessage,
                                                verificationRequiredMessage = voteVerificationMessage,
                                                signInRequiredMessage = signInRequiredMessage,
                                                request = { baseline ->
                                                    if (baseline.hasVoted) {
                                                        client.deleteVote(item.id, userToken)
                                                    } else {
                                                        client.vote(item.id, userToken)
                                                    }
                                                },
                                                onFeedback = { showFeedbackSnackbar(it) }
                                            )
                                        } finally {
                                            votingIds = votingIds - item.id
                                        }
                                    }
                                }
                            )
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
                    try {
                        applyOptimisticVote(
                            readRows = { items },
                            commitRows = { items = it },
                            readSelection = { selectedItem },
                            commitSelection = { selectedItem = it },
                            target = currentItem,
                            rateLimitedMessage = rateLimitedMessage,
                            verificationRequiredMessage = voteVerificationMessage,
                            signInRequiredMessage = signInRequiredMessage,
                            request = { baseline ->
                                if (baseline.hasVoted) {
                                    client.deleteVote(currentItem.id, userToken)
                                } else {
                                    client.vote(currentItem.id, userToken)
                                }
                            },
                            onFeedback = { showFeedbackSnackbar(it) }
                        )
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

/**
 * Reverts exactly the optimistic vote change from a row list (issue #178): the tap-time
 * [previous] row replaces only [optimistic] — the instance the optimistic update put into
 * the list. A row refetched while the vote was in flight (pull-to-refresh, a search or
 * version reload, token rotation) is a different instance and keeps its fresh server state
 * instead of being clobbered by the stale tap-time snapshot.
 *
 * One refetch shape is reconciled rather than skipped: a response snapshotted before the
 * failed call's target change (e.g. taken before an overlapping upvote was applied) can
 * replace [optimistic] while still carrying its pre-revert vote state. Such a row still
 * shows the state the failed call was supposed to undo, so [previous]'s vote values are
 * applied onto it — while its other refetched fields survive.
 */
internal fun revertedItems(
    items: List<FeatureRequestItem>,
    previous: FeatureRequestItem,
    optimistic: FeatureRequestItem?
): List<FeatureRequestItem> = when (optimistic) {
    null -> items
    else -> items.map { row ->
        when {
            row === optimistic -> previous
            row.id == optimistic.id &&
                row.hasVoted == optimistic.hasVoted &&
                row.voteCount == optimistic.voteCount ->
                row.withVoteState(previous.hasVoted, previous.voteCount)
            else -> row
        }
    }
}

/**
 * Instance-level revert for a detail-sheet item: restores the baseline vote values the
 * optimistic flip was built on ([previous]) only while the sheet still shows
 * [optimisticSelection], the copy the optimistic update set. A dismissed sheet (null) or
 * one reopened on a refetched row is left untouched.
 */
internal fun revertedSelection(
    selection: FeatureRequestItem?,
    optimisticSelection: FeatureRequestItem?,
    previous: FeatureRequestItem
): FeatureRequestItem? = if (selection != null && selection === optimisticSelection) {
    selection.withVoteState(previous.hasVoted, previous.voteCount)
} else {
    selection
}

/**
 * Applies a successful vote's returned state onto the current detail-sheet selection, but
 * only while the selection still shows the voted request: a sheet dismissed or reopened
 * on another request while the vote was in flight is neither resurrected nor swapped, and
 * a selection refetched to a fresher instance of the same request keeps its newer fields.
 */
internal fun reconciledSelection(
    selection: FeatureRequestItem?,
    votedId: String,
    voted: Boolean,
    voteCount: Int
): FeatureRequestItem? = when {
    selection == null -> null
    selection.id != votedId -> selection
    else -> selection.withVoteState(voted, voteCount)
}

/**
 * The one optimistic-vote orchestration shared by every vote handler — the list-row pill,
 * the detail sheet on this screen, and the detail sheet on the roadmap board (issue #192).
 * Before the extraction the sequence below existed as three near-identical catch ladders
 * that drifted apart under every vote-flow fix (#178, #179, #182, #184, #187, #190 each
 * had to edit all copies in lockstep); now a new typed rejection or revert nuance is a
 * single-site edit here.
 *
 * Sequence: in-flight guard and release stay with the caller — this helper owns everything
 * between them:
 *
 * 1. Resolve the baseline [previous] from the fresher list row (issue #182): the caller's
 *    snapshot can be stale once a reload replaced the list while a sheet stayed open, and
 *    mixing the caller's values onto the refetched row rendered an optimistic count below
 *    the server's baseline. When the row is absent from the list, [target] itself stays
 *    the baseline.
 * 2. Flip the row and — when the open selection shows the same request — the selection
 *    onto that baseline, so both views agree during the optimistic window.
 * 3. Run [request] with the resolved baseline and reconcile both views with the returned
 *    state.
 * 4. On failure, revert exactly the instances the optimistic update created
 *    ([revertedItems] / [revertedSelection]): a row refetched while the vote was in flight
 *    keeps its fresh server state (issues #178, #179).
 *
 * Typed rejections surface feedback through [onFeedback] with the localized message the
 * caller picked for them ([rateLimitedMessage], [verificationRequiredMessage],
 * [signInRequiredMessage]) — the strings live at the call sites where the resources are
 * resolved; the exception-to-message mapping lives only here. A rate-limited rejection
 * prefers the server's own message over the generic copy. [onFeedback] must be
 * fire-and-forget: the vote has to release the caller's in-flight guard (its `finally`)
 * without waiting for the snackbar to go away.
 *
 * A [CancellationException] is rethrown when this coroutine's own job is cancelled (a
 * screen leaving composition, issue #190); a provider-local timeout inside the host's
 * turnstile bridge arrives as a CancellationException too while the job is still alive —
 * that one recovers as a silent failure instead of keeping the optimistic flip. Every
 * other exception reverts silently.
 */
internal suspend fun applyOptimisticVote(
    readRows: () -> List<FeatureRequestItem>,
    commitRows: (List<FeatureRequestItem>) -> Unit,
    readSelection: () -> FeatureRequestItem?,
    commitSelection: (FeatureRequestItem?) -> Unit,
    target: FeatureRequestItem,
    rateLimitedMessage: String,
    verificationRequiredMessage: String,
    signInRequiredMessage: String,
    request: suspend (baseline: FeatureRequestItem) -> VoteResult,
    onFeedback: (String) -> Unit
) {
    val rows = readRows()
    val listRow = rows.firstOrNull { it.id == target.id }
    val previous = listRow ?: target
    val newVoted = !previous.hasVoted
    val newCount = if (previous.hasVoted) previous.voteCount - 1 else previous.voteCount + 1
    val optimisticRow = listRow?.withVoteState(newVoted, newCount)
    if (optimisticRow != null) {
        commitRows(rows.map { if (it.id == target.id) optimisticRow else it })
    }
    // The open selection flips on the same baseline so both views agree during the
    // optimistic window even when its copy still shows a stale instance; a selection
    // showing another request is left untouched.
    val optimisticSelection = readSelection()
        ?.takeIf { it.id == target.id }
        ?.withVoteState(newVoted, newCount)
    if (optimisticSelection != null) {
        commitSelection(optimisticSelection)
    }
    val revert = {
        commitRows(revertedItems(readRows(), previous, optimisticRow))
        commitSelection(revertedSelection(readSelection(), optimisticSelection, previous))
    }
    try {
        val result = request(previous)
        commitRows(
            readRows().map {
                if (it.id == target.id) it.withVoteState(result.voted, result.voteCount) else it
            }
        )
        commitSelection(reconciledSelection(readSelection(), target.id, result.voted, result.voteCount))
    } catch (e: FeedbackException.RateLimited) {
        revert()
        onFeedback(e.serverMessage ?: rateLimitedMessage)
    } catch (e: FeedbackException.VerificationRequired) {
        // The human-verification gate rejected the vote (issue #184): tell the user
        // instead of silently reverting the optimistic flip.
        revert()
        onFeedback(verificationRequiredMessage)
    } catch (e: FeedbackException.AuthenticationRequired) {
        // Anonymous voting is disabled on this app (issue #184).
        revert()
        onFeedback(signInRequiredMessage)
    } catch (e: CancellationException) {
        // Propagate only our own cancellation (issue #190): ensureActive() rethrows it.
        coroutineContext.ensureActive()
        revert()
    } catch (_: Exception) {
        revert()
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
                            friendlyDate(item.createdAt),
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
    ModalBottomSheet(onDismissRequest = onDismiss) {
        FeatureRequestComposeSheetBody(
            client = client,
            userToken = userToken,
            onSubmitted = onSubmitted
        )
    }
}

/**
 * The feature-request form the compose sheet hosts, split out of the modal window so
 * Robolectric tests can render it directly (same pattern as `FeedbackFormBody`, which
 * Robolectric cannot query across).
 */
@Composable
internal fun FeatureRequestComposeSheetBody(
    client: FeedbackClient,
    userToken: String,
    onSubmitted: () -> Unit,
    modifier: Modifier = Modifier
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val canSubmit = title.trim().length >= 3 && description.trim().length >= 5
    val defaultSubmitError = stringResource(R.string.cupthread_features_submit_failed)
    val verificationRequiredError = stringResource(R.string.cupthread_feedback_verification_required)
    val submitRateLimitedError = stringResource(R.string.cupthread_features_submit_rate_limited)

    Column(
        modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp),
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
                    } catch (ex: FeedbackException.VerificationRequired) {
                        error = verificationRequiredError
                    } catch (ex: FeedbackException.RateLimited) {
                        // The submission endpoint sits behind the same per-client rate
                        // limiting as the vote paths (issue #191): show the localized
                        // text instead of the raw 429 response body.
                        error = submitRateLimitedError
                    } catch (e: CancellationException) {
                        throw e
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
