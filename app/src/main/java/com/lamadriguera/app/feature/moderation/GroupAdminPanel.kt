package com.lamadriguera.app.feature.moderation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import com.lamadriguera.app.domain.model.BettingGroupMember
import com.lamadriguera.app.domain.model.ModerationReview
import com.lamadriguera.app.domain.model.Sanction

/**
 * GroupAdminPanel: para que el owner del grupo vea los reportes/revisiones de
 * SU grupo, edite las reglas visibles y expulse miembros. Cada grupo tiene un
 * admin responsable; la plataforma recibe reportes pero no modera lo privado.
 */
@Composable
fun GroupAdminPanel(
    state: GroupAdminPanelUiState,
    onRefresh: () -> Unit,
    onRulesChanged: (String) -> Unit,
    onSaveRules: () -> Unit,
    onExpelMember: (BettingGroupMember) -> Unit,
    onOpenReview: (ModerationReview) -> Unit,
    onCloseReview: () -> Unit,
    onReasonChanged: (String) -> Unit,
    onApplySanction: (Sanction) -> Unit,
    onDismissReview: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (state.isLoading) {
        Column(
            modifier = modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }

    if (!state.isOwner) {
        Column(modifier = modifier.fillMaxSize().padding(24.dp)) {
            Text(
                state.errorMessage ?: stringResource(R.string.group_admin_only_owner),
                color = MaterialTheme.colorScheme.error
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                state.group?.name.orEmpty(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }
        state.errorMessage?.let { message ->
            item { Text(message, color = MaterialTheme.colorScheme.error) }
        }
        state.message?.let { message ->
            item {
                Text(message, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
        }

        // --- Reglas visibles del grupo ---
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.group_admin_rules_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        stringResource(R.string.group_admin_rules_default),
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = state.rulesInput,
                        onValueChange = onRulesChanged,
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        placeholder = { Text(stringResource(R.string.group_admin_rules_hint)) }
                    )
                    Button(onClick = onSaveRules, enabled = !state.isBusy) {
                        Text(stringResource(R.string.common_save))
                    }
                }
            }
        }

        // --- Reportes y revisiones del grupo ---
        item {
            Text(
                stringResource(R.string.group_admin_reports_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        if (state.reviews.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.group_admin_reports_empty),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else {
            items(state.reviews, key = { it.id }) { review ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (review.severity >= 4) {
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    ),
                    onClick = { onOpenReview(review) }
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "${AdminQueueViewModel.targetTypeLabel(review.targetType)} · ${review.category} · sev ${review.severity}",
                            style = MaterialTheme.typography.titleSmall
                        )
                        review.excerpt?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                    }
                }
            }
        }

        // --- Miembros ---
        item {
            Text(
                stringResource(R.string.group_admin_members_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        items(state.members, key = { it.id }) { member ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = member.userId,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (member.userId == state.group?.ownerId) {
                        Text(
                            text = stringResource(R.string.group_admin_title),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                if (member.userId != state.group?.ownerId) {
                    OutlinedButton(
                        onClick = { onExpelMember(member) },
                        enabled = !state.isBusy
                    ) {
                        Text(stringResource(R.string.group_admin_expel))
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (state.selected != null) {
        GroupReviewDialog(
            state = state,
            onClose = onCloseReview,
            onReasonChanged = onReasonChanged,
            onApplySanction = onApplySanction,
            onDismissReview = onDismissReview
        )
    }
}

@Composable
private fun GroupReviewDialog(
    state: GroupAdminPanelUiState,
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
                Text(stringResource(R.string.moderation_hide_note), style = MaterialTheme.typography.bodySmall)
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
