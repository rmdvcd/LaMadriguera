package com.lamadriguera.app.data.remote.supabase.row

import com.lamadriguera.app.domain.model.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** PostgREST wire format. bytea uses PostgreSQL hex; timestamps use ISO-8601. */
@Serializable
data class ModerationActionRow(
    val id: String,
    @SerialName("target_type")
    val targetType: String,
    @SerialName("target_id")
    val targetId: String,
    val source: ModerationSource,
    val category: String,
    val severity: Short,
    val action: String,
    val reason: String,
    @SerialName("actor_id")
    val actorId: String? = null,
    @SerialName("created_at")
    val createdAt: String,
    val evidence: JsonObject? = null
)
