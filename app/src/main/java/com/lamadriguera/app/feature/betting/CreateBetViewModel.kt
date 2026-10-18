package com.lamadriguera.app.feature.betting

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.domain.model.BetDraft
import com.lamadriguera.app.domain.model.ModeratedContent
import com.lamadriguera.app.domain.model.ModerationDecision
import com.lamadriguera.app.domain.model.ModerationTargetType
import com.lamadriguera.app.domain.model.BettingControls
import com.lamadriguera.app.domain.model.BettingGroup
import com.lamadriguera.app.domain.repository.AuthRepository
import com.lamadriguera.app.domain.repository.BettingRepository
import com.lamadriguera.app.domain.repository.IdentityVerificationsRepository
import com.lamadriguera.app.domain.usecase.BettingRules
import com.lamadriguera.app.domain.usecase.ModerateContentUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

 data class CreateBetUiState(
    val isLoading: Boolean = true,
    val isSubmitting: Boolean = false,
    val group: BettingGroup? = null,
    val groupId: String = "",
    val isGroupMember: Boolean = false,
    val canBet: Boolean = false,
    val eligibilityMessage: String? = null,
    val controls: BettingControls? = null,
    val usedTodayCup: BigDecimal = BigDecimal.ZERO,
    val dailyUsageAvailable: Boolean = false,
    val descriptionInput: String = "",
    val stakeInput: String = "",
    val oddsInput: String = "",
    val errorMessage: String? = null,
    val createdBetId: String? = null
) {
    val remainingTodayCup: BigDecimal
        get() = BettingRules.remainingDailyLimit(controls, usedTodayCup)

    val parsedStake: BigDecimal?
        get() = stakeInput.trim().replace(',', '.').toBigDecimalOrNull()

    val parsedOdds: BigDecimal?
        get() = oddsInput.trim().replace(',', '.').takeIf(String::isNotBlank)?.toBigDecimalOrNull()

    val canSubmit: Boolean
        get() = !isLoading && !isSubmitting && isGroupMember && canBet && dailyUsageAvailable &&
            BettingRules.validateBetDraft(groupId, descriptionInput, parsedStake, parsedOdds) == null &&
            controls != null && parsedStake?.let {
                BettingRules.fitsDailyLimit(controls, usedTodayCup, it)
            } == true
}

@HiltViewModel
class CreateBetViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val authRepository: AuthRepository,
    private val bettingRepository: BettingRepository,
    private val identityVerifications: IdentityVerificationsRepository,
    private val moderateContent: ModerateContentUseCase
) : ViewModel() {

    private val groupId: String = savedStateHandle["groupId"].orEmpty()
    private val _uiState = MutableStateFlow(CreateBetUiState(groupId = groupId))
    val uiState: StateFlow<CreateBetUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val session = when (val result = authRepository.currentSession()) {
                is Result.Success -> result.data
                is Result.Error -> null
            }
            val user = session?.user
            if (user == null) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Inicia sesión para crear una apuesta.") }
                return@launch
            }
            val controlsResult = bettingRepository.getControls()
            val controls = (controlsResult as? Result.Success)?.data
            val identity = when (val result = identityVerifications.latestApprovedCarnetForUser(user.id)) {
                is Result.Success -> result.data
                is Result.Error -> null
            }
            val eligibility = BettingRules.eligibility(user, identity, controls)
            val membershipResult = bettingRepository.getMyMemberGroupIds()
            val isMember = (membershipResult as? Result.Success)?.data?.contains(groupId) == true
            val groupResult = bettingRepository.getGroup(groupId)
            val group = (groupResult as? Result.Success)?.data
            val historyResult = bettingRepository.getMyBets()
            val myBets = (historyResult as? Result.Success)?.data.orEmpty()
            val controlsError = (controlsResult as? Result.Error)?.message
            val error = when {
                groupId.isBlank() -> "Selecciona un grupo antes de crear una apuesta."
                group == null -> (groupResult as? Result.Error)?.message ?: "No se encontró el grupo."
                !isMember -> "Debes ser miembro del grupo para crear una apuesta."
                controls == null -> controlsError ?: "No se pudo comprobar tu límite diario."
                historyResult is Result.Error -> historyResult.message ?: "No se pudo comprobar tu límite diario."
                else -> null
            }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    group = group,
                    isGroupMember = isMember,
                    controls = controls,
                    usedTodayCup = BettingRules.dailyUsage(myBets, user.id),
                    dailyUsageAvailable = historyResult is Result.Success,
                    canBet = eligibility.canBet && controls != null,
                    eligibilityMessage = if (controls == null) {
                        controlsError ?: "No se pudo comprobar tu autoexclusión."
                    } else eligibility.reason,
                    errorMessage = error ?: (historyResult as? Result.Error)?.message,
                    descriptionInput = it.descriptionInput,
                    stakeInput = it.stakeInput,
                    oddsInput = it.oddsInput
                )
            }
        }
    }

    fun onDescriptionChanged(value: String) {
        _uiState.update { it.copy(descriptionInput = value.take(600), errorMessage = null) }
    }

    fun onStakeChanged(value: String) {
        if (value.length <= 14 && (value.isEmpty() || value.matches(Regex("^\\d{0,10}([.,]\\d{0,2})?$")))) {
            _uiState.update { it.copy(stakeInput = value, errorMessage = null) }
        }
    }

    fun onOddsChanged(value: String) {
        if (value.length <= 14 && (value.isEmpty() || value.matches(Regex("^\\d{0,8}([.,]\\d{0,4})?$")))) {
            _uiState.update { it.copy(oddsInput = value, errorMessage = null) }
        }
    }

    fun submit() {
        val state = _uiState.value
        val validation = BettingRules.validateBetDraft(
            state.groupId,
            state.descriptionInput,
            state.parsedStake,
            state.parsedOdds
        )
        if (validation != null) {
            _uiState.update { it.copy(errorMessage = validation) }
            return
        }
        if (!state.isGroupMember) {
            _uiState.update { it.copy(errorMessage = "Debes ser miembro de este grupo.") }
            return
        }
        if (!state.canBet) {
            _uiState.update {
                it.copy(errorMessage = it.eligibilityMessage ?: "No cumples los requisitos para apostar.")
            }
            return
        }
        if (!state.dailyUsageAvailable) {
            _uiState.update {
                it.copy(errorMessage = "No se pudo comprobar tu límite diario. Inténtalo de nuevo.")
            }
            return
        }
        val amount = requireNotNull(state.parsedStake)
        if (state.controls == null || !BettingRules.fitsDailyLimit(state.controls, state.usedTodayCup, amount)) {
            _uiState.update {
                it.copy(errorMessage = "Este monto supera el saldo disponible de tu límite diario.")
            }
            return
        }

        _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
        viewModelScope.launch {
            val draft = BetDraft(
                groupId = state.groupId,
                description = state.descriptionInput.trim(),
                stakeAmount = amount,
                odds = state.parsedOdds
            )
            // Moderación de texto antes de publicar la apuesta en el grupo.
            val decision = when (
                val moderation = moderateContent.moderate(
                    ModeratedContent(targetType = ModerationTargetType.BET, body = draft.description)
                )
            ) {
                is Result.Success -> moderation.data
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isSubmitting = false,
                        errorMessage = moderation.message ?: "No se pudo verificar el contenido."
                    )
                    return@launch
                }
            }
            if (decision is ModerationDecision.Blocked) {
                _uiState.value = _uiState.value.copy(
                    isSubmitting = false,
                    errorMessage = decision.userMessage
                )
                return@launch
            }
            _uiState.value = when (val result = bettingRepository.createBet(draft)) {
                is Result.Success -> {
                    if (decision is ModerationDecision.Doubtful) {
                        moderateContent.flagDoubtful(
                            targetType = ModerationTargetType.BET,
                            targetId = result.data.id,
                            decision = decision
                        )
                    }
                    _uiState.value.copy(
                        isSubmitting = false,
                        createdBetId = result.data.id
                    )
                }
                is Result.Error -> _uiState.value.copy(
                    isSubmitting = false,
                    errorMessage = result.message ?: "No se pudo crear la apuesta."
                )
            }
        }
    }
}
