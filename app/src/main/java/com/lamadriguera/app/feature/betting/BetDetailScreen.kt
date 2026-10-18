package com.lamadriguera.app.feature.betting

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lamadriguera.app.domain.model.BetEvidenceImage
import com.lamadriguera.app.feature.moderation.ReportButton
import com.lamadriguera.app.domain.model.ModerationTargetType
import com.lamadriguera.app.domain.model.BetOutcome
import com.lamadriguera.app.domain.model.BetStatus
import com.lamadriguera.app.domain.usecase.BettingRules
import java.io.ByteArrayOutputStream
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BetDetailScreen(
    state: BetDetailUiState,
    onRefresh: () -> Unit,
    onAccept: () -> Unit,
    onSubmitResult: (BetOutcome, String?, BetEvidenceImage?) -> Unit,
    onOpenDispute: (String?, BetEvidenceImage?) -> Unit,
    onResolveDispute: (BetOutcome, String) -> Unit,
    onVerifyIdentity: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bet = state.bet
    var selectedOutcome by remember(bet?.id) { mutableStateOf(BetOutcome.CREATOR) }
    var adminOutcome by remember(bet?.id) { mutableStateOf(BetOutcome.CREATOR) }
    var evidenceText by remember(bet?.id) { mutableStateOf("") }
    var decisionNote by remember(bet?.id) { mutableStateOf("") }
    var selectedImage by remember(bet?.id) { mutableStateOf<BetEvidenceImage?>(null) }
    var imageError by remember(bet?.id) { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedImage = null
            imageError = null
            scope.launch {
                val content = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8 * 1024)
                            var totalBytes = 0
                            while (true) {
                                val bytesRead = input.read(buffer)
                                if (bytesRead < 0) break
                                totalBytes += bytesRead
                                if (totalBytes > BettingRules.MAX_EVIDENCE_IMAGE_BYTES) return@use null
                                output.write(buffer, 0, bytesRead)
                            }
                            output.toByteArray()
                        }
                    }.getOrNull()
                }
                val mime = content?.evidenceMimeType()
                if (content == null) {
                    imageError = "No se pudo leer la captura o supera 5 MB."
                } else if (mime == null) {
                    imageError = "La captura debe ser una imagen JPEG o PNG válida."
                } else {
                    selectedImage = BetEvidenceImage(content, mime)
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        BettingRiskNotice()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Detalle de apuesta", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            TextButton(onClick = onRefresh, enabled = !state.isBusy && !state.isLoading) { Text("Actualizar") }
        }
        Text("el pago se acuerda y ejecuta fuera de la app", fontWeight = FontWeight.SemiBold)

        if (state.errorMessage != null) Text(state.errorMessage, color = MaterialTheme.colorScheme.error)
        if (state.message != null) Text(state.message, color = MaterialTheme.colorScheme.primary)
        if (state.isLoading) {
            CircularProgressIndicator()
            return@Column
        }
        if (bet == null) {
            OutlinedButton(onClick = onRefresh) { Text("Reintentar") }
            return@Column
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        bet.description,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    ReportButton(targetType = ModerationTargetType.BET, targetId = bet.id)
                }
                Text("Monto acordado: ${formatCup(bet.stakeAmount)}", style = MaterialTheme.typography.bodyLarge)
                if (bet.odds != null) {
                    Text("Cuota propuesta por el creador: ${formatDecimal(bet.odds)}", style = MaterialTheme.typography.bodyMedium)
                }
                Text("Creador: ${bet.creatorId.take(8)}")
                Text("Aceptante: ${bet.acceptorId?.take(8) ?: "Aún no hay aceptante"}")
                Text("Estado: ${bet.status.label()}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                if (bet.decisionBy != null) {
                    Text("Decisión del administrador ${bet.decisionBy.take(8)}: ${bet.decisionNote.orEmpty()}")
                }
                if (bet.settledAt != null) Text("Resultado registrado: ${formatDateTime(bet.settledAt)}")
            }
        }

        if (state.canAccept) {
            BettingEligibilityNotice(state.canBet, state.eligibilityMessage)
            Button(onClick = onAccept, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) {
                if (state.isBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Aceptar apuesta")
            }
        } else if (bet.status == BetStatus.OPEN && state.currentUserId != bet.creatorId && !state.canBet) {
            BettingEligibilityNotice(
                canBet = false,
                message = state.eligibilityMessage,
                onVerifyIdentity = onVerifyIdentity
            )
        }

        if (state.canSubmitResult || state.canOpenDispute) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Registrar resultado o desacuerdo", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Indica quién ganó según el acuerdo. Se requiere texto o una captura como evidencia.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = selectedOutcome == BetOutcome.CREATOR,
                            onClick = { selectedOutcome = BetOutcome.CREATOR },
                            enabled = !state.isBusy,
                            label = { Text("Gana el creador") }
                        )
                        FilterChip(
                            selected = selectedOutcome == BetOutcome.ACCEPTOR,
                            onClick = { selectedOutcome = BetOutcome.ACCEPTOR },
                            enabled = !state.isBusy,
                            label = { Text("Gana el aceptante") }
                        )
                    }
                    OutlinedTextField(
                        value = evidenceText,
                        onValueChange = { evidenceText = it.take(2_100) },
                        label = { Text("Evidencia en texto (opcional si adjuntas captura)") },
                        minLines = 2,
                        maxLines = 5,
                        enabled = !state.isBusy,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { imagePicker.launch("image/*") }, enabled = !state.isBusy) {
                            Text(if (selectedImage == null) "Adjuntar captura" else "Cambiar captura")
                        }
                        if (selectedImage != null) Text("Captura lista (JPEG/PNG)", style = MaterialTheme.typography.bodySmall)
                    }
                    imageError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Text("Puedes aportar texto, una captura o ambos.", style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = { onSubmitResult(selectedOutcome, evidenceText, selectedImage) },
                        enabled = !state.isBusy && (evidenceText.isNotBlank() || selectedImage != null),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Registrar / responder resultado") }
                    if (state.canOpenDispute) {
                        OutlinedButton(
                            onClick = { onOpenDispute(evidenceText, selectedImage) },
                            enabled = !state.isBusy && (evidenceText.isNotBlank() || selectedImage != null),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Abrir disputa con esta evidencia") }
                    }
                }
            }
        }

        if (state.canResolveDispute) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Decisión del administrador", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Revisa el historial y decide el resultado. La app no ejecuta pagos.")
                    OutcomeOption("Gana el creador", adminOutcome == BetOutcome.CREATOR) { adminOutcome = BetOutcome.CREATOR }
                    OutcomeOption("Gana el aceptante", adminOutcome == BetOutcome.ACCEPTOR) { adminOutcome = BetOutcome.ACCEPTOR }
                    OutcomeOption("Anular la apuesta", adminOutcome == BetOutcome.VOID) { adminOutcome = BetOutcome.VOID }
                    OutlinedTextField(
                        value = decisionNote,
                        onValueChange = { decisionNote = it.take(2_100) },
                        label = { Text("Explicación de la decisión (obligatoria)") },
                        minLines = 2,
                        maxLines = 5,
                        enabled = !state.isBusy,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { onResolveDispute(adminOutcome, decisionNote) },
                        enabled = !state.isBusy && decisionNote.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Resolver disputa") }
                }
            }
        }

        Text("Historial completo", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (!state.eventsAvailable) {
            Text("No se pudo cargar el historial. Las acciones de resolución permanecen bloqueadas; actualiza para reintentar.")
        } else if (state.events.isEmpty()) {
            Text("No hay eventos registrados para esta apuesta.")
        } else {
            state.events.forEach { eventUi ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(eventUi.event.title(), fontWeight = FontWeight.SemiBold)
                        Text("${eventUi.event.actorId.take(8)} · ${formatDateTime(eventUi.event.createdAt)}", style = MaterialTheme.typography.bodySmall)
                        eventUi.event.outcome?.let { Text("Resultado indicado: ${it.outcomeLabel()}") }
                        eventUi.event.evidenceText?.let { Text("Evidencia: $it") }
                        eventUi.event.decisionNote?.let { Text("Nota de decisión: $it") }
                        if (eventUi.evidenceSignedUrl != null) {
                            AsyncImage(
                                model = eventUi.evidenceSignedUrl,
                                contentDescription = "Captura de evidencia",
                                modifier = Modifier.fillMaxWidth().height(180.dp)
                            )
                        } else if (eventUi.event.evidenceRef != null) {
                            Text("Captura adjunta; no se pudo cargar la vista previa.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun OutcomeOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label)
    }
}

private fun ByteArray.evidenceMimeType(): String? = when {
    size >= 3 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte() && this[2] == 0xFF.toByte() -> "image/jpeg"
    size >= 4 && this[0] == 0x89.toByte() && this[1] == 0x50.toByte() &&
        this[2] == 0x4E.toByte() && this[3] == 0x47.toByte() -> "image/png"
    else -> null
}

private fun com.lamadriguera.app.domain.model.BetEvent.title(): String = when (eventType) {
    "created" -> "Apuesta creada"
    "accepted" -> "Apuesta aceptada"
    "settlement_claim" -> "Resultado declarado"
    "settlement_confirmed" -> "Resultado confirmado por la otra persona"
    "disputed" -> "Se abrió una disputa"
    "admin_decision" -> "Decisión del administrador"
    else -> "Actualización de la apuesta"
}

private fun String.outcomeLabel(): String = when (this) {
    "creator" -> "creador"
    "acceptor" -> "aceptante"
    "void" -> "anulada"
    else -> this
}

private fun formatDateTime(value: java.time.Instant): String = value.atZone(ZoneOffset.UTC).format(
    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm 'UTC'")
)
