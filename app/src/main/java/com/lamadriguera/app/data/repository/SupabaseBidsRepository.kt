package com.lamadriguera.app.data.repository

import com.lamadriguera.app.data.mapper.toDomain
import com.lamadriguera.app.data.mapper.toRow
import com.lamadriguera.app.data.remote.supabase.row.BidRow
import com.lamadriguera.app.domain.model.Bid
import com.lamadriguera.app.domain.repository.BidsRepository
import io.github.jan.supabase.SupabaseClient
import javax.inject.Inject

class SupabaseBidsRepository @Inject constructor(client: SupabaseClient) :
    SupabaseTableRepository<Bid, BidRow>(
        client = client,
        table = "bids",
        primaryKey = "id",
        serializer = BidRow.serializer(),
        toDomain = { it.toDomain() },
        toRow = { it.toRow() },
        insertColumns = setOf("id", "listing_id", "bidder_id", "amount", "device_hash", "ip_hash"),
        updateColumns = emptySet()
    ), BidsRepository
