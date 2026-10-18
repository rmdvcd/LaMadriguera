package com.lamadriguera.app.feature.moderation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.domain.model.ModerationReview
import com.lamadriguera.app.domain.model.ModerationReviewStatus
import com.lamadriguera.app.domain.model.ModerationTargetType
import com.lamadriguera.app.domain.model.Sanction
import com.lamadriguera.app.domain.model.SanctionRequest
import com.lamadriguera.app.domain.repository.ModerationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SeverityFilter(val minSeverity: Int, val label: String) {
    ANY(0, "Cualquiera"),
    MEDIUM(2, "Media (≥ 2)"),
    HIGH(4, "Alta (≥ 4)")
}

data class AdminQueueUiState(
    val isLoading: Boolean = true,
    val isBusy: Boolean = false,
    val items: List<ModerationReview> = emptyList(),
    val statusFilter: ModerationReviewStatus? = null,
    val severityFilter: SeverityFilter = SeverityFilter.ANY,
    val selected: ModerationReview? = null,
    val reasonInput: String = "",
    val errorMessage: String? = null,
    val message: String? = null
) {
    val pendingCount: Int get() = items.count { it.status == ModerationReviewStatus.PENDING }
}

/**
 * Cola humana de moderación. Solo llega lo que la IA marca como dudoso
 * (umbral bajo) más los reportes de usuarios. Las sanciones nunca borran
 * contenido: se marca oculto y se guarda evidencia en `moderation_actions`.
 */
@HiltViewModel
class AdminQueueViewModel @Inject constructor(
    private val moderationRepository: ModerationRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AdminQueueUiState())
    val uiState: StateFlow<AdminQueueUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val state = _uiState.value
            when (
                val result = moderationRepository.reviewQueue(
                    status = state.statusFilter,
                    minSeverity = state.severityFilter.minSeverity
                )
            ) {
                is Result.Success -> _uiState.update {
                    it.copy(isLoading = false, items = result.data, errorMessage = null)
                }
                is Result.Error -> _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = result.message ?: "No se pudo cargar la cola de moderación"
                    )
                }
            }
        }
    }

    fun onStatusFilterChanged(status: ModerationReviewStatus?) {
        _uiState.update { it.copy(statusFilter = status) }
        refresh()
    }

    fun onSeverityFilterChanged(filter: SeverityFilter) {
        _uiState.update { it.copy(severityFilter = filter) }
        refresh()
    }

    fun openItem(item: ModerationReview) {
        _uiState.update { it.copy(selected = item, reasonInput = "", errorMessage = null, message = null) }
    }

    fun closeItem() {
        _uiState.update { it.copy(selected = null, errorMessage = null) }
    }

    fun onReasonChanged(reason: String) {
        _uiState.update { it.copy(reasonInput = reason) }
    }

    fun applySanction(sanction: Sanction) {
        val state = _uiState.value
        val selected = state.selected ?: return
        if (state.isBusy) return
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
                is Result.Success -> _uiState.update {
                    it.copy(
                        isBusy = false,
                        selected = null,
                        message = "Sanción aplicada y registrada",
                        reasonInput = ""
                    )
                }.also { refresh() }
                is Result.Error -> _uiState.update {
                    it.copy(
                        isBusy = false,
                        errorMessage = result.message ?: "No se pudo aplicar la sanción"
                    )
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
                is Result.Success -> _uiState.update {
                    it.copy(isBusy = false, selected = null, message = "Revisión descartada")
                }.also { refresh() }
                is Result.Error -> _uiState.update {
                    it.copy(
                        isBusy = false,
                        errorMessage = result.message ?: "No se pudo descartar la revisión"
                    )
                }
            }
        }
    }

    companion object {
        fun targetTypeLabel(targetType: ModerationTargetType): String = when (targetType) {
            ModerationTargetType.LISTING -> "Publicación"
            ModerationTargetType.GROUP_MESSAGE -> "Mensaje de grupo"
            ModerationTargetType.BET -> "Apuesta"
            ModerationTargetType.PROFILE -> "Perfil"
            ModerationTargetType.MESSAGE -> "Mensaje privado"
            ModerationTargetType.BETTING_GROUP -> "Grupo"
        }
    }
}
