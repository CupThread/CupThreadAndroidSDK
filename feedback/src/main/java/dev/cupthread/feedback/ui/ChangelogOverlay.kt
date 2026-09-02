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
import androidx.compose.ui.platform.LocalContext
import dev.cupthread.feedback.ChangelogEntry
import dev.cupthread.feedback.ChangelogStore
import dev.cupthread.feedback.FeedbackClient
import dev.cupthread.feedback.R
import dev.cupthread.feedback.SdkAppearance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Convenience extension function that fetches console-configured What's-New copy and presents
 * a modal bottom sheet dialog directly over an Android [Activity].
 *
 * This function suspends until the user dismisses the dialog, allowing straightforward sequential
 * integration within Activity lifecycles or coroutine launch scopes.
 *
 * ### Behavior & Thread Safety
 * - Fetches changelog entries and configuration asynchronously on [Dispatchers.IO].
 * - When [onlyIfUnseen] is `true`, skips presentation if the latest release note was already viewed on this device.
 * - When [autoMarkSeen] is `true`, automatically records the latest release note in [ChangelogStore] on presentation.
 * - Dynamically mounts a translucent Compose dialog on the main UI thread.
 * - Wraps the content in [CupThreadTheme] to ensure console-selected theme palettes are honored.
 * - Resumes with `false` immediately if the changelog feature is disabled, if no entries exist, or if already seen.
 * - Resumes with `true` once the user views and closes the sheet.
 *
 * ### Example Usage
 * ```kotlin
 * class MainActivity : ComponentActivity() {
 *     private val feedbackClient by lazy {
 *         FeedbackClient(FeedbackClientConfig("https://api.cupthread.com", "app_live_sample123"))
 *     }
 *
 *     override fun onResume() {
 *         super.onResume()
 *         lifecycleScope.launch {
 *             val shown = feedbackClient.presentLatestChangelog(
 *                 activity = this@MainActivity,
 *                 onlyIfUnseen = true
 *             )
 *             if (shown) {
 *                 Log.d("Changelog", "User viewed new What's-New release notes.")
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * @receiver The [FeedbackClient] used to fetch changelog entries and appearance configuration.
 * @param activity The host Android [Activity] over which the dialog window will be shown.
 * @param onlyIfUnseen If `true`, checks [ChangelogStore] and skips presentation if the latest version was already seen.
 * @param autoMarkSeen If `true`, automatically marks the latest version as seen upon presentation.
 * @return `false` if the changelog is disabled, empty, or already seen; `true` if the sheet was presented and dismissed.
 */
suspend fun FeedbackClient.presentLatestChangelog(
    activity: Activity,
    onlyIfUnseen: Boolean = false,
    autoMarkSeen: Boolean = true
): Boolean {
    val prepared = withContext(Dispatchers.IO) {
        prepareChangelogOverlay(context = activity, onlyIfUnseen = onlyIfUnseen)
    } ?: return false

    if (autoMarkSeen) {
        val store = ChangelogStore.create(activity)
        val latest = prepared.first.firstOrNull()
        if (latest != null) {
            if (!latest.versionLabel.isNullOrBlank()) store.markChangelogSeen(latest.versionLabel)
            store.markChangelogSeen(latest.id)
        }
    }

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
 * Pure Jetpack Compose What's-New modal bottom sheet overlay.
 *
 * When [visible] becomes `true`, asynchronously loads the latest published release notes
 * via [FeedbackClient.prepareChangelogOverlay] and displays them inside a modal bottom sheet
 * styled according to console configuration.
 *
 * ### Auto-Dismissal and Clean Lifecycle
 * If the changelog feature is disabled in the CupThread developer console, no published entries exist,
 * or [onlyIfUnseen] is `true` and the release was already seen, this composable automatically invokes [onDismiss],
 * making it safe to mount unconditionally on app startup or home screen composables.
 *
 * ### Theming
 * Inherits the ambient Compose [MaterialTheme]. Wrap inside [CupThreadTheme] to inherit the remote
 * color theme configured in the developer console.
 *
 * ### Example Integration
 * ```kotlin
 * @Composable
 * fun MainAppScreen(client: FeedbackClient) {
 *     var showChangelog by rememberSaveable { mutableStateOf(true) }
 *
 *     CupThreadTheme(client) {
 *         Scaffold { padding ->
 *             AppNavigation(Modifier.padding(padding))
 *
 *             ChangelogOverlay(
 *                 client = client,
 *                 visible = showChangelog,
 *                 onlyIfUnseen = true,
 *                 onDismiss = { showChangelog = false }
 *             )
 *         }
 *     }
 * }
 * ```
 *
 * @param client Shared [FeedbackClient] instance.
 * @param visible Whether the What's-New bottom sheet should be displayed.
 * @param onlyIfUnseen If `true`, checks [ChangelogStore] and auto-dismisses if the latest version was already seen.
 * @param autoMarkSeen If `true`, automatically marks the latest version as seen in [ChangelogStore].
 * @param onDismiss Invoked when the user taps close/continue or if there are no new updates to show.
 */
@Composable
fun ChangelogOverlay(
    client: FeedbackClient,
    visible: Boolean,
    onlyIfUnseen: Boolean = false,
    autoMarkSeen: Boolean = true,
    onDismiss: () -> Unit
) {
    if (!visible) return
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<ChangelogEntry>?>(null) }
    var appearance by remember { mutableStateOf(SdkAppearance.defaults) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(client, visible, onlyIfUnseen) {
        try {
            val prepared = client.prepareChangelogOverlay(context = context, onlyIfUnseen = onlyIfUnseen)
            if (prepared == null) {
                onDismiss()
                return@LaunchedEffect
            }
            entries = prepared.first
            appearance = prepared.second
            if (autoMarkSeen) {
                val store = ChangelogStore.create(context)
                val latest = prepared.first.firstOrNull()
                if (latest != null) {
                    if (!latest.versionLabel.isNullOrBlank()) store.markChangelogSeen(latest.versionLabel)
                    store.markChangelogSeen(latest.id)
                }
            }
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
