package dev.cupthread.feedback.ui

import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.cupthread.feedback.FeedbackClient
import dev.cupthread.feedback.FeedbackDraft
import dev.cupthread.feedback.FeedbackSubmissionResult
import dev.cupthread.feedback.R
import dev.cupthread.feedback.SdkFeature
import kotlinx.coroutines.launch

/**
 * Feedback form screen: title, description, optional reporter name and email,
 * and a send button.
 *
 * When no [initialDraft] is supplied, the draft is pre-filled with the host
 * app's version name and code from the package manager, so end users never
 * type them. After a successful send the composer swaps to a confirmation
 * state; use [onSubmit] for custom handling, such as closing the screen.
 *
 * @param client Shared API client.
 * @param userToken Optional stable anonymous token from
 *   [dev.cupthread.feedback.UserTokenStore], sent as the `X-User-Token`
 *   header.
 * @param initialDraft Draft to start editing from; defaults to an
 *   auto-filled draft for the host package.
 * @param onSubmit Invoked with the server result after a successful submit.
 * @param modifier Modifier applied to the root [Scaffold].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackComposer(
    client: FeedbackClient,
    userToken: String? = null,
    initialDraft: FeedbackDraft? = null,
    onSubmit: (FeedbackSubmissionResult) -> Unit = {},
    modifier: Modifier = Modifier
) {
    SdkSurface(client, SdkFeature.FEEDBACK) {
        FeedbackComposerContent(client, userToken, initialDraft, onSubmit, modifier)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedbackComposerContent(
    client: FeedbackClient,
    userToken: String?,
    initialDraft: FeedbackDraft?,
    onSubmit: (FeedbackSubmissionResult) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember {
        mutableStateOf(
            initialDraft ?: run {
                val info = try {
                    context.packageManager.getPackageInfo(context.packageName, 0)
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                }
                FeedbackDraft.autofilled(
                    platform = client.config.defaultPlatform,
                    versionName = info?.versionName.orEmpty(),
                    versionCode = info?.longVersionCode?.toString().orEmpty()
                )
            }
        )
    }
    var submitting by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<FeedbackSubmissionResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val canSubmit = draft.title.trim().length >= 3 && draft.description.trim().length >= 5

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.cupthread_feedback_title)) }) }
    ) { padding ->
        if (result != null) {
            FeedbackSent(
                warning = result?.warning,
                modifier = Modifier.padding(padding),
                onSendMore = { result = null }
            )
        } else {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = draft.title,
                    onValueChange = { draft = draft.copy(title = it) },
                    label = { Text(stringResource(R.string.cupthread_feedback_title_label)) },
                    placeholder = { Text(stringResource(R.string.cupthread_feedback_short_summary)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = draft.description,
                    onValueChange = { draft = draft.copy(description = it) },
                    label = { Text(stringResource(R.string.cupthread_feedback_desc_label)) },
                    placeholder = { Text(stringResource(R.string.cupthread_feedback_desc_placeholder)) },
                    minLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    stringResource(R.string.cupthread_feedback_desc_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = draft.reporterName,
                    onValueChange = { draft = draft.copy(reporterName = it) },
                    label = { Text(stringResource(R.string.cupthread_feedback_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = draft.reporterEmail,
                    onValueChange = { draft = draft.copy(reporterEmail = it) },
                    label = { Text(stringResource(R.string.cupthread_feedback_email_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    stringResource(R.string.cupthread_feedback_contact_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AnimatedVisibility(error != null) {
                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                val defaultError = stringResource(R.string.cupthread_feedback_generic_error)
                Button(
                    onClick = {
                        scope.launch {
                            submitting = true
                            error = null
                            try {
                                val sent = client.submit(draft, userToken)
                                onSubmit(sent)
                                result = sent
                            } catch (ex: Exception) {
                                error = ex.message ?: defaultError
                            } finally {
                                submitting = false
                            }
                        }
                    },
                    enabled = canSubmit && !submitting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (submitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text(if (submitting) stringResource(R.string.cupthread_feedback_sending_button) else stringResource(R.string.cupthread_feedback_send_button))
                    }
                }
            }
        }
    }

}

@Composable
private fun FeedbackSent(
    warning: String?,
    onSendMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.height(64.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.cupthread_feedback_thanks_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.cupthread_feedback_thanks_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!warning.isNullOrBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onSendMore) { Text(stringResource(R.string.cupthread_feedback_send_more)) }
    }
}
