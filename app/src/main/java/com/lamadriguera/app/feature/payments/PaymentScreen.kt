package com.lamadriguera.app.feature.payments

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lamadriguera.app.domain.model.MonetizationProduct
import com.lamadriguera.app.domain.model.Payment
import com.lamadriguera.app.domain.model.PaymentStatus
import com.lamadriguera.app.domain.model.ProductType
import com.lamadriguera.app.domain.usecase.PaymentDateTime
import java.io.ByteArrayOutputStream
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Selección de promoción, instrucciones de Transfermóvil, carga de comprobante
 * e historial del usuario. Las escrituras de estado quedan en RPC seguras.
 */
@Composable
fun PaymentScreen(
    state: PaymentUiState,
    onRefresh: () -> Unit,
    onCreateOrder: (String) -> Unit,
    onSubmitProof: (ByteArray, String, String) -> Unit,
    onRetryPayment: () -> Unit,
    onPaymentNavigated: () -> Unit,
    onOpenPayment: (String) -> Unit,
    onOpenAdminQueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val paymentKey = state.payment?.id
    var receiptBytes by remember(paymentKey) { mutableStateOf<ByteArray?>(null) }
    var imageError by remember(paymentKey) { mutableStateOf<String?>(null) }
    var transferLast4 by remember(paymentKey) {
        mutableStateOf(state.payment?.transferLast4.orEmpty())
    }
    var transferAtInput by remember(paymentKey) {
        mutableStateOf(PaymentDateTime.formatInput(Instant.now()))
    }

    val receiptPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        imageError = null
        receiptBytes = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8 * 1024)
                        var total = 0
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_RECEIPT_BYTES) return@use null
                            output.write(buffer, 0, read)
                        }
                        output.toByteArray()
                    }
                }.getOrNull()
            }
            when {
                result == null || result.isEmpty() -> imageError = "No se pudo leer la captura o supera 5 MB."
                !isJpegOrPng(result) -> imageError = "La captura debe ser una imagen JPEG o PNG válida."
                else -> receiptBytes = result
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = when {
                    state.historyMode -> "Mis pagos de promoción"
                    state.payment != null -> "Pago por Transfermóvil"
                    else -> "Promocionar publicación"
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRefresh, enabled = !state.isBusy) {
                Icon(Icons.Filled.Refresh, contentDescription = "Actualizar")
            }
        }

        state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) }
        if (state.isLoading) {
            CircularProgressIndicator()
            return@Column
        }

        if (state.isPaymentAdmin) {
            OutlinedButton(onClick = onOpenAdminQueue, modifier = Modifier.fillMaxWidth()) {
                Text("Abrir panel de conciliación")
            }
        }

        when {
            state.historyMode -> {
                if (state.history.isEmpty()) {
                    EmptyPaymentsCard()
                } else {
                    state.history.forEach { payment ->
                        PaymentHistoryCard(payment = payment, onClick = { onOpenPayment(payment.id) })
                    }
                }
            }
            state.payment != null -> {
                PaymentOrderCard(
                    payment = state.payment,
                    listingTitle = state.listing?.title,
                    settings = state.settings,
                    isBusy = state.isBusy,
                    receiptAttached = receiptBytes != null,
                    transferLast4 = transferLast4,
                    transferAtInput = transferAtInput,
                    imageError = imageError,
                    onPickReceipt = { receiptPicker.launch("image/*") },
                    onLast4Changed = { value -> transferLast4 = value.filter(Char::isDigit).take(4) },
                    onTransferAtChanged = { transferAtInput = it.take(20) },
                    onSubmitProof = {
                        val bytes = receiptBytes ?: return@PaymentOrderCard
                        onSubmitProof(bytes, transferLast4, transferAtInput)
                    },
                    onRetryPayment = onRetryPayment
                )
            }
            state.listing != null -> {
                state.lastConfirmedPayment?.let { lastPayment ->
                    if (state.listing.boostedUntil?.isAfter(Instant.now()) == true) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Promoción activa", fontWeight = FontWeight.Bold)
                                Text("Pago ${lastPayment.code.orEmpty()} confirmado. Vigente hasta ${state.listing.boostedUntil?.let { PaymentDateTime.formatDisplay(it) } ?: "la fecha de vencimiento"}.")
                            }
                        }
                    }
                }
                ProductSelection(
                    listingTitle = state.listing.title,
                    municipalityConfigured = state.listing.municipalityId != null,
                    products = state.products,
                    accountConfigured = state.settings?.isTransferAccountConfigured == true,
                    isBusy = state.isBusy,
                    onChooseProduct = onCreateOrder
                )
                if (state.listing.boostedUntil?.isAfter(Instant.now()) == true) {
                    Text("También puedes contratar otra promoción para esta publicación.", style = MaterialTheme.typography.bodySmall)
                }
            }
            else -> EmptyPaymentsCard(
                message = "Abre una publicación tuya y elige «Promocionar publicación» para iniciar un pago."
            )
        }
    }

    androidx.compose.runtime.LaunchedEffect(state.createdPaymentId) {
        state.createdPaymentId?.let { id ->
            onOpenPayment(id)
            onPaymentNavigated()
        }
    }
}

@Composable
private fun ProductSelection(
    listingTitle: String,
    municipalityConfigured: Boolean,
    products: List<MonetizationProduct>,
    accountConfigured: Boolean,
    isBusy: Boolean,
    onChooseProduct: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Publicación: $listingTitle", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Elige el producto. El precio y la cuenta destino se validan en el servidor.", style = MaterialTheme.typography.bodyMedium)
        if (!accountConfigured) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "La cuenta Transfermóvil todavía no está configurada. El cobrador debe agregarla en el panel de conciliación antes de recibir pagos.",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
        if (products.isEmpty()) {
            EmptyPaymentsCard(message = "No hay productos de promoción disponibles en este momento.")
        } else {
            products.forEach { product ->
                val unavailable = product.requiresMunicipality && !municipalityConfigured
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isBusy && accountConfigured && !unavailable) {
                            onChooseProduct(product.code)
                        },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(product.title, style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text("${formatAmount(product.amount)} ${product.currency}",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold)
                        }
                        Text(product.description, style = MaterialTheme.typography.bodyMedium)
                        AssistChip(onClick = {}, label = { Text("${product.durationHours / 24} días".takeIf { product.durationHours >= 48 } ?: "${product.durationHours} horas") })
                        if (product.productType == ProductType.FEATURE) {
                            Text("Se ubica en categoría y municipio: máximo 1 de cada 5 espacios.",
                                style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        }
                        product.reachScope?.let { scope ->
                            Text("Alcance geográfico: ${scope.name.lowercase().replaceFirstChar(Char::uppercase)}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (unavailable) {
                            Text("Añade un municipio a la publicación para usar este producto.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Button(
                            onClick = { onChooseProduct(product.code) },
                            enabled = !isBusy && accountConfigured && !unavailable,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text("Elegir y generar código de pago")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaymentOrderCard(
    payment: Payment,
    listingTitle: String?,
    settings: com.lamadriguera.app.domain.model.MonetizationSettings?,
    isBusy: Boolean,
    receiptAttached: Boolean,
    transferLast4: String,
    transferAtInput: String,
    imageError: String?,
    onPickReceipt: () -> Unit,
    onLast4Changed: (String) -> Unit,
    onTransferAtChanged: (String) -> Unit,
    onSubmitProof: () -> Unit,
    onRetryPayment: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(paymentStatusLabel(payment.status), fontWeight = FontWeight.Bold,
                        color = paymentStatusColor(payment.status))
                    AssistChip(onClick = {}, label = { Text(payment.code ?: "Sin código") })
                }
                Text(listingTitle ?: "Publicación", style = MaterialTheme.typography.titleMedium)
                Text("Monto exacto: ${formatAmount(payment.amount)} ${payment.currency}",
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Producto: ${payment.productCode?.replace('_', ' ') ?: payment.productType.name.lowercase()}")
                if (payment.expiresAt != null && payment.status == PaymentStatus.PENDING) {
                    Text("Paga y envía la captura antes de ${PaymentDateTime.formatDisplay(payment.expiresAt)}. La orden vence a las 24 h.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        when (payment.status) {
            PaymentStatus.PENDING -> {
                StepCard(1, "Paga por Transfermóvil", "Transfiere exactamente el monto indicado a la cuenta destino. Si la app lo permite, incluye el código en el concepto.")
                StepCard(2, "Conserva el comprobante", "La captura debe mostrar la operación y permitir verificar el monto y la hora.")
                StepCard(3, "Adjunta los datos", "Sube la captura, escribe los últimos cuatro dígitos de la cuenta de origen y la fecha/hora que aparece en el comprobante.")
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Cuenta Transfermóvil destino", fontWeight = FontWeight.SemiBold)
                        SelectionContainer {
                            Text(settings?.transferAccount ?: "Cuenta pendiente de configuración",
                                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                        settings?.accountHolder?.takeIf(String::isNotBlank)?.let { Text("Titular: $it") }
                    }
                }
                OutlinedButton(onClick = onPickReceipt, enabled = !isBusy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (receiptAttached) "Cambiar captura" else "Adjuntar captura JPEG/PNG")
                }
                if (receiptAttached) Text("Captura adjunta (se calculará un hash seguro en el servidor).", style = MaterialTheme.typography.bodySmall)
                imageError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedTextField(
                    value = transferLast4,
                    onValueChange = onLast4Changed,
                    label = { Text("Últimos 4 dígitos de origen") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = transferAtInput,
                    onValueChange = onTransferAtChanged,
                    label = { Text("Fecha y hora de la transferencia") },
                    placeholder = { Text(PaymentDateTime.INPUT_PATTERN) },
                    supportingText = { Text("Formato ${PaymentDateTime.INPUT_PATTERN}, hora de Cuba") },
                    singleLine = true,
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = onSubmitProof,
                    enabled = !isBusy && receiptAttached && transferLast4.length == 4 && transferAtInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("Enviar comprobante a revisión")
                }
            }
            PaymentStatus.REVIEW -> {
                StatusNotice(
                    title = "Comprobante en revisión",
                    body = "La publicación quedó en pausa de pago, pero no se eliminó. El cobrador verificará monto, hora, últimos dígitos y captura contra el extracto."
                )
            }
            PaymentStatus.REJECTED -> {
                StatusNotice(
                    title = "Comprobante rechazado",
                    body = payment.rejectionReason ?: "El cobrador rechazó el comprobante."
                )
                Button(onClick = onRetryPayment, enabled = !isBusy, modifier = Modifier.fillMaxWidth()) {
                    if (isBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("Corregir y generar un nuevo código")
                }
                Text("El rechazo conserva el motivo y el pago anterior. El reintento crea una nueva orden.",
                    style = MaterialTheme.typography.bodySmall)
            }
            PaymentStatus.CONFIRMED -> {
                StatusNotice(
                    title = "Pago confirmado · Promoción activa",
                    body = "El cobrador confirmó el depósito. La publicación ya está promocionada${payment.confirmedAt?.let { " desde ${PaymentDateTime.formatDisplay(it)}" }.orEmpty()}. También recibirás una notificación."
                )
            }
            PaymentStatus.CANCELLED, PaymentStatus.EXPIRED -> {
                StatusNotice(
                    title = "Orden vencida",
                    body = "La orden se canceló al vencer el plazo de 24 horas. Puedes generar un pago nuevo."
                )
                Button(onClick = onRetryPayment, enabled = !isBusy, modifier = Modifier.fillMaxWidth()) {
                    if (isBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("Crear una nueva orden")
                }
            }
        }
    }
}

@Composable
private fun StepCard(number: Int, title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Text("$number", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun StatusNotice(title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PaymentHistoryCard(payment: Payment, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(payment.code ?: "Pago sin código", fontWeight = FontWeight.Bold)
                Text("${formatAmount(payment.amount)} ${payment.currency} · ${payment.productCode?.replace('_', ' ') ?: "promoción"}",
                    maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(PaymentDateTime.formatDisplay(payment.createdAt), style = MaterialTheme.typography.bodySmall)
            }
            Text(paymentStatusLabel(payment.status), color = paymentStatusColor(payment.status), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun EmptyPaymentsCard(message: String = "Todavía no tienes pagos de promoción.") {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(message, modifier = Modifier.padding(18.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun paymentStatusLabel(status: PaymentStatus): String = when (status) {
    PaymentStatus.PENDING -> "Pendiente"
    PaymentStatus.REVIEW -> "En revisión"
    PaymentStatus.CONFIRMED -> "Confirmado"
    PaymentStatus.REJECTED -> "Rechazado"
    PaymentStatus.EXPIRED, PaymentStatus.CANCELLED -> "Cancelado"
}

@Composable
private fun paymentStatusColor(status: PaymentStatus) = when (status) {
    PaymentStatus.CONFIRMED -> MaterialTheme.colorScheme.primary
    PaymentStatus.REJECTED -> MaterialTheme.colorScheme.error
    PaymentStatus.CANCELLED, PaymentStatus.EXPIRED -> MaterialTheme.colorScheme.error
    PaymentStatus.REVIEW -> MaterialTheme.colorScheme.tertiary
    PaymentStatus.PENDING -> MaterialTheme.colorScheme.secondary
}

private fun formatAmount(value: java.math.BigDecimal): String =
    value.stripTrailingZeros().toPlainString()

private fun isJpegOrPng(bytes: ByteArray): Boolean =
    (bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()) ||
        (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4e.toByte() && bytes[3] == 0x47.toByte() && bytes[4] == 0x0d.toByte() &&
            bytes[5] == 0x0a.toByte() && bytes[6] == 0x1a.toByte() && bytes[7] == 0x0a.toByte())

private const val MAX_RECEIPT_BYTES = 5 * 1024 * 1024
