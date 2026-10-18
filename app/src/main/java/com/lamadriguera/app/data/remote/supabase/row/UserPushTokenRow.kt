package com.lamadriguera.app.data.remote.supabase.row

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** PostgREST wire format de `user_push_tokens` (registro de dispositivos FCM). */
@Serializable
data class UserPushTokenRow(
    val id: String? = null,
    @SerialName("user_id") val userId: String,
    val token: String,
    val platform: String = "android",
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null
)
