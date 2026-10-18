package com.lamadriguera.app.feature.payments

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lamadriguera.app.domain.model.MonetizationProduct
import com.lamadriguera.app.domain.model.PaymentReviewItem
import com.lamadriguera.app.domain.usecase.PaymentDateTime
import java.math.BigDecimal

/** Cola privada de cobros manuales. Solo el backend valida el rol admin. */
@Composable
fun AdminPaymentQueueScreen(
    state: AdminPaymentQueueUiState,
    onRefresh: () -> Unit,
    onOpenPayment: (PaymentReviewItem) -> Unit,
    onClosePayment: () -> Unit,
    onAmountChanged: (String) -> Unit,
    onTransferAtChanged: (String) -> Unit,
    onTransferLast4Changed: (String) -> Unit,
    onReferenceChanged: (String) -> Unit,
    onRejectionReasonChanged: (String) -> Unit,
    onBlacklistChanged: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
    onTransferAccountChanged: (String) -> Unit,
    onAccountHolderChanged: (String) -> Unit,
    onSaveSettings: () -> Unit,
    onProductPriceChanged: (String, String) -> Unit,
    onSaveProductPrice: (MonetizationProduct) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Conciliación de pagos", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (state.isAdmin) Text("${state.queue.size} comprobantes pendientes", style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onRefresh, enabled = !state.isBusy) {
                Icon(Icons.Filled.Refresh, contentDescription = "Actualizar cola")
            }
        }

        if (state.isLoading) {
            CircularProgressIndicator(modifier = Modifier.padding(24.dp))
            return@Column
        }
        if (!state.isAdmin) {
            Text(state.errorMessage ?: "Acceso restringido al rol admin/cobrador", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            state.errorMessage?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            state.message?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) } }

            item {
                PaymentConfigurationCard(
                    state = state,
                    onTransferAccountChanged = onTransferAccountChanged,
                    onAccountHolderChanged = onAccountHolderChanged,
                    onSaveSettings = onSaveSettings,
                    onProductPriceChanged = onProductPriceChanged,
                    onSaveProductPrice = onSaveProductPrice
                )
            }

            state.selected?.let { selected ->
                item(key = "selected-${selected.payment.id}") {
                    PaymentReconciliationCard(
                        item = selected,
                        proofUrl = state.proofUrl,
                        amountInput = state.amountInput,
                        transferAtInput = state.transferAtInput,
                        transferLast4Input = state.transferLast4Input,
                        referenceInput = state.referenceInput,
                        rejectionReasonInput = state.rejectionReasonInput,
                        blacklistReceipt = state.blacklistReceipt,
                        isBusy = state.isBusy,
                        onClose = onClosePayment,
                        onAmountChanged = onAmountChanged,
                        onTransferAtChanged = onTransferAtChanged,
                        onTransferLast4Changed = onTransferLast4Changed,
                        onReferenceChanged = onReferenceChanged,
                        onRejectionReasonChanged = onRejectionReasonChanged,
                        onBlacklistChanged = onBlacklistChanged,
                        onConfirm = onConfirm,
                        onReject = onReject
                    )
                }
            }

            item { Text("Pendientes de revisión", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            if (state.queue.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text("No hay comprobantes esperando conciliación.", modifier = Modifier.padding(18.dp))
                    }
                }
            } else {
                items(state.queue, key = { it.payment.id }) { item ->
                    QueuePaymentCard(item = item, selected = state.selected?.payment?.id == item.payment.id,
                        onClick = { onOpenPayment(item) })
                }
            }
        }
    }
}

@Composable
private fun PaymentConfigurationCard(
    state: AdminPaymentQueueUiState,
    onTransferAccountChanged: (String) -> Unit,
    onAccountHolderChanged: (String) -> Unit,
    onSaveSettings: () -> Unit,
    onProductPriceChanged: (String, String) -> Unit,
    onSaveProductPrice: (MonetizationProduct) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Configuración de cobro", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("La cuenta se muestra a los compradores. Nunca configures aquí una clave privada.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = state.transferAccountInput,
                onValueChange = onTransferAccountChanged,
                label = { Text("Cuenta Transfermóvil (8–20 dígitos)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                enabled = !state.isBusy,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.accountHolderInput,
                onValueChange = onAccountHolderChanged,
                label = { Text("Titular (opcional)") },
                singleLine = true,
                enabled = !state.isBusy,
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = onSaveSettings, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) {
                if (state.isBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Guardar cuenta destino")
            }
            Spacer(Modifier.height(4.dp))
            Text("Tarifas en CUP", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "El catálogo empieza sin precios y no acepta compras. Guarda cada tarifa real para activar ese producto.",
                style = MaterialTheme.typography.bodySmall
            )
            state.products.forEach { product ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = state.productPriceInputs[product.code].orEmpty(),
                        onValueChange = { onProductPriceChanged(product.code, it) },
                        label = { Text(product.title) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        enabled = !state.isBusy,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { onSaveProductPrice(product) }, enabled = !state.isBusy) { Text("Guardar") }
                }
            }
        }
    }
}

@Composable
private fun QueuePaymentCard(item: PaymentReviewItem, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(item.payment.code.orEmpty(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${formatAmount(item.payment.amount)} ${item.payment.currency}", fontWeight = FontWeight.Bold)
            }
            Text(item.listingTitle, fontWeight = FontWeight.SemiBold)
            Text("${item.ownerName}${item.ownerPhone?.let { " · $it" }.orEmpty()}", style = MaterialTheme.typography.bodySmall)
            Text("${item.productTitle} · origen •••• ${item.payment.transferLast4.orEmpty()}", style = MaterialTheme.typography.bodySmall)
            Text("Transferencia declarada: ${item.payment.transferAt?.let { PaymentDateTime.formatDisplay(it) }.orEmpty()}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PaymentReconciliationCard(
    item: PaymentReviewItem,
    proofUrl: String?,
    amountInput: String,
    transferAtInput: String,
    transferLast4Input: String,
    referenceInput: String,
    rejectionReasonInput: String,
    blacklistReceipt: Boolean,
    isBusy: Boolean,
    onClose: () -> Unit,
    onAmountChanged: (String) -> Unit,
    onTransferAtChanged: (String) -> Unit,
    onTransferLast4Changed: (String) -> Unit,
    onReferenceChanged: (String) -> Unit,
    onRejectionReasonChanged: (String) -> Unit,
    onBlacklistChanged: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onReject: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()) {
                Text("Revisar ${item.payment.code.orEmpty()}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                TextButton(onClick = onClose) { Text("Cerrar") }
            }
            Text("Anunciante: ${item.ownerName}${item.ownerPhone?.let { " · $it" }.orEmpty()}")
            Text("Publicación: ${item.listingTitle}")
            Text("Producto: ${item.productTitle} · esperado ${formatAmount(item.payment.amount)} ${item.payment.currency}")
            Text("Últimos 4 declarados: ${item.payment.transferLast4.orEmpty()}")
            Text("Fecha/hora declarada: ${item.payment.transferAt?.let { PaymentDateTime.formatDisplay(it) }.orEmpty()}")
            if (proofUrl != null) {
                AsyncImage(
                    model = proofUrl,
                    contentDescription = "Comprobante de ${item.payment.code}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(240.dp)
                )
            } else {
                Text("Cargando la captura privada…")
            }
            item.payment.receiptSha256?.let { hash ->
                Text("SHA-256 servidor: ${hash.take(20)}…", style = MaterialTheme.typography.bodySmall)
            }
            Text("Compara el comprobante con el extracto de Transfermóvil antes de confirmar.",
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = amountInput,
                onValueChange = onAmountChanged,
                label = { Text("Monto encontrado en el extracto (CUP)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = transferAtInput,
                onValueChange = onTransferAtChanged,
                label = { Text("Fecha/hora del extracto en Cuba (${PaymentDateTime.INPUT_PATTERN})") },
                singleLine = true,
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = transferLast4Input,
                onValueChange = onTransferLast4Changed,
                label = { Text("Últimos 4 dígitos en el extracto") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = referenceInput,
                onValueChange = onReferenceChanged,
                label = { Text("Referencia de Transfermóvil (opcional)") },
                singleLine = true,
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = onConfirm, enabled = !isBusy, modifier = Modifier.fillMaxWidth()) {
                if (isBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Confirmar pago y activar promoción")
            }
            OutlinedTextField(
                value = rejectionReasonInput,
                onValueChange = onRejectionReasonChanged,
                label = { Text("Motivo del rechazo (obligatorio)") },
                minLines = 2,
                maxLines = 4,
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = blacklistReceipt, onCheckedChange = onBlacklistChanged, enabled = !isBusy)
                Text("Añadir el hash de este comprobante a la lista negra")
            }
            OutlinedButton(onClick = onReject, enabled = !isBusy && rejectionReasonInput.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text("Rechazar y permitir reintento")
            }
        }
    }
}

private fun formatAmount(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()
