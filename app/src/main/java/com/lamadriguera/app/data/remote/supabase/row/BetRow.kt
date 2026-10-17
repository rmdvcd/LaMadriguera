package com.lamadriguera.app.data.remote.supabase.row

import com.lamadriguera.app.domain.model.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.lamadriguera.app.data.remote.supabase.serialization.DecimalSerializer
import java.math.BigDecimal

/** PostgREST wire format. bytea uses PostgreSQL hex; timestamps use ISO-8601. */
@Serializable
data class BetRow(
    val id: String,
    @SerialName("group_id")
    val groupId: String,
    @SerialName("creator_id")
    val creatorId: String,
    @SerialName("acceptor_id")
    val acceptorId: String? = null,
    val description: String,
    @SerialName("stake_amount")
    @Serializable(with = DecimalSerializer::class)
    val stakeAmount: BigDecimal,
    @SerialName("stake_currency")
    val stakeCurrency: String = "CUP",
    @Serializable(with = DecimalSerializer::class)
    val odds: BigDecimal = BigDecimal.ONE,
    val status: BetStatus = BetStatus.OPEN,
    @SerialName("settled_by")
    val settledBy: String? = null,
    @SerialName("settled_at")
    val settledAt: String? = null,
    @SerialName("evidence_url")
    val evidenceUrl: String? = null,
    @SerialName("created_at")
    val createdAt: String
)
