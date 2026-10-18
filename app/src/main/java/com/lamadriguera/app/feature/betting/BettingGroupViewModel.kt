package com.lamadriguera.app.feature.betting

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.domain.model.Bet
import com.lamadriguera.app.domain.model.BettingControls
import com.lamadriguera.app.domain.model.BettingGroup
import com.lamadriguera.app.domain.model.BettingGroupMember
import com.lamadriguera.app.domain.model.GroupMessage
import com.lamadriguera.app.domain.model.ModeratedContent
import com.lamadriguera.app.domain.model.ModerationDecision
import com.lamadriguera.app.domain.model.ModerationTargetType
import com.lamadriguera.app.domain.model.IdentityVerification
import com.lamadriguera.app.domain.repository.AuthRepository
import com.lamadriguera.app.domain.repository.BettingRepository
import com.lamadriguera.app.domain.repository.GroupMessagesRepository
import com.lamadriguera.app.domain.repository.IdentityVerificationsRepository
import com.lamadriguera.app.domain.usecase.BettingEligibility
import com.lamadriguera.app.domain.usecase.ModerateContentUseCase
import com.lamadriguera.app.domain.usecase.BettingRules
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Estado común de lista de grupos e historial; con groupId muestra la pantalla del grupo. */
data class BettingGroupUiState(
    val isLoading: Boolean = true,
    val isBusy: Boolean = false,
    val groupId: String? = null,
    val currentUserId: String? = null,
    val groups: List<BettingGroup> = emptyList(),
    val memberGroupIds: Set<String> = emptySet(),
    val group: BettingGroup? = null,
    val members: List<BettingGroupMember> = emptyList(),
    val groupBets: List<Bet> = emptyList(),
    val wallMessages: List<GroupMessage> = emptyList(),
    val wallDraft: String = "",
    val myBets: List<Bet> = emptyList(),
    val myBetsAvailable: Boolean = false,
    val controls: BettingControls? = null,
    val usedTodayCup: BigDecimal = BigDecimal.ZERO,
    val canBet: Boolean = false,
    val eligibilityMessage: String? = null,
    val canCreateGroups: Boolean = false,
    val dailyLimitInput: String = BettingRules.DEFAULT_DAILY_LIMIT_CUP.toPlainString(),
    val errorMessage: String? = null,
    val message: String? = null,
    val createdGroupId: String? = null
) {
    val isMemberOfCurrentGroup: Boolean
        get() = group?.id?.let { it in memberGroupIds } == true

    val isCurrentGroupOwner: Boolean
        get() = group != null && group.ownerId == currentUserId

    val dailyLimitCup: BigDecimal
        get() = controls?.dailyLimitCup ?: BettingRules.DEFAULT_DAILY_LIMIT_CUP

    val remainingTodayCup: BigDecimal
        get() = BettingRules.remainingDailyLimit(controls, usedTodayCup)
}

@HiltViewModel
class BettingGroupViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val authRepository: AuthRepository,
    private val bettingRepository: BettingRepository,
    private val identityVerifications: IdentityVerificationsRepository,
    private val moderateContent: ModerateContentUseCase,
    private val groupMessages: GroupMessagesRepository
) : ViewModel() {

    private val routeGroupId: String? = savedStateHandle["groupId"]
    private val _uiState = MutableStateFlow(BettingGroupUiState(groupId = routeGroupId))
    val uiState: StateFlow<BettingGroupUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val session = when (val result = authRepository.currentSession()) {
                is Result.Success -> result.data
                is Result.Error -> null
            }
            val user = session?.user
            if (user == null) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Inicia sesión para ver tus grupos de apuestas."
                    )
                }
                return@launch
            }

            val controlsResult = bettingRepository.getControls()
            val controls = (controlsResult as? Result.Success)?.data
            val identity = when (val result = identityVerifications.latestApprovedCarnetForUser(user.id)) {
                is Result.Success -> result.data
                is Result.Error -> null
            }
            val gate: BettingEligibility = BettingRules.eligibility(user, identity, controls)
            val myBetsResult = bettingRepository.getMyBets()
            val myBets = (myBetsResult as? Result.Success)?.data.orEmpty()
            val membershipResult = bettingRepository.getMyMemberGroupIds()
            val memberGroupIds = (membershipResult as? Result.Success)?.data.orEmpty()
            val groupsResult = bettingRepository.getGroups()
            val groups = (groupsResult as? Result.Success)?.data.orEmpty()
            val groupId = routeGroupId

            var group: BettingGroup? = null
            var members = emptyList<BettingGroupMember>()
            var groupBets = emptyList<Bet>()
            var wallMessages = emptyList<GroupMessage>()
            var groupLoadError: String? = null
            if (groupId != null) {
                when (val result = bettingRepository.getGroup(groupId)) {
                    is Result.Success -> group = result.data
                    is Result.Error -> groupLoadError = result.message
                }
                if (group != null && groupId in memberGroupIds) {
                    when (val result = bettingRepository.getGroupMembers(groupId)) {
                        is Result.Success -> members = result.data
                        is Result.Error -> groupLoadError = groupLoadError ?: result.message
                    }
                    when (val result = bettingRepository.getGroupBets(groupId)) {
                        is Result.Success -> groupBets = result.data
                        is Result.Error -> groupLoadError = groupLoadError ?: result.message
                    }
                    wallMessages = (groupMessages.listForGroup(groupId) as? Result.Success)?.data.orEmpty()
                }
            }

            val controlsError = (controlsResult as? Result.Error)?.message
            val canBet = gate.canBet && controls != null && myBetsResult is Result.Success
            val reason = when {
                controls == null -> controlsError ?: "No se pudo comprobar tu límite o autoexclusión. Inténtalo de nuevo."
                myBetsResult is Result.Error -> myBetsResult.message ?: "No se pudo comprobar tu límite diario. Inténtalo de nuevo."
                !gate.canBet -> gate.reason
                else -> null
            }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    groupId = groupId,
                    currentUserId = user.id,
                    groups = groups,
                    memberGroupIds = memberGroupIds,
                    group = group,
                    members = members,
                    groupBets = groupBets,
                    wallMessages = wallMessages,
                    myBets = myBets,
                    myBetsAvailable = myBetsResult is Result.Success,
                    controls = controls,
                    usedTodayCup = BettingRules.dailyUsage(myBets, user.id),
                    canBet = canBet,
                    eligibilityMessage = reason,
                    canCreateGroups = session.capabilities.canCreateGroups,
                    dailyLimitInput = controls?.dailyLimitCup?.stripTrailingZeros()?.toPlainString()
                        ?: BettingRules.DEFAULT_DAILY_LIMIT_CUP.toPlainString(),
                    errorMessage = groupLoadError
                        ?: controlsError
                        ?: (groupsResult as? Result.Error)?.message
                        ?: (myBetsResult as? Result.Error)?.message,
                    message = it.message
                )
            }
        }
    }

    fun createGroup(name: String, topic: String?, visibility: String) {
        val error = BettingRules.validateGroup(name, topic, visibility)
        if (error != null) {
            _uiState.update { it.copy(errorMessage = error, message = null) }
            return
        }
        _uiState.update { it.copy(isBusy = true, errorMessage = null, message = null) }
        viewModelScope.launch {
            val decision = when (
                val moderation = moderateContent.moderate(
                    ModeratedContent(
                        targetType = ModerationTargetType.BETTING_GROUP,
                        title = name,
                        body = topic
                    )
                )
            ) {
                is Result.Success -> moderation.data
                is Result.Error -> {
                    _uiState.update {
                        it.copy(isBusy = false, errorMessage = moderation.message ?: "No se pudo verificar el contenido.")
                    }
                    return@launch
                }
            }
            if (decision is ModerationDecision.Blocked) {
                _uiState.update { it.copy(isBusy = false, errorMessage = decision.userMessage) }
                return@launch
            }
            when (val result = bettingRepository.createGroup(name, topic, visibility)) {
                is Result.Success -> {
                    if (decision is ModerationDecision.Doubtful) {
                        moderateContent.flagDoubtful(
                            targetType = ModerationTargetType.BETTING_GROUP,
                            targetId = result.data.id,
                            decision = decision
                        )
                    }
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            groups = listOf(result.data) + it.groups.filterNot { group -> group.id == result.data.id },
                            memberGroupIds = it.memberGroupIds + result.data.id,
                            createdGroupId = result.data.id,
                            message = "Grupo creado. Tú eres su administrador."
                        )
                    }
                }
                is Result.Error -> _uiState.update {
                    it.copy(isBusy = false, errorMessage = result.message ?: "No se pudo crear el grupo.")
                }
            }
        }
    }

    fun clearCreatedGroupId() {
        _uiState.update { it.copy(createdGroupId = null) }
    }

    fun onWallDraftChanged(draft: String) {
        _uiState.update { it.copy(wallDraft = draft.take(1000)) }
    }

    /**
     * Publica en el muro del grupo tras pasar la moderación de texto.
     * Grave → se bloquea; dudoso → se publica y entra a la cola humana.
     */
    fun postWallMessage() {
        val state = _uiState.value
        val groupId = state.groupId ?: return
        val body = state.wallDraft.trim()
        if (body.isEmpty() || state.isBusy) return
        _uiState.update { it.copy(isBusy = true, errorMessage = null, message = null) }
        viewModelScope.launch {
            val decision = when (
                val moderation = moderateContent.moderate(
                    ModeratedContent(targetType = ModerationTargetType.GROUP_MESSAGE, body = body)
                )
            ) {
                is Result.Success -> moderation.data
                is Result.Error -> {
                    _uiState.update {
                        it.copy(isBusy = false, errorMessage = moderation.message ?: "No se pudo verificar el mensaje.")
                    }
                    return@launch
                }
            }
            if (decision is ModerationDecision.Blocked) {
                _uiState.update { it.copy(isBusy = false, errorMessage = decision.userMessage) }
                return@launch
            }
            when (val result = groupMessages.post(groupId, body)) {
                is Result.Success -> {
                    if (decision is ModerationDecision.Doubtful) {
                        moderateContent.flagDoubtful(
                            targetType = ModerationTargetType.GROUP_MESSAGE,
                            targetId = result.data.id,
                            decision = decision
                        )
                    }
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            wallDraft = "",
                            wallMessages = listOf(result.data) + it.wallMessages,
                            message = decision.userMessage.ifBlank { "Mensaje publicado" }
                        )
                    }
                }
                is Result.Error -> _uiState.update {
                    it.copy(isBusy = false, errorMessage = result.message ?: "No se pudo publicar el mensaje.")
                }
            }
        }
    }

    fun joinGroup(groupId: String) {
        runAction("No se pudo unir al grupo.") {
            when (val result = bettingRepository.joinPublicGroup(groupId)) {
                is Result.Success -> {
                    _uiState.update { it.copy(memberGroupIds = it.memberGroupIds + groupId) }
                    refreshKeepingMessage("Ya eres miembro del grupo.")
                    true
                }
                is Result.Error -> {
                    setError(result.message ?: "No se pudo unir al grupo.")
                    false
                }
            }
        }
    }

    fun inviteMember(phone: String) {
        val groupId = _uiState.value.group?.id ?: return
        if (phone.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Escribe el teléfono del miembro.", message = null) }
            return
        }
        runAction("No se pudo invitar a la persona.") {
            when (val result = bettingRepository.inviteMemberByPhone(groupId, phone)) {
                is Result.Success -> {
                    refreshKeepingMessage("Invitación añadida al grupo.")
                    true
                }
                is Result.Error -> {
                    setError(result.message ?: "No se pudo invitar a la persona.")
                    false
                }
            }
        }
    }

    fun removeMember(userId: String) {
        val groupId = _uiState.value.group?.id ?: return
        runAction("No se pudo expulsar al miembro.") {
            when (val result = bettingRepository.removeMember(groupId, userId)) {
                is Result.Success -> {
                    refreshKeepingMessage("Miembro expulsado del grupo.")
                    true
                }
                is Result.Error -> {
                    setError(result.message ?: "No se pudo expulsar al miembro.")
                    false
                }
            }
        }
    }

    fun onDailyLimitInputChanged(value: String) {
        if (value.length <= 12 && (value.isEmpty() || value.matches(Regex("^\\d{0,7}([.,]\\d{0,2})?$")))) {
            _uiState.update { it.copy(dailyLimitInput = value, errorMessage = null, message = null) }
        }
    }

    fun saveDailyLimit() {
        val amount = _uiState.value.dailyLimitInput.trim().replace(',', '.').toBigDecimalOrNull()
        val error = BettingRules.validateDailyLimit(amount)
        if (error != null) {
            _uiState.update { it.copy(errorMessage = error, message = null) }
            return
        }
        _uiState.update { it.copy(isBusy = true, errorMessage = null, message = null) }
        viewModelScope.launch {
            when (val result = bettingRepository.updateDailyLimit(requireNotNull(amount))) {
                is Result.Success -> _uiState.update {
                    it.copy(
                        isBusy = false,
                        controls = result.data,
                        dailyLimitInput = result.data.dailyLimitCup.stripTrailingZeros().toPlainString(),
                        message = "Límite diario actualizado."
                    )
                }
                is Result.Error -> _uiState.update {
                    it.copy(isBusy = false, errorMessage = result.message ?: "No se pudo guardar el límite.")
                }
            }
        }
    }

    fun selfExclude() {
        val until = _uiState.value.controls?.selfExcludedUntil
        if (until != null && until.isAfter(java.time.Instant.now())) {
            _uiState.update { it.copy(errorMessage = "La autoexclusión ya está activa.") }
            return
        }
        _uiState.update { it.copy(isBusy = true, errorMessage = null, message = null) }
        viewModelScope.launch {
            when (val result = bettingRepository.selfExcludeForThirtyDays()) {
                is Result.Success -> _uiState.update {
                    it.copy(
                        isBusy = false,
                        controls = result.data,
                        canBet = false,
                        eligibilityMessage = "La autoexclusión está activa por 30 días.",
                        message = "Autoexclusión activada durante 30 días."
                    )
                }
                is Result.Error -> _uiState.update {
                    it.copy(isBusy = false, errorMessage = result.message ?: "No se pudo activar la autoexclusión.")
                }
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun runAction(fallback: String, action: suspend () -> Boolean) {
        _uiState.update { it.copy(isBusy = true, errorMessage = null, message = null) }
        viewModelScope.launch {
            val succeeded = action()
            if (succeeded) _uiState.update { it.copy(isBusy = false) }
            else _uiState.update { it.copy(isBusy = false, errorMessage = it.errorMessage ?: fallback) }
        }
    }

    private fun refreshKeepingMessage(message: String) {
        viewModelScope.launch {
            refresh()
            _uiState.update { it.copy(message = message) }
        }
    }

    private fun setError(message: String) {
        _uiState.update { it.copy(errorMessage = message, message = null) }
    }
}
