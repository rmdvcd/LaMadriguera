package com.lamadriguera.app.data.remote.supabase.row

import com.lamadriguera.app.domain.model.DisputeStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** PostgREST wire format de `dispute_tickets` (tickets de disputa del chat). */
@Serializable
data class DisputeTicketRow(
    val id: String,
    @SerialName("conversation_id")
    val conversationId: String,
    @SerialName("opened_by")
    val openedBy: String,
    val reason: String,
    val status: DisputeStatus = DisputeStatus.OPEN,
    @SerialName("approved_by")
    val approvedBy: String? = null,
    @SerialName("approved_at")
    val approvedAt: String? = null,
    @SerialName("decrypted_at")
    val decryptedAt: String? = null,
    @SerialName("created_at")
    val createdAt: String
)
