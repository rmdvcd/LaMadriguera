package com.lamadriguera.app.feature.betting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun CreateBetScreen(
    state: CreateBetUiState,
    onDescriptionChanged: (String) -> Unit,
    onStakeChanged: (String) -> Unit,
    onOddsChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onVerifyIdentity: () -> Unit,
    onBackToGroup: () -> Unit,
    modifier: Modifier = Modifier
) {
    val parsedStake = state.parsedStake
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        BettingRiskNotice()
        Text("Crear apuesta", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Grupo: ${state.group?.name ?: "Cargando…"}", style = MaterialTheme.typography.bodyLarge)
        Text("el pago se acuerda y ejecuta fuera de la app", fontWeight = FontWeight.SemiBold)

        if (state.isLoading) {
            CircularProgressIndicator()
            return@Column
        }

        if (state.errorMessage != null) {
            Text(state.errorMessage, color = MaterialTheme.colorScheme.error)
        }
        if (!state.isGroupMember) {
            Text("Solo los miembros pueden crear apuestas en este grupo.", color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onBackToGroup) { Text("Volver al grupo") }
        }

        BettingEligibilityNotice(
            canBet = state.canBet,
            message = state.eligibilityMessage,
            onVerifyIdentity = onVerifyIdentity
        )

        if (state.dailyUsageAvailable && state.controls != null) {
            Text(
                "Límite diario: ${formatCup(state.controls.dailyLimitCup)} · " +
                    "Usado hoy: ${formatCup(state.usedTodayCup)} · Disponible: ${formatCup(state.remainingTodayCup)}",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            Text(
                "No se pudo comprobar tu límite diario. Actualiza antes de publicar.",
                color = MaterialTheme.colorScheme.error
            )
        }

        OutlinedTextField(
            value = state.descriptionInput,
            onValueChange = onDescriptionChanged,
            label = { Text("Descripción de la apuesta") },
            supportingText = { Text("Explica con claridad qué resultado se está acordando.") },
            minLines = 3,
            maxLines = 6,
            enabled = !state.isSubmitting,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = state.stakeInput,
            onValueChange = onStakeChanged,
            label = { Text("Monto de la apuesta (CUP)") },
            supportingText = { Text("No puede superar el disponible de tu límite diario.") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            enabled = !state.isSubmitting,
            modifier = Modifier.fillMaxWidth()
        )
        if (parsedStake != null && parsedStake > state.remainingTodayCup) {
            Text(
                "El monto supera tu límite diario disponible.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        OutlinedTextField(
            value = state.oddsInput,
            onValueChange = onOddsChanged,
            label = { Text("Cuota propuesta por ti (opcional)") },
            supportingText = {
                Text("La plataforma no fija cuotas. Si escribes una, será una propuesta de quien crea la apuesta.")
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            enabled = !state.isSubmitting,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "La app solo registra el acuerdo y su resultado; no retiene ni transfiere dinero.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onSubmit,
                enabled = state.canSubmit,
                modifier = Modifier.weight(1f)
            ) {
                if (state.isSubmitting) CircularProgressIndicator(strokeWidth = 2.dp)
                else Text("Publicar apuesta")
            }
            OutlinedButton(onClick = onBackToGroup, enabled = !state.isSubmitting) {
                Text("Cancelar")
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
