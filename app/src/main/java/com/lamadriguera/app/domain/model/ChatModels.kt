package com.lamadriguera.app.domain.model

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Modelos del chat cifrado (diseño C).
 *
 * En reposo los mensajes son ciphertext (`messages.content_ref`); estos
 * modelos representan la vista descifrada que existe SOLO en memoria.
 */

/** Caducidad opcional de un mensaje: 24 h, 7 días o nunca. */
enum class MessageExpiry(val hours: Long?) {
    HOURS_24(24),
    DAYS_7(24 * 7),
    NEVER(null);

    fun expiresAtFrom(now: Instant): Instant? =
        hours?.let { now.plusSeconds(it * 3600) }
}

/** Contenido ya descifrado (o estado de no disponible) de un mensaje. */
sealed interface MessageContent {

    data class Text(val text: String) : MessageContent

    /** Multimedia descifrada; existe solo en memoria, nunca en disco. */
    data class Image(val bytes: ByteArray, val mimeType: String) : MessageContent {
        override fun equals(other: Any?): Boolean =
            other is Image && bytes.contentEquals(other.bytes) && mimeType == other.mimeType

        override fun hashCode(): Int = 31 * bytes.contentHashCode() + mimeType.hashCode()
    }

    data class Audio(val bytes: ByteArray, val mimeType: String) : MessageContent {
        override fun equals(other: Any?): Boolean =
            other is Audio && bytes.contentEquals(other.bytes) && mimeType == other.mimeType

        override fun hashCode(): Int = 31 * bytes.contentHashCode() + mimeType.hashCode()
    }

    data class Location(val latitude: Double, val longitude: Double) : MessageContent

    /**
     * No se pudo descifrar: falta la clave de sesión o el ciphertext no
     * autentica. En reposo SIEMPRE se ve así para quien no tenga la clave.
     */
    data object Undecryptable : MessageContent

    /** Mensaje autodestruido: expiró; el servidor elimina su content_ref. */
    data object Expired : MessageContent
}

/** Vista descifrada en memoria de una fila de `messages`. */
data class ChatMessage(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val isMine: Boolean,
    val type: MessageType,
    val content: MessageContent,
    val expiresAt: Instant?,
    val reported: Boolean,
    val createdAt: Instant
) {
    fun hasExpired(now: Instant): Boolean = expiresAt?.let { !it.isAfter(now) } == true
}

/**
 * Hilo de la lista de conversaciones: metadatos + vista previa del último
 * mensaje + badge de no leídos. La vista previa se descifra solo en memoria;
 * en la base de datos sigue siendo ciphertext.
 */
data class ConversationSummary(
    val conversation: Conversation,
    val otherParticipantId: String,
    val lastMessage: ChatMessage?,
    val lastMessagePreview: String,
    val unreadCount: Int
) {
    val inDispute: Boolean get() = conversation.status == STATUS_DISPUTED

    companion object {
        const val STATUS_DISPUTED = "disputed"
        const val STATUS_ACTIVE = "active"
        const val STATUS_CLOSED = "closed"
    }
}

/** Ticket de disputa abierto al reportar una conversación. */
@Serializable
enum class DisputeStatus {
    @SerialName("open") OPEN,
    @SerialName("approved") APPROVED,
    @SerialName("decrypted") DECRYPTED,
    @SerialName("rejected") REJECTED
}

data class DisputeTicket(
    val id: String,
    val conversationId: String,
    val openedBy: String,
    val reason: String,
    val status: DisputeStatus,
    val approvedBy: String?,
    val approvedAt: Instant?,
    val decryptedAt: Instant?,
    val createdAt: Instant
)

/** Notificación del sistema de disputas (p. ej. "tu hilo fue descifrado"). */
data class ChatNotification(
    val id: String,
    val type: String,
    val title: String,
    val body: String,
    val readAt: Instant?,
    val createdAt: Instant
)

/**
 * Formato del plaintext cifrado dentro de `content_ref` (protocolo del chat v1).
 * Es lo que AES-GCM protege; la columna solo ve ciphertext.
 */
@Serializable
data class ChatPayload(
    val v: Int = 1,
    /** "text" | "image" | "audio" | "location" */
    val t: String,
    val text: String? = null,
    @SerialName("media_b64") val mediaB64: String? = null,
    val mime: String? = null,
    val lat: Double? = null,
    val lon: Double? = null
) {
    companion object {
        const val KIND_TEXT = "text"
        const val KIND_IMAGE = "image"
        const val KIND_AUDIO = "audio"
        const val KIND_LOCATION = "location"
    }
}
