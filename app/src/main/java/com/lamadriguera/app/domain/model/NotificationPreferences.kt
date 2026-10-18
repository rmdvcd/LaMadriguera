package com.lamadriguera.app.domain.model

/**
 * Preferencias de notificación del usuario (tabla `user_notification_settings`).
 *
 * `pushEnabled` es el interruptor maestro del push FCM; los demás afinan por
 * tipo de evento. Telegram es ESTRICTAMENTE opcional: el bot solo escribe al
 * usuario si compartió su `telegramChatId` y además activó la casilla.
 */
data class NotificationPreferences(
    val pushEnabled: Boolean = true,
    val notifyOffers: Boolean = true,
    val notifyMessages: Boolean = true,
    val notifyPayments: Boolean = true,
    val notifyAuctions: Boolean = true,
    val telegramEnabled: Boolean = false,
    val telegramChatId: String? = null
) {
    /** `chat_id` de Telegram tal y como lo acepta la BD (solo dígitos con signo opcional). */
    val normalizedTelegramChatId: String?
        get() = telegramChatId?.trim()?.takeIf { it.matches(Regex("^-?[0-9]{1,20}$")) }

    companion object {
        /** true si el chat_id tiene formato válido de id numérico. */
        fun isValidTelegramChatId(value: String): Boolean =
            value.trim().matches(Regex("^-?[0-9]{1,20}$"))
    }
}

/** Tipos de aviso que generan push (coinciden con user_notifications.type en SQL). */
object NotificationTypes {
    const val OFFER = "oferta_recibida"
    const val MESSAGE = "mensaje_nuevo"
    const val PAYMENT_CONFIRMED = "payment_confirmed"
    const val PAYMENT_REJECTED = "payment_rejected"
    const val PAYMENT_EXPIRED = "payment_cancelled"
    const val AUCTION_WON = "subasta_ganada"
}
