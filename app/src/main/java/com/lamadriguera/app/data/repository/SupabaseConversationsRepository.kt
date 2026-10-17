package com.lamadriguera.app.data.repository

import com.lamadriguera.app.data.mapper.toDomain
import com.lamadriguera.app.data.mapper.toRow
import com.lamadriguera.app.data.remote.supabase.row.ConversationRow
import com.lamadriguera.app.domain.model.Conversation
import com.lamadriguera.app.domain.repository.ConversationsRepository
import io.github.jan.supabase.SupabaseClient
import javax.inject.Inject

class SupabaseConversationsRepository @Inject constructor(client: SupabaseClient) :
    SupabaseTableRepository<Conversation, ConversationRow>(
        client = client,
        table = "conversations",
        primaryKey = "id",
        serializer = ConversationRow.serializer(),
        toDomain = { it.toDomain() },
        toRow = { it.toRow() },
        insertColumns = setOf(
            "id", "listing_id", "participant_a", "participant_b", "reveal_policy",
            "session_key_wrapped", "dispute_key_split_a", "dispute_key_split_b"
        ),
        updateColumns = setOf(
            "reveal_policy", "session_key_wrapped", "dispute_key_split_a", "dispute_key_split_b"
        )
    ), ConversationsRepository
