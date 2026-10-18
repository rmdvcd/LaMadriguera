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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lamadriguera.app.R
import com.lamadriguera.app.domain.model.Bet
import com.lamadriguera.app.domain.model.BettingGroup
import com.lamadriguera.app.domain.model.BettingGroupMember
import com.lamadriguera.app.domain.model.GroupMessage
import com.lamadriguera.app.domain.model.ModerationTargetType
import com.lamadriguera.app.feature.moderation.ReportButton
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import androidx.compose.ui.res.stringResource

/** Lista de grupos, gestión de miembros, controles de juego e historial completo. */
@Composable
fun BettingGroupScreen(
    state: BettingGroupUiState,
    initialTab: Int = 0,
    onRefresh: () -> Unit,
    onCreateGroup: (name: String, topic: String?, visibility: String) -> Unit,
    onOpenGroup: (String) -> Unit,
    onJoinGroup: (String) -> Unit,
    onCreateBet: (String) -> Unit,
    onOpenBet: (String) -> Unit,
    onVerifyIdentity: () -> Unit,
    onInviteMember: (String) -> Unit,
    onRemoveMember: (String) -> Unit,
    onDailyLimitChanged: (String) -> Unit,
    onSaveDailyLimit: () -> Unit,
    onSelfExclude: () -> Unit,
    onOpenGroupAdmin: () -> Unit = {},
    wallMessages: List<GroupMessage> = emptyList(),
    wallDraft: String = "",
    onWallDraftChanged: (String) -> Unit = {},
    onPostWallMessage: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedTab by remember(state.groupId, initialTab) { mutableIntStateOf(initialTab.coerceIn(0, 1)) }
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var invitePhone by remember(state.group?.id) { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        BettingRiskNotice()
        if (state.errorMessage != null) {
            Text(state.errorMessage, color = MaterialTheme.colorScheme.error)
        }
        if (state.message != null) {
            Text(state.message, color = MaterialTheme.colorScheme.primary)
        }

        if (state.isLoading) {
            CircularProgressIndicator()
            return@Column
        }

        val groupId = state.groupId
        if (groupId == null) {
            DailyControlsCard(
                state = state,
                onLimitChanged = onDailyLimitChanged,
                onSaveLimit = onSaveDailyLimit,
                onSelfExclude = onSelfExclude,
                enabled = !state.isBusy
            )
            BettingEligibilityNotice(state.canBet, state.eligibilityMessage, onVerifyIdentity)
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Grupos") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Mi historial") })
            }

            if (selectedTab == 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Grupos de apuestas", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    TextButton(onClick = onRefresh, enabled = !state.isBusy) { Text("Actualizar") }
                }
                if (state.canCreateGroups) {
                    Button(onClick = { showCreateGroupDialog = true }, enabled = !state.isBusy) {
                        Text("Crear grupo")
                    }
                } else {
                    Text("Tu cuenta debe estar activa para crear grupos.", style = MaterialTheme.typography.bodySmall)
                }
                if (state.groups.isEmpty()) {
                    Text("Aún no hay grupos disponibles. Puedes crear uno o pedir una invitación.")
                }
                state.groups.forEach { group ->
                    GroupListCard(
                        group = group,
                        isMember = group.id in state.memberGroupIds,
                        isOwner = group.ownerId == state.currentUserId,
                        enabled = !state.isBusy,
                        onOpen = { onOpenGroup(group.id) },
                        onJoin = { onJoinGroup(group.id) }
                    )
                }
            } else {
                Text("Historial completo", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Se muestran apuestas abiertas, aceptadas, resueltas, anuladas y en disputa.", style = MaterialTheme.typography.bodySmall)
                if (!state.myBetsAvailable) {
                    Text("No se pudo comprobar tu historial ahora. Actualiza para volver a intentarlo.")
                } else if (state.myBets.isEmpty()) {
                    Text("Todavía no tienes apuestas registradas.")
                } else {
                    state.myBets.forEach { bet ->
                        BetSummaryCard(bet = bet, onClick = { onOpenBet(bet.id) })
                    }
                }
            }
        } else {
            val group = state.group
            if (group == null) {
                Text("No se encontró el grupo o ya no tienes acceso.")
                OutlinedButton(onClick = onRefresh) { Text("Reintentar") }
            } else {
                GroupHeader(group = group, isOwner = state.isCurrentGroupOwner)
                if (group.rules.isNotBlank()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                stringResource(R.string.group_admin_rules_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(group.rules, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (state.isCurrentGroupOwner) {
                    OutlinedButton(onClick = onOpenGroupAdmin, enabled = !state.isBusy) {
                        Text(stringResource(R.string.group_admin_open))
                    }
                }
                if (!state.isMemberOfCurrentGroup) {
                    Text("Las apuestas y miembros solo están visibles para quienes pertenecen al grupo.")
                    if (group.visibility == "public") {
                        Button(onClick = { onJoinGroup(group.id) }, enabled = !state.isBusy) {
                            Text("Unirme al grupo público")
                        }
                    }
                } else {
                    if (state.isCurrentGroupOwner) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("Administrar miembros", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("Como owner, puedes invitar por teléfono o expulsar miembros.", style = MaterialTheme.typography.bodySmall)
                                OutlinedTextField(
                                    value = invitePhone,
                                    onValueChange = { invitePhone = it.take(24) },
                                    label = { Text("Teléfono del miembro") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                    singleLine = true,
                                    enabled = !state.isBusy,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Button(
                                    onClick = {
                                        onInviteMember(invitePhone)
                                        invitePhone = ""
                                    },
                                    enabled = !state.isBusy && invitePhone.isNotBlank()
                                ) { Text("Invitar") }
                                Text("Miembros (${state.members.size})", style = MaterialTheme.typography.titleSmall)
                                state.members.forEach { member ->
                                    MemberRow(
                                        member = member,
                                        isOwner = member.userId == group.ownerId,
                                        canRemove = !state.isBusy && member.userId != group.ownerId,
                                        onRemove = { onRemoveMember(member.userId) }
                                    )
                                }
                            }
                        }
                    } else {
                        Text("Miembros (${state.members.size})", style = MaterialTheme.typography.titleMedium)
                        state.members.forEach { member ->
                            MemberRow(member, isOwner = member.userId == group.ownerId, canRemove = false, onRemove = {})
                        }
                    }

                    GroupWallCard(
                        messages = wallMessages,
                        draft = wallDraft,
                        onDraftChanged = onWallDraftChanged,
                        onPost = onPostWallMessage,
                        enabled = !state.isBusy
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Apuestas e historial", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        TextButton(onClick = onRefresh, enabled = !state.isBusy) { Text("Actualizar") }
                    }
                    BettingEligibilityNotice(state.canBet, state.eligibilityMessage, onVerifyIdentity)
                    Button(onClick = { onCreateBet(group.id) }, enabled = state.canBet && !state.isBusy) {
                        Text("Crear apuesta")
                    }
                    if (state.groupBets.isEmpty()) {
                        Text("Este grupo todavía no tiene apuestas.")
                    } else {
                        state.groupBets.forEach { bet ->
                            BetSummaryCard(bet = bet, onClick = { onOpenBet(bet.id) })
                        }
                    }
                }
            }
        }
    }

    if (showCreateGroupDialog) {
        CreateGroupDialog(
            isBusy = state.isBusy,
            onDismiss = { showCreateGroupDialog = false },
            onCreate = { name, topic, visibility ->
                onCreateGroup(name, topic, visibility)
                showCreateGroupDialog = false
            }
        )
    }
}

@Composable
private fun DailyControlsCard(
    state: BettingGroupUiState,
    onLimitChanged: (String) -> Unit,
    onSaveLimit: () -> Unit,
    onSelfExclude: () -> Unit,
    enabled: Boolean
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Tus controles", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (state.myBetsAvailable) {
                Text("Límite usado hoy: ${formatCup(state.usedTodayCup)} · Disponible: ${formatCup(state.remainingTodayCup)}")
            } else {
                Text("No se pudo comprobar el uso del límite diario. Actualiza antes de apostar.", color = MaterialTheme.colorScheme.error)
            }
            OutlinedTextField(
                value = state.dailyLimitInput,
                onValueChange = onLimitChanged,
                label = { Text("Límite diario (CUP)") },
                supportingText = { Text("Predeterminado: 1000 CUP · Puedes configurar entre 1 y 1 000 000 CUP.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = onSaveLimit, enabled = enabled) { Text("Guardar límite") }
            val excludedUntil = state.controls?.selfExcludedUntil
            val excluded = excludedUntil != null && excludedUntil.isAfter(Instant.now())
            if (excluded) {
                Text(
                    "Autoexclusión activa hasta ${formatDate(excludedUntil)}. No se puede apostar durante este período.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            OutlinedButton(onClick = onSelfExclude, enabled = enabled && !excluded) {
                Text("No apostar más — bloquear por 30 días")
            }
        }
    }
}

@Composable
private fun GroupListCard(
    group: BettingGroup,
    isMember: Boolean,
    isOwner: Boolean,
    enabled: Boolean,
    onOpen: () -> Unit,
    onJoin: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(group.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (!group.topic.isNullOrBlank()) Text(group.topic, style = MaterialTheme.typography.bodyMedium)
            Text("${if (group.visibility == "public") "Público" else "Privado"} · ${if (isOwner) "Administrador" else if (isMember) "Miembro" else "Disponible"}")
            if (isMember) {
                Button(onClick = onOpen, enabled = enabled) { Text("Abrir grupo") }
            } else if (group.visibility == "public") {
                Button(onClick = onJoin, enabled = enabled) { Text("Unirme al grupo") }
            }
        }
    }
}

@Composable
private fun GroupHeader(group: BettingGroup, isOwner: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(group.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (!group.topic.isNullOrBlank()) Text(group.topic, style = MaterialTheme.typography.bodyLarge)
        Text("${if (group.visibility == "public") "Grupo público" else "Grupo privado"}${if (isOwner) " · Administras este grupo" else ""}")
    }
}

@Composable
private fun MemberRow(
    member: BettingGroupMember,
    isOwner: Boolean,
    canRemove: Boolean,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(if (isOwner) "${member.userId.take(8)} · Owner" else "Miembro ${member.userId.take(8)}")
        if (canRemove) TextButton(onClick = onRemove) { Text("Expulsar") }
    }
}

@Composable
private fun CreateGroupDialog(
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String, String?, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var topic by remember { mutableStateOf("") }
    var visibility by remember { mutableStateOf("private") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Crear grupo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(100) },
                    label = { Text("Nombre") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = topic,
                    onValueChange = { topic = it.take(220) },
                    label = { Text("Tema (opcional)") },
                    minLines = 2
                )
                Text("Visibilidad", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = visibility == "private",
                        onClick = { visibility = "private" },
                        label = { Text("Privado") }
                    )
                    FilterChip(
                        selected = visibility == "public",
                        onClick = { visibility = "public" },
                        label = { Text("Público") }
                    )
                }
                Text("El owner será el administrador y podrá invitar o expulsar miembros.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name, topic, visibility) },
                enabled = !isBusy && name.isNotBlank()
            ) { Text("Crear") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isBusy) { Text("Cancelar") } }
    )
}

private fun formatDate(value: Instant?): String = value?.atZone(ZoneOffset.UTC)?.format(
    DateTimeFormatter.ofPattern("dd/MM/yyyy")
) ?: ""

/**
 * Muro del grupo (grupos públicos): los mensajes pasan la moderación de texto
 * (ModerateContentUseCase) antes de publicar. Cada mensaje tiene su botón de
 * reporte; los admins del grupo los ocultan desde el panel sin borrar nada.
 */
@Composable
private fun GroupWallCard(
    messages: List<GroupMessage>,
    draft: String,
    onDraftChanged: (String) -> Unit,
    onPost: () -> Unit,
    enabled: Boolean
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(R.string.group_wall_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChanged,
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                enabled = enabled,
                placeholder = { Text(stringResource(R.string.group_wall_hint)) }
            )
            Button(onClick = onPost, enabled = enabled && draft.isNotBlank()) {
                Text(stringResource(R.string.group_wall_send))
            }
            if (messages.isEmpty()) {
                Text(stringResource(R.string.group_wall_empty), style = MaterialTheme.typography.bodySmall)
            }
            messages.forEach { message ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        if (message.isHidden) {
                            Text(
                                stringResource(R.string.group_wall_hidden),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else {
                            Text(message.body, style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(
                            formatDate(message.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!message.isHidden) {
                        ReportButton(
                            targetType = ModerationTargetType.GROUP_MESSAGE,
                            targetId = message.id
                        )
                    }
                }
            }
        }
    }
}
