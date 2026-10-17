package com.lamadriguera.app.data.remote.supabase.row

import com.lamadriguera.app.domain.model.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.lamadriguera.app.data.remote.supabase.serialization.DecimalSerializer
import java.math.BigDecimal
import kotlinx.serialization.json.JsonObject

/** PostgREST wire format. bytea uses PostgreSQL hex; timestamps use ISO-8601. */
@Serializable
data class ListingRow(
    val id: String,
    @SerialName("owner_id")
    val ownerId: String,
    val kind: ListingKind = ListingKind.OFFER,
    val title: String,
    val description: String,
    @SerialName("category_id")
    val categoryId: String,
    @Serializable(with = DecimalSerializer::class)
    val price: BigDecimal,
    val currency: String = "CUP",
    @SerialName("price_negotiable")
    val priceNegotiable: Boolean = false,
    @SerialName("municipality_id")
    val municipalityId: Int? = null,
    @SerialName("radius_km")
    val radiusKm: Int = 10,
    @SerialName("privacy_level")
    val privacyLevel: PrivacyLevel = PrivacyLevel.PUBLIC,
    @SerialName("sale_mode")
    val saleMode: SaleMode = SaleMode.FIXED,
    @SerialName("auction_config")
    val auctionConfig: JsonObject? = null,
    val status: ListingStatus = ListingStatus.ACTIVE,
    @SerialName("boosted_until")
    val boostedUntil: String? = null,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("photo_url")
    val photoUrl: String? = null,
    val visibility: ListingVisibility = ListingVisibility.PUBLIC
)
