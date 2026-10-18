package com.lamadriguera.app.data.mapper

import com.lamadriguera.app.domain.model.BettingGroup
import com.lamadriguera.app.data.remote.supabase.row.BettingGroupRow
import java.time.Instant

fun BettingGroupRow.toDomain(): BettingGroup = BettingGroup(
    id = id,
    ownerId = ownerId,
    name = name,
    topic = topic,
    visibility = visibility,
    status = status,
    createdAt = Instant.parse(createdAt),
    rules = rules
)

fun BettingGroup.toRow(): BettingGroupRow = BettingGroupRow(
    id = id,
    ownerId = ownerId,
    name = name,
    topic = topic,
    visibility = visibility,
    status = status,
    createdAt = createdAt.toString(),
    rules = rules
)
