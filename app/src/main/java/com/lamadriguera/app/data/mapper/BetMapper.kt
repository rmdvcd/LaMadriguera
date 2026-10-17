package com.lamadriguera.app.data.mapper

import com.lamadriguera.app.domain.model.Bet
import com.lamadriguera.app.data.remote.supabase.row.BetRow
import java.time.Instant

fun BetRow.toDomain(): Bet = Bet(
    id = id,
    groupId = groupId,
    creatorId = creatorId,
    acceptorId = acceptorId,
    description = description,
    stakeAmount = stakeAmount,
    stakeCurrency = stakeCurrency,
    odds = odds,
    status = status,
    settledBy = settledBy,
    settledAt = settledAt?.let(Instant::parse),
    evidenceUrl = evidenceUrl,
    createdAt = Instant.parse(createdAt)
)

fun Bet.toRow(): BetRow = BetRow(
    id = id,
    groupId = groupId,
    creatorId = creatorId,
    acceptorId = acceptorId,
    description = description,
    stakeAmount = stakeAmount,
    stakeCurrency = stakeCurrency,
    odds = odds,
    status = status,
    settledBy = settledBy,
    settledAt = settledAt?.toString(),
    evidenceUrl = evidenceUrl,
    createdAt = createdAt.toString()
)
