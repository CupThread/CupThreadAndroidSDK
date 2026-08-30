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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.cupthread.feedback.ChangelogEntry
import dev.cupthread.feedback.FeedbackClient
import dev.cupthread.feedback.FeedbackException
import dev.cupthread.feedback.R
import dev.cupthread.feedback.SdkFeature
import kotlinx.coroutines.launch

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
    var entries by remember { mutableStateOf<List<ChangelogEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var hasLoaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var subscribeOpen by remember { mutableStateOf(false) }
    val defaultLoadError = stringResource(R.string.cupthread_whatsnew_empty_body)

    suspend fun load() {
        loading = true
        error = null
        try {
            entries = client.fetchChangelog()
        } catch (ex: FeedbackException.AuthenticationRequired) {
            error = ex.message
        } catch (ex: Exception) {
            error = ex.message ?: defaultLoadError
        } finally {
            loading = false
            hasLoaded = true
        }
    }

    LaunchedEffect(client) { load() }

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
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
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
                    item {
                        SubscribeFooter { subscribeOpen = true }
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
    var already by remember { mutableStateOf(false) }
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
                "subscribed" -> {
                    Text(if (already) stringResource(R.string.cupthread_whatsnew_already_subscribed) else stringResource(R.string.cupthread_whatsnew_subscribed))
                    Text(stringResource(R.string.cupthread_whatsnew_subscribed_sub, email.trim()), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    Text(stringResource(R.string.cupthread_whatsnew_unsubscribed))
                    Text(stringResource(R.string.cupthread_whatsnew_unsubscribed_sub, email.trim()), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                    val result = client.subscribeToChangelog(email, userToken)
                                    already = result.alreadySubscribed
                                    phase = "subscribed"
                                }
                                "subscribed" -> {
                                    client.unsubscribeFromChangelog(email)
                                    phase = "unsubscribed"
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
                        working && phase == "subscribed" -> stringResource(R.string.cupthread_whatsnew_unsubscribing)
                        phase == "form" -> stringResource(R.string.cupthread_whatsnew_subscribe)
                        phase == "subscribed" -> stringResource(R.string.cupthread_whatsnew_unsubscribe)
                        else -> stringResource(R.string.cupthread_whatsnew_done)
                    }
                )
            }
        }
    }
}
