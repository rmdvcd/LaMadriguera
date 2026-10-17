package com.lamadriguera.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Auction(
    val id: String,
    val listingId: String,
    val startingPrice: Double,
    val currentBid: Double? = null,
    val currentBidderId: String? = null,
    val endsAt: String,
    val status: AuctionStatus = AuctionStatus.OPEN
)

enum class AuctionStatus { OPEN, CLOSED, CANCELLED }
