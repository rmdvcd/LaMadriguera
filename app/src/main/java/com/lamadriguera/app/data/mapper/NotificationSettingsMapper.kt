package com.lamadriguera.app.data.mapper

import com.lamadriguera.app.data.remote.supabase.row.UserNotificationSettingsRow
import com.lamadriguera.app.domain.model.NotificationPreferences

/** `user_notification_settings` → dominio. */
fun UserNotificationSettingsRow.toDomain(): NotificationPreferences = NotificationPreferences(
    pushEnabled = pushEnabled,
    notifyOffers = notifyOffers,
    notifyMessages = notifyMessages,
    notifyPayments = notifyPayments,
    notifyAuctions = notifyAuctions,
    telegramEnabled = telegramEnabled,
    telegramChatId = telegramChatId
)
