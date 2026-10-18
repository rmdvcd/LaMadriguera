package com.lamadriguera.app.core.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.lamadriguera.app.BuildConfig
import com.lamadriguera.app.R

/**
 * Soporte de push (Firebase Cloud Messaging) para la distribución fuera de
 * Google Play.
 *
 * Sin `google-services.json`: la configuración de Firebase viene de
 * BuildConfig (`FCM_PROJECT_ID` + `GOOGLE_API_KEY`, ver local.properties).
 * Si no están, NO se inicializa Firebase: la app sigue funcionando y los
 * avisos llegan por el fallback in-app (Realtime sobre `user_notifications`)
 * y, opcionalmente, por el bot de Telegram.
 *
 * El proveedor `FirebaseInitProvider` está quitado del manifest; por eso la
 * inicialización ocurre aquí, de forma idempotente.
 */
object PushSupport {

    // Canales de sistema (uno por familia de avisos; el backend los referencia
    // con el mismo id en `android.notification.channel_id`).
    const val CHANNEL_GENERAL = "madriguera_general"
    const val CHANNEL_OFFERS = "madriguera_ofertas"
    const val CHANNEL_MESSAGES = "madriguera_mensajes"
    const val CHANNEL_PAYMENTS = "madriguera_pagos"
    const val CHANNEL_AUCTIONS = "madriguera_subastas"

    /** Deep link interno: tocar una notificación abre la bandeja. */
    const val DEEP_LINK_NOTIFICATIONS = "lamadriguera://notifications"

    fun isFirebaseConfigured(): Boolean =
        BuildConfig.FCM_PROJECT_ID.isNotBlank() &&
            BuildConfig.GOOGLE_API_KEY.isNotBlank() &&
            !BuildConfig.FCM_PROJECT_ID.startsWith("placeholder") &&
            !BuildConfig.GOOGLE_API_KEY.startsWith("placeholder")

    /** Inicializa Firebase (si está configurado) y crea los canales. Idempotente. */
    fun ensureInitialized(context: Context) {
        ensureChannels(context)
        if (!isFirebaseConfigured()) return
        runCatching {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(
                    context,
                    FirebaseOptions.Builder()
                        .setApplicationId(BuildConfig.APPLICATION_ID)
                        .setApiKey(BuildConfig.GOOGLE_API_KEY)
                        .setProjectId(BuildConfig.FCM_PROJECT_ID)
                        .build()
                )
            }
        }
    }

    /**
     * Push realmente usable en ESTE momento: Firebase inicializado y permiso
     * de notificaciones concedido (Android 13+). Sin Play Services el SDK
     * lanza al primer uso; quien llama debe tolerar fallos vía runCatching.
     */
    fun isAvailable(context: Context): Boolean =
        isFirebaseConfigured() && FirebaseApp.getApps(context).isNotEmpty()

    /** El usuario puede recibir avisos del sistema ahora mismo. */
    fun notificationsAllowed(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun messaging(): FirebaseMessaging = FirebaseMessaging.getInstance()

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val specs = listOf(
            CHANNEL_GENERAL to R.string.notif_channel_general,
            CHANNEL_OFFERS to R.string.notif_channel_offers,
            CHANNEL_MESSAGES to R.string.notif_channel_messages,
            CHANNEL_PAYMENTS to R.string.notif_channel_payments,
            CHANNEL_AUCTIONS to R.string.notif_channel_auctions
        )
        specs.forEach { (id, nameRes) ->
            if (manager.getNotificationChannel(id) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        id,
                        context.getString(nameRes),
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply { description = context.getString(R.string.notif_channel_description) }
                )
            }
        }
    }

    /** Canal del sistema según el `type` de `user_notifications`. */
    fun channelFor(type: String?): String = when (type) {
        "oferta_recibida" -> CHANNEL_OFFERS
        "mensaje_nuevo" -> CHANNEL_MESSAGES
        "subasta_ganada" -> CHANNEL_AUCTIONS
        "payment_confirmed", "payment_rejected", "payment_cancelled" -> CHANNEL_PAYMENTS
        else -> CHANNEL_GENERAL
    }

    /** Publica un aviso en la bandeja del sistema (id dedupe = hash del id de BD). */
    fun show(context: Context, notificationId: String, type: String?, title: String, body: String) {
        val tapIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(DEEP_LINK_NOTIFICATIONS))
            .setPackage(context.packageName)
            .apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(Intent.EXTRA_TEXT, notificationId)
            }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, channelFor(type))
            .setSmallIcon(R.drawable.ic_stat_madriguera)
            .setColor(context.getColor(R.color.brand_primary))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
        // Sin permiso (Android 13+) esto es un no-op silencioso: la UI de
        // ajustes pide el permiso y el fallback in-app sigue funcionando.
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(notificationId.hashCode(), builder.build())
        }
    }
}
