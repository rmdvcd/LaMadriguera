package com.lamadriguera.app.feature.auth

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import com.lamadriguera.app.R
import com.lamadriguera.app.domain.model.IdentityVerification
import com.lamadriguera.app.domain.model.IdentityVerificationStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Verificación de identidad: frente y reverso del carnet, nombre completo,
 * fecha de nacimiento y aceptación de términos. Muestra el estado de la
 * solicitud (pending/approved/rejected).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentityVerificationScreen(
    state: IdentityVerificationUiState,
    onFullNameChanged: (String) -> Unit,
    onBirthdateChanged: (LocalDate) -> Unit,
    onFrontImageSelected: (ByteArray) -> Unit,
    onBackImageSelected: (ByteArray) -> Unit,
    onTermsAcceptedChanged: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun readImage(onRead: (ByteArray) -> Unit): (android.net.Uri?) -> Unit = { uri ->
        if (uri != null) {
            scope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
                if (bytes != null && bytes.isNotEmpty()) onRead(bytes)
            }
        }
    }

    val frontLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> readImage(onFrontImageSelected)(uri) }
    val backLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> readImage(onBackImageSelected)(uri) }

    var showDatePicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(
            text = stringResource(R.string.identity_title),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.identity_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))

        state.current?.let { verification ->
            VerificationStatusCard(verification)
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (!state.isLocked) {
            OutlinedTextField(
                value = state.fullNameInput,
                onValueChange = onFullNameChanged,
                label = { Text(stringResource(R.string.identity_full_name)) },
                singleLine = true,
                enabled = !state.isSubmitting,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = { showDatePicker = true },
                enabled = !state.isSubmitting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = state.birthdateInput?.toString()
                        ?: stringResource(R.string.identity_birthdate_select)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            ImagePickerRow(
                label = stringResource(R.string.identity_front),
                selected = state.hasFrontImage,
                enabled = !state.isSubmitting,
                onPick = { frontLauncher.launch("image/*") }
            )
            Spacer(modifier = Modifier.height(8.dp))
            ImagePickerRow(
                label = stringResource(R.string.identity_back),
                selected = state.hasBackImage,
                enabled = !state.isSubmitting,
                onPick = { backLauncher.launch("image/*") }
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(
                    checked = state.termsAccepted,
                    onCheckedChange = onTermsAcceptedChanged,
                    enabled = !state.isSubmitting
                )
                Text(
                    text = stringResource(R.string.identity_terms),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onSubmit,
                enabled = state.canSubmit,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(stringResource(R.string.identity_submit))
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.identity_retention_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.birthdateInput
                ?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                            onBirthdateChanged(date)
                        }
                        showDatePicker = false
                    }
                ) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
private fun VerificationStatusCard(verification: IdentityVerification) {
    val (title, description) = when (verification.status) {
        IdentityVerificationStatus.PENDING ->
            stringResource(R.string.identity_status_pending) to
                stringResource(R.string.identity_status_pending_desc)
        IdentityVerificationStatus.APPROVED ->
            stringResource(R.string.identity_status_approved) to
                stringResource(R.string.identity_status_approved_desc)
        IdentityVerificationStatus.REJECTED ->
            stringResource(R.string.identity_status_rejected) to
                stringResource(R.string.identity_status_rejected_desc)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ImagePickerRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onPick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
                shape = MaterialTheme.shapes.small
            )
            .padding(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            if (selected) {
                Text(
                    text = stringResource(R.string.identity_image_selected),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        OutlinedButton(onClick = onPick, enabled = enabled) {
            Text(stringResource(R.string.identity_select_image))
        }
    }
}
