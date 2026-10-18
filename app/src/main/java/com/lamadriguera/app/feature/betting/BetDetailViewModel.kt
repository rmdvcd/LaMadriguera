package com.lamadriguera.app.feature.betting

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.domain.model.Bet
import com.lamadriguera.app.domain.model.BetEvent
import com.lamadriguera.app.domain.model.BetEvidenceImage
import com.lamadriguera.app.domain.model.BetOutcome
import com.lamadriguera.app.domain.model.BetStatus
import com.lamadriguera.app.domain.model.BettingControls
import com.lamadriguera.app.domain.model.BettingGroup
import com.lamadriguera.app.domain.model.IdentityVerification
import com.lamadriguera.app.domain.model.outcome
import com.lamadriguera.app.domain.repository.AuthRepository
import com.lamadriguera.app.domain.repository.BettingRepository
import com.lamadriguera.app.domain.repository.IdentityVerificationsRepository
import com.lamadriguera.app.domain.usecase.BettingRules
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BetEventUi(
    val event: BetEvent,
    val evidenceSignedUrl: String? = null
)

data class BetDetailUiState(
    val isLoading: Boolean = true,
    val isBusy: Boolean = false,
    val bet: Bet? = null,
    val group: BettingGroup? = null,
    val events: List<BetEventUi> = emptyList(),
    val eventsAvailable: Boolean = false,
    val currentUserId: String? = null,
    val controls: BettingControls? = null,
    val canBet: Boolean = false,
    val eligibilityMessage: String? = null,
    val isGroupMember: Boolean = false,
    val canAccept: Boolean = false,
    val canSubmitResult: Boolean = false,
    val canOpenDispute: Boolean = false,
    val canResolveDispute: Boolean = false,
    val errorMessage: String? = null,
    val message: String? = null
) {
    val currentResult: BetOutcome?
        get() = bet?.status?.outcome()?.takeIf { it != BetOutcome.VOID }
}

@HiltViewModel
class BetDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val authRepository: AuthRepository,
    private val bettingRepository: BettingRepository,
    private val identityVerifications: IdentityVerificationsRepository
) : ViewModel() {

    private val betId: String = savedStateHandle["betId"].orEmpty()
    private val _uiState = MutableStateFlow(BetDetailUiState())
    val uiState: StateFlow<BetDetailUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch { loadSnapshot() }
    }

    private suspend fun loadSnapshot() {
        val session = when (val result = authRepository.currentSession()) {
            is Result.Success -> result.data
            is Result.Error -> null
        }
        val user = session?.user
        if (user == null) {
            _uiState.update { it.copy(isLoading = false, errorMessage = "Inicia sesión para ver esta apuesta.") }
            return
        }
        if (betId.isBlank()) {
            _uiState.update { it.copy(isLoading = false, errorMessage = "Apuesta no válida.") }
            return
        }
        val betResult = bettingRepository.getBet(betId)
        val bet = (betResult as? Result.Success)?.data
        if (bet == null) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    currentUserId = user.id,
                    errorMessage = (betResult as? Result.Error)?.message ?: "No se encontró la apuesta."
                )
            }
            return
        }
        val group = (bettingRepository.getGroup(bet.groupId) as? Result.Success)?.data
        val membershipResult = bettingRepository.getMyMemberGroupIds()
        val memberGroupIds = (membershipResult as? Result.Success)?.data.orEmpty()
        val controlsResult = bettingRepository.getControls()
        val controls = (controlsResult as? Result.Success)?.data
        val myBetsResult = bettingRepository.getMyBets()
        val myBets = (myBetsResult as? Result.Success)?.data.orEmpty()
        val dailyUsageAvailable = myBetsResult is Result.Success
        val identity: IdentityVerification? = when (val result = identityVerifications.latestApprovedCarnetForUser(user.id)) {
            is Result.Success -> result.data
            is Result.Error -> null
        }
        val eligibility = BettingRules.eligibility(user, identity, controls)
        val eventsResult = bettingRepository.getBetEvents(bet.id)
        val events = when (eventsResult) {
            is Result.Success -> eventsResult.data.map { event ->
                val signedUrl = event.evidenceRef?.let { reference ->
                    (bettingRepository.getEvidenceSignedUrl(reference) as? Result.Success)?.data
                }
                BetEventUi(event, signedUrl)
            }
            is Result.Error -> emptyList()
        }
        val eventsAvailable = eventsResult is Result.Success && events.all {
            it.event.evidenceRef == null || it.evidenceSignedUrl != null
        }
        val isMember = bet.groupId in memberGroupIds
        val isParticipant = user.id == bet.creatorId || user.id == bet.acceptorId
        val resultFinalized = events.any {
            it.event.eventType == "settlement_confirmed" || it.event.eventType == "admin_decision"
        }
        val canAccept = bet.status == BetStatus.OPEN &&
            user.id != bet.creatorId && isMember && eligibility.canBet && controls != null &&
            dailyUsageAvailable && eventsAvailable && BettingRules.fitsDailyLimit(
                controls,
                BettingRules.dailyUsage(myBets, user.id),
                bet.stakeAmount
            )
        val canSubmitResult = eventsAvailable && isParticipant && !resultFinalized &&
            bet.status in setOf(BetStatus.ACCEPTED, BetStatus.SETTLED_CREATOR, BetStatus.SETTLED_ACCEPTOR) &&
            (bet.status == BetStatus.ACCEPTED || bet.settledBy != user.id)
        val canOpenDispute = eventsAvailable && isParticipant && !resultFinalized &&
            bet.status in setOf(BetStatus.ACCEPTED, BetStatus.SETTLED_CREATOR, BetStatus.SETTLED_ACCEPTOR) &&
            (bet.status == BetStatus.ACCEPTED || bet.settledBy != user.id)
        val error = when {
            controls == null -> (controlsResult as? Result.Error)?.message
            membershipResult is Result.Error && bet.status == BetStatus.OPEN -> membershipResult.message
            myBetsResult is Result.Error && bet.status == BetStatus.OPEN -> myBetsResult.message
            eventsResult is Result.Error -> eventsResult.message ?: "No se pudo cargar el historial. Actualiza para reintentar."
            !eventsAvailable -> "No se pudo cargar toda la evidencia. Actualiza antes de continuar."
            else -> null
        }
        _uiState.update {
            it.copy(
                isLoading = false,
                bet = bet,
                group = group,
                events = events,
                eventsAvailable = eventsAvailable,
                currentUserId = user.id,
                controls = controls,
                canBet = eligibility.canBet && controls != null,
                eligibilityMessage = if (controls == null) {
                    error ?: "No se pudo comprobar la autoexclusión."
                } else eligibility.reason,
                isGroupMember = isMember,
                canAccept = canAccept,
                canSubmitResult = canSubmitResult,
                canOpenDispute = canOpenDispute,
                canResolveDispute = eventsAvailable && bet.status == BetStatus.DISPUTED && group?.ownerId == user.id,
                errorMessage = error
            )
        }
    }

    fun accept() = performAction("Apuesta aceptada.") {
        bettingRepository.acceptBet(betId)
    }

    fun submitResult(outcome: BetOutcome, evidenceText: String?, image: BetEvidenceImage?) {
        val error = BettingRules.validateEvidence(evidenceText, image)
        if (error != null) {
            _uiState.update { it.copy(errorMessage = error, message = null) }
            return
        }
        performAction("Resultado registrado.") {
            bettingRepository.submitResult(betId, outcome, evidenceText, image)
        }
    }

    fun openDispute(evidenceText: String?, image: BetEvidenceImage?) {
        val error = BettingRules.validateEvidence(evidenceText, image)
        if (error != null) {
            _uiState.update { it.copy(errorMessage = error, message = null) }
            return
        }
        performAction("Apuesta en disputa. El administrador del grupo decidirá.") {
            bettingRepository.openDispute(betId, evidenceText, image)
        }
    }

    fun resolveDispute(outcome: BetOutcome, decisionNote: String) {
        if (outcome == BetOutcome.VOID && decisionNote.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Explica por qué se anula la apuesta.", message = null) }
            return
        }
        if (decisionNote.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Escribe la explicación de la decisión.", message = null) }
            return
        }
        performAction("Disputa resuelta por el administrador del grupo.") {
            bettingRepository.resolveDispute(betId, outcome, decisionNote)
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun performAction(successMessage: String, action: suspend () -> Result<*>) {
        if (_uiState.value.isBusy) return
        _uiState.update { it.copy(isBusy = true, errorMessage = null, message = null) }
        viewModelScope.launch {
            when (val result = action()) {
                is Result.Success -> {
                    loadSnapshot()
                    _uiState.update { it.copy(isBusy = false, message = successMessage) }
                }
                is Result.Error -> _uiState.update {
                    it.copy(isBusy = false, errorMessage = result.message ?: "No se pudo actualizar la apuesta.")
                }
            }
        }
    }
}
