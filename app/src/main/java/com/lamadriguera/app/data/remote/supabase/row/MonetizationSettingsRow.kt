package com.lamadriguera.app.data.remote.supabase.row

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MonetizationSettingsRow(
    val id: Int = 1,
    @SerialName("transfer_account") val transferAccount: String? = null,
    @SerialName("account_holder") val accountHolder: String? = null,
    @SerialName("payment_window_hours") val paymentWindowHours: Int = 24,
    @SerialName("updated_at") val updatedAt: String = "1970-01-01T00:00:00Z",
    @SerialName("updated_by") val updatedBy: String? = null
)
