package com.lamadriguera.app.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.core.security.chat.PersonalDataGuard
import com.lamadriguera.app.domain.model.ChatMessage
import com.lamadriguera.app.domain.model.MessageExpiry
import com.lamadriguera.app.domain.model.MessageType
import com.lamadriguera.app.domain.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Acción de envío pendiente de confirmación por advertencia de datos
 * personales (p. ej. un teléfono detectado en el texto). Se permite enviar,
 * pero solo tras la confirmación explícita del usuario.
 */
sealed interface PendingSendAction {
    data class Text(val text: String) : PendingSendAction
}

/** Estado de la pantalla de hilo (ChatScreen). */
data class ChatUiState(
    val isLoading: Boolean = true,
    val messages: List<ChatMessage> = emptyList(),
    val draft: String = "",
    val expiry: MessageExpiry = MessageExpiry.NEVER,
    val isSending: Boolean = false,
    val isProcessingMedia: Boolean = false,
    val inDispute: Boolean = false,
    val showReportDialog: Boolean = false,
    val reportReason: String = "",
    val isReporting: Boolean = false,
    val reportDoneBanner: Boolean = false,
    val pendingPersonalDataSend: PendingSendAction? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val chatRepository: ChatRepository,
    private val personalDataGuard: PersonalDataGuard
) : ViewModel() {

    private val conversationId: String = savedStateHandle["conversationId"] ?: ""

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var realtimeJob: Job? = null
    private var expirySweepJob: Job? = null

    init {
        if (conversationId.isBlank()) {
            _uiState.update {
                it.copy(isLoading = false, errorMessage = "Conversación no especificada")
            }
        } else {
            refresh()
            observeRealtime()
            startExpirySweep()
        }
    }

    fun refresh() {
        if (conversationId.isBlank()) return
        viewModelScope.launch {
            chatRepository.ensureChatIdentity()
            when (val result = chatRepository.getMessages(conversationId)) {
                is Result.Success -> {
                    val now = Instant.now()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            // El cliente "olvida" el ciphertext expirado al momento.
                            messages = result.data.filter { m -> !m.hasExpired(now) },
                            errorMessage = null
                        )
                    }
                    chatRepository.markRead(conversationId)
                }
                is Result.Error -> _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = result.message ?: "No se pudieron cargar los mensajes"
                    )
                }
            }
            refreshThreadMetadata()
        }
    }

    private fun refreshThreadMetadata() {
        viewModelScope.launch {
            val summary = (chatRepository.getConversations() as? Result.Success)
                ?.data?.firstOrNull { it.conversation.id == conversationId } ?: return@launch
            _uiState.update {
                it.copy(
                    inDispute = summary.inDispute,
                    reportDoneBanner = it.reportDoneBanner || summary.inDispute
                )
            }
        }
    }

    private fun observeRealtime() {
        realtimeJob?.cancel()
        realtimeJob = viewModelScope.launch {
            runCatching {
                chatRepository.observeMessages(conversationId).collect { incoming ->
                    val now = Instant.now()
                    if (incoming.hasExpired(now)) return@collect
                    _uiState.update { state ->
                        if (state.messages.any { it.id == incoming.id }) state
                        else state.copy(messages = state.messages + incoming)
                    }
                    chatRepository.markRead(conversationId)
                }
            }
            // Si Realtime no está disponible, la pantalla sigue funcionando con refresh.
        }
    }

    /** Barre mensajes autodestruidos: elimina del estado local lo ya expirado. */
    private fun startExpirySweep() {
        expirySweepJob?.cancel()
        expirySweepJob = viewModelScope.launch {
            while (true) {
                delay(EXPIRY_SWEEP_MS)
                val now = Instant.now()
                _uiState.update { state ->
                    state.copy(messages = state.messages.filter { !it.hasExpired(now) })
                }
            }
        }
    }

    override fun onCleared() {
        realtimeJob?.cancel()
        expirySweepJob?.cancel()
        super.onCleared()
    }

    // ------------------------------------------------------------------
    // Composer
    // ------------------------------------------------------------------

    fun onDraftChanged(text: String) {
        _uiState.update { it.copy(draft = text) }
    }

    fun onExpiryChanged(expiry: MessageExpiry) {
        _uiState.update { it.copy(expiry = expiry) }
    }

    /**
     * Envío de texto con advertencia de datos personales: si el borrador
     * contiene un teléfono/contacto, se pausa y se muestra el aviso
     * "estás compartiendo un dato personal"; el usuario decide.
     */
    fun onSendText() {
        val snapshot = _uiState.value
        val text = snapshot.draft.trim()
        if (text.isEmpty() || snapshot.isSending) return
        if (personalDataGuard.detect(text).isNotEmpty()) {
            _uiState.update { it.copy(pendingPersonalDataSend = PendingSendAction.Text(text)) }
            return
        }
        sendTextNow(text)
    }

    /** El usuario confirma compartir el dato personal: se envía igualmente. */
    fun confirmPersonalDataSend() {
        val pending = _uiState.value.pendingPersonalDataSend ?: return
        _uiState.update { it.copy(pendingPersonalDataSend = null) }
        when (pending) {
            is PendingSendAction.Text -> sendTextNow(pending.text)
        }
    }

    fun dismissPersonalDataWarning() {
        _uiState.update { it.copy(pendingPersonalDataSend = null) }
    }

    private fun sendTextNow(text: String) {
        send { chatRepository.sendText(conversationId, text, _uiState.value.expiry) }
    }

    fun onImagePicked(bytes: ByteArray, mimeType: String) {
        guardMediaSend(bytes.size) {
            chatRepository.sendMedia(
                conversationId, MessageType.IMAGE, bytes, mimeType, _uiState.value.expiry
            )
        }
    }

    fun onAudioPicked(bytes: ByteArray, mimeType: String) {
        guardMediaSend(bytes.size) {
            chatRepository.sendMedia(
                conversationId, MessageType.AUDIO, bytes, mimeType, _uiState.value.expiry
            )
        }
    }

    fun onLocationAvailable(latitude: Double, longitude: Double) {
        send {
            chatRepository.sendLocation(conversationId, latitude, longitude, _uiState.value.expiry)
        }
    }

    fun onLocationFailed() {
        _uiState.update {
            it.copy(
                isProcessingMedia = false,
                errorMessage = "No se pudo obtener la ubicación"
            )
        }
    }

    /** El selector de archivos no devolvió bytes legibles. */
    fun onMediaPickFailed() {
        _uiState.update {
            it.copy(
                isProcessingMedia = false,
                errorMessage = "No se pudo leer el archivo seleccionado"
            )
        }
    }

    fun onLocationRequestStarted() {
        _uiState.update { it.copy(isProcessingMedia = true) }
    }

    private inline fun guardMediaSend(size: Int, crossinline block: suspend () -> Result<ChatMessage>) {
        if (_uiState.value.isSending) return
        if (size == 0) {
            _uiState.update { it.copy(errorMessage = "El archivo está vacío") }
            return
        }
        send(block)
    }

    private fun send(block: suspend () -> Result<ChatMessage>) {
        if (_uiState.value.isSending) return
        _uiState.update { it.copy(isSending = true, isProcessingMedia = true, errorMessage = null) }
        viewModelScope.launch {
            when (val result = block()) {
                is Result.Success -> _uiState.update { state ->
                    val exists = state.messages.any { it.id == result.data.id }
                    state.copy(
                        isSending = false,
                        isProcessingMedia = false,
                        draft = "",
                        messages = if (exists) state.messages else state.messages + result.data
                    )
                }
                is Result.Error -> _uiState.update {
                    it.copy(
                        isSending = false,
                        isProcessingMedia = false,
                        errorMessage = result.message ?: "No se pudo enviar el mensaje"
                    )
                }
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    // ------------------------------------------------------------------
    // Reporte / disputa
    // ------------------------------------------------------------------

    fun openReportDialog() {
        _uiState.update { it.copy(showReportDialog = true, reportReason = "") }
    }

    fun closeReportDialog() {
        _uiState.update { it.copy(showReportDialog = false, isReporting = false) }
    }

    fun onReportReasonChanged(reason: String) {
        _uiState.update { it.copy(reportReason = reason) }
    }

    /** Reporta la conversación: abre el ticket de disputa en el servidor. */
    fun submitReport() {
        val reason = _uiState.value.reportReason.trim()
        if (reason.isEmpty() || _uiState.value.isReporting) return
        _uiState.update { it.copy(isReporting = true) }
        viewModelScope.launch {
            when (val result = chatRepository.reportConversation(conversationId, reason)) {
                is Result.Success -> _uiState.update {
                    it.copy(
                        isReporting = false,
                        showReportDialog = false,
                        reportReason = "",
                        reportDoneBanner = true,
                        inDispute = true
                    )
                }
                is Result.Error -> _uiState.update {
                    it.copy(
                        isReporting = false,
                        errorMessage = result.message ?: "No se pudo crear la disputa"
                    )
                }
            }
        }
    }

    fun dismissReportBanner() {
        _uiState.update { it.copy(reportDoneBanner = false) }
    }

    private companion object {
        const val EXPIRY_SWEEP_MS = 15_000L
    }
}
