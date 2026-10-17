package com.lamadriguera.app.domain.model

import java.time.Instant
import java.math.BigDecimal
import kotlinx.serialization.json.JsonObject

/** Domain model; money uses exact decimal arithmetic, dates use java.time. */
data class Listing(
    val id: String,
    val ownerId: String,
    val kind: ListingKind = ListingKind.OFFER,
    val title: String,
    val description: String,
    val categoryId: String,
    val price: BigDecimal,
    val currency: String = "CUP",
    val priceNegotiable: Boolean = false,
    val municipalityId: Int? = null,
    val radiusKm: Int = 10,
    val privacyLevel: PrivacyLevel = PrivacyLevel.PUBLIC,
    val saleMode: SaleMode = SaleMode.FIXED,
    val auctionConfig: JsonObject? = null,
    val status: ListingStatus = ListingStatus.ACTIVE,
    val boostedUntil: Instant? = null,
    val createdAt: Instant
)
