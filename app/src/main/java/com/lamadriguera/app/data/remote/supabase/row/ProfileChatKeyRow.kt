package com.lamadriguera.app.data.remote.supabase.row

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Proyección mínima de `profiles` para la clave pública de chat (bytea hex). */
@Serializable
data class ProfileChatKeyRow(
    @SerialName("user_id")
    val userId: String,
    @SerialName("chat_public_key")
    val chatPublicKey: String? = null
)
