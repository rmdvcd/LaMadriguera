package com.lamadriguera.app.data.mapper

import com.lamadriguera.app.domain.model.BettingGroupMember
import com.lamadriguera.app.data.remote.supabase.row.BettingGroupMemberRow
import java.time.Instant

fun BettingGroupMemberRow.toDomain(): BettingGroupMember = BettingGroupMember(
    id = id,
    groupId = groupId,
    userId = userId,
    createdAt = Instant.parse(createdAt)
)

fun BettingGroupMember.toRow(): BettingGroupMemberRow = BettingGroupMemberRow(
    id = id,
    groupId = groupId,
    userId = userId,
    createdAt = createdAt.toString()
)
