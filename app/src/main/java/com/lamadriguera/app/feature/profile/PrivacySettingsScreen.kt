package com.lamadriguera.app.feature.profile

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.lamadriguera.app.R
import com.lamadriguera.app.domain.model.PrivacyLevel
import com.lamadriguera.app.domain.model.RevealPolicy

/**
 * Privacidad: nivel por defecto (public/anon), política de revelación en chat
 * (never/on_deal/on_dispute/configurable) y alias. Aviso: solo puedes cambiar
 * el nivel antes de publicar.
 */
@Composable
fun PrivacySettingsScreen(
    state: PrivacySettingsUiState,
    onPrivacyLevelChanged: (PrivacyLevel) -> Unit,
    onRevealPolicyChanged: (RevealPolicy) -> Unit,
    onAliasChanged: (String) -> Unit,
    onSave: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(
            text = stringResource(R.string.privacy_title),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.privacy_level_title),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        PrivacyOption(
            title = stringResource(R.string.privacy_level_public),
            description = stringResource(R.string.privacy_level_public_desc),
            selected = state.privacyLevel == PrivacyLevel.PUBLIC,
            enabled = state.canChangePrivacyLevel,
            onClick = { onPrivacyLevelChanged(PrivacyLevel.PUBLIC) }
        )
        PrivacyOption(
            title = stringResource(R.string.privacy_level_anon),
            description = stringResource(R.string.privacy_level_anon_desc),
            selected = state.privacyLevel == PrivacyLevel.ANON,
            enabled = state.canChangePrivacyLevel,
            onClick = { onPrivacyLevelChanged(PrivacyLevel.ANON) }
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = state.aliasInput,
            onValueChange = onAliasChanged,
            label = { Text(stringResource(R.string.privacy_alias_hint)) },
            singleLine = true,
            enabled = !state.isSaving,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.privacy_notice),
            style = MaterialTheme.typography.bodySmall,
            color = if (state.hasPublished) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.privacy_reveal_title),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        RevealOption(
            title = stringResource(R.string.privacy_reveal_never),
            description = stringResource(R.string.privacy_reveal_never_desc),
            selected = state.revealPolicy == RevealPolicy.NEVER,
            enabled = !state.isSaving,
            onClick = { onRevealPolicyChanged(RevealPolicy.NEVER) }
        )
        RevealOption(
            title = stringResource(R.string.privacy_reveal_on_deal),
            description = stringResource(R.string.privacy_reveal_on_deal_desc),
            selected = state.revealPolicy == RevealPolicy.ON_DEAL,
            enabled = !state.isSaving,
            onClick = { onRevealPolicyChanged(RevealPolicy.ON_DEAL) }
        )
        RevealOption(
            title = stringResource(R.string.privacy_reveal_on_dispute),
            description = stringResource(R.string.privacy_reveal_on_dispute_desc),
            selected = state.revealPolicy == RevealPolicy.ON_DISPUTE,
            enabled = !state.isSaving,
            onClick = { onRevealPolicyChanged(RevealPolicy.ON_DISPUTE) }
        )
        RevealOption(
            title = stringResource(R.string.privacy_reveal_configurable),
            description = stringResource(R.string.privacy_reveal_configurable_desc),
            selected = state.revealPolicy == RevealPolicy.CONFIGURABLE,
            enabled = !state.isSaving,
            onClick = { onRevealPolicyChanged(RevealPolicy.CONFIGURABLE) }
        )
        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onSave,
            enabled = !state.isSaving && !state.isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (state.isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Text(stringResource(R.string.common_save))
            }
        }

        if (state.errorMessage != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = state.errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun PrivacyOption(
    title: String,
    description: String,
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
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RevealOption(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) = PrivacyOption(
    title = title,
    description = description,
    selected = selected,
    enabled = enabled,
    onClick = onClick
)
