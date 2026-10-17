package com.lamadriguera.app.data.repository

import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.data.mapper.toDomain
import com.lamadriguera.app.data.mapper.toRow
import com.lamadriguera.app.data.remote.supabase.SupabaseJson
import com.lamadriguera.app.data.remote.supabase.row.ListingRow
import com.lamadriguera.app.domain.model.Listing
import com.lamadriguera.app.domain.repository.ListingsRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import javax.inject.Inject
import kotlinx.serialization.json.jsonArray

class SupabaseListingsRepository @Inject constructor(
    private val client: SupabaseClient
) :
    SupabaseTableRepository<Listing, ListingRow>(
        client = client,
        table = "listings",
        primaryKey = "id",
        serializer = ListingRow.serializer(),
        toDomain = { it.toDomain() },
        toRow = { it.toRow() },
        insertColumns = setOf(
            "id", "owner_id", "kind", "title", "description", "category_id", "price", "currency",
            "price_negotiable", "municipality_id", "radius_km", "privacy_level", "sale_mode",
            "auction_config", "status"
        ),
        updateColumns = setOf(
            "kind", "title", "description", "category_id", "price", "currency", "price_negotiable",
            "municipality_id", "radius_km", "privacy_level", "sale_mode", "auction_config", "status"
        )
    ), ListingsRepository {

    override suspend fun hasPublished(ownerId: String): Result<Boolean> = request {
        require(ownerId.isNotBlank()) { "Empty owner" }
        val data = client.from("listings").select {
            filter { eq("owner_id", ownerId) }
            range(0, 0)
        }.data
        SupabaseJson.parseToJsonElement(data).jsonArray.isNotEmpty()
    }
}
