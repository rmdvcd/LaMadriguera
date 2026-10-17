package com.lamadriguera.app.data.mapper

import com.lamadriguera.app.data.remote.supabase.row.DisputeTicketRow
import com.lamadriguera.app.data.remote.supabase.row.UserNotificationRow
import com.lamadriguera.app.domain.model.ChatNotification
import com.lamadriguera.app.domain.model.DisputeTicket
import java.time.Instant

fun DisputeTicketRow.toDomain(): DisputeTicket = DisputeTicket(
    id = id,
    conversationId = conversationId,
    openedBy = openedBy,
    reason = reason,
    status = status,
    approvedBy = approvedBy,
    approvedAt = approvedAt?.let(Instant::parse),
    decryptedAt = decryptedAt?.let(Instant::parse),
    createdAt = Instant.parse(createdAt)
)

fun UserNotificationRow.toDomain(): ChatNotification = ChatNotification(
    id = id,
    type = type,
    title = title,
    body = body,
    readAt = readAt?.let(Instant::parse),
    createdAt = Instant.parse(createdAt)
)
