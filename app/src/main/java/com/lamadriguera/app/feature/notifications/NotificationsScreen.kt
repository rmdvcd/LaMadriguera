package com.lamadriguera.app.feature.notifications

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.lamadriguera.app.R
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Notificaciones: estado del push (FCM), interruptores por tipo, bot opcional
 * de Telegram (solo si compartes tu chat_id) y bandeja in-app — que además
 * ES el canal de avisos cuando el dispositivo no tiene Google Play Services.
 */
@Composable
fun NotificationsScreen(
    state: NotificationsUiState,
    onMasterToggled: (Boolean) -> Unit,
    onOffersToggled: (Boolean) -> Unit,
    onMessagesToggled: (Boolean) -> Unit,
    onPaymentsToggled: (Boolean) -> Unit,
    onAuctionsToggled: (Boolean) -> Unit,
    onTelegramToggled: (Boolean) -> Unit,
    onTelegramChatIdChanged: (String) -> Unit,
    onSave: () -> Unit,
    onMarkAllRead: () -> Unit,
    onPermissionResult: (Boolean) -> Unit = {}
) {
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> onPermissionResult(granted) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(
            text = stringResource(R.string.notif_title),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(8.dp))

        PushStatusCard(
            state = state,
            onAskPermission = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        )
        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.notif_switches_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                ToggleRow(
                    label = stringResource(R.string.notif_switch_master),
                    description = stringResource(R.string.notif_switch_master_desc),
                    checked = state.preferences.pushEnabled,
                    onCheckedChange = onMasterToggled
                )
                ToggleRow(
                    label = stringResource(R.string.notif_switch_offers),
                    description = null,
                    checked = state.preferences.notifyOffers,
                    enabled = state.preferences.pushEnabled,
                    onCheckedChange = onOffersToggled
                )
                ToggleRow(
                    label = stringResource(R.string.notif_switch_messages),
                    description = null,
                    checked = state.preferences.notifyMessages,
                    enabled = state.preferences.pushEnabled,
                    onCheckedChange = onMessagesToggled
                )
                ToggleRow(
                    label = stringResource(R.string.notif_switch_payments),
                    description = null,
                    checked = state.preferences.notifyPayments,
                    enabled = state.preferences.pushEnabled,
                    onCheckedChange = onPaymentsToggled
                )
                ToggleRow(
                    label = stringResource(R.string.notif_switch_auctions),
                    description = null,
                    checked = state.preferences.notifyAuctions,
                    enabled = state.preferences.pushEnabled,
                    onCheckedChange = onAuctionsToggled
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.notif_telegram_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.notif_telegram_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                ToggleRow(
                    label = stringResource(R.string.notif_telegram_enable),
                    description = null,
                    checked = state.preferences.telegramEnabled,
                    onCheckedChange = onTelegramToggled
                )
                OutlinedTextField(
                    value = state.preferences.telegramChatId.orEmpty(),
                    onValueChange = onTelegramChatIdChanged,
                    label = { Text(stringResource(R.string.notif_telegram_chat_id)) },
                    supportingText = { Text(stringResource(R.string.notif_telegram_chat_id_hint)) },
                    isError = state.telegramIdError != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                state.telegramIdError?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onSave,
            enabled = !state.isSaving,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (state.isSaving) {
                CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.notif_saving))
            } else {
                Text(stringResource(R.string.notif_save))
            }
        }
        state.savedAtMillis?.let {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.notif_saved),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall
            )
        }
        state.errorMessage?.let {
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.height(24.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.notif_inbox_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            if (state.inbox.isNotEmpty()) {
                OutlinedButton(onClick = onMarkAllRead) {
                    Text(stringResource(R.string.notif_mark_read))
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (state.isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        } else if (state.inbox.isEmpty()) {
            Text(
                text = stringResource(R.string.notif_inbox_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            state.inbox.forEach { notification ->
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.elevatedCardColors()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = notification.title,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = notification.body,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = notification.createdAt.atZone(ZoneId.systemDefault())
                                .format(InboxTimeFormat),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private val InboxTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM HH:mm")

@Composable
private fun PushStatusCard(state: NotificationsUiState, onAskPermission: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.notif_push_title),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = when {
                    !state.pushAvailable -> stringResource(R.string.notif_push_unavailable)
                    !state.systemPermissionGranted -> stringResource(R.string.notif_push_no_permission)
                    else -> stringResource(R.string.notif_push_active)
                },
                style = MaterialTheme.typography.bodyMedium
            )
            if (state.pushAvailable && !state.systemPermissionGranted) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onAskPermission) {
                    Text(stringResource(R.string.notif_push_ask_permission))
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    description: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}
