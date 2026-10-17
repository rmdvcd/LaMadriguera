package com.lamadriguera.app.feature.auction

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lamadriguera.app.R
import com.lamadriguera.app.domain.model.BidVisibility
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Pantalla de configuración de subasta (`AuctionConfigScreen`).
 *
 * Incluye validaciones de `ends_at > starts_at`, `starting_price > 0`,
 * `min_increment > 0` y `reserve_price >= starting_price`, además de bloquear
 * la edición si ya existen pujas o el usuario no es el dueño.
 */
@Composable
fun AuctionConfigScreen(
    state: AuctionConfigUiState,
    onStartingPriceChanged: (String) -> Unit,
    onMinIncrementChanged: (String) -> Unit,
    onReservePriceChanged: (String) -> Unit,
    onStartsAtChanged: (String) -> Unit,
    onEndsAtChanged: (String) -> Unit,
    onAutoExtendMinutesChanged: (String) -> Unit,
    onRequiresIdentityChanged: (Boolean) -> Unit,
    onVisibleBidsChanged: (BidVisibility) -> Unit,
    onSave: () -> Unit,
    onCancel: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val editable = state.canEdit && !state.isSaving

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(
            text = stringResource(R.string.auction_config_title),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.auction_config_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))

        if (!state.canEdit) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = stringResource(R.string.auction_config_locked_notice),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(16.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        OutlinedTextField(
            value = state.startingPriceInput,
            onValueChange = onStartingPriceChanged,
            label = { Text(stringResource(R.string.auction_config_starting_price)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            enabled = editable,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = state.minIncrementInput,
            onValueChange = onMinIncrementChanged,
            label = { Text(stringResource(R.string.auction_config_min_increment)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            enabled = editable,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = state.reservePriceInput,
            onValueChange = onReservePriceChanged,
            label = { Text(stringResource(R.string.auction_config_reserve_price)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            enabled = editable,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = state.startsAtInput,
            onValueChange = onStartsAtChanged,
            label = { Text(stringResource(R.string.auction_config_starts_at)) },
            singleLine = true,
            enabled = editable,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            FilterChip(
                selected = false,
                enabled = editable,
                onClick = {
                    val now = Instant.now().truncatedTo(ChronoUnit.MINUTES)
                    onStartsAtChanged(now.toString())
                },
                label = { Text("Ahora") }
            )
            FilterChip(
                selected = false,
                enabled = editable,
                onClick = {
                    val inOneHour = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES)
                    onStartsAtChanged(inOneHour.toString())
                },
                label = { Text("+1 h") }
            )
            FilterChip(
                selected = false,
                enabled = editable,
                onClick = {
                    val tomorrow = Instant.now().plus(24, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES)
                    onStartsAtChanged(tomorrow.toString())
                },
                label = { Text("+24 h") }
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = state.endsAtInput,
            onValueChange = onEndsAtChanged,
            label = { Text(stringResource(R.string.auction_config_ends_at)) },
            singleLine = true,
            enabled = editable,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            val baseStart = runCatching { Instant.parse(state.startsAtInput.trim()) }
                .getOrNull() ?: Instant.now()
            FilterChip(
                selected = false,
                enabled = editable,
                onClick = {
                    onEndsAtChanged(baseStart.plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES).toString())
                },
                label = { Text("+2 h") }
            )
            FilterChip(
                selected = false,
                enabled = editable,
                onClick = {
                    onEndsAtChanged(baseStart.plus(24, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES).toString())
                },
                label = { Text("+24 h") }
            )
            FilterChip(
                selected = false,
                enabled = editable,
                onClick = {
                    onEndsAtChanged(baseStart.plus(72, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES).toString())
                },
                label = { Text("+3 días") }
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = state.autoExtendMinutesInput,
            onValueChange = onAutoExtendMinutesChanged,
            label = { Text(stringResource(R.string.auction_config_auto_extend)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            enabled = editable,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.auction_config_requires_identity),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = stringResource(R.string.auction_config_requires_identity_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = if (state.isMoneyAuction) true else state.requiresIdentity,
                onCheckedChange = onRequiresIdentityChanged,
                enabled = editable && !state.isMoneyAuction
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.auction_config_visible_bids_label),
            style = MaterialTheme.typography.titleSmall
        )
        Spacer(modifier = Modifier.height(6.dp))
        BidVisibilityOption(
            title = stringResource(R.string.auction_config_visible_all),
            selected = state.visibleBids == BidVisibility.ALL,
            enabled = editable,
            onClick = { onVisibleBidsChanged(BidVisibility.ALL) }
        )
        BidVisibilityOption(
            title = stringResource(R.string.auction_config_visible_owner),
            selected = state.visibleBids == BidVisibility.OWNER,
            enabled = editable,
            onClick = { onVisibleBidsChanged(BidVisibility.OWNER) }
        )

        val liveValidationError = state.validationError ?: state.validate()
        if (liveValidationError != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = liveValidationError,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(modifier = Modifier.height(20.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (onCancel != null) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
            Button(
                onClick = onSave,
                enabled = editable && state.validate() == null,
                modifier = Modifier.weight(1f)
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(stringResource(R.string.auction_config_save))
                }
            }
        }
    }
}

@Composable
private fun BidVisibilityOption(
    title: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick
            )
            .padding(vertical = 4.dp)
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}
