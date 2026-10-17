package com.lamadriguera.app.data.repository

import com.lamadriguera.app.data.mapper.toDomain
import com.lamadriguera.app.data.mapper.toRow
import com.lamadriguera.app.data.remote.supabase.row.ModerationActionRow
import com.lamadriguera.app.domain.model.ModerationAction
import com.lamadriguera.app.domain.repository.ModerationActionsRepository
import io.github.jan.supabase.SupabaseClient
import javax.inject.Inject

class SupabaseModerationActionsRepository @Inject constructor(client: SupabaseClient) :
    SupabaseTableRepository<ModerationAction, ModerationActionRow>(
        client = client,
        table = "moderation_actions",
        primaryKey = "id",
        serializer = ModerationActionRow.serializer(),
        toDomain = { it.toDomain() },
        toRow = { it.toRow() },
        insertColumns = setOf(
            "id", "target_type", "target_id", "source", "category", "severity", "action", "reason",
            "actor_id"
        ),
        updateColumns = emptySet()
    ), ModerationActionsRepository
