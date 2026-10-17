package com.lamadriguera.app.data.remote.supabase.row

import com.lamadriguera.app.domain.model.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** PostgREST wire format. bytea uses PostgreSQL hex; timestamps use ISO-8601. */
@Serializable
data class ReviewRow(
    val id: String,
    @SerialName("reviewer_id")
    val reviewerId: String,
    @SerialName("reviewee_id")
    val revieweeId: String,
    @SerialName("conversation_id")
    val conversationId: String,
    val rating: Short,
    val text: String? = null,
    val status: String = "published",
    @SerialName("created_at")
    val createdAt: String
)
