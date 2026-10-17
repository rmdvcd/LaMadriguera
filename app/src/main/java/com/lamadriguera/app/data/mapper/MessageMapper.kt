package com.lamadriguera.app.data.mapper

import com.lamadriguera.app.domain.model.Message
import com.lamadriguera.app.data.remote.supabase.row.MessageRow
import java.time.Instant

fun MessageRow.toDomain(): Message = Message(
    id = id,
    conversationId = conversationId,
    senderId = senderId,
    type = type,
    contentRef = contentRef,
    expiresAt = expiresAt?.let(Instant::parse),
    reported = reported,
    createdAt = Instant.parse(createdAt)
)

fun Message.toRow(): MessageRow = MessageRow(
    id = id,
    conversationId = conversationId,
    senderId = senderId,
    type = type,
    contentRef = contentRef,
    expiresAt = expiresAt?.toString(),
    reported = reported,
    createdAt = createdAt.toString()
)
