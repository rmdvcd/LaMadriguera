package com.lamadriguera.app.data.remote.supabase.row

import com.lamadriguera.app.domain.model.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** PostgREST wire format. bytea uses PostgreSQL hex; timestamps use ISO-8601. */
@Serializable
data class BettingGroupRow(
    val id: String,
    @SerialName("owner_id")
    val ownerId: String,
    val name: String,
    val topic: String? = null,
    val visibility: String = "private",
    val status: String = "active",
    @SerialName("created_at")
    val createdAt: String
)
