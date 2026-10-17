package com.lamadriguera.app.data.repository

import com.lamadriguera.app.data.mapper.toDomain
import com.lamadriguera.app.data.mapper.toRow
import com.lamadriguera.app.data.remote.supabase.row.BetRow
import com.lamadriguera.app.domain.model.Bet
import com.lamadriguera.app.domain.repository.BetsRepository
import io.github.jan.supabase.SupabaseClient
import javax.inject.Inject

class SupabaseBetsRepository @Inject constructor(client: SupabaseClient) :
    SupabaseTableRepository<Bet, BetRow>(
        client = client,
        table = "bets",
        primaryKey = "id",
        serializer = BetRow.serializer(),
        toDomain = { it.toDomain() },
        toRow = { it.toRow() },
        insertColumns = setOf(
            "id", "group_id", "creator_id", "description", "stake_amount", "stake_currency", "odds",
            "evidence_url"
        ),
        updateColumns = emptySet()
    ), BetsRepository
