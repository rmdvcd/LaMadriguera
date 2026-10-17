package com.lamadriguera.app.domain.model

import java.time.Instant
import java.math.BigDecimal

/** Domain model; money uses exact decimal arithmetic, dates use java.time. */
data class Bid(
    val id: String,
    val listingId: String,
    val bidderId: String,
    val amount: BigDecimal,
    val deviceHash: String? = null,
    val ipHash: String? = null,
    val status: BidStatus = BidStatus.VALID,
    val createdAt: Instant
)
