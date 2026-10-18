package com.lamadriguera.app.data.remote.supabase.row

import com.lamadriguera.app.domain.model.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** PostgREST wire format. bytea uses PostgreSQL hex; timestamps use ISO-8601. */
@Serializable
data class ReportRow(
    val id: String,
    @SerialName("reporter_id")
    val reporterId: String,
    @SerialName("target_type")
    val targetType: String,
    @SerialName("target_id")
    val targetId: String,
    val reason: String,
    @SerialName("evidence_url")
    val evidenceUrl: String? = null,
    val status: String = "open",
    @SerialName("handled_by")
    val handledBy: String? = null,
    @SerialName("created_at")
    val createdAt: String,
    val category: String = "other",
    val severity: Int = 2
)
