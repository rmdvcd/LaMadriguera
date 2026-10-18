package com.lamadriguera.app.feature.chat

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.lamadriguera.app.R
import com.lamadriguera.app.feature.moderation.ReportButton
import com.lamadriguera.app.domain.model.ModerationTargetType
import com.lamadriguera.app.domain.model.MessageContent
import com.lamadriguera.app.domain.model.ChatMessage
import com.lamadriguera.app.domain.model.MessageExpiry
import com.lamadriguera.app.ui.components.LoadingState
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Hilo de chat cifrado (diseño C): texto, imagen (máx. 1 MB), audio
 * (máx. 1 MB) y ubicación, con autodestrucción opcional (24 h / 7 d / nunca),
 * advertencia de datos personales y flujo de reporte/disputa.
 */
@Composable
fun ChatScreen(
    state: ChatUiState,
    onDraftChanged: (String) -> Unit,
    onExpiryChanged: (MessageExpiry) -> Unit,
    onSendText: () -> Unit,
    onImagePicked: (ByteArray, String) -> Unit,
    onAudioPicked: (ByteArray, String) -> Unit,
    onMediaPickFailed: () -> Unit,
    onLocationRequestStarted: () -> Unit,
    onLocationAvailable: (Double, Double) -> Unit,
    onLocationFailed: () -> Unit,
    onConfirmPersonalData: () -> Unit,
    onDismissPersonalData: () -> Unit,
    onOpenReport: () -> Unit,
    onCloseReport: () -> Unit,
    onReportReasonChanged: (String) -> Unit,
    onSubmitReport: () -> Unit,
    onDismissReportBanner: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            if (bytes != null) onImagePicked(bytes, mime) else onMediaPickFailed()
        }
    }
    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            val mime = context.contentResolver.getType(uri) ?: "audio/mp4"
            if (bytes != null) onAudioPicked(bytes, mime) else onMediaPickFailed()
        }
    }
    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val location = LocationHelper.lastKnownLocation(context)
            if (location != null) {
                onLocationAvailable(location.first, location.second)
            } else {
                onLocationFailed()
            }
        } else {
            onLocationFailed()
        }
    }

    DisposableEffect(Unit) {
        onDispose { AudioPlayer.stop() }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (state.reportDoneBanner) {
            InfoBanner(
                text = stringResource(R.string.chat_report_done),
                actionLabel = stringResource(R.string.chat_got_it),
                onAction = onDismissReportBanner,
                errorStyle = true
            )
        } else if (state.inDispute) {
            InfoBanner(
                text = stringResource(R.string.chat_dispute_banner),
                actionLabel = null,
                onAction = {},
                errorStyle = true
            )
        }

        if (state.isProcessingMedia) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            if (state.isLoading && state.messages.isEmpty()) {
                LoadingState(modifier = Modifier.fillMaxSize())
            } else {
                MessageList(messages = state.messages)
            }
        }

        ChatComposer(
            state = state,
            onDraftChanged = onDraftChanged,
            onExpiryChanged = onExpiryChanged,
            onSendText = onSendText,
            onPickImage = { imagePicker.launch("image/*") },
            onPickAudio = { audioPicker.launch("audio/*") },
            onShareLocation = {
                onLocationRequestStarted()
                if (LocationHelper.hasPermission(context)) {
                    val location = LocationHelper.lastKnownLocation(context)
                    if (location != null) {
                        onLocationAvailable(location.first, location.second)
                    } else {
                        onLocationFailed()
                    }
                } else {
                    locationPermission.launch(LocationHelper.PERMISSION)
                }
            },
            onOpenReport = onOpenReport
        )
    }

    if (state.pendingPersonalDataSend != null) {
        AlertDialog(
            onDismissRequest = onDismissPersonalData,
            title = { Text(stringResource(R.string.chat_personal_data_title)) },
            text = { Text(stringResource(R.string.chat_personal_data_body)) },
            confirmButton = {
                Button(onClick = onConfirmPersonalData) {
                    Text(stringResource(R.string.chat_personal_data_send))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissPersonalData) {
                    Text(stringResource(R.string.chat_personal_data_edit))
                }
            }
        )
    }

    if (state.showReportDialog) {
        AlertDialog(
            onDismissRequest = onCloseReport,
            title = { Text(stringResource(R.string.chat_report_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.chat_report_body),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = state.reportReason,
                        onValueChange = onReportReasonChanged,
                        placeholder = { Text(stringResource(R.string.chat_report_reason_hint)) },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = onSubmitReport,
                    enabled = state.reportReason.isNotBlank() && !state.isReporting
                ) {
                    Text(stringResource(R.string.chat_report_submit))
                }
            },
            dismissButton = {
                TextButton(onClick = onCloseReport) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    state.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text(stringResource(R.string.common_error)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = onDismissError) {
                    Text(stringResource(R.string.chat_got_it))
                }
            }
        )
    }
}

@Composable
private fun InfoBanner(
    text: String,
    actionLabel: String?,
    onAction: () -> Unit,
    errorStyle: Boolean
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (errorStyle) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = if (errorStyle) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
                modifier = Modifier.weight(1f)
            )
            if (actionLabel != null) {
                TextButton(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun MessageList(messages: List<ChatMessage>) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        items(messages, key = { it.id }) { message ->
            MessageBubble(message)
        }
        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isMine) Arrangement.End else Arrangement.Start
    ) {
        Card(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (message.isMine) 16.dp else 4.dp,
                bottomEnd = if (message.isMine) 4.dp else 16.dp
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (message.isMine) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            ),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                when (val content = message.content) {
                    is MessageContent.Text -> Text(
                        text = content.text,
                        style = MaterialTheme.typography.bodyMedium
                    )

                    is MessageContent.Image -> ImageContent(content)

                    is MessageContent.Audio -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        IconButton(onClick = {
                            AudioPlayer.play(context, content.bytes, content.mimeType)
                        }) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = stringResource(R.string.chat_audio_play)
                            )
                        }
                        Icon(imageVector = Icons.Filled.AudioFile, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.chat_audio_play),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    is MessageContent.Location -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Filled.LocationOn, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = String.format(
                                Locale.US, "%.5f, %.5f", content.latitude, content.longitude
                            ),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    MessageContent.Undecryptable -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.chat_undecryptable),
                            style = MaterialTheme.typography.bodySmall,
                            fontStyle = FontStyle.Italic
                        )
                    }

                    MessageContent.Expired -> Text(
                        text = stringResource(R.string.chat_expired_message),
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    message.expiresAt?.let { expiresAt ->
                        Icon(
                            imageVector = Icons.Filled.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(11.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = remainingText(expiresAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = timeFormatter.format(message.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!message.isMine) {
                        Spacer(modifier = Modifier.width(2.dp))
                        ReportButton(
                            targetType = ModerationTargetType.MESSAGE,
                            targetId = message.id,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageContent(content: MessageContent.Image) {
    val bitmap = remember(content.bytes) {
        runCatching { BitmapFactory.decodeByteArray(content.bytes, 0, content.bytes.size) }
            .getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .clip(RoundedCornerShape(12.dp))
        )
    } else {
        Text(text = "🖼", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ChatComposer(
    state: ChatUiState,
    onDraftChanged: (String) -> Unit,
    onExpiryChanged: (MessageExpiry) -> Unit,
    onSendText: () -> Unit,
    onPickImage: () -> Unit,
    onPickAudio: () -> Unit,
    onShareLocation: () -> Unit,
    onOpenReport: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(R.string.chat_expiry_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ExpiryChip(
                labelRes = R.string.chat_expiry_24h,
                selected = state.expiry == MessageExpiry.HOURS_24,
                onClick = { onExpiryChanged(MessageExpiry.HOURS_24) }
            )
            ExpiryChip(
                labelRes = R.string.chat_expiry_7d,
                selected = state.expiry == MessageExpiry.DAYS_7,
                onClick = { onExpiryChanged(MessageExpiry.DAYS_7) }
            )
            ExpiryChip(
                labelRes = R.string.chat_expiry_never,
                selected = state.expiry == MessageExpiry.NEVER,
                onClick = { onExpiryChanged(MessageExpiry.NEVER) }
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onOpenReport) {
                Icon(
                    imageVector = Icons.Filled.Flag,
                    contentDescription = stringResource(R.string.chat_report),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }

        Row(verticalAlignment = Alignment.Bottom) {
            IconButton(onClick = onPickImage, enabled = !state.isSending) {
                Icon(
                    imageVector = Icons.Filled.Image,
                    contentDescription = stringResource(R.string.chat_attach_image)
                )
            }
            IconButton(onClick = onPickAudio, enabled = !state.isSending) {
                Icon(
                    imageVector = Icons.Filled.AudioFile,
                    contentDescription = stringResource(R.string.chat_attach_audio)
                )
            }
            IconButton(onClick = onShareLocation, enabled = !state.isSending) {
                Icon(
                    imageVector = Icons.Filled.LocationOn,
                    contentDescription = stringResource(R.string.chat_attach_location)
                )
            }
            OutlinedTextField(
                value = state.draft,
                onValueChange = onDraftChanged,
                placeholder = { Text(stringResource(R.string.chat_type_message)) },
                maxLines = 4,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp)
            )
            IconButton(onClick = onSendText, enabled = state.draft.isNotBlank() && !state.isSending) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.chat_send)
                )
            }
        }
    }
}

@Composable
private fun ExpiryChip(
    labelRes: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.labelSmall
            )
        }
    )
}

private val timeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

private fun remainingText(expiresAt: Instant): String {
    val remaining = Duration.between(Instant.now(), expiresAt)
    val hours = remaining.toHours().coerceAtLeast(0)
    val days = remaining.toDays().coerceAtLeast(0)
    return when {
        days >= 1 -> "${days}d"
        hours >= 1 -> "${hours}h"
        else -> "${remaining.toMinutes().coerceAtLeast(0)}m"
    }
}
