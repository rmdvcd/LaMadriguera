package com.lamadriguera.app.domain.repository

import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.domain.model.ChatNotification
import com.lamadriguera.app.domain.model.NotificationPreferences
import kotlinx.coroutines.flow.Flow

/**
 * Contrato de notificaciones: registro del token FCM del dispositivo,
 * preferencias del usuario, bandeja de entrada (`user_notifications`) y
 * flujo en tiempo real usado como fallback in-app cuando no hay push.
 *
 * Regla de distribución fuera de Play: el push es una MEJORA, nunca un
 * requisito. Si Firebase no está configurado o el dispositivo carece de
 * Google Play Services, todo sigue llegando por [notificationEvents].
 */
interface NotificationsRepository {

    /** Firebase Messaging accesible en este dispositivo (config + Play Services). */
    fun isPushCapable(): Boolean

    /**
     * Registra el token FCM actual de la cuenta iniciada.
     * Devuelve Success(null) cuando el push no está disponible (no es error).
     */
    suspend fun registerPushToken(): Result<String?>

    /** Quita el token del dispositivo (logout / desactivación total). */
    suspend fun unregisterPushToken(): Result<Unit>

    suspend fun preferences(): Result<NotificationPreferences>

    suspend fun savePreferences(preferences: NotificationPreferences): Result<Unit>

    /** Bandeja: últimas notificaciones sin leer (máx. 50). */
    suspend fun unreadNotifications(): Result<List<ChatNotification>>

    suspend fun markNotificationsRead(ids: List<String>): Result<Unit>

    /**
     * Fallback in-app: flujo vivo de `user_notifications` para la cuenta
     * actual (RLS ya filtra). Se usa cuando el push no está disponible y
     * para refrescar la bandeja mientras la app está en primer plano.
     */
    fun notificationEvents(userId: String): Flow<ChatNotification>
}
