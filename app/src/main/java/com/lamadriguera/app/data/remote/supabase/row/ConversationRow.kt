package com.lamadriguera.app.data.remote.supabase.row

import com.lamadriguera.app.domain.model.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** PostgREST wire format. bytea uses PostgreSQL hex; timestamps use ISO-8601. */
@Serializable
data class ConversationRow(
    val id: String,
    @SerialName("listing_id")
    val listingId: String,
    @SerialName("participant_a")
    val participantA: String,
    @SerialName("participant_b")
    val participantB: String,
    @SerialName("reveal_policy")
    val revealPolicy: RevealPolicy = RevealPolicy.NEVER,
    @SerialName("session_key_wrapped")
    val sessionKeyWrapped: String? = null,
    @SerialName("dispute_key_split_a")
    val disputeKeySplitA: String? = null,
    @SerialName("dispute_key_split_b")
    val disputeKeySplitB: String? = null,
    val status: String = "active"
)
