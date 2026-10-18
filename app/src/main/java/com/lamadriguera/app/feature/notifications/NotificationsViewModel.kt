package com.lamadriguera.app.feature.notifications

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lamadriguera.app.core.push.PushSupport
import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.core.result.getOrNull
import com.lamadriguera.app.domain.model.ChatNotification
import com.lamadriguera.app.domain.model.NotificationPreferences
import com.lamadriguera.app.domain.repository.AuthRepository
import com.lamadriguera.app.domain.repository.NotificationsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Estado de la pantalla de notificaciones (ajustes + bandeja). */
data class NotificationsUiState(
    val isLoading: Boolean = true,
    /** Build con Firebase y Play Services: hay push real en este dispositivo. */
    val pushAvailable: Boolean = false,
    /** Permiso del sistema concedido (Android 13+) y notificaciones activas. */
    val systemPermissionGranted: Boolean = false,
    val preferences: NotificationPreferences = NotificationPreferences(),
    val inbox: List<ChatNotification> = emptyList(),
    val isSaving: Boolean = false,
    val savedAtMillis: Long? = null,
    val telegramIdError: String? = null,
    val errorMessage: String? = null
)

/**
 * Ajustes de notificación y bandeja in-app.
 *
 * El fallback in-app vive aquí: si el push no está disponible, las filas que
 * el servidor inserta en `user_notifications` se observan por Realtime y se
 * muestran como aviso del sistema + entrada de bandeja. Si hay push, Realtime
 * solo refresca la lista (FCM ya pintó el aviso).
 */
@HiltViewModel
class NotificationsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val authRepository: AuthRepository,
    private val notificationsRepository: NotificationsRepository,
    private val pushRegistration: PushRegistrationManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotificationsUiState())
    val uiState: StateFlow<NotificationsUiState> = _uiState.asStateFlow()

    private var realtimeJob: Job? = null

    init {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    pushAvailable = PushSupport.isAvailable(appContext),
                    systemPermissionGranted = PushSupport.notificationsAllowed(appContext)
                )
            }
            loadPreferences()
            loadInbox()
            val accountId = authRepository.currentSession().getOrNull()?.user?.id
            if (accountId != null) watchRealtime(accountId)
        }
    }

    /** Tras conceder/revocar el permiso del sistema, la pantalla refresca estado. */
    fun onPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(systemPermissionGranted = granted || PushSupport.notificationsAllowed(appContext)) }
        if (granted) viewModelScope.launch { pushRegistration.syncNow() }
    }

    fun updateMasterSwitch(enabled: Boolean) = updatePreferences { it.copy(pushEnabled = enabled) }
    fun updateOffersSwitch(enabled: Boolean) = updatePreferences { it.copy(notifyOffers = enabled) }
    fun updateMessagesSwitch(enabled: Boolean) = updatePreferences { it.copy(notifyMessages = enabled) }
    fun updatePaymentsSwitch(enabled: Boolean) = updatePreferences { it.copy(notifyPayments = enabled) }
    fun updateAuctionsSwitch(enabled: Boolean) = updatePreferences { it.copy(notifyAuctions = enabled) }
    fun updateTelegramSwitch(enabled: Boolean) = updatePreferences { it.copy(telegramEnabled = enabled) }
    fun onTelegramChatIdChanged(value: String) {
        _uiState.update {
            it.copy(
                preferences = it.preferences.copy(telegramChatId = value),
                telegramIdError = null
            )
        }
    }

    fun save() {
        val preferences = _uiState.value.preferences
        if (preferences.telegramEnabled &&
            (preferences.telegramChatId.isNullOrBlank() ||
                !NotificationPreferences.isValidTelegramChatId(preferences.telegramChatId))
        ) {
            _uiState.update { it.copy(telegramIdError = "Necesito un chat_id numérico para activar el bot de Telegram") }
            return
        }
        _uiState.update { it.copy(isSaving = true, telegramIdError = null) }
        viewModelScope.launch {
            val result = notificationsRepository.savePreferences(preferences)
            _uiState.update {
                when (result) {
                    is Result.Success -> it.copy(isSaving = false, savedAtMillis = System.currentTimeMillis())
                    is Result.Error -> it.copy(
                        isSaving = false,
                        errorMessage = result.message ?: "No se pudieron guardar tus ajustes"
                    )
                }
            }
            if (result is Result.Success) pushRegistration.syncNow()
        }
    }

    fun markAllRead() {
        val unread = _uiState.value.inbox.map { it.id }
        if (unread.isEmpty()) return
        viewModelScope.launch {
            notificationsRepository.markNotificationsRead(unread)
            loadInbox()
        }
    }

    private fun updatePreferences(transform: (NotificationPreferences) -> NotificationPreferences) {
        _uiState.update { it.copy(preferences = transform(it.preferences), savedAtMillis = null) }
    }

    private suspend fun loadPreferences() {
        when (val result = notificationsRepository.preferences()) {
            is Result.Success -> _uiState.update { it.copy(isLoading = false, preferences = result.data, errorMessage = null) }
            is Result.Error -> _uiState.update {
                it.copy(isLoading = false, errorMessage = result.message ?: "No se pudieron cargar tus ajustes")
            }
        }
    }

    private suspend fun loadInbox() {
        when (val result = notificationsRepository.unreadNotifications()) {
            is Result.Success -> _uiState.update { it.copy(inbox = result.data) }
            is Result.Error -> Unit // la bandeja no bloquea los ajustes
        }
    }

    /** Realtime sobre `user_notifications`: fallback in-app cuando no hay push. */
    private fun watchRealtime(userId: String) {
        realtimeJob?.cancel()
        realtimeJob = viewModelScope.launch {
            notificationsRepository.notificationEvents(userId).collect { notification ->
                _uiState.update { it.copy(inbox = listOf(notification) + it.inbox) }
                val pushDidIt = _uiState.value.pushAvailable && _uiState.value.systemPermissionGranted
                if (!pushDidIt) {
                    PushSupport.show(
                        context = appContext,
                        notificationId = notification.id,
                        type = notification.type,
                        title = notification.title,
                        body = notification.body
                    )
                }
            }
        }
    }

    override fun onCleared() {
        realtimeJob?.cancel()
        super.onCleared()
    }
}
