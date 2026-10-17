package com.lamadriguera.app.domain.repository

import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.domain.model.Auction
import com.lamadriguera.app.domain.model.AuctionConfig
import com.lamadriguera.app.domain.model.Bid
import com.lamadriguera.app.domain.model.Listing
import java.math.BigDecimal

/**
 * Contrato de subastas: lectura de estado en tiempo real/bajo demanda,
 * pujas con validaciones de seguridad, historial de pujas y cierre de subastas.
 */
interface AuctionRepository {

    suspend fun getAuctions(): Result<List<Auction>>

    suspend fun getAuction(auctionId: String): Result<Auction>

    suspend fun placeBid(
        listingId: String,
        amount: BigDecimal,
        deviceHash: String? = null
    ): Result<Bid>

    suspend fun placeBid(auctionId: String, amount: Double): Result<Auction>

    /**
     * Devuelve el historial de pujas de una publicación si `visible_bids = 'all'`
     * (o si el usuario autenticado es el dueño).
     */
    suspend fun getBidHistory(listingId: String): Result<List<Bid>>

    /**
     * Devuelve la cantidad de pujas que el usuario actual ya realizó en esta subasta.
     */
    suspend fun countUserBids(listingId: String, userId: String): Result<Int>

    /**
     * Verifica si el usuario tiene alguna disputa abierta (conversaciones o apuestas).
     */
    suspend fun hasOpenDispute(userId: String): Result<Boolean>

    /**
     * Actualiza la configuración de subasta de un listing (solo el owner y sin pujas).
     */
    suspend fun updateAuctionConfig(listingId: String, config: AuctionConfig): Result<Listing>

    /**
     * Ejecuta el cierre y adjudicación de una subasta vencida (`close_auction` RPC).
     */
    suspend fun closeAuction(listingId: String): Result<Listing>

    /**
     * Ejecuta el barrido de subastas vencidas (`close_due_auctions` RPC).
     */
    suspend fun closeDueAuctions(): Result<List<Listing>>
}
