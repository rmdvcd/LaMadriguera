package com.lamadriguera.app.feature.notifications

import android.content.Context
import com.lamadriguera.app.core.push.PushSupport
import com.lamadriguera.app.core.result.getOrNull
import com.lamadriguera.app.domain.model.AuthSessionState
import com.lamadriguera.app.domain.repository.AuthRepository
import com.lamadriguera.app.domain.repository.NotificationsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Mantiene `user_push_tokens` coherente con la sesión:
 * - login/sesión restaurada → registra el token FCM del dispositivo;
 * - logout / sesión perdida → lo elimina (sin token no hay push);
 * - rotación de token por FCM → re-registro.
 *
 * Sin Firebase configurado o sin Play Services, cada método es un no-op: el
 * push es opcional por diseño (distribución fuera de Google Play).
 */
@Singleton
class PushRegistrationManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val authRepository: AuthRepository,
    private val notificationsRepository: NotificationsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastSyncedToken: String? = null

    fun start() {
        scope.launch {
            authRepository.sessionState.collect { state ->
                when (state) {
                    is AuthSessionState.Authenticated -> syncToken()
                    AuthSessionState.Unauthenticated -> clearToken()
                    else -> Unit // Initializing/Transient error: esperar.
                }
            }
        }
    }

    /** FCM avisó de un token nuevo. */
    fun onTokenRefresh() {
        lastSyncedToken = null
        scope.launch { syncToken() }
    }

    /** Para la pantalla de ajustes: fuerza el registro y dice si hay push. */
    suspend fun syncNow(): String? = syncToken()

    private suspend fun syncToken(): String? {
        if (!PushSupport.isAvailable(appContext)) return null
        val token = notificationsRepository.registerPushToken().getOrNull()
        lastSyncedToken = token
        return token
    }

    private suspend fun clearToken() {
        if (lastSyncedToken == null && !PushSupport.isAvailable(appContext)) return
        lastSyncedToken = null
        notificationsRepository.unregisterPushToken()
    }
}
