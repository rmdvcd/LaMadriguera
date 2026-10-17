package com.lamadriguera.app.data.repository

import com.lamadriguera.app.core.result.AppError
import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.core.result.map
import com.lamadriguera.app.data.mapper.toDomain
import com.lamadriguera.app.data.remote.supabase.SupabaseJson
import com.lamadriguera.app.data.remote.supabase.row.BetRow
import com.lamadriguera.app.data.remote.supabase.row.BidRow
import com.lamadriguera.app.data.remote.supabase.row.ConversationRow
import com.lamadriguera.app.data.remote.supabase.row.ListingRow
import com.lamadriguera.app.domain.model.Auction
import com.lamadriguera.app.domain.model.AuctionConfig
import com.lamadriguera.app.domain.model.BetStatus
import com.lamadriguera.app.domain.model.Bid
import com.lamadriguera.app.domain.model.BidStatus
import com.lamadriguera.app.domain.model.Listing
import com.lamadriguera.app.domain.model.SaleMode
import com.lamadriguera.app.domain.repository.AuctionRepository
import com.lamadriguera.app.domain.repository.ListingsRepository
import com.lamadriguera.app.domain.repository.UsersRepository
import com.lamadriguera.app.domain.usecase.AuctionConfigValidator
import com.lamadriguera.app.domain.usecase.AuctionStateMachine
import com.lamadriguera.app.domain.usecase.BidValidator
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import java.math.BigDecimal
import java.time.Instant
import javax.inject.Inject
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SupabaseAuctionRepository @Inject constructor(
    private val client: SupabaseClient,
    private val listingsRepository: ListingsRepository,
    private val usersRepository: UsersRepository
) : AuctionRepository {

    override suspend fun getAuctions(): Result<List<Auction>> = request {
        val raw = client.from("listings").select {
            filter { eq("sale_mode", "auction") }
            order("created_at", Order.DESCENDING)
            range(0, 99)
        }.data
        val listings = SupabaseJson.decodeFromString(
            ListSerializer(ListingRow.serializer()),
            raw
        ).map { it.toDomain() }

        val currentUserId = client.auth.currentUserOrNull()?.id
        listings.map { listing -> buildAuctionModel(listing, currentUserId) }
    }

    override suspend fun getAuction(auctionId: String): Result<Auction> {
        val listing = when (val found = listingsRepository.find(auctionId)) {
            is Result.Success -> found.data ?: return Result.Error(AppError.NotFound())
            is Result.Error -> return found
        }
        if (listing.saleMode != SaleMode.AUCTION) {
            return Result.Error(AppError.Validation(BidValidator.MSG_NOT_AUCTION))
        }
        val currentUserId = client.auth.currentUserOrNull()?.id
        return request { buildAuctionModel(listing, currentUserId) }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun placeBid(
        listingId: String,
        amount: BigDecimal,
        deviceHash: String?
    ): Result<Bid> {
        val userId = client.auth.currentUserOrNull()?.id
            ?: return Result.Error(AppError.Unauthorized())
        val user = when (val u = usersRepository.find(userId)) {
            is Result.Success -> u.data ?: return Result.Error(AppError.Unauthorized())
            is Result.Error -> return u
        }
        val listing = when (val l = listingsRepository.find(listingId)) {
            is Result.Success -> l.data ?: return Result.Error(AppError.NotFound())
            is Result.Error -> return l
        }
        val disputeOpen = when (val d = hasOpenDispute(userId)) {
            is Result.Success -> d.data
            is Result.Error -> false
        }
        val existingBids = fetchBidsForListing(listingId)

        BidValidator.validateBid(
            listing = listing,
            bidder = user,
            amount = amount,
            existingBids = existingBids,
            hasOpenDispute = disputeOpen
        )?.let { validationError ->
            return Result.Error(AppError.Validation(validationError))
        }

        return request {
            val params = buildJsonObject {
                put("p_listing_id", listingId)
                put("p_amount", JsonUnquotedLiteral(amount.toPlainString()))
                if (deviceHash != null) put("p_device_hash", deviceHash)
            }
            val data = client.postgrest.rpc("place_auction_bid", params).data
            SupabaseJson.decodeFromString(BidRow.serializer(), data).toDomain()
        }
    }

    override suspend fun placeBid(auctionId: String, amount: Double): Result<Auction> {
        val placed = placeBid(
            listingId = auctionId,
            amount = BigDecimal.valueOf(amount)
        )
        return when (placed) {
            is Result.Success -> getAuction(auctionId)
            is Result.Error -> placed
        }
    }

    override suspend fun getBidHistory(listingId: String): Result<List<Bid>> {
        val listing = when (val found = listingsRepository.find(listingId)) {
            is Result.Success -> found.data ?: return Result.Error(AppError.NotFound())
            is Result.Error -> return found
        }
        val config = listing.parsedAuctionConfig
        if (!AuctionConfigValidator.canShowBidHistory(config)) {
            return Result.Error(
                AppError.Unauthorized("El historial de pujas no es público en esta subasta")
            )
        }
        return request {
            fetchBidsForListing(listingId)
        }
    }

    override suspend fun countUserBids(listingId: String, userId: String): Result<Int> = request {
        require(listingId.isNotBlank() && userId.isNotBlank()) { "Invalid ids" }
        fetchBidsForListing(listingId).count { it.bidderId == userId }
    }

    override suspend fun hasOpenDispute(userId: String): Result<Boolean> = request {
        require(userId.isNotBlank()) { "Empty user id" }
        val convRaw = client.from("conversations").select {
            filter { eq("status", "disputed") }
        }.data
        val disputedConversations = SupabaseJson.decodeFromString(
            ListSerializer(ConversationRow.serializer()),
            convRaw
        ).any { it.participantA == userId || it.participantB == userId }

        if (disputedConversations) return@request true

        val betsRaw = client.from("bets").select {
            filter { eq("status", "disputed") }
        }.data
        SupabaseJson.decodeFromString(
            ListSerializer(BetRow.serializer()),
            betsRaw
        ).any {
            it.status == BetStatus.DISPUTED &&
                (it.creatorId == userId || it.acceptorId == userId)
        }
    }

    override suspend fun updateAuctionConfig(
        listingId: String,
        config: AuctionConfig
    ): Result<Listing> = listingsRepository.updateAuctionConfig(listingId, config)

    override suspend fun closeAuction(listingId: String): Result<Listing> = request {
        require(listingId.isNotBlank()) { "Empty listing id" }
        val params = buildJsonObject {
            put("p_listing_id", listingId)
        }
        val data = client.postgrest.rpc("close_auction", params).data
        SupabaseJson.decodeFromString(ListingRow.serializer(), data).toDomain()
    }

    override suspend fun closeDueAuctions(): Result<List<Listing>> = request {
        val data = client.postgrest.rpc("close_due_auctions").data
        SupabaseJson.decodeFromString(ListSerializer(ListingRow.serializer()), data)
            .map { it.toDomain() }
    }

    private suspend fun fetchBidsForListing(listingId: String): List<Bid> = runCatching {
        val raw = client.from("bids").select {
            filter { eq("listing_id", listingId) }
            order("amount", Order.DESCENDING)
            order("created_at", Order.ASCENDING)
        }.data
        SupabaseJson.decodeFromString(ListSerializer(BidRow.serializer()), raw)
            .map { it.toDomain() }
    }.getOrDefault(emptyList())

    private suspend fun buildAuctionModel(listing: Listing, currentUserId: String?): Auction {
        val config = listing.parsedAuctionConfig
        val bids = fetchBidsForListing(listing.id)
        val validBids = bids.filter {
            it.status == BidStatus.VALID || it.status == BidStatus.WINNING
        }
        val highestBid = validBids.maxByOrNull { it.amount }
        val userBidsCount = if (currentUserId != null) {
            bids.count { it.bidderId == currentUserId }
        } else {
            0
        }
        val state = AuctionStateMachine.resolveState(listing, Instant.now())
        return Auction(
            id = listing.id,
            listingId = listing.id,
            listing = listing,
            config = config,
            startingPrice = config?.startingPrice ?: listing.price,
            currentBid = highestBid?.amount,
            currentBidderId = highestBid?.bidderId,
            endsAt = config?.endsAt ?: listing.createdAt,
            state = state,
            bidsCount = validBids.size,
            userBidsCount = userBidsCount
        )
    }
}
