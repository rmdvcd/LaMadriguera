package com.lamadriguera.app.data.mapper

import com.lamadriguera.app.domain.model.ModerationAction
import com.lamadriguera.app.data.remote.supabase.row.ModerationActionRow
import java.time.Instant

fun ModerationActionRow.toDomain(): ModerationAction = ModerationAction(
    id = id,
    targetType = targetType,
    targetId = targetId,
    source = source,
    category = category,
    severity = severity,
    action = action,
    reason = reason,
    actorId = actorId,
    createdAt = Instant.parse(createdAt),
    evidence = evidence
)

fun ModerationAction.toRow(): ModerationActionRow = ModerationActionRow(
    id = id,
    targetType = targetType,
    targetId = targetId,
    source = source,
    category = category,
    severity = severity,
    action = action,
    reason = reason,
    actorId = actorId,
    createdAt = createdAt.toString(),
    evidence = evidence
)
