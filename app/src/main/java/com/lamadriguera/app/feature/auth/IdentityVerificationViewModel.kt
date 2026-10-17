package com.lamadriguera.app.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.domain.model.IdentityVerification
import com.lamadriguera.app.domain.model.IdentityVerificationRequest
import com.lamadriguera.app.domain.model.IdentityVerificationStatus
import com.lamadriguera.app.domain.repository.AuthRepository
import com.lamadriguera.app.domain.repository.IdentityVerificationsRepository
import com.lamadriguera.app.domain.usecase.IdentityVerificationForm
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Estado de la pantalla de verificación de identidad:
 * la solicitud existente (pending/approved/rejected) y el formulario de envío.
 */
data class IdentityVerificationUiState(
    val isLoading: Boolean = true,
    val current: IdentityVerification? = null,
    val fullNameInput: String = "",
    val birthdateInput: LocalDate? = null,
    val hasFrontImage: Boolean = false,
    val hasBackImage: Boolean = false,
    val termsAccepted: Boolean = false,
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    val submitted: Boolean = false
) {
    /** true mientras hay una solicitud pendiente o ya aprobada. */
    val isLocked: Boolean
        get() = current?.status == IdentityVerificationStatus.PENDING ||
            current?.status == IdentityVerificationStatus.APPROVED

    val canSubmit: Boolean
        get() = !isLoading && !isSubmitting && !isLocked &&
            fullNameInput.isNotBlank() && birthdateInput != null &&
            hasFrontImage && hasBackImage && termsAccepted
}

@HiltViewModel
class IdentityVerificationViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val identityVerifications: IdentityVerificationsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(IdentityVerificationUiState())
    val uiState: StateFlow<IdentityVerificationUiState> = _uiState.asStateFlow()

    private var frontImage: ByteArray? = null
    private var backImage: ByteArray? = null

    init {
        refresh()
    }

    fun refresh() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val userId = when (val session = authRepository.currentSession()) {
                is Result.Success -> session.data?.user?.id
                is Result.Error -> null
            }
            if (userId == null) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = "Inicia sesión para verificar tu identidad")
                }
                return@launch
            }
            _uiState.value = when (val result = identityVerifications.latestForUser(userId)) {
                is Result.Success -> _uiState.value.copy(
                    isLoading = false,
                    current = result.data
                )
                is Result.Error -> _uiState.value.copy(
                    isLoading = false,
                    errorMessage = result.message ?: "No se pudo cargar tu solicitud"
                )
            }
        }
    }

    fun onFullNameChanged(name: String) {
        _uiState.update { it.copy(fullNameInput = name, errorMessage = null) }
    }

    fun onBirthdateChanged(birthdate: LocalDate) {
        _uiState.update { it.copy(birthdateInput = birthdate, errorMessage = null) }
    }

    fun onFrontImageSelected(bytes: ByteArray) {
        frontImage = bytes
        _uiState.update { it.copy(hasFrontImage = true, errorMessage = null) }
    }

    fun onBackImageSelected(bytes: ByteArray) {
        backImage = bytes
        _uiState.update { it.copy(hasBackImage = true, errorMessage = null) }
    }

    fun onTermsAcceptedChanged(accepted: Boolean) {
        _uiState.update { it.copy(termsAccepted = accepted, errorMessage = null) }
    }

    /** Envía la solicitud con las fotos del carnet (queda en estado pending). */
    fun submit() {
        val state = _uiState.value
        val validationError = validate(state)
        if (validationError != null) {
            _uiState.update { it.copy(errorMessage = validationError) }
            return
        }
        val request = IdentityVerificationRequest(
            fullName = state.fullNameInput.trim(),
            birthdate = requireNotNull(state.birthdateInput),
            frontImage = requireNotNull(frontImage),
            backImage = requireNotNull(backImage)
        )
        _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
        viewModelScope.launch {
            _uiState.value = when (val result = identityVerifications.submit(request)) {
                is Result.Success -> _uiState.value.copy(
                    isSubmitting = false,
                    current = result.data,
                    submitted = true
                )
                is Result.Error -> _uiState.value.copy(
                    isSubmitting = false,
                    errorMessage = result.message ?: "No se pudo enviar la solicitud"
                )
            }
        }
    }

    private fun validate(state: IdentityVerificationUiState): String? {
        IdentityVerificationForm.validateFullName(state.fullNameInput)?.let { return it }
        IdentityVerificationForm.validateBirthdate(state.birthdateInput)?.let { return it }
        if (!state.hasFrontImage) return IdentityVerificationForm.FRONT_IMAGE_MESSAGE
        if (!state.hasBackImage) return IdentityVerificationForm.BACK_IMAGE_MESSAGE
        if (!state.termsAccepted) return IdentityVerificationForm.TERMS_MESSAGE
        return null
    }
}
