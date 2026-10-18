package com.lamadriguera.app.feature.moderation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.domain.model.ModerationCategory
import com.lamadriguera.app.domain.model.ModerationTargetType
import com.lamadriguera.app.domain.model.Report
import com.lamadriguera.app.domain.repository.AuthRepository
import com.lamadriguera.app.domain.repository.ReportsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ReportCategoryChoice(val category: ModerationCategory, val severity: Int, val label: String) {
    SPAM(ModerationCategory.SPAM, 2, "Spam"),
    SCAM(ModerationCategory.OTHER, 3, "Estafa"),
    HARASSMENT(ModerationCategory.HARASSMENT, 3, "Acoso"),
    VIOLENCE(ModerationCategory.VIOLENCE, 3, "Violencia"),
    SEXUAL(ModerationCategory.SEXUAL, 3, "Contenido sexual"),
    ILLEGAL_GAMBLING(ModerationCategory.ILLEGAL_GAMBLING, 3, "Apuestas ilegales"),
    OTHER(ModerationCategory.OTHER, 2, "Otro")
}

data class ReportUiState(
    val showDialog: Boolean = false,
    val category: ReportCategoryChoice = ReportCategoryChoice.OTHER,
    val reason: String = "",
    val isSubmitting: Boolean = false,
    val done: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Envía reportes de contenido (listings, mensajes, perfiles y apuestas).
 * La plataforma los recibe en su cola; el contenido original nunca se borra
 * y el chat privado no se modera (solo queda registrado el reporte).
 */
@HiltViewModel
class ReportViewModel @Inject constructor(
    private val reportsRepository: ReportsRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReportUiState())
    val uiState: StateFlow<ReportUiState> = _uiState.asStateFlow()

    fun open() {
        _uiState.update {
            it.copy(showDialog = true, done = false, errorMessage = null, reason = "", category = ReportCategoryChoice.OTHER)
        }
    }

    fun close() {
        _uiState.update { it.copy(showDialog = false, errorMessage = null) }
    }

    fun onCategorySelected(category: ReportCategoryChoice) {
        _uiState.update { it.copy(category = category) }
    }

    fun onReasonChanged(reason: String) {
        _uiState.update { it.copy(reason = reason) }
    }

    fun submit(targetType: ModerationTargetType, targetId: String) {
        val state = _uiState.value
        if (state.isSubmitting || state.reason.isBlank()) return
        _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
        viewModelScope.launch {
            val session = (authRepository.currentSession() as? Result.Success)?.data
            val reporterId = session?.user?.id
            if (reporterId == null) {
                _uiState.update {
                    it.copy(isSubmitting = false, errorMessage = "Inicia sesión para reportar contenido")
                }
                return@launch
            }
            val report = Report(
                id = UUID.randomUUID().toString(),
                reporterId = reporterId,
                targetType = targetType.wireValue,
                targetId = targetId,
                reason = state.reason.trim(),
                status = "open",
                createdAt = Instant.now(),
                category = state.category.category.wireValue,
                severity = state.category.severity
            )
            when (val result = reportsRepository.create(report)) {
                is Result.Success -> _uiState.update {
                    it.copy(isSubmitting = false, showDialog = false, done = true)
                }
                is Result.Error -> _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        errorMessage = result.message ?: "No se pudo enviar el reporte"
                    )
                }
            }
        }
    }
}
