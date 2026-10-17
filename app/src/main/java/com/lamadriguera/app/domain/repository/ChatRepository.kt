package com.lamadriguera.app.domain.repository

import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.domain.model.Conversation
import com.lamadriguera.app.domain.model.Message
import kotlinx.coroutines.flow.Flow

/**
 * Contrato del chat. Implementado en `data/repository` sobre Postgrest + Realtime.
 */
interface ChatRepository {

    suspend fun getConversations(): Result<List<Conversation>>

    suspend fun getMessages(conversationId: String, limit: Int = 50): Result<List<Message>>

    suspend fun sendMessage(conversationId: String, content: String): Result<Message>

    /** Mensajes nuevos en tiempo real para una conversación. */
    fun observeMessages(conversationId: String): Flow<Message>
}
