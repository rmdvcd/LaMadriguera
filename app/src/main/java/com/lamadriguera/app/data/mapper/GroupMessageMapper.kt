package com.lamadriguera.app.data.mapper

import com.lamadriguera.app.data.remote.supabase.row.GroupMessageRow
import com.lamadriguera.app.domain.model.GroupMessage
import java.time.Instant

fun GroupMessageRow.toDomain(): GroupMessage = GroupMessage(
    id = id,
    groupId = groupId,
    senderId = senderId,
    body = body,
    hiddenAt = hiddenAt?.let(Instant::parse),
    hiddenBy = hiddenBy,
    hiddenReason = hiddenReason,
    createdAt = Instant.parse(createdAt)
)

fun GroupMessage.toRow(): GroupMessageRow = GroupMessageRow(
    id = id,
    groupId = groupId,
    senderId = senderId,
    body = body,
    hiddenAt = hiddenAt?.toString(),
    hiddenBy = hiddenBy,
    hiddenReason = hiddenReason,
    createdAt = createdAt.toString()
)
