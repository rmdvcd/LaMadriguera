package com.lamadriguera.app.feature.auction

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lamadriguera.app.R
import com.lamadriguera.app.domain.model.Bid
import com.lamadriguera.app.domain.model.BidStatus
import com.lamadriguera.app.ui.components.EmptyState
import com.lamadriguera.app.ui.components.ErrorState
import com.lamadriguera.app.ui.components.LoadingState
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Pantalla de historial de pujas (`BidHistoryScreen`).
 * Solo es accesible y muestra datos cuando `visible_bids = 'all'`.
 */
@Composable
fun BidHistoryScreen(
    state: BidHistoryUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    when {
        state.isLoading -> LoadingState(modifier = modifier.fillMaxSize())
        !state.isHistoryVisible -> {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = stringResource(R.string.bid_history_restricted),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
        state.errorMessage != null -> ErrorState(
            message = state.errorMessage,
            onRetry = onRetry,
            modifier = modifier.fillMaxSize()
        )
        state.bids.isEmpty() -> EmptyState(
            messageRes = R.string.bid_history_empty,
            modifier = modifier.fillMaxSize()
        )
        else -> {
            val currency = state.listing?.currency ?: "CUP"
            LazyColumn(
                modifier = modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text(
                        text = state.listing?.title ?: stringResource(R.string.bid_history_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                itemsIndexed(state.bids, key = { _, bid -> bid.id }) { index, bid ->
                    BidHistoryRow(
                        rank = index + 1,
                        bid = bid,
                        currency = currency
                    )
                }
            }
        }
    }
}

@Composable
private fun BidHistoryRow(
    rank: Int,
    bid: Bid,
    currency: String
) {
    val statusText = when (bid.status) {
        BidStatus.WINNING -> stringResource(R.string.bid_status_winning)
        BidStatus.VALID -> stringResource(R.string.bid_status_valid)
        BidStatus.LOSING -> stringResource(R.string.bid_status_losing)
        BidStatus.RETRACTED -> stringResource(R.string.bid_status_retracted)
    }
    val formattedDate = DATE_FORMATTER.format(bid.createdAt.atZone(ZoneId.systemDefault()))
    val shortBidder = "Postor #${bid.bidderId.takeLast(6).uppercase()}"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (bid.status == BidStatus.WINNING || rank == 1) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "#$rank · ${bid.amount.stripTrailingZeros().toPlainString()} $currency",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "$shortBidder · $formattedDate",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AssistChip(
                onClick = {},
                label = { Text(statusText) }
            )
        }
    }
}

private val DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
