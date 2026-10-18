package com.lamadriguera.app.data.remote.supabase.row

import com.lamadriguera.app.data.remote.supabase.serialization.DecimalSerializer
import com.lamadriguera.app.domain.model.PaymentStatus
import com.lamadriguera.app.domain.model.ProductType
import java.math.BigDecimal
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PaymentReviewQueueRow(
    @SerialName("payment_id") val paymentId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("product_type") val productType: ProductType,
    @SerialName("target_id") val targetId: String,
    @Serializable(with = DecimalSerializer::class) val amount: BigDecimal,
    val currency: String,
    val code: String,
    val status: PaymentStatus,
    @SerialName("product_code") val productCode: String,
    @SerialName("product_tier") val productTier: String,
    @SerialName("proof_ref") val proofRef: String,
    @SerialName("receipt_sha256") val receiptSha256: String,
    @SerialName("transfer_last4") val transferLast4: String,
    @SerialName("transfer_at") val transferAt: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("submitted_at") val submittedAt: String,
    @SerialName("product_title") val productTitle: String,
    @SerialName("listing_title") val listingTitle: String,
    @SerialName("owner_name") val ownerName: String,
    @SerialName("owner_phone") val ownerPhone: String? = null
)
