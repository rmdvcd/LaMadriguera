package com.lamadriguera.app.feature.betting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lamadriguera.app.domain.model.Bet
import com.lamadriguera.app.domain.model.BetStatus
import com.lamadriguera.app.domain.usecase.BettingRules
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

@Composable
fun BettingRiskNotice(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Aviso de riesgo",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = BettingRules.RISK_NOTICE,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = BettingRules.PAYMENT_NOTICE,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
fun BettingEligibilityNotice(
    canBet: Boolean,
    message: String?,
    onVerifyIdentity: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (canBet) return
    val explanation = message ?: "Para apostar necesitas identidad aprobada y tener 18 años o más según el carnet."
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = explanation,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            if (onVerifyIdentity != null &&
                (explanation.contains("identidad", ignoreCase = true) || explanation.contains("carnet", ignoreCase = true))
            ) {
                TextButton(onClick = onVerifyIdentity) { Text("Abrir verificación de identidad") }
            }
        }
    }
}

@Composable
fun BetSummaryCard(
    bet: Bet,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(bet.description, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(formatCup(bet.stakeAmount), style = MaterialTheme.typography.bodyLarge)
                Text(bet.status.label(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            if (bet.odds != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Cuota propuesta por el creador: ${formatDecimal(bet.odds)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

fun BetStatus.label(): String = when (this) {
    BetStatus.OPEN -> "Abierta"
    BetStatus.ACCEPTED -> "Aceptada"
    BetStatus.SETTLED_CREATOR -> "Resultado: creador"
    BetStatus.SETTLED_ACCEPTOR -> "Resultado: aceptante"
    BetStatus.VOID -> "Anulada"
    BetStatus.DISPUTED -> "En disputa"
}

fun formatCup(amount: BigDecimal): String = "${formatDecimal(amount)} CUP"

fun formatDecimal(amount: BigDecimal): String = NumberFormat.getNumberInstance(Locale("es", "CU")).apply {
    minimumFractionDigits = 0
    maximumFractionDigits = 2
    roundingMode = RoundingMode.HALF_UP
}.format(amount.stripTrailingZeros())
