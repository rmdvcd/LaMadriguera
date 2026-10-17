package com.lamadriguera.app.data.remote.supabase.row

import com.lamadriguera.app.domain.model.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** PostgREST wire format. bytea uses PostgreSQL hex; timestamps use ISO-8601. */
@Serializable
data class MessageRow(
    val id: String,
    @SerialName("conversation_id")
    val conversationId: String,
    @SerialName("sender_id")
    val senderId: String,
    val type: MessageType = MessageType.TEXT,
    @SerialName("content_ref")
    val contentRef: String,
    @SerialName("expires_at")
    val expiresAt: String? = null,
    val reported: Boolean = false,
    @SerialName("created_at")
    val createdAt: String
)
