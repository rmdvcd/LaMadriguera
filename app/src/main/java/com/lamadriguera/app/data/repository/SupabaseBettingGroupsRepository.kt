package com.lamadriguera.app.data.repository

import com.lamadriguera.app.data.mapper.toDomain
import com.lamadriguera.app.data.mapper.toRow
import com.lamadriguera.app.data.remote.supabase.row.BettingGroupRow
import com.lamadriguera.app.domain.model.BettingGroup
import com.lamadriguera.app.domain.repository.BettingGroupsRepository
import io.github.jan.supabase.SupabaseClient
import javax.inject.Inject

class SupabaseBettingGroupsRepository @Inject constructor(client: SupabaseClient) :
    SupabaseTableRepository<BettingGroup, BettingGroupRow>(
        client = client,
        table = "betting_groups",
        primaryKey = "id",
        serializer = BettingGroupRow.serializer(),
        toDomain = { it.toDomain() },
        toRow = { it.toRow() },
        insertColumns = setOf("id", "owner_id", "name", "topic", "visibility"),
        updateColumns = setOf("name", "topic", "visibility")
    ), BettingGroupsRepository
