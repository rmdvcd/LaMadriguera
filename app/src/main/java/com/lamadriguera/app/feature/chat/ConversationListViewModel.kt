package com.lamadriguera.app.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.domain.model.ChatNotification
import com.lamadriguera.app.domain.model.ConversationSummary
import com.lamadriguera.app.domain.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Estado de la lista de hilos: conversaciones activas con vista previa del
 * último mensaje (descifrada solo en memoria) y badge de no leídos, más el
 * aviso persistente de notificaciones del sistema (pagos, moderación y seguridad).
 */
data class ConversationListUiState(
    val isLoading: Boolean = true,
    val items: List<ConversationSummary> = emptyList(),
    val systemNotice: ChatNotification? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class ConversationListViewModel @Inject constructor(
    private val chatRepository: ChatRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConversationListUiState())
    val uiState: StateFlow<ConversationListUiState> = _uiState.asStateFlow()

    init {
        refresh()
        loadUnreadNotifications()
    }

    fun refresh() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            // Publica la clave pública del dispositivo para que otros
            // participantes puedan envolverme la session_key (idempotente).
            chatRepository.ensureChatIdentity()
            when (val result = chatRepository.getConversations()) {
                is Result.Success -> _uiState.update {
                    it.copy(isLoading = false, items = result.data, errorMessage = null)
                }
                is Result.Error -> _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = result.message ?: "No se pudieron cargar las conversaciones"
                    )
                }
            }
        }
    }

    /** Muestra primero la notificación no leída más reciente, incluidas las de pagos. */
    private fun loadUnreadNotifications() {
        viewModelScope.launch {
            when (val result = chatRepository.getUnreadNotifications()) {
                is Result.Success -> _uiState.update { it.copy(systemNotice = result.data.firstOrNull()) }
                is Result.Error -> Unit // no bloquea el listado
            }
        }
    }

    fun dismissNotifications() {
        val notice = _uiState.value.systemNotice ?: return
        _uiState.update { it.copy(systemNotice = null) }
        viewModelScope.launch {
            chatRepository.markNotificationsRead(listOf(notice.id))
            loadUnreadNotifications()
        }
    }

}
