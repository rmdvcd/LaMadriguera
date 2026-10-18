package com.lamadriguera.app.data.remote.supabase.row

import com.lamadriguera.app.domain.model.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** PostgREST wire format. bytea uses PostgreSQL hex; timestamps use ISO-8601. */
@Serializable
data class UserRow(
    val id: String,
    val phone: String? = null,
    @SerialName("verification_level")
    val verificationLevel: VerificationLevel = VerificationLevel.NONE,
    val status: UserStatus = UserStatus.TEMP,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("suspended_until")
    val suspendedUntil: String? = null
)
