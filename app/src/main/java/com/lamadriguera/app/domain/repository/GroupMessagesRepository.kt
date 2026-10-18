package com.lamadriguera.app.domain.repository

import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.domain.model.GroupMessage

/**
 * Muro de mensajes de un grupo de apuestas. El texto se modera en cliente antes
 * de publicar (ModerateContentUseCase); la escritura va por RPC `post_group_message`.
 */
interface GroupMessagesRepository {
    suspend fun listForGroup(groupId: String, limit: Int = 100): Result<List<GroupMessage>>
    suspend fun post(groupId: String, body: String): Result<GroupMessage>
}
