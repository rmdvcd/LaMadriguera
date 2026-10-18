package com.lamadriguera.app.data.remote.supabase.row

import com.lamadriguera.app.data.remote.supabase.serialization.DecimalSerializer
import com.lamadriguera.app.data.remote.supabase.serialization.NullableDecimalSerializer
import com.lamadriguera.app.domain.model.ListingStatus
import com.lamadriguera.app.domain.model.PaymentStatus
import com.lamadriguera.app.domain.model.ProductType
import java.math.BigDecimal
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** PostgREST wire format de payments; la resolución manual solo la escribe el servidor. */
@Serializable
data class PaymentRow(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("product_type") val productType: ProductType,
    @SerialName("target_id") val targetId: String,
    @Serializable(with = DecimalSerializer::class) val amount: BigDecimal,
    val currency: String = "CUP",
    val method: String = "transfermovil",
    @SerialName("proof_ref") val proofRef: String? = null,
    val code: String? = null,
    val status: PaymentStatus = PaymentStatus.PENDING,
    @SerialName("product_code") val productCode: String? = null,
    @SerialName("product_tier") val productTier: String? = null,
    @SerialName("created_at") val createdAt: String = "1970-01-01T00:00:00Z",
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("transfer_last4") val transferLast4: String? = null,
    @SerialName("transfer_at") val transferAt: String? = null,
    @SerialName("submitted_at") val submittedAt: String? = null,
    @SerialName("receipt_sha256") val receiptSha256: String? = null,
    @SerialName("rejection_reason") val rejectionReason: String? = null,
    @SerialName("listing_status_before_review") val listingStatusBeforeReview: ListingStatus? = null,
    @SerialName("confirmed_by") val confirmedBy: String? = null,
    @SerialName("confirmed_at") val confirmedAt: String? = null,
    @SerialName("reviewed_by") val reviewedBy: String? = null,
    @SerialName("reviewed_at") val reviewedAt: String? = null,
    @Serializable(with = NullableDecimalSerializer::class)
    @SerialName("reconciliation_amount") val reconciliationAmount: BigDecimal? = null,
    @SerialName("reconciliation_transfer_at") val reconciliationTransferAt: String? = null,
    @SerialName("reconciliation_last4") val reconciliationLast4: String? = null,
    @SerialName("reconciliation_reference") val reconciliationReference: String? = null
)
