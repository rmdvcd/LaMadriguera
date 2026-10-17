package com.lamadriguera.app.domain

import com.lamadriguera.app.data.remote.supabase.SupabaseJson
import com.lamadriguera.app.domain.model.ChatPayload
import com.lamadriguera.app.domain.model.ConversationSummary
import com.lamadriguera.app.domain.model.MessageExpiry
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

/**
 * Modelos del chat: caducidad (24 h / 7 d / nunca), marcadores de disputa y
 * protocolo del payload cifrado (lo que AES-GCM protege dentro de content_ref).
 */
class ChatModelsTest {

    private val now: Instant = Instant.parse("2026-10-17T12:00:00Z")

    @Test
    fun expiryComputesExpiresAtOrNever() {
        assertEquals(
            now.plusSeconds(24 * 3600),
            MessageExpiry.HOURS_24.expiresAtFrom(now)
        )
        assertEquals(
            now.plusSeconds(7 * 24 * 3600),
            MessageExpiry.DAYS_7.expiresAtFrom(now)
        )
        assertNull(MessageExpiry.NEVER.expiresAtFrom(now))
    }

    @Test
    fun chatPayloadWireFormatIsStable() {
        val payload = ChatPayload(t = ChatPayload.KIND_IMAGE, mediaB64 = "QUJD", mime = "image/jpeg")
        val json = SupabaseJson.encodeToString(ChatPayload.serializer(), payload)
        val decoded = SupabaseJson.decodeFromString(ChatPayload.serializer(), json)
        assertEquals(payload, decoded)
        assertTrue(json.contains("\"media_b64\":\"QUJD\""))
        assertTrue(json.contains("\"t\":\"image\""))
    }

    @Test
    fun payloadToleratesMissingOptionalFields() {
        val decoded = SupabaseJson.decodeFromString(
            ChatPayload.serializer(),
            """{"v":1,"t":"location","lat":23.13,"lon":-82.38}"""
        )
        assertEquals(ChatPayload.KIND_LOCATION, decoded.t)
        assertEquals(23.13, decoded.lat!!, 0.0001)
        assertNull(decoded.text)
    }

    @Test
    fun conversationSummaryDisputeMarkers() {
        val summary = ConversationSummary(
            conversation = com.lamadriguera.app.domain.model.Conversation(
                id = "c", listingId = "l", participantA = "a", participantB = "b",
                status = ConversationSummary.STATUS_DISPUTED
            ),
            otherParticipantId = "b",
            lastMessage = null,
            lastMessagePreview = "",
            unreadCount = 3
        )
        assertTrue(summary.inDispute)
        val calm = summary.copy(
            conversation = summary.conversation.copy(status = ConversationSummary.STATUS_ACTIVE)
        )
        assertFalse(calm.inDispute)
        assertEquals(3, summary.unreadCount)
    }
}
