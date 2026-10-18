package com.lamadriguera.app.data.remote.supabase.row

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** PostgREST wire format de `user_notifications` (avisos del sistema de disputas). */
@Serializable
data class UserNotificationRow(
    val id: String,
    @SerialName("user_id")
    val userId: String,
    val type: String,
    val title: String,
    val body: String,
    @SerialName("read_at")
    val readAt: String? = null,
    @SerialName("created_at")
    val createdAt: String
)
