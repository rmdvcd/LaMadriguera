package com.lamadriguera.app.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lamadriguera.app.R
import com.lamadriguera.app.domain.model.ConversationSummary
import com.lamadriguera.app.ui.components.EmptyState
import com.lamadriguera.app.ui.components.ErrorState
import com.lamadriguera.app.ui.components.LoadingState
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Lista de hilos activos: último mensaje (que en reposo es ciphertext y solo
 * se muestra descifrado desde memoria) y badge de no leídos por hilo.
 */
@Composable
fun ConversationListScreen(
    state: ConversationListUiState,
    onOpenConversation: (String) -> Unit,
    onDismissNotice: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Notificación persistente del sistema, incluidos pagos y moderación.
        state.systemNotice?.let { notice ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (notice.type != "thread_decrypted") {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (notice.type != "thread_decrypted") {
                                Icons.Filled.Notifications
                            } else {
                                Icons.Filled.Gavel
                            },
                            contentDescription = null,
                            tint = if (notice.type != "thread_decrypted") {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onErrorContainer
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (notice.type == "thread_decrypted") {
                                stringResource(R.string.chat_thread_decrypted_notice)
                            } else notice.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (notice.type != "thread_decrypted") {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onErrorContainer
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = notice.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (notice.type != "thread_decrypted") {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        }
                    )
                    TextButton(
                        onClick = onDismissNotice,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(stringResource(R.string.chat_got_it))
                    }
                }
            }
        }

        when {
            state.isLoading && state.items.isEmpty() ->
                LoadingState(modifier = Modifier.fillMaxSize())

            state.errorMessage != null && state.items.isEmpty() ->
                ErrorState(
                    message = state.errorMessage,
                    onRetry = onRetry,
                    modifier = Modifier.fillMaxSize()
                )

            state.items.isEmpty() -> EmptyState(
                messageRes = R.string.chat_empty,
                modifier = Modifier.fillMaxSize()
            )

            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(state.items, key = { it.conversation.id }) { summary ->
                    ConversationRowCard(
                        summary = summary,
                        onClick = { onOpenConversation(summary.conversation.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationRowCard(
    summary: ConversationSummary,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        R.string.chat_conversation_with,
                        summary.otherParticipantId.take(8)
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                summary.lastMessage?.let { message ->
                    Text(
                        text = shortTimeFormatter.format(message.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .width(14.dp)
                        .height(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = summary.lastMessagePreview.ifBlank {
                        stringResource(R.string.chat_encrypted_note)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (summary.unreadCount > 0) {
                    Badge {
                        Text(text = summary.unreadCount.coerceAtMost(99).toString())
                    }
                }
            }
            if (summary.inDispute) {
                Spacer(modifier = Modifier.height(6.dp))
                Badge(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ) {
                    Text(text = stringResource(R.string.chat_badge_dispute))
                }
            }
        }
    }
}

private val shortTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault())
