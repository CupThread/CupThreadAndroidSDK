package dev.cupthread.feedback.ui

import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import dev.cupthread.feedback.FeedbackException
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cupthread.feedback.FeedbackAttachment
import dev.cupthread.feedback.FeedbackClient
import dev.cupthread.feedback.FeedbackDraft
import dev.cupthread.feedback.FeedbackSubmissionResult
import dev.cupthread.feedback.UserTokenStore
import dev.cupthread.feedback.R
import dev.cupthread.feedback.SdkFeature
import dev.cupthread.feedback.internal.AttachmentLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Ready-made User Feedback Submission Form composable.
 *
 * Provides a streamlined interface for collecting user feedback, issue reports, diagnostic attachments,
 * and contact info. Includes title input, multiline description, optional reporter name/email fields,
 * attachment picker with upload support, validation indicators, and a confirmation state.
 *
 * ### Behavior & Input Validation
 * - **Pre-filled App Version Info**: When [initialDraft] is omitted, automatically extracts `versionName`
 *   and `versionCode` from [android.content.pm.PackageManager] so users don't need to specify them.
 * - **Attachments & PhotoPicker**: Includes built-in system PhotoPicker integration or custom [onPickAttachment]
 *   callback to upload screenshots and logs via [FeedbackClient.uploadAttachment].
 * - **Character Validation**: Submit button remains disabled until the title contains at least 3 non-whitespace
 *   characters and the description contains at least 5 characters.
 * - **Success Confirmation View**: Upon successful delivery via [FeedbackClient.submit], displays a confirmation
 *   card thanking the user, rendering any server warnings, and offering a "Send More" button.
 * - **Callback Handling**: [onSubmit] is invoked with the [FeedbackSubmissionResult], allowing the host app
 *   to trigger navigation (e.g. popping the back stack) or custom analytics.
 * - **Remote Theming & Feature Gating**: Automatically wrapped in [SdkSurface] with [SdkFeature.FEEDBACK].
 *
 * ### Example Integration
 * ```kotlin
 * @Composable
 * fun FeedbackNavigationDestination(
 *     client: FeedbackClient,
 *     userTokenStore: UserTokenStore,
 *     onNavigateBack: () -> Unit
 * ) {
 *     FeedbackComposer(
 *         client = client,
 *         userToken = userTokenStore.token,
 *         onSubmit = { result ->
 *             Log.d("Feedback", "Sent: ${result.submissionId}")
 *             onNavigateBack()
 *         },
 *         modifier = Modifier.fillMaxSize()
 *     )
 * }
 * ```
 *
 * ### Screenshot
 * <img src="https://raw.githubusercontent.com/CupThread/CupThreadAndroidSDK/main/docs/images/feedback_composer.jpg" width="320" alt="Feedback Composer" />
 *
 * @param client Shared [FeedbackClient] instance used to submit feedback and upload attachments.
 * @param userToken Optional stable anonymous token from [UserTokenStore], sent as the `X-User-Token` header.
 * @param initialDraft Optional pre-populated [FeedbackDraft]; defaults to an auto-filled draft for the current package.
 * @param onSubmit Callback invoked with the [FeedbackSubmissionResult] upon successful submission.
 * @param onPickAttachment Optional callback to override image attachment selection; if null, uses system PhotoPicker.
 * @param modifier Optional [Modifier] applied to the root [Scaffold] layout.
 * @param maxAttachmentBytes Optional client-side upload size cap in bytes. Takes precedence over console configuration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackComposer(
    client: FeedbackClient,
    userToken: String? = null,
    initialDraft: FeedbackDraft? = null,
    onSubmit: (FeedbackSubmissionResult) -> Unit = {},
    onPickAttachment: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    maxAttachmentBytes: Long? = null
) {
    SdkSurface(client, SdkFeature.FEEDBACK) {
        FeedbackComposerContent(
            client = client,
            userToken = userToken,
            initialDraft = initialDraft,
            onSubmit = onSubmit,
            onPickAttachment = onPickAttachment,
            maxAttachmentBytes = maxAttachmentBytes,
            modifier = modifier
        )
    }
}

/**
 * Interactive Modal Bottom Sheet version of the feedback submission form.
 *
 * Renders the complete [FeedbackComposer] inside a [ModalBottomSheet], ideal for triggering
 * feedback submission overlays without navigating away from the current screen.
 *
 * ### Example Usage
 * ```kotlin
 * @Composable
 * fun FeedbackModal(
 *     client: FeedbackClient,
 *     userToken: String,
 *     onDismiss: () -> Unit
 * ) {
 *     FeedbackComposerSheet(
 *         client = client,
 *         userToken = userToken,
 *         onDismiss = onDismiss,
 *         onSubmit = { result ->
 *             Log.d("Feedback", "Submitted: ${result.submissionId}")
 *             onDismiss()
 *         }
 *     )
 * }
 * ```
 *
 * @param client Shared [FeedbackClient] instance used to submit feedback and upload attachments.
 * @param userToken Optional stable anonymous token from [UserTokenStore].
 * @param initialDraft Optional pre-populated [FeedbackDraft].
 * @param onDismiss Callback invoked when the sheet is dismissed.
 * @param onSubmit Callback invoked with [FeedbackSubmissionResult] on successful submission.
 * @param onPickAttachment Optional custom attachment picker callback.
 * @param maxAttachmentBytes Optional client-side upload size cap in bytes. Takes precedence over console configuration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackComposerSheet(
    client: FeedbackClient,
    userToken: String? = null,
    initialDraft: FeedbackDraft? = null,
    onDismiss: () -> Unit,
    onSubmit: (FeedbackSubmissionResult) -> Unit = {},
    onPickAttachment: (() -> Unit)? = null,
    maxAttachmentBytes: Long? = null
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        FeedbackFormBody(
            client = client,
            userToken = userToken,
            initialDraft = initialDraft,
            onSubmit = onSubmit,
            onPickAttachment = onPickAttachment,
            onCloseSheet = onDismiss,
            maxAttachmentBytes = maxAttachmentBytes,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedbackComposerContent(
    client: FeedbackClient,
    userToken: String?,
    initialDraft: FeedbackDraft?,
    onSubmit: (FeedbackSubmissionResult) -> Unit,
    onPickAttachment: (() -> Unit)?,
    maxAttachmentBytes: Long?,
    modifier: Modifier = Modifier
) {
    var result by remember { mutableStateOf<FeedbackSubmissionResult?>(null) }

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
            FeedbackFormBody(
                client = client,
                userToken = userToken,
                initialDraft = initialDraft,
                onSubmit = {
                    result = it
                    onSubmit(it)
                },
                onPickAttachment = onPickAttachment,
                onCloseSheet = null,
                maxAttachmentBytes = maxAttachmentBytes,
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            )
        }
    }
}

@Composable
private fun FeedbackFormBody(
    client: FeedbackClient,
    userToken: String?,
    initialDraft: FeedbackDraft?,
    onSubmit: (FeedbackSubmissionResult) -> Unit,
    onPickAttachment: (() -> Unit)?,
    onCloseSheet: (() -> Unit)?,
    maxAttachmentBytes: Long?,
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
    var uploadingAttachment by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<FeedbackSubmissionResult?>(null) }

    var effectiveMaxBytes by remember(client, maxAttachmentBytes) {
        val positiveOverride = maxAttachmentBytes?.takeIf { it > 0 }
        mutableStateOf(positiveOverride ?: AttachmentLimits.DEFAULT_MAX_ATTACHMENT_BYTES)
    }
    var isConfigLoaded by remember(client, maxAttachmentBytes) {
        mutableStateOf(maxAttachmentBytes != null && maxAttachmentBytes > 0)
    }

    LaunchedEffect(client, maxAttachmentBytes) {
        if (maxAttachmentBytes == null || maxAttachmentBytes <= 0) {
            try {
                val config = client.fetchAppConfig()
                if (config.maxAttachmentBytes > 0) {
                    effectiveMaxBytes = config.maxAttachmentBytes
                }
                isConfigLoaded = true
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                // Fall back to default or last-known limit on network failure
                isConfigLoaded = true
            }
        }
    }

    val defaultUploadError = stringResource(R.string.cupthread_feedback_upload_failed)
    val defaultGenericError = stringResource(R.string.cupthread_feedback_generic_error)
    val unsupportedImageTypeError = stringResource(R.string.cupthread_feedback_unsupported_image_type)
    val scanRejectedError = stringResource(R.string.cupthread_feedback_attachment_scan_rejected)

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            if (!AttachmentLimits.canAddAttachment(draft.attachments.size) || !isConfigLoaded) {
                return@rememberLauncherForActivityResult
            }
            scope.launch {
                uploadingAttachment = true
                error = null
                try {
                    val (bytes, totalSize) = withContext(Dispatchers.IO) {
                        val declaredSize = try {
                            val cursor = context.contentResolver.query(
                                uri,
                                arrayOf(OpenableColumns.SIZE),
                                null,
                                null,
                                null
                            )
                            cursor?.use {
                                if (it.moveToFirst()) {
                                    val idx = it.getColumnIndex(OpenableColumns.SIZE)
                                    if (idx >= 0 && !it.isNull(idx)) it.getLong(idx) else null
                                } else null
                            }
                        } catch (_: Exception) {
                            null
                        }

                        if (declaredSize != null && declaredSize > effectiveMaxBytes) {
                            return@withContext null to declaredSize
                        }

                        val input = context.contentResolver.openInputStream(uri)
                            ?: throw IllegalStateException("Could not read image file")
                        input.use { stream ->
                            val buffer = ByteArray(8192)
                            val output = java.io.ByteArrayOutputStream()
                            var totalRead = 0L
                            var isOverLimit = false
                            while (true) {
                                val read = stream.read(buffer)
                                if (read == -1) break
                                totalRead += read
                                if (totalRead > effectiveMaxBytes) {
                                    isOverLimit = true
                                    break
                                }
                                output.write(buffer, 0, read)
                            }
                            if (isOverLimit) {
                                null to null
                            } else {
                                output.toByteArray() to totalRead
                            }
                        }
                    }

                    if (bytes == null) {
                        val formattedLimit = AttachmentLimits.formatFileSize(effectiveMaxBytes)
                        error = if (totalSize != null) {
                            val formattedSize = AttachmentLimits.formatFileSize(totalSize)
                            val displaySize = if (formattedSize == formattedLimit && totalSize != effectiveMaxBytes) {
                                "$formattedSize ($totalSize bytes)"
                            } else {
                                formattedSize
                            }
                            context.getString(
                                R.string.cupthread_attachment_oversized,
                                displaySize,
                                formattedLimit
                            )
                        } else {
                            context.getString(
                                R.string.cupthread_attachment_exceeds_limit,
                                formattedLimit
                            )
                        }
                        return@launch
                    }

                    val mimeType = context.contentResolver.getType(uri) ?: "image/png"
                    val filename = withContext(Dispatchers.IO) {
                        var name: String? = null
                        val cursor = context.contentResolver.query(uri, null, null, null, null)
                        cursor?.use {
                            if (it.moveToFirst()) {
                                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                if (nameIndex >= 0) name = it.getString(nameIndex)
                            }
                        }
                        name ?: "screenshot_${System.currentTimeMillis()}.${if (mimeType.contains("jpeg") || mimeType.contains("jpg")) "jpg" else "png"}"
                    }

                    if (AttachmentLimits.isSvg(filename, mimeType) || AttachmentLimits.hasSvgContent(bytes)) {
                        error = unsupportedImageTypeError
                        return@launch
                    }
                    if (!AttachmentLimits.isSupportedImageMime(mimeType)) {
                        error = unsupportedImageTypeError
                        return@launch
                    }

                    val uploaded = client.uploadAttachment(
                        data = bytes,
                        filename = filename,
                        mimeType = mimeType,
                        preferredKind = FeedbackAttachment.Kind.IMAGE,
                        userToken = userToken
                    )
                    draft = draft.copy(attachments = draft.attachments + uploaded)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (ex: FeedbackException.UnsupportedMediaType) {
                    error = unsupportedImageTypeError
                } catch (ex: Exception) {
                    error = ex.message ?: defaultUploadError
                } finally {
                    uploadingAttachment = false
                }
            }
        }
    }

    val canSubmit = draft.title.trim().length >= 3 &&
        draft.description.trim().length >= 5 &&
        draft.attachments.size <= AttachmentLimits.MAX_ATTACHMENT_COUNT

    if (result != null) {
        FeedbackSent(
            warning = result?.warning,
            modifier = modifier,
            onSendMore = { result = null }
        )
        return
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (onCloseSheet != null) {
            Text(
                stringResource(R.string.cupthread_feedback_title),
                style = MaterialTheme.typography.titleLarge
            )
        }
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
            minLines = 5,
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

        // Attachments Section
        if (draft.attachments.isNotEmpty() || uploadingAttachment) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.cupthread_feedback_attachments_title),
                    style = MaterialTheme.typography.titleSmall
                )
                draft.attachments.forEach { attachment ->
                    AttachmentItemRow(
                        attachment = attachment,
                        onDelete = {
                            draft = draft.copy(
                                attachments = draft.attachments.filter { it.key != attachment.key }
                            )
                        }
                    )
                }
                if (uploadingAttachment) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(R.string.cupthread_feedback_uploading_attachment),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (draft.attachments.size < AttachmentLimits.MAX_ATTACHMENT_COUNT) {
            OutlinedButton(
                onClick = {
                    if (onPickAttachment != null) {
                        onPickAttachment()
                    } else {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                },
                enabled = !submitting && !uploadingAttachment && (onPickAttachment != null || isConfigLoaded),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Outlined.AddPhotoAlternate,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.cupthread_feedback_add_attachment))
            }
        }

        AnimatedVisibility(error != null) {
            Text(
                error.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(Modifier.height(4.dp))

        Button(
            onClick = {
                scope.launch {
                    submitting = true
                    error = null
                    try {
                        val sent = client.submit(draft, userToken)
                        result = sent
                        onSubmit(sent)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (ex: FeedbackException.AttachmentScanRejected) {
                        error = ex.serverMessage ?: scanRejectedError
                    } catch (ex: Exception) {
                        error = ex.message ?: defaultGenericError
                    } finally {
                        submitting = false
                    }
                }
            },
            enabled = canSubmit && !submitting && !uploadingAttachment,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (submitting) {
                CircularProgressIndicator(
                    modifier = Modifier.height(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text(
                    if (submitting) stringResource(R.string.cupthread_feedback_sending_button)
                    else stringResource(R.string.cupthread_feedback_send_button)
                )
            }
        }
    }
}

@Composable
private fun AttachmentItemRow(
    attachment: FeedbackAttachment,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                Icons.Outlined.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = attachment.filename ?: "Attachment",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (attachment.size != null && attachment.size > 0) {
                    Text(
                        text = formatBytes(attachment.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = stringResource(R.string.cupthread_feedback_remove_attachment),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1024 * 1024))
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
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

