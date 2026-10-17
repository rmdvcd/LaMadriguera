package com.lamadriguera.app.domain.repository

import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.domain.model.ChatMessage
import com.lamadriguera.app.domain.model.ChatNotification
import com.lamadriguera.app.domain.model.ConversationSummary
import com.lamadriguera.app.domain.model.DisputeTicket
import com.lamadriguera.app.domain.model.MessageExpiry
import com.lamadriguera.app.domain.model.MessageType
import kotlinx.coroutines.flow.Flow

/**
 * Contrato del chat cifrado (diseño C). Implementado en
 * `data/repository/SupabaseChatRepository` sobre PostgREST + Realtime.
 *
 * Reglas cumplidas aquí:
 * - La `session_key` AES-256-GCM se genera en cliente por conversación; el
 *   servidor solo ve `content_ref` como ciphertext y `session_key_wrapped`.
 * - Los mensajes devueltos vienen descifrados en memoria; quien no tenga la
 *   clave ve [com.lamadriguera.app.domain.model.MessageContent.Undecryptable].
 * - La app nunca usa las mitades de la clave privada de disputa: solo el
 *   proceso backend de disputa.
 */
interface ChatRepository {

    companion object {
        /** Límite de adjuntos: 1 MB (imagen comprimida o audio). */
        const val MAX_MEDIA_BYTES = 1024 * 1024
    }

    /** Publica/renueva en el perfil la clave pública X25519 de chat del dispositivo. */
    suspend fun ensureChatIdentity(): Result<Unit>

    /** Hilos activos con último mensaje (descifrado solo en memoria) y badge. */
    suspend fun getConversations(): Result<List<ConversationSummary>>

    /** Busca o crea la conversación 1:1 de un anuncio y deja su crypto lista. */
    suspend fun getOrCreateConversation(listingId: String, otherUserId: String): Result<ConversationSummary>

    /** Historial descifrado del hilo (los mensajes expirados llegan como [MessageContent.Expired]). */
    suspend fun getMessages(conversationId: String, limit: Int = 100): Result<List<ChatMessage>>

    suspend fun sendText(
        conversationId: String,
        text: String,
        expiry: MessageExpiry = MessageExpiry.NEVER
    ): Result<ChatMessage>

    /** [type] debe ser MessageType.IMAGE o MessageType.AUDIO; máx. [MAX_MEDIA_BYTES] en claro. */
    suspend fun sendMedia(
        conversationId: String,
        type: MessageType,
        bytes: ByteArray,
        mimeType: String,
        expiry: MessageExpiry = MessageExpiry.NEVER
    ): Result<ChatMessage>

    suspend fun sendLocation(
        conversationId: String,
        latitude: Double,
        longitude: Double,
        expiry: MessageExpiry = MessageExpiry.NEVER
    ): Result<ChatMessage>

    /** Actualiza la marca de lectura local (badge de no leídos). */
    suspend fun markRead(conversationId: String): Result<Unit>

    /**
     * Reporta la conversación: abre ticket de disputa (`dispute_tickets`),
     * marca el hilo como `disputed` y deja rastro en `reports`.
     * A partir de ahí, un proceso backend con ambas mitades de custodia podrá
     * descifrar SOLO esta conversación y lo registrará en moderation_actions.
     */
    suspend fun reportConversation(conversationId: String, reason: String): Result<DisputeTicket>

    /** Notificaciones del usuario (p. ej. "tu hilo fue descifrado por un moderador"). */
    suspend fun getUnreadNotifications(): Result<List<ChatNotification>>

    suspend fun markNotificationsRead(notificationIds: List<String>): Result<Unit>

    /** Mensajes nuevos en tiempo real para una conversación (Realtime). */
    fun observeMessages(conversationId: String): Flow<ChatMessage>
}
