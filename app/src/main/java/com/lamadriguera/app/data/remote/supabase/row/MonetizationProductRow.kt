package com.lamadriguera.app.data.remote.supabase.row

import com.lamadriguera.app.data.remote.supabase.serialization.DecimalSerializer
import com.lamadriguera.app.domain.model.ProductType
import java.math.BigDecimal
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MonetizationProductRow(
    val code: String,
    @SerialName("product_type") val productType: ProductType,
    val tier: String,
    val title: String,
    val description: String,
    @Serializable(with = DecimalSerializer::class) val amount: BigDecimal,
    val currency: String = "CUP",
    @SerialName("duration_hours") val durationHours: Int,
    @SerialName("reach_scope") val reachScope: String? = null,
    @SerialName("requires_municipality") val requiresMunicipality: Boolean = false,
    @SerialName("is_active") val isActive: Boolean = false,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("updated_at") val updatedAt: String = "1970-01-01T00:00:00Z",
    @SerialName("updated_by") val updatedBy: String? = null
)
