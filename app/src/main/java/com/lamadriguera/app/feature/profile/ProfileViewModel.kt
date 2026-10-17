package com.lamadriguera.app.feature.profile

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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Datos que muestra [ProfileScreen]: perfil, cuenta y capacidades. */
data class ProfileScreenData(
    val session: AuthSession
)

data class ProfileUiExtras(
    val loggedOut: Boolean = false
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<ProfileScreenData>>(UiState.Loading)
    val state: StateFlow<UiState<ProfileScreenData>> = _state.asStateFlow()

    private val _extras = MutableStateFlow(ProfileUiExtras())
    val extras: StateFlow<ProfileUiExtras> = _extras.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = when (val result = authRepository.currentSession()) {
                is Result.Success -> {
                    val session = result.data
                    if (session == null) {
                        UiState.Error("Inicia sesión para ver tu perfil")
                    } else {
                        UiState.Success(ProfileScreenData(session))
                    }
                }
                is Result.Error -> UiState.Error(
                    message = result.message ?: "No se pudo cargar tu perfil",
                    throwable = result.throwable
                )
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.signOut()
            _extras.update { it.copy(loggedOut = true) }
        }
    }
}
