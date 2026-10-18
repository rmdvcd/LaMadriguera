package com.lamadriguera.app.data.remote.supabase.row

import com.lamadriguera.app.data.remote.supabase.serialization.DecimalSerializer
import com.lamadriguera.app.data.remote.supabase.serialization.NullableDecimalSerializer
import com.lamadriguera.app.domain.model.BetStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.math.BigDecimal

/** PostgREST wire format; odds are optional and remain exact PostgreSQL numeric values. */
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
    @Serializable(with = NullableDecimalSerializer::class)
    val odds: BigDecimal? = null,
    val status: BetStatus = BetStatus.OPEN,
    @SerialName("settled_by")
    val settledBy: String? = null,
    @SerialName("settled_at")
    val settledAt: String? = null,
    @SerialName("evidence_ref")
    val evidenceRef: String? = null,
    @SerialName("evidence_text")
    val evidenceText: String? = null,
    @SerialName("accepted_at")
    val acceptedAt: String? = null,
    @SerialName("decision_by")
    val decisionBy: String? = null,
    @SerialName("decision_note")
    val decisionNote: String? = null,
    @SerialName("created_at")
    val createdAt: String
)
