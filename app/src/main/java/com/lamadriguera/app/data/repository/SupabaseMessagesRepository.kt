package com.lamadriguera.app.data.repository

import com.lamadriguera.app.data.mapper.toDomain
import com.lamadriguera.app.data.mapper.toRow
import com.lamadriguera.app.data.remote.supabase.row.MessageRow
import com.lamadriguera.app.domain.model.Message
import com.lamadriguera.app.domain.repository.MessagesRepository
import io.github.jan.supabase.SupabaseClient
import javax.inject.Inject

class SupabaseMessagesRepository @Inject constructor(client: SupabaseClient) :
    SupabaseTableRepository<Message, MessageRow>(
        client = client,
        table = "messages",
        primaryKey = "id",
        serializer = MessageRow.serializer(),
        toDomain = { it.toDomain() },
        toRow = { it.toRow() },
        insertColumns = setOf("id", "conversation_id", "sender_id", "type", "content_ref", "expires_at"),
        updateColumns = emptySet()
    ), MessagesRepository
