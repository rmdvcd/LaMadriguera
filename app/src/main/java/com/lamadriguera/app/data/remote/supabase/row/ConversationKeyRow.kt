package com.lamadriguera.app.data.remote.supabase.row

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** PostgREST wire format de `conversation_keys`. `wrapped_key` es bytea hex. */
@Serializable
data class ConversationKeyRow(
    @SerialName("conversation_id")
    val conversationId: String,
    @SerialName("user_id")
    val userId: String,
    @SerialName("wrapped_key")
    val wrappedKey: String,
    @SerialName("created_at")
    val createdAt: String? = null
)
