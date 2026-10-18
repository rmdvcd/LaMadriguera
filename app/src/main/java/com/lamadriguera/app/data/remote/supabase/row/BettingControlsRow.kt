package com.lamadriguera.app.data.remote.supabase.row

import com.lamadriguera.app.data.remote.supabase.serialization.DecimalSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.math.BigDecimal

/** Per-user wager cap and self-exclusion state. */
@Serializable
data class BettingControlsRow(
    @SerialName("user_id")
    val userId: String,
    @SerialName("daily_limit_cup")
    @Serializable(with = DecimalSerializer::class)
    val dailyLimitCup: BigDecimal,
    @SerialName("self_excluded_until")
    val selfExcludedUntil: String? = null,
    @SerialName("updated_at")
    val updatedAt: String
)
