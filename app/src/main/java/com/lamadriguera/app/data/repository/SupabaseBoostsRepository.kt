package com.lamadriguera.app.data.repository

import com.lamadriguera.app.data.mapper.toDomain
import com.lamadriguera.app.data.mapper.toRow
import com.lamadriguera.app.data.remote.supabase.row.BoostRow
import com.lamadriguera.app.domain.model.Boost
import com.lamadriguera.app.domain.repository.BoostsRepository
import io.github.jan.supabase.SupabaseClient
import javax.inject.Inject

class SupabaseBoostsRepository @Inject constructor(client: SupabaseClient) :
    SupabaseTableRepository<Boost, BoostRow>(
        client = client,
        table = "boosts",
        primaryKey = "id",
        serializer = BoostRow.serializer(),
        toDomain = { it.toDomain() },
        toRow = { it.toRow() },
        insertColumns = setOf("id", "listing_id", "tier", "starts_at", "ends_at", "payment_id", "product_type", "reach_scope"),
        updateColumns = emptySet()
    ), BoostsRepository
