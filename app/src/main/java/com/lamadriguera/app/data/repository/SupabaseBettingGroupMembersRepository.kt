package com.lamadriguera.app.data.repository

import com.lamadriguera.app.data.mapper.toDomain
import com.lamadriguera.app.data.mapper.toRow
import com.lamadriguera.app.data.remote.supabase.row.BettingGroupMemberRow
import com.lamadriguera.app.domain.model.BettingGroupMember
import com.lamadriguera.app.domain.repository.BettingGroupMembersRepository
import io.github.jan.supabase.SupabaseClient
import javax.inject.Inject

/** Read-only membership table access; invites/removals use BettingRepository RPCs. */
class SupabaseBettingGroupMembersRepository @Inject constructor(client: SupabaseClient) :
    SupabaseTableRepository<BettingGroupMember, BettingGroupMemberRow>(
        client = client,
        table = "betting_group_members",
        primaryKey = "id",
        serializer = BettingGroupMemberRow.serializer(),
        toDomain = { it.toDomain() },
        toRow = { it.toRow() },
        insertColumns = emptySet(),
        updateColumns = emptySet()
    ), BettingGroupMembersRepository
