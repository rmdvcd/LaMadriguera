package com.lamadriguera.app.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.core.result.UiState
import com.lamadriguera.app.domain.model.AuthSession
import com.lamadriguera.app.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Resuelve la sesión guardada al arrancar:
 * sesión válida -> Home/Publicaciones; sin sesión -> PhoneLogin.
 */
@HiltViewModel
class SplashViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<AuthSession?>>(UiState.Loading)
    val state: StateFlow<UiState<AuthSession?>> = _state.asStateFlow()

    init {
        restoreSession()
    }

    fun restoreSession() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = when (val result = authRepository.restoreSession()) {
                is Result.Success -> UiState.Success(result.data)
                is Result.Error -> UiState.Error(
                    message = result.message ?: "No se pudo recuperar la sesión",
                    throwable = result.throwable
                )
            }
        }
    }
}
