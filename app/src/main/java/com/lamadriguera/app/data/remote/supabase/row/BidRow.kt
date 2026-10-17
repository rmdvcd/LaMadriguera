package com.lamadriguera.app.data.remote.supabase.row

import com.lamadriguera.app.domain.model.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.lamadriguera.app.data.remote.supabase.serialization.DecimalSerializer
import java.math.BigDecimal

/** PostgREST wire format. bytea uses PostgreSQL hex; timestamps use ISO-8601. */
@Serializable
data class BidRow(
    val id: String,
    @SerialName("listing_id")
    val listingId: String,
    @SerialName("bidder_id")
    val bidderId: String,
    @Serializable(with = DecimalSerializer::class)
    val amount: BigDecimal,
    @SerialName("device_hash")
    val deviceHash: String? = null,
    @SerialName("ip_hash")
    val ipHash: String? = null,
    val status: BidStatus = BidStatus.VALID,
    @SerialName("created_at")
    val createdAt: String
)
