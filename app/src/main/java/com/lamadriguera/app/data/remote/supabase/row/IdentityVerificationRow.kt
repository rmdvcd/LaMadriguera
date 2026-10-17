package com.lamadriguera.app.data.remote.supabase.row

import com.lamadriguera.app.domain.model.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** PostgREST wire format. bytea uses PostgreSQL hex; timestamps use ISO-8601. */
@Serializable
data class IdentityVerificationRow(
    val id: String,
    @SerialName("user_id")
    val userId: String,
    @SerialName("doc_type")
    val docType: String,
    @SerialName("doc_ref")
    val docRef: String,
    @SerialName("full_name")
    val fullName: String,
    val birthdate: String,
    val status: IdentityVerificationStatus = IdentityVerificationStatus.PENDING,
    @SerialName("verified_by")
    val verifiedBy: String? = null,
    @SerialName("verified_at")
    val verifiedAt: String? = null,
    @SerialName("retention_until")
    val retentionUntil: String
)
