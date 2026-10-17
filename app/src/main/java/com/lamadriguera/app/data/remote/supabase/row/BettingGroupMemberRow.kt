package com.lamadriguera.app.data.remote.supabase.row

import com.lamadriguera.app.domain.model.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** PostgREST wire format. bytea uses PostgreSQL hex; timestamps use ISO-8601. */
@Serializable
data class BettingGroupMemberRow(
    val id: String,
    @SerialName("group_id")
    val groupId: String,
    @SerialName("user_id")
    val userId: String,
    @SerialName("created_at")
    val createdAt: String
)
