package com.lamadriguera.app.data.repository

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.android.gms.tasks.Tasks
import com.lamadriguera.app.core.push.PushSupport
import com.lamadriguera.app.core.result.AppError
import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.data.mapper.toDomain
import com.lamadriguera.app.data.remote.supabase.SupabaseJson
import com.lamadriguera.app.data.remote.supabase.row.UserNotificationRow
import com.lamadriguera.app.data.remote.supabase.row.UserNotificationSettingsRow
import com.lamadriguera.app.data.remote.supabase.row.UserPushTokenRow
import com.lamadriguera.app.domain.model.ChatNotification
import com.lamadriguera.app.domain.model.NotificationPreferences
import com.lamadriguera.app.domain.repository.NotificationsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Push y preferencias de notificación sobre Supabase (009_push_notifications).
 *
 * Tokens en `user_push_tokens` (RLS: solo el dueño escribe los suyos). El
 * token físico es UNIQUE: si el dispositivo cambió de cuenta, el upsert
 * on-conflict no puede reclamar la fila ajena; se limpia la propia y se
 * reintenta, y el backend (push-dispatch) purga además los tokens que FCM
 * reporte como no registrados.
 *
 * El token leído de Firebase se cachea en DataStore para poder borrarlo del
 * backend en el logout sin depender de Play Services en ese momento.
 */
@Singleton
class SupabaseNotificationsRepository @Inject constructor(
    private val client: SupabaseClient,
    @ApplicationContext private val appContext: Context,
    private val dataStore: DataStore<Preferences>
) : NotificationsRepository {

    private val userId: String get() = client.auth.currentUserOrNull()?.id ?: throw AppError.Unauthorized()

    override fun isPushCapable(): Boolean =
        PushSupport.isAvailable(appContext)

    override suspend fun registerPushToken(): Result<String?> = request {
        if (!isPushCapable()) return@request null
        val account = client.auth.currentUserOrNull()?.id ?: return@request null
        val token = fetchFcmToken() ?: return@request null
        rememberToken(token)
        val payload: kotlinx.serialization.json.JsonObject = SupabaseJson.encodeToJsonElement(
            UserPushTokenRow.serializer(),
            UserPushTokenRow(userId = account, token = token)
        ).jsonObject
        try {
            client.from(TABLE_TOKENS).upsert(payload) { onConflict = "token" }
        } catch (conflict: Exception) {
            // El token pertenece a otra cuenta en este dispositivo:
            // limpiar las filas propias y volver a intentar una vez.
            client.from(TABLE_TOKENS).delete { filter { eq("user_id", account) } }
            client.from(TABLE_TOKENS).insert(payload)
        }
        token
    }

    override suspend fun unregisterPushToken(): Result<Unit> = request {
        val token = cachedToken()
        if (token != null) {
            client.from(TABLE_TOKENS).delete { filter { eq("token", token) } }
        } else runCatching { userId }.getOrNull()?.let { account ->
            client.from(TABLE_TOKENS).delete { filter { eq("user_id", account) } }
        }
        dataStore.edit { it.remove(KEY_PUSH_TOKEN) }
        Unit
    }

    override suspend fun preferences(): Result<NotificationPreferences> = request {
        val account = userId
        val rows = decodeList(
            UserNotificationSettingsRow.serializer(),
            client.from(TABLE_SETTINGS).select {
                filter { eq("user_id", account) }
            }.data
        )
        val row = rows.firstOrNull() ?: decodeList(
            // Las cuentas anteriores a 009 no traen fila: se crea con defaults
            // (trigger + backfill en la migración cubren el resto de los casos).
            UserNotificationSettingsRow.serializer(),
            client.from(TABLE_SETTINGS).upsert(
                buildJsonObject { put("user_id", account) }
            ) { select() }.data
        ).single()
        row.toDomain()
    }

    override suspend fun savePreferences(preferences: NotificationPreferences): Result<Unit> = request {
        val account = userId
        val payload = buildJsonObject {
            put("user_id", account)
            put("push_enabled", preferences.pushEnabled)
            put("notify_offers", preferences.notifyOffers)
            put("notify_messages", preferences.notifyMessages)
            put("notify_payments", preferences.notifyPayments)
            put("notify_auctions", preferences.notifyAuctions)
            put("telegram_enabled", preferences.telegramEnabled)
            preferences.normalizedTelegramChatId
                ?.let { put("telegram_chat_id", it) }
                ?: putNull("telegram_chat_id")
            put("updated_at", Instant.now().toString())
        }
        client.from(TABLE_SETTINGS).upsert(payload) { onConflict = "user_id" }
        Unit
    }

    override suspend fun unreadNotifications(): Result<List<ChatNotification>> = request {
        val account = userId
        decodeList(
            UserNotificationRow.serializer(),
            client.from(TABLE_NOTIFICATIONS).select {
                filter { eq("user_id", account) }
                order("created_at", Order.DESCENDING)
                range(0, 49)
            }.data
        ).filter { it.readAt == null }.map { it.toDomain() }
    }

    override suspend fun markNotificationsRead(ids: List<String>): Result<Unit> = request {
        if (ids.isEmpty()) return@request Unit
        val account = userId
        client.from(TABLE_NOTIFICATIONS).update(
            buildJsonObject { put("read_at", Instant.now().toString()) }
        ) {
            filter {
                eq("user_id", account)
                isIn("id", ids)
            }
        }
        Unit
    }

    override fun notificationEvents(userId: String): Flow<ChatNotification> = flow {
        val channel = client.realtime.channel("notifications:$userId")
        val inserts = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
            table = TABLE_NOTIFICATIONS
            filter("user_id", FilterOperator.EQ, userId)
        }
        try {
            channel.subscribe()
            inserts.collect { action ->
                val row = runCatching { action.decodeRecord<UserNotificationRow>() }.getOrNull()
                    ?: return@collect
                emit(row.toDomain())
            }
        } finally {
            runCatching { client.realtime.removeChannel(channel) }
        }
    }

    // ------------------------------------------------------------------
    // Token FCM (bloqueante sobre el thread actual: se invoca en IO)
    // ------------------------------------------------------------------

    /** Blocking Task API de Firebase: se ejecuta SIEMPRE en IO. */
    @VisibleForTesting
    internal suspend fun fetchFcmToken(): String? =
        runCatching {
            withContext(Dispatchers.IO) { Tasks.await(PushSupport.messaging().token).token }
        }.getOrNull()

    private suspend fun rememberToken(token: String) {
        dataStore.edit { it[KEY_PUSH_TOKEN] = token }
    }

    private suspend fun cachedToken(): String? = dataStore.data.first()[KEY_PUSH_TOKEN]

    private fun <T> decodeList(serializer: KSerializer<T>, data: String): List<T> =
        SupabaseJson.decodeFromString(ListSerializer(serializer), data)

    private companion object {
        const val TABLE_TOKENS = "user_push_tokens"
        const val TABLE_SETTINGS = "user_notification_settings"
        const val TABLE_NOTIFICATIONS = "user_notifications"
        val KEY_PUSH_TOKEN = stringPreferencesKey("fcm_device_token")
    }
}
