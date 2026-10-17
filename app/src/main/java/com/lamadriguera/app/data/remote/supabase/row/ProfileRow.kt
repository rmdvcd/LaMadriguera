package com.lamadriguera.app.data.remote.supabase.row

import com.lamadriguera.app.domain.model.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.lamadriguera.app.data.remote.supabase.serialization.DecimalSerializer
import java.math.BigDecimal

/** PostgREST wire format. bytea uses PostgreSQL hex; timestamps use ISO-8601. */
@Serializable
data class ProfileRow(
    @SerialName("user_id")
    val userId: String,
    @SerialName("display_name")
    val displayName: String,
    val alias: String? = null,
    @SerialName("avatar_url")
    val avatarUrl: String? = null,
    @SerialName("municipality_id")
    val municipalityId: Int? = null,
    @SerialName("privacy_level")
    val privacyLevel: PrivacyLevel = PrivacyLevel.PUBLIC,
    val bio: String? = null,
    @SerialName("reputation_avg")
    @Serializable(with = DecimalSerializer::class)
    val reputationAvg: BigDecimal = BigDecimal.ZERO,
    @SerialName("reviews_count")
    val reviewsCount: Int = 0,
    @SerialName("default_reveal_policy")
    val defaultRevealPolicy: RevealPolicy = RevealPolicy.NEVER,
    /** Clave pública X25519 de chat (bytea hex). La usa el diseño C del chat. */
    @SerialName("chat_public_key")
    val chatPublicKey: String? = null
)
