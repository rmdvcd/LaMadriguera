package com.lamadriguera.app.domain.repository

import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.domain.model.Listing

/**
 * Contrato del marketplace. Implementado en `data/repository` sobre Postgrest + Storage.
 */
interface ListingsRepository {

    suspend fun getListings(query: String? = null, page: Int = 0): Result<List<Listing>>

    suspend fun getListing(listingId: String): Result<Listing>

    suspend fun createListing(listing: Listing): Result<Listing>

    suspend fun updateListing(listing: Listing): Result<Listing>

    suspend fun deleteListing(listingId: String): Result<Unit>
}
