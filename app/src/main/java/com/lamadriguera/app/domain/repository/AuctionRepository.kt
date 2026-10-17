package com.lamadriguera.app.domain.repository

import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.domain.model.Auction

/**
 * Contrato de subastas. La validación temporal de las pujas vive en los casos de uso.
 */
interface AuctionRepository {

    suspend fun getAuctions(): Result<List<Auction>>

    suspend fun getAuction(auctionId: String): Result<Auction>

    suspend fun placeBid(auctionId: String, amount: Double): Result<Auction>
}
