package com.lamadriguera.app.data.remote.supabase.row

import com.lamadriguera.app.domain.model.ProductType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Promoción activa o histórica de una publicación. */
@Serializable
data class BoostRow(
    val id: String,
    @SerialName("listing_id") val listingId: String,
    val tier: String,
    @SerialName("starts_at") val startsAt: String,
    @SerialName("ends_at") val endsAt: String,
    @SerialName("payment_id") val paymentId: String,
    @SerialName("product_type") val productType: ProductType = ProductType.BOOST,
    @SerialName("reach_scope") val reachScope: String? = null
)
