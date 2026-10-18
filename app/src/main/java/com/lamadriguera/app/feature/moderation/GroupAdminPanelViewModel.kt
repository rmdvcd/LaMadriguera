package com.lamadriguera.app.feature.moderation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.domain.model.BettingGroup
import com.lamadriguera.app.domain.model.BettingGroupMember
import com.lamadriguera.app.domain.model.ModerationReview
import com.lamadriguera.app.domain.model.ModerationReviewStatus
import com.lamadriguera.app.domain.model.Sanction
import com.lamadriguera.app.domain.model.SanctionRequest
import com.lamadriguera.app.domain.repository.AuthRepository
import com.lamadriguera.app.domain.repository.BettingRepository
import com.lamadriguera.app.domain.repository.ModerationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GroupAdminPanelUiState(
    val isLoading: Boolean = true,
    val isBusy: Boolean = false,
    val isOwner: Boolean = false,
    val group: BettingGroup? = null,
    val members: List<BettingGroupMember> = emptyList(),
    val reviews: List<ModerationReview> = emptyList(),
    val rulesInput: String = "",
    val selected: ModerationReview? = null,
    val reasonInput: String = "",
    val errorMessage: String? = null,
    val message: String? = null
)

/**
 * Panel del admin responsable de un grupo: reglas visibles, reportes/revisiones
 * del contenido de SU grupo y expulsión de miembros. La plataforma recibe los
 * reportes pero no modera el contenido privado; aquí solo se actúa sobre
 * contenido del grupo (mensajes del muro, apuestas, el propio grupo).
 */
@HiltViewModel
class GroupAdminPanelViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val authRepository: AuthRepository,
    private val bettingRepository: BettingRepository,
    private val moderationRepository: ModerationRepository
) : ViewModel() {

    private val groupId: String = checkNotNull(savedStateHandle["groupId"]) { "Falta el grupo" }

    private val _uiState = MutableStateFlow(GroupAdminPanelUiState())
    val uiState: StateFlow<GroupAdminPanelUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val session = (authRepository.currentSession() as? Result.Success)?.data
            val currentUserId = session?.user?.id
            val group = (bettingRepository.getGroup(groupId) as? Result.Success)?.data
            val isOwner = group != null && group.ownerId == currentUserId
            if (group == null || !isOwner) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        group = group,
                        isOwner = false,
                        errorMessage = "Solo el administrador del grupo ve este panel."
                    )
                }
                return@launch
            }
            val members = (bettingRepository.getGroupMembers(groupId) as? Result.Success)?.data.orEmpty()
            val reviews = (moderationRepository.groupReviewQueue(groupId) as? Result.Success)?.data.orEmpty()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isOwner = true,
                    group = group,
                    members = members,
                    reviews = reviews,
                    rulesInput = group.rules.ifBlank { it.rulesInput }
                )
            }
        }
    }

    fun onRulesChanged(rules: String) {
        _uiState.update { it.copy(rulesInput = rules) }
    }

    fun saveRules() {
        val state = _uiState.value
        if (state.isBusy) return
        _uiState.update { it.copy(isBusy = true, errorMessage = null) }
        viewModelScope.launch {
            when (val result = bettingRepository.setGroupRules(groupId, state.rulesInput)) {
                is Result.Success -> _uiState.update {
                    it.copy(isBusy = false, group = result.data, message = "Reglas guardadas")
                }
                is Result.Error -> _uiState.update {
                    it.copy(isBusy = false, errorMessage = result.message ?: "No se pudieron guardar las reglas")
                }
            }
        }
    }

    fun expelMember(member: BettingGroupMember) {
        val state = _uiState.value
        if (state.isBusy) return
        _uiState.update { it.copy(isBusy = true, errorMessage = null) }
        viewModelScope.launch {
            when (val result = bettingRepository.removeMember(groupId, member.userId)) {
                is Result.Success -> _uiState.update {
                    it.copy(
                        isBusy = false,
                        members = it.members.filterNot { m -> m.userId == member.userId },
                        message = "Miembro expulsado"
                    )
                }
                is Result.Error -> _uiState.update {
                    it.copy(isBusy = false, errorMessage = result.message ?: "No se pudo expulsar al miembro")
                }
            }
        }
    }

    fun openReview(item: ModerationReview) {
        _uiState.update { it.copy(selected = item, reasonInput = "", errorMessage = null) }
    }

    fun closeReview() {
        _uiState.update { it.copy(selected = null) }
    }

    fun onReasonChanged(reason: String) {
        _uiState.update { it.copy(reasonInput = reason) }
    }

    /** Los admins de grupo solo avisan u ocultan (warn/hide); suspend/ban son de plataforma. */
    fun applySanction(sanction: Sanction) {
        val state = _uiState.value
        val selected = state.selected ?: return
        if (state.isBusy) return
        if (sanction == Sanction.SUSPEND || sanction == Sanction.BAN) {
            _uiState.update { it.copy(errorMessage = "Solo la plataforma puede suspender o expulsar cuentas") }
            return
        }
        if (state.reasonInput.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Indica el motivo de la sanción") }
            return
        }
        _uiState.update { it.copy(isBusy = true, errorMessage = null) }
        viewModelScope.launch {
            val request = SanctionRequest(
                targetType = selected.targetType,
                targetId = selected.targetId,
                sanction = sanction,
                reason = state.reasonInput.trim(),
                category = selected.category,
                severity = selected.severity.coerceIn(1, 5),
                evidence = selected.evidence
            )
            when (val result = moderationRepository.applySanction(request)) {
                is Result.Success -> {
                    _uiState.update {
                        it.copy(isBusy = false, selected = null, message = "Sanción aplicada y registrada")
                    }
                    refresh()
                }
                is Result.Error -> _uiState.update {
                    it.copy(isBusy = false, errorMessage = result.message ?: "No se pudo aplicar la sanción")
                }
            }
        }
    }

    fun dismissReview() {
        val state = _uiState.value
        val selected = state.selected ?: return
        if (state.isBusy) return
        _uiState.update { it.copy(isBusy = true, errorMessage = null) }
        viewModelScope.launch {
            when (val result = moderationRepository.dismissReview(selected.id, state.reasonInput.trim())) {
                is Result.Success -> {
                    _uiState.update { it.copy(isBusy = false, selected = null, message = "Revisión descartada") }
                    refresh()
                }
                is Result.Error -> _uiState.update {
                    it.copy(isBusy = false, errorMessage = result.message ?: "No se pudo descartar la revisión")
                }
            }
        }
    }

    companion object {
        fun openReviews(items: List<ModerationReview>): List<ModerationReview> =
            items.filter { it.status == ModerationReviewStatus.PENDING || it.status == ModerationReviewStatus.IN_REVIEW }
    }
}
