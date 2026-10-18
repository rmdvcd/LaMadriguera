package com.lamadriguera.app.data.remote.supabase.row

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Inmutable, auditable event in a bet's complete history. */
@Serializable
data class BetEventRow(
    val id: String,
    @SerialName("bet_id")
    val betId: String,
    @SerialName("actor_id")
    val actorId: String,
    @SerialName("event_type")
    val eventType: String,
    val outcome: String? = null,
    @SerialName("evidence_text")
    val evidenceText: String? = null,
    @SerialName("evidence_ref")
    val evidenceRef: String? = null,
    @SerialName("decision_note")
    val decisionNote: String? = null,
    @SerialName("created_at")
    val createdAt: String
)
