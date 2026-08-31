package dev.cupthread.feedback.ui

import android.app.Activity
import android.app.Dialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.cupthread.feedback.ChangelogEntry
import dev.cupthread.feedback.FeedbackClient
import dev.cupthread.feedback.R
import dev.cupthread.feedback.SdkAppearance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Fetches console-configured overlay copy and presents the What's-New bottom
 * sheet over [activity], styled with the console theme.
 *
 * Suspends until the user dismisses the sheet.
 *
 * @return `false` when the changelog feature is disabled in the console or
 *   there are no published entries (nothing was shown); `true` once the
 *   sheet was shown and dismissed.
 */
suspend fun FeedbackClient.presentLatestChangelog(activity: Activity): Boolean {
    val prepared = withContext(Dispatchers.IO) { prepareChangelogOverlay() } ?: return false
    return suspendCancellableCoroutine { continuation ->
        activity.runOnUiThread {
            val dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
            val view = ComposeView(activity).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    CupThreadTheme(this@presentLatestChangelog) {
                        ChangelogOverlaySheet(
                            entries = prepared.first,
                            appearance = prepared.second,
                            onDismiss = {
                                dialog.dismiss()
                                if (continuation.isActive) continuation.resume(true)
                            }
                        )
                    }
                }
            }
            dialog.setContentView(view)
            dialog.setOnDismissListener {
                if (continuation.isActive) continuation.resume(true)
            }
            dialog.show()
        }
    }
}

/**
 * Compose-native What's-New overlay. Loads the newest published entries when
 * [visible] becomes `true` and shows them in a modal bottom sheet styled
 * with the console theme.
 *
 * Dismisses itself by calling [onDismiss] when the changelog feature is
 * disabled in the console or nothing has shipped. For Activity-based entry
 * points prefer the awaitable [FeedbackClient.presentLatestChangelog].
 *
 * @param client Shared API client.
 * @param visible Whether the overlay should be shown.
 * @param onDismiss Called when the sheet is dismissed or there is nothing
 *   to show.
 */
@Composable
fun ChangelogOverlay(
    client: FeedbackClient,
    visible: Boolean,
    onDismiss: () -> Unit
) {
    if (!visible) return
    var entries by remember { mutableStateOf<List<ChangelogEntry>?>(null) }
    var appearance by remember { mutableStateOf(SdkAppearance.defaults) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(client) {
        try {
            val prepared = client.prepareChangelogOverlay()
            if (prepared == null) {
                onDismiss()
                return@LaunchedEffect
            }
            entries = prepared.first
            appearance = prepared.second
        } catch (ex: Exception) {
            error = ex.message ?: "Failed to load updates"
        }
    }

    if (error != null || entries != null) {
        ChangelogOverlaySheet(
            entries = entries.orEmpty(),
            appearance = appearance,
            error = error,
            onDismiss = onDismiss
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChangelogOverlaySheet(
    entries: List<ChangelogEntry>,
    appearance: SdkAppearance,
    error: String? = null,
    onDismiss: () -> Unit
) {
    val overlay = appearance.changelogOverlay
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(overlay.title, style = MaterialTheme.typography.headlineSmall)
            if (overlay.subtitle.isNotBlank()) {
                Text(overlay.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                error != null -> LoadErrorState(error) { onDismiss() }
                entries.isEmpty() -> EmptyState(
                    title = stringResource(R.string.cupthread_whatsnew_empty_title),
                    body = stringResource(R.string.cupthread_whatsnew_empty_body)
                )
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(entries, key = { it.id }) { ChangelogCard(it) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(overlay.primaryButton)
                }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(overlay.closeButton)
                }
            }
        }
    }
}
