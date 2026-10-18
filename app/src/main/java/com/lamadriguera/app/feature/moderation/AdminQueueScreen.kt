package com.lamadriguera.app.feature.moderation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lamadriguera.app.R
import com.lamadriguera.app.domain.model.ModerationReview
import com.lamadriguera.app.domain.model.ModerationReviewStatus
import com.lamadriguera.app.domain.model.ModerationSource
import com.lamadriguera.app.domain.model.Sanction

/**
 * AdminModerationQueue: cola humana con filtros por severidad y estado.
 * Solo aparece lo dudoso que marcó la IA (umbral bajo) y los reportes de
 * usuarios. Toda sanción deja registro con evidencia; nada se borra.
 */
@Composable
fun AdminQueueScreen(
    state: AdminQueueUiState,
    onRefresh: () -> Unit,
    onStatusFilterChanged: (ModerationReviewStatus?) -> Unit,
    onSeverityFilterChanged: (SeverityFilter) -> Unit,
    onOpenItem: (ModerationReview) -> Unit,
    onCloseItem: () -> Unit,
    onReasonChanged: (String) -> Unit,
    onApplySanction: (Sanction) -> Unit,
    onDismissReview: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.moderation_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.moderation_queue_subtitle, state.items.size, state.pendingCount),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = onRefresh) {
                Text(stringResource(R.string.common_retry))
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = state.statusFilter == null,
                onClick = { onStatusFilterChanged(null) },
                label = { Text(stringResource(R.string.moderation_filter_all)) }
            )
            ModerationReviewStatus.entries.forEach { status ->
                FilterChip(
                    selected = state.statusFilter == status,
                    onClick = { onStatusFilterChanged(status) },
                    label = { Text(statusLabel(status)) }
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SeverityFilter.entries.forEach { filter ->
                FilterChip(
                    selected = state.severityFilter == filter,
                    onClick = { onSeverityFilterChanged(filter) },
                    label = { Text(filter.label) }
                )
            }
        }

        when {
            state.isLoading -> CircularProgressIndicator(Modifier.padding(24.dp))
            state.items.isEmpty() -> Text(
                text = stringResource(
                    if (state.statusFilter == null && state.severityFilter == SeverityFilter.ANY) {
                        R.string.moderation_queue_empty
                    } else {
                        R.string.moderation_queue_empty_filtered
                    }
                ),
                modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyMedium
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                state.errorMessage?.let { message ->
                    item { Text(message, color = MaterialTheme.colorScheme.error) }
                }
                state.message?.let { message ->
                    item {
                        Text(
                            message,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                items(state.items, key = { it.id }) { item ->
                    QueueItemCard(item = item, onClick = { onOpenItem(item) })
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (state.selected != null) {
        ReviewDetailDialog(
            state = state,
            onClose = onCloseItem,
            onReasonChanged = onReasonChanged,
            onApplySanction = onApplySanction,
            onDismissReview = onDismissReview
        )
    }
}

@Composable
private fun QueueItemCard(item: ModerationReview, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = when {
                item.severity >= 4 -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
                item.status == ModerationReviewStatus.PENDING -> MaterialTheme.colorScheme.surfaceVariant
                else -> MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${AdminQueueViewModel.targetTypeLabel(item.targetType)} · ${item.category}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "sev ${item.severity}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.severity >= 4) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            item.excerpt?.let { excerpt ->
                Text(excerpt, style = MaterialTheme.typography.bodySmall, maxLines = 3)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (item.source == ModerationSource.AI) {
                        stringResource(R.string.moderation_source_ai)
                    } else {
                        stringResource(R.string.moderation_source_user_report)
                    },
                    style = MaterialTheme.typography.labelSmall
                )
                Text(text = statusLabel(item.status), style = MaterialTheme.typography.labelSmall)
                item.aiProvider?.let { provider ->
                    Text(text = provider, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun ReviewDetailDialog(
    state: AdminQueueUiState,
    onClose: () -> Unit,
    onReasonChanged: (String) -> Unit,
    onApplySanction: (Sanction) -> Unit,
    onDismissReview: () -> Unit
) {
    val selected = state.selected ?: return
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Text("${AdminQueueViewModel.targetTypeLabel(selected.targetType)} · ${selected.category}")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                selected.excerpt?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Text(
                    "Severidad ${selected.severity} · ${statusLabel(selected.status)}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    stringResource(R.string.moderation_hide_note),
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = state.reasonInput,
                    onValueChange = onReasonChanged,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    placeholder = { Text(stringResource(R.string.moderation_reason_hint)) }
                )
                state.errorMessage?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onApplySanction(Sanction.WARN) }, enabled = !state.isBusy) {
                        Text(stringResource(R.string.moderation_action_warn))
                    }
                    OutlinedButton(onClick = { onApplySanction(Sanction.HIDE) }, enabled = !state.isBusy) {
                        Text(stringResource(R.string.moderation_action_hide))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onApplySanction(Sanction.SUSPEND) }, enabled = !state.isBusy) {
                        Text(stringResource(R.string.moderation_action_suspend))
                    }
                    Button(
                        onClick = { onApplySanction(Sanction.BAN) },
                        enabled = !state.isBusy,
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.moderation_action_ban))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = onDismissReview, enabled = !state.isBusy) {
                        Text(stringResource(R.string.moderation_action_dismiss))
                    }
                    TextButton(onClick = onClose) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun statusLabel(status: ModerationReviewStatus): String = when (status) {
    ModerationReviewStatus.PENDING -> stringResource(R.string.moderation_status_pending)
    ModerationReviewStatus.IN_REVIEW -> stringResource(R.string.moderation_status_in_review)
    ModerationReviewStatus.RESOLVED -> stringResource(R.string.moderation_status_resolved)
    ModerationReviewStatus.DISMISSED -> stringResource(R.string.moderation_status_dismissed)
}
