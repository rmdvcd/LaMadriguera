package com.lamadriguera.app.data.remote.supabase.row

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * PostgREST wire format de `user_notification_settings`.
 * `telegramChatId` solo existe si el usuario lo comparte voluntariamente;
 * la BD exige que sea un id numérico y que telegram_enabled⇒chat_id.
 */
@Serializable
data class UserNotificationSettingsRow(
    @SerialName("user_id") val userId: String,
    @SerialName("push_enabled") val pushEnabled: Boolean = true,
    @SerialName("notify_offers") val notifyOffers: Boolean = true,
    @SerialName("notify_messages") val notifyMessages: Boolean = true,
    @SerialName("notify_payments") val notifyPayments: Boolean = true,
    @SerialName("notify_auctions") val notifyAuctions: Boolean = true,
    @SerialName("telegram_enabled") val telegramEnabled: Boolean = false,
    @SerialName("telegram_chat_id") val telegramChatId: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)
