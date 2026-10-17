package com.lamadriguera.app.data.remote.supabase.row

import com.lamadriguera.app.domain.model.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.lamadriguera.app.data.remote.supabase.serialization.DecimalSerializer
import java.math.BigDecimal

/** PostgREST wire format. bytea uses PostgreSQL hex; timestamps use ISO-8601. */
@Serializable
data class PaymentRow(
    val id: String,
    @SerialName("user_id")
    val userId: String,
    @SerialName("product_type")
    val productType: ProductType,
    @SerialName("target_id")
    val targetId: String,
    @Serializable(with = DecimalSerializer::class)
    val amount: BigDecimal,
    val method: String = "transfermovil",
    @SerialName("proof_ref")
    val proofRef: String? = null,
    val code: String? = null,
    val status: PaymentStatus = PaymentStatus.PENDING,
    @SerialName("confirmed_by")
    val confirmedBy: String? = null,
    @SerialName("confirmed_at")
    val confirmedAt: String? = null
)
