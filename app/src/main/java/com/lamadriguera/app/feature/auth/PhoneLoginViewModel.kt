package com.lamadriguera.app.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.domain.repository.AuthRepository
import com.lamadriguera.app.domain.usecase.PhoneNumber
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Estado del formulario de acceso con teléfono + código OTP.
 * Registro y login comparten el mismo flujo.
 */
data class PhoneLoginUiState(
    val phoneInput: String = "",
    val codeInput: String = "",
    val codeSent: Boolean = false,
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    val authenticated: Boolean = false
)

@HiltViewModel
class PhoneLoginViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PhoneLoginUiState())
    val uiState: StateFlow<PhoneLoginUiState> = _uiState.asStateFlow()

    fun onPhoneChanged(phone: String) {
        _uiState.update { it.copy(phoneInput = phone, errorMessage = null) }
    }

    fun onCodeChanged(code: String) {
        _uiState.update { it.copy(codeInput = code, errorMessage = null) }
    }

    /** Envía el código SMS al teléfono introducido. */
    fun sendCode() {
        val phone = _uiState.value.phoneInput
        if (!PhoneNumber.isValid(phone)) {
            _uiState.update { it.copy(errorMessage = PhoneNumber.INVALID_MESSAGE) }
            return
        }
        _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
        viewModelScope.launch {
            _uiState.value = when (val result = authRepository.sendLoginCode(phone)) {
                is Result.Success -> _uiState.value.copy(
                    isSubmitting = false,
                    codeSent = true
                )
                is Result.Error -> _uiState.value.copy(
                    isSubmitting = false,
                    errorMessage = result.message ?: "No se pudo enviar el código"
                )
            }
        }
    }

    /** Reenvía el código SMS. */
    fun resendCode() {
        val phone = _uiState.value.phoneInput
        _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
        viewModelScope.launch {
            _uiState.value = when (val result = authRepository.resendLoginCode(phone)) {
                is Result.Success -> _uiState.value.copy(isSubmitting = false, codeSent = true)
                is Result.Error -> _uiState.value.copy(
                    isSubmitting = false,
                    errorMessage = result.message ?: "No se pudo reenviar el código"
                )
            }
        }
    }

    /** Verifica el código OTP y completa el acceso. */
    fun verifyCode() {
        val state = _uiState.value
        if (!PhoneNumber.isValid(state.phoneInput)) {
            _uiState.update { it.copy(errorMessage = PhoneNumber.INVALID_MESSAGE) }
            return
        }
        _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
        viewModelScope.launch {
            _uiState.value = when (
                val result = authRepository.verifyLoginCode(state.phoneInput, state.codeInput)
            ) {
                is Result.Success -> _uiState.value.copy(
                    isSubmitting = false,
                    authenticated = true
                )
                is Result.Error -> _uiState.value.copy(
                    isSubmitting = false,
                    errorMessage = result.message ?: "No se pudo verificar el código"
                )
            }
        }
    }
}
