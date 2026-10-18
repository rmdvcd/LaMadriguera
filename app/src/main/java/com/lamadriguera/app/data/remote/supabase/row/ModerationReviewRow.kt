package com.lamadriguera.app.data.remote.supabase.row

import com.lamadriguera.app.domain.model.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** PostgREST wire format. timestamps use ISO-8601; jsonb maps to JsonObject. */
@Serializable
data class ModerationReviewRow(
    val id: String,
    @SerialName("target_type")
    val targetType: String,
    @SerialName("target_id")
    val targetId: String,
    val source: ModerationSource,
    val category: String,
    val severity: Int,
    val status: String,
    val excerpt: String? = null,
    val evidence: JsonObject? = null,
    @SerialName("ai_provider")
    val aiProvider: String? = null,
    @SerialName("ai_scores")
    val aiScores: JsonObject? = null,
    @SerialName("report_id")
    val reportId: String? = null,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("handled_by")
    val handledBy: String? = null,
    @SerialName("handled_at")
    val handledAt: String? = null,
    val resolution: String? = null
)
