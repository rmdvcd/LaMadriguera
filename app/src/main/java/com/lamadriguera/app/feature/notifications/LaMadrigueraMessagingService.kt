package com.lamadriguera.app.feature.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.lamadriguera.app.R
import com.lamadriguera.app.core.push.PushSupport
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID
import javax.inject.Inject

/**
 * Entrada de push FCM (los 4 eventos del proyecto: oferta_recibida,
 * mensaje_nuevo, payment_confirmed y subasta_ganada, más el resto de
 * `user_notifications`).
 *
 * Con la app en segundo plano y payload de notificación, el sistema pinta el
 * aviso sin pasar por aquí; esto recibe los mensajes data-only y todo lo que
 * llega en primer plano. Si el dispositivo no tiene Play Services (o la build
 * no trae Firebase), este servicio jamás se invoca: el aviso entra por el
 * flujo Realtime de [NotificationsViewModel] (fallback in-app).
 */
@AndroidEntryPoint
class LaMadrigueraMessagingService : FirebaseMessagingService() {

    @Inject
    lateinit var pushRegistration: PushRegistrationManager

    override fun onNewToken(token: String) {
        // FCM rotó el token: re-registrar en `user_push_tokens`.
        pushRegistration.onTokenRefresh()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val title = data["title"] ?: message.notification?.title
            ?: getString(R.string.app_name)
        val body = data["body"] ?: message.notification?.body ?: return
        val notificationId = data["notification_id"] ?: UUID.randomUUID().toString()
        PushSupport.show(
            context = this,
            notificationId = notificationId,
            type = data["type"],
            title = title,
            body = body
        )
    }
}
