package com.lamadriguera.app.feature.moderation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lamadriguera.app.R
import com.lamadriguera.app.domain.model.ModerationTargetType

/**
 * Botón de reporte reutilizable en listings, mensajes, perfiles y apuestas.
 * Abre un diálogo con categoría y motivo; el reporte entra a la cola de la
 * plataforma (y a la del admin del grupo cuando el contenido es de un grupo).
 */
@Composable
fun ReportButton(
    targetType: ModerationTargetType,
    targetId: String,
    modifier: Modifier = Modifier,
    viewModel: ReportViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    IconButton(onClick = viewModel::open, modifier = modifier) {
        Icon(
            imageVector = Icons.Filled.Flag,
            contentDescription = stringResource(R.string.report_button),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (state.showDialog) {
        AlertDialog(
            onDismissRequest = viewModel::close,
            title = { Text(stringResource(R.string.report_title)) },
            text = {
                ReportDialogContent(
                    state = state,
                    onCategorySelected = viewModel::onCategorySelected,
                    onReasonChanged = viewModel::onReasonChanged
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.submit(targetType, targetId) },
                    enabled = state.reason.isNotBlank() && !state.isSubmitting
                ) {
                    Text(stringResource(R.string.report_submit))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::close) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    if (state.done) {
        AlertDialog(
            onDismissRequest = viewModel::close,
            title = { Text(stringResource(R.string.report_button)) },
            text = { Text(stringResource(R.string.report_done)) },
            confirmButton = {
                TextButton(onClick = viewModel::close) {
                    Text(stringResource(R.string.common_confirm))
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReportDialogContent(
    state: ReportUiState,
    onCategorySelected: (ReportCategoryChoice) -> Unit,
    onReasonChanged: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.report_body), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ReportCategoryChoice.entries.forEach { choice ->
                FilterChip(
                    selected = state.category == choice,
                    onClick = { onCategorySelected(choice) },
                    label = { Text(choice.label) }
                )
            }
        }
        OutlinedTextField(
            value = state.reason,
            onValueChange = onReasonChanged,
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            placeholder = { Text(stringResource(R.string.report_reason_hint)) }
        )
        state.errorMessage?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}
