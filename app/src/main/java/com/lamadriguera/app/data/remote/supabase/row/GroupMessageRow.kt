package com.lamadriguera.app.data.remote.supabase.row

import com.lamadriguera.app.domain.model.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** PostgREST wire format. bytea uses PostgreSQL hex; timestamps use ISO-8601. */
@Serializable
data class GroupMessageRow(
    val id: String,
    @SerialName("group_id")
    val groupId: String,
    @SerialName("sender_id")
    val senderId: String,
    val body: String,
    @SerialName("hidden_at")
    val hiddenAt: String? = null,
    @SerialName("hidden_by")
    val hiddenBy: String? = null,
    @SerialName("hidden_reason")
    val hiddenReason: String? = null,
    @SerialName("created_at")
    val createdAt: String
)
