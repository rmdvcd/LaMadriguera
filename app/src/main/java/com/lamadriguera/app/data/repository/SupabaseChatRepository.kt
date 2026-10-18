package com.lamadriguera.app.data.repository

import com.lamadriguera.app.core.result.AppError
import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.core.result.getOrNull
import com.lamadriguera.app.core.security.chat.AesGcmCipher
import com.lamadriguera.app.core.security.chat.DisputeEnvelope
import com.lamadriguera.app.core.security.chat.DisputeKeyConfig
import com.lamadriguera.app.core.security.chat.SessionKeyStore
import com.lamadriguera.app.core.security.chat.X25519
import com.lamadriguera.app.data.mapper.decodeBytea
import com.lamadriguera.app.data.mapper.encodeBytea
import com.lamadriguera.app.data.mapper.toDomain
import com.lamadriguera.app.data.remote.supabase.SupabaseJson
import com.lamadriguera.app.data.remote.supabase.row.ConversationKeyRow
import com.lamadriguera.app.data.remote.supabase.row.ConversationRow
import com.lamadriguera.app.data.remote.supabase.row.DisputeTicketRow
import com.lamadriguera.app.data.remote.supabase.row.MessageRow
import com.lamadriguera.app.data.remote.supabase.row.ProfileChatKeyRow
import com.lamadriguera.app.data.remote.supabase.row.UserNotificationRow
import com.lamadriguera.app.domain.model.ChatMessage
import com.lamadriguera.app.domain.model.ChatNotification
import com.lamadriguera.app.domain.model.ChatPayload
import com.lamadriguera.app.domain.model.ConversationSummary
import com.lamadriguera.app.domain.model.DisputeTicket
import com.lamadriguera.app.domain.model.MessageContent
import com.lamadriguera.app.domain.model.MessageExpiry
import com.lamadriguera.app.domain.model.MessageType
import com.lamadriguera.app.domain.repository.ChatRepository
import com.lamadriguera.app.domain.usecase.ImageCompressor
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Implementación del chat cifrado (diseño C) sobre PostgREST + Realtime.
 *
 * Flujo criptográfico por conversación:
 * 1. El que inicia genera en cliente la `session_key` AES-256-GCM y un par
 *    X25519 de disputa. La clave de sesión se envuelve para la pública de
 *    disputa → `conversations.session_key_wrapped`; la privada de disputa se
 *    divide XOR y cada mitad se envuelve a su custodio →
 *    `dispute_key_split_a` (admin general) y `dispute_key_split_b`
 *    (moderador senior). La app jamás recombina esas mitades.
 * 2. Además, la sesión se envuelve para la clave pública de chat de cada
 *    participante (`conversation_keys`), de modo que ambos puedan descifrar
 *    sin tocar el material de disputa.
 * 3. Los mensajes se cifran con AAD `msg:<convId>:<tipo>` y se guardan como
 *    ciphertext en `messages.content_ref`. En reposo el último mensaje del
 *    listado es ciphertext; solo se descifra en memoria al pintarlo.
 *
 * Disputa: [reportConversation] abre el ticket vía RPC; el proceso backend
 * (Edge Function con ambas privadas de custodia) es el único que descifra, y
 * lo registra en `moderation_actions` notificando a ambas partes.
 */
class SupabaseChatRepository @Inject constructor(
    private val client: SupabaseClient,
    private val cipher: AesGcmCipher,
    private val envelope: DisputeEnvelope,
    private val keyConfig: DisputeKeyConfig,
    private val keyStore: SessionKeyStore,
    private val imageCompressor: ImageCompressor
) : ChatRepository {

    private val base64Encoder = Base64.getEncoder()
    private val base64Decoder = Base64.getDecoder()

    // ------------------------------------------------------------------
    // Identidad de chat (par X25519 del dispositivo, publicado en el perfil)
    // ------------------------------------------------------------------

    override suspend fun ensureChatIdentity(): Result<Unit> = request {
        val userId = currentUserIdOrThrow()
        ensureIdentityPair()
        val publicKey = keyStore.identityPublicKey()
            ?: throw AppError.Unknown("No se pudo crear la identidad de chat")
        client.from("profiles").update(
            buildJsonObject { put("chat_public_key", publicKey.encodeBytea()) }
        ) {
            filter { eq("user_id", userId) }
        }
        Unit
    }

    /** Genera y guarda el par de identidad si no existe; devuelve (privada, pública). */
    private fun ensureIdentityPair(): Pair<ByteArray, ByteArray> {
        val privateKey = keyStore.identityPrivateKey()
        val publicKey = keyStore.identityPublicKey()
        if (privateKey != null && publicKey != null) return privateKey to publicKey
        val pair = envelope.generateKeyPair()
        keyStore.putIdentityKeyPair(pair.privateKey, pair.publicKey)
        return pair.privateKey to pair.publicKey
    }

    // ------------------------------------------------------------------
    // Listado de conversaciones
    // ------------------------------------------------------------------

    override suspend fun getConversations(): Result<List<ConversationSummary>> = request {
        val userId = currentUserIdOrThrow()
        val conversationRows = decodeList(
            ConversationRow.serializer(),
            client.from("conversations").select().data
        )
        if (conversationRows.isEmpty()) return@request emptyList()

        val ids = conversationRows.map { it.id }
        val messageRows = decodeList(
            MessageRow.serializer(),
            client.from("messages").select {
                filter { isIn("conversation_id", ids) }
            }.data
        )

        conversationRows.map { row ->
            buildSummary(row, messageRows, userId)
        }.sortedByDescending { it.lastMessage?.createdAt ?: Instant.EPOCH }
    }

    override suspend fun getOrCreateConversation(
        listingId: String,
        otherUserId: String
    ): Result<ConversationSummary> {
        val userId = client.auth.currentUserOrNull()?.id
            ?: return Result.Error(AppError.Unauthorized())
        if (listingId.isBlank() || otherUserId.isBlank()) {
            return Result.Error(AppError.Validation("Datos no válidos"))
        }
        if (otherUserId == userId) {
            return Result.Error(AppError.Validation("No puedes abrir un chat contigo mismo"))
        }

        val existing = request {
            decodeList(
                ConversationRow.serializer(),
                client.from("conversations").select {
                    filter { eq("listing_id", listingId) }
                }.data
            ).firstOrNull { row ->
                (row.participantA == userId && row.participantB == otherUserId) ||
                    (row.participantB == userId && row.participantA == otherUserId)
            }
        }
        when (existing) {
            is Result.Error -> return existing
            is Result.Success -> existing.data?.let { row ->
                return summaryFor(row = row, userId = userId)
            }
        }

        val conversationId = UUID.randomUUID().toString()
        return request {
            val inserted = decodeList(
                ConversationRow.serializer(),
                client.from("conversations").insert(
                    buildJsonObject {
                        put("id", conversationId)
                        put("listing_id", listingId)
                        put("participant_a", userId)
                        put("participant_b", otherUserId)
                        put("reveal_policy", "never")
                    }
                ) { select() }.data
            ).single()
            // Inicializa el material criptográfico del hilo (clave de sesión +
            // envolturas de disputa/custodia/participantes).
            resolveSessionKey(inserted, userId)
            ConversationSummary(
                conversation = inserted.toDomain(),
                otherParticipantId = otherUserId,
                lastMessage = null,
                lastMessagePreview = "",
                unreadCount = 0
            )
        }
    }

    private suspend fun summaryFor(
        row: ConversationRow,
        userId: String
    ): Result<ConversationSummary> = request {
        val messageRows = decodeList(
            MessageRow.serializer(),
            client.from("messages").select {
                filter { eq("conversation_id", row.id) }
                order("created_at", Order.DESCENDING)
            }.data
        )
        buildSummary(row, messageRows, userId)
    }

    private suspend fun buildSummary(
        row: ConversationRow,
        messagesInThread: List<MessageRow>,
        userId: String
    ): ConversationSummary {
        val messages = messagesInThread.filter { it.conversationId == row.id }
        val sessionKey = resolveSessionKey(row, userId)
        val last = messages.maxByOrNull { Instant.parse(it.createdAt) }
        val lastChat = last?.toChatMessage(sessionKey, userId)
        val lastRead = keyStore.lastReadAt(row.id)
        val unread = messages.count { message ->
            message.senderId != userId &&
                (lastRead == null || Instant.parse(message.createdAt).isAfter(lastRead))
        }
        return ConversationSummary(
            conversation = row.toDomain(),
            otherParticipantId = if (row.participantA == userId) row.participantB else row.participantA,
            lastMessage = lastChat,
            lastMessagePreview = previewOf(lastChat),
            unreadCount = unread
        )
    }

    private fun previewOf(message: ChatMessage?): String = when (message?.content) {
        null -> ""
        is MessageContent.Text -> message.content.text.take(PREVIEW_LENGTH)
        is MessageContent.Image -> "[Imagen]"
        is MessageContent.Audio -> "[Audio]"
        is MessageContent.Location -> "[Ubicación]"
        MessageContent.Undecryptable -> "[Mensaje cifrado]"
        MessageContent.Expired -> "[Mensaje autodestruido]"
    }

    // ------------------------------------------------------------------
    // Mensajes
    // ------------------------------------------------------------------

    override suspend fun getMessages(conversationId: String, limit: Int): Result<List<ChatMessage>> {
        val userId = client.auth.currentUserOrNull()?.id
            ?: return Result.Error(AppError.Unauthorized())
        return request {
            val row = fetchConversationOrThrow(conversationId)
            val sessionKey = resolveSessionKey(row, userId)
            val rows = decodeList(
                MessageRow.serializer(),
                client.from("messages").select {
                    filter { eq("conversation_id", conversationId) }
                    order("created_at", Order.DESCENDING)
                    range(0, limit.toLong() - 1)
                }.data
            )
            rows.map { it.toChatMessage(sessionKey, userId) }
                .sortedBy { it.createdAt.toEpochMilli() }
        }
    }

    override suspend fun sendText(
        conversationId: String,
        text: String,
        expiry: MessageExpiry
    ): Result<ChatMessage> {
        if (text.isBlank()) return Result.Error(AppError.Validation("El mensaje está vacío"))
        return sendPayload(
            conversationId = conversationId,
            payload = ChatPayload(t = ChatPayload.KIND_TEXT, text = text),
            type = MessageType.TEXT,
            expiry = expiry
        )
    }

    override suspend fun sendMedia(
        conversationId: String,
        type: MessageType,
        bytes: ByteArray,
        mimeType: String,
        expiry: MessageExpiry
    ): Result<ChatMessage> {
        if (type != MessageType.IMAGE && type != MessageType.AUDIO) {
            return Result.Error(AppError.Validation("Tipo de adjunto no soportado"))
        }
        if (bytes.isEmpty()) return Result.Error(AppError.Validation("El archivo está vacío"))

        val finalBytes: ByteArray
        val finalMime: String
        if (type == MessageType.IMAGE) {
            // Imagen (máx. 1 MB): compresión automática igual que en publicaciones.
            when (val compressed = imageCompressor.compressForListing(bytes, ChatRepository.MAX_MEDIA_BYTES)) {
                is Result.Success -> {
                    finalBytes = compressed.data
                    finalMime = if (finalBytes.contentEquals(bytes)) mimeType else "image/jpeg"
                }
                is Result.Error -> return compressed
            }
        } else {
            // Audio (máx. 1 MB): no se recomprime, se rechaza si excede.
            if (bytes.size > ChatRepository.MAX_MEDIA_BYTES) {
                return Result.Error(AppError.Validation("El audio supera el máximo de 1 MB"))
            }
            finalBytes = bytes
            finalMime = mimeType
        }

        return sendPayload(
            conversationId = conversationId,
            payload = ChatPayload(
                t = if (type == MessageType.IMAGE) ChatPayload.KIND_IMAGE else ChatPayload.KIND_AUDIO,
                mediaB64 = base64Encoder.encodeToString(finalBytes),
                mime = finalMime
            ),
            type = type,
            expiry = expiry
        )
    }

    override suspend fun sendLocation(
        conversationId: String,
        latitude: Double,
        longitude: Double,
        expiry: MessageExpiry
    ): Result<ChatMessage> {
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            return Result.Error(AppError.Validation("Ubicación no válida"))
        }
        return sendPayload(
            conversationId = conversationId,
            payload = ChatPayload(t = ChatPayload.KIND_LOCATION, lat = latitude, lon = longitude),
            type = MessageType.LOCATION,
            expiry = expiry
        )
    }

    private suspend fun sendPayload(
        conversationId: String,
        payload: ChatPayload,
        type: MessageType,
        expiry: MessageExpiry
    ): Result<ChatMessage> {
        val userId = client.auth.currentUserOrNull()?.id
            ?: return Result.Error(AppError.Unauthorized())
        return request {
            val row = fetchConversationOrThrow(conversationId)
            val sessionKey = resolveSessionKey(row, userId)
                ?: throw AppError.Validation(
                    "No se pudo establecer la clave del hilo. Vuelve a intentarlo."
                )

            val plaintext = SupabaseJson.encodeToString(ChatPayload.serializer(), payload)
            val ciphertext = cipher.encrypt(
                sessionKey,
                plaintext.toByteArray(Charsets.UTF_8),
                messageAad(conversationId, type)
            )
            val messageId = UUID.randomUUID().toString()
            val expiresAt = expiry.expiresAtFrom(Instant.now())
            val inserted = decodeList(
                MessageRow.serializer(),
                client.from("messages").insert(
                    buildJsonObject {
                        put("id", messageId)
                        put("conversation_id", conversationId)
                        put("sender_id", userId)
                        put("type", wireType(type))
                        put("content_ref", ciphertext)
                        if (expiresAt != null) put("expires_at", expiresAt.toString())
                    }
                ) { select() }.data
            ).single()
            inserted.toChatMessage(sessionKey, userId)
        }
    }

    override suspend fun markRead(conversationId: String): Result<Unit> = request {
        require(conversationId.isNotBlank()) { "Empty conversation id" }
        keyStore.markConversationRead(conversationId)
        Unit
    }

    override fun observeMessages(conversationId: String): Flow<ChatMessage> = flow {
        val userId = currentUserIdOrThrow()
        val channel = client.realtime.channel("chat:$conversationId")
        val inserts = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
            table = "messages"
            filter("conversation_id", FilterOperator.EQ, conversationId)
        }
        try {
            channel.subscribe()
            inserts.collect { action ->
                val row = runCatching { action.decodeRecord<MessageRow>() }.getOrNull()
                    ?: return@collect
                val sessionKey = fetchConversationOrNull(conversationId)
                    ?.let { resolveSessionKey(it, userId) }
                emit(row.toChatMessage(sessionKey, userId))
            }
        } finally {
            runCatching { client.realtime.removeChannel(channel) }
        }
    }

    // ------------------------------------------------------------------
    // Disputas y notificaciones
    // ------------------------------------------------------------------

    override suspend fun reportConversation(
        conversationId: String,
        reason: String
    ): Result<DisputeTicket> {
        val trimmed = reason.trim()
        when {
            conversationId.isBlank() || trimmed.isEmpty() ->
                return Result.Error(AppError.Validation("Indica el motivo del reporte"))
            trimmed.length > MAX_REPORT_REASON ->
                return Result.Error(AppError.Validation("El motivo es demasiado largo"))
        }
        return request {
            val params = buildJsonObject {
                put("p_conversation_id", conversationId)
                put("p_reason", trimmed)
            }
            val data = client.postgrest.rpc("report_conversation", params).data
            SupabaseJson.decodeFromJsonElement(
                DisputeTicketRow.serializer(),
                SupabaseJson.parseToJsonElement(data)
            ).toDomain()
        }
    }

    override suspend fun getUnreadNotifications(): Result<List<ChatNotification>> = request {
        val userId = currentUserIdOrThrow()
        decodeList(
            UserNotificationRow.serializer(),
            client.from("user_notifications").select {
                filter { eq("user_id", userId) }
                order("created_at", Order.DESCENDING)
                range(0, 49)
            }.data
        ).filter { it.readAt == null }.map { it.toDomain() }
    }

    override suspend fun markNotificationsRead(notificationIds: List<String>): Result<Unit> = request {
        if (notificationIds.isEmpty()) return@request Unit
        val userId = currentUserIdOrThrow()
        client.from("user_notifications").update(
            buildJsonObject { put("read_at", Instant.now().toString()) }
        ) {
            filter {
                eq("user_id", userId)
                isIn("id", notificationIds)
            }
        }
        Unit
    }

    // ------------------------------------------------------------------
    // Criptografía por conversación (creación, resolución y reparación)
    // ------------------------------------------------------------------

    /**
     * Devuelve la session_key plana del hilo si puedo descifrarlo:
     * 1. del cofre local; 2. creándola si el hilo aún no tiene material;
     * 3. desenvolviéndola de `conversation_keys` con mi privada de identidad.
     * Devuelve null si el hilo no es descifrable para mí todavía.
     */
    private suspend fun resolveSessionKey(row: ConversationRow, userId: String): ByteArray? {
        keyStore.sessionKey(row.id)?.let { return it }

        if (row.sessionKeyWrapped == null) {
            // El hilo aún no tiene material criptográfico: lo crea el primer
            // participante que lo use (p. ej. conversaciones heredadas).
            if (userId != row.participantA && userId != row.participantB) return null
            return bootstrapConversationCrypto(row.id, userId).getOrNull().also { key ->
                key?.let { repairPeerWraps(row, it, userId) }
            }
        }

        val identityPrivate = keyStore.identityPrivateKey() ?: ensureIdentityPair().first
        val myWrap = runCatching {
            decodeList(
                ConversationKeyRow.serializer(),
                client.from("conversation_keys").select {
                    filter {
                        eq("conversation_id", row.id)
                        eq("user_id", userId)
                    }
                    limit(1)
                }.data
            ).firstOrNull()
        }.getOrNull() ?: return null

        return runCatching {
            envelope.unwrapSecret(
                blob = myWrap.wrappedKey.decodeBytea(),
                recipientPrivateKey = identityPrivate,
                aad = wrapAad(row.id),
                purpose = DisputeEnvelope.PURPOSE_SESSION_WRAP
            )
        }.getOrNull()?.also { key ->
            keyStore.putSessionKey(row.id, key)
            repairPeerWraps(row, key, userId)
        }
    }

    /**
     * Genera el material del diseño C para el hilo: session_key + par X25519
     * de disputa, y persiste las envolturas (disputa, custodios, mi usuario).
     */
    private suspend fun bootstrapConversationCrypto(
        conversationId: String,
        userId: String
    ): Result<ByteArray> = request {
        val sessionKey = cipher.generateSessionKey()
        val dispute = envelope.generateKeyPair()
        val aad = wrapAad(conversationId)

        val wrappedForDispute = envelope.wrapSecret(
            sessionKey, dispute.publicKey, aad, DisputeEnvelope.PURPOSE_DISPUTE_WRAP
        )
        val (splitA, splitB) = envelope.splitPrivateKey(dispute.privateKey)
        val wrappedSplitA = envelope.wrapSecret(
            splitA, keyConfig.adminCustodianPublicKey, aad, DisputeEnvelope.PURPOSE_CUSTODIAN_WRAP
        )
        val wrappedSplitB = envelope.wrapSecret(
            splitB, keyConfig.seniorModeratorCustodianPublicKey, aad, DisputeEnvelope.PURPOSE_CUSTODIAN_WRAP
        )

        // Solo tiene efecto si nadie ha reclamado antes el hilo (carrera benigna).
        val updated = decodeList(
            ConversationRow.serializer(),
            client.from("conversations").update(
                buildJsonObject {
                    put("session_key_wrapped", wrappedForDispute.encodeBytea())
                    put("dispute_key_split_a", wrappedSplitA.encodeBytea())
                    put("dispute_key_split_b", wrappedSplitB.encodeBytea())
                }
            ) {
                filter { eq("id", conversationId) }
                select()
            }.data
        )
        if (updated.isEmpty()) throw AppError.Conflict("El hilo ya fue inicializado")

        insertParticipantWrap(conversationId, userId, sessionKey)
        keyStore.putSessionKey(conversationId, sessionKey)

        dispute.wipe()
        splitA.wipe()
        splitB.wipe()

        sessionKey
    }

    /** Envuelve la session_key para [userId] si aún no tiene sobre (best-effort). */
    private suspend fun insertParticipantWrap(
        conversationId: String,
        userId: String,
        sessionKey: ByteArray
    ) {
        val publicKey = fetchChatPublicKey(userId) ?: return
        runCatching {
            val wrapped = envelope.wrapSecret(
                sessionKey, publicKey, wrapAad(conversationId), DisputeEnvelope.PURPOSE_SESSION_WRAP
            )
            client.from("conversation_keys").insert(
                buildJsonObject {
                    put("conversation_id", conversationId)
                    put("user_id", userId)
                    put("wrapped_key", wrapped.encodeBytea())
                }
            )
        } // 23505 (ya existe) u offline: se reintenta en la próxima apertura.
    }

    /**
     * Si el otro participante no tiene sobre propio todavía, lo creo con su
     * clave pública para que pueda descifrar al entrar (best-effort).
     */
    private suspend fun repairPeerWraps(row: ConversationRow, sessionKey: ByteArray, userId: String) {
        val peerId = if (row.participantA == userId) row.participantB else row.participantA
        val hasWrap = runCatching {
            decodeList(
                ConversationKeyRow.serializer(),
                client.from("conversation_keys").select {
                    filter {
                        eq("conversation_id", row.id)
                        eq("user_id", peerId)
                    }
                    limit(1)
                }.data
            ).isNotEmpty()
        }.getOrDefault(true)
        if (!hasWrap) insertParticipantWrap(row.id, peerId, sessionKey)
    }

    private suspend fun fetchChatPublicKey(userId: String): ByteArray? = runCatching {
        decodeList(
            ProfileChatKeyRow.serializer(),
            client.from("profiles").select {
                filter { eq("user_id", userId) }
                limit(1)
            }.data
        ).firstOrNull()?.chatPublicKey?.decodeBytea()
            ?.takeIf { it.size == X25519.KEY_BYTES }
    }.getOrNull()

    // ------------------------------------------------------------------
    // Conversión ciphertext → vista en memoria
    // ------------------------------------------------------------------

    private fun MessageRow.toChatMessage(sessionKey: ByteArray?, userId: String): ChatMessage {
        val expires = expiresAt?.let(Instant::parse)
        val expired = expires?.let { !it.isAfter(Instant.now()) } == true
        val content = when {
            expired -> MessageContent.Expired
            sessionKey == null -> MessageContent.Undecryptable
            else -> decryptContent(sessionKey)
        }
        return ChatMessage(
            id = id,
            conversationId = conversationId,
            senderId = senderId,
            isMine = senderId == userId,
            type = type,
            content = content,
            expiresAt = expires,
            reported = reported,
            createdAt = Instant.parse(createdAt)
        )
    }

    private fun MessageRow.decryptContent(sessionKey: ByteArray): MessageContent = runCatching {
        val plaintext = cipher.decrypt(sessionKey, contentRef, messageAad(conversationId, type))
        val payload = SupabaseJson.decodeFromString(
            ChatPayload.serializer(),
            plaintext.toString(Charsets.UTF_8)
        )
        payload.toMessageContent()
    }.getOrDefault(MessageContent.Undecryptable)

    private fun ChatPayload.toMessageContent(): MessageContent = when (t) {
        ChatPayload.KIND_TEXT -> text?.let(MessageContent::Text) ?: MessageContent.Undecryptable
        ChatPayload.KIND_IMAGE -> mediaB64?.let {
            MessageContent.Image(base64Decoder.decode(it), mime ?: "image/jpeg")
        } ?: MessageContent.Undecryptable
        ChatPayload.KIND_AUDIO -> mediaB64?.let {
            MessageContent.Audio(base64Decoder.decode(it), mime ?: "audio/mp4")
        } ?: MessageContent.Undecryptable
        ChatPayload.KIND_LOCATION -> if (lat != null && lon != null) {
            MessageContent.Location(lat, lon)
        } else {
            MessageContent.Undecryptable
        }
        else -> MessageContent.Undecryptable
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private suspend fun fetchConversationOrNull(conversationId: String): ConversationRow? =
        runCatching {
            decodeList(
                ConversationRow.serializer(),
                client.from("conversations").select {
                    filter { eq("id", conversationId) }
                    limit(1)
                }.data
            ).singleOrNull()
        }.getOrNull()

    private suspend fun fetchConversationOrThrow(conversationId: String): ConversationRow =
        fetchConversationOrNull(conversationId)
            ?: throw AppError.NotFound("Conversación no encontrada")

    private fun currentUserIdOrThrow(): String =
        client.auth.currentUserOrNull()?.id ?: throw AppError.Unauthorized()

    private fun <T> decodeList(serializer: KSerializer<T>, data: String): List<T> =
        SupabaseJson.decodeFromString(ListSerializer(serializer), data)

    /** AAD que liga cada ciphertext a su conversación y tipo de mensaje. */
    private fun messageAad(conversationId: String, type: MessageType): ByteArray =
        "msg:$conversationId:${wireType(type)}".toByteArray(Charsets.UTF_8)

    /** AAD que liga las envolturas de clave a su conversación. */
    private fun wrapAad(conversationId: String): ByteArray =
        "wrap:$conversationId".toByteArray(Charsets.UTF_8)

    private fun wireType(type: MessageType): String = when (type) {
        MessageType.TEXT -> "text"
        MessageType.IMAGE -> "image"
        MessageType.AUDIO -> "audio"
        MessageType.LOCATION -> "location"
    }

    private companion object {
        const val PREVIEW_LENGTH = 120
        const val MAX_REPORT_REASON = 1000
    }
}
