package com.lamadriguera.app.data.mapper

import com.lamadriguera.app.data.remote.supabase.row.BetEventRow
import com.lamadriguera.app.data.remote.supabase.row.BetRow
import com.lamadriguera.app.data.remote.supabase.row.BettingControlsRow
import com.lamadriguera.app.domain.model.Bet
import com.lamadriguera.app.domain.model.BetEvent
import com.lamadriguera.app.domain.model.BettingControls
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
    evidenceRef = evidenceRef,
    evidenceText = evidenceText,
    acceptedAt = acceptedAt?.let(Instant::parse),
    decisionBy = decisionBy,
    decisionNote = decisionNote,
    createdAt = Instant.parse(createdAt),
    hiddenAt = hiddenAt?.let(Instant::parse),
    hiddenBy = hiddenBy,
    hiddenReason = hiddenReason
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
    evidenceRef = evidenceRef,
    evidenceText = evidenceText,
    acceptedAt = acceptedAt?.toString(),
    decisionBy = decisionBy,
    decisionNote = decisionNote,
    createdAt = createdAt.toString(),
    hiddenAt = hiddenAt?.toString(),
    hiddenBy = hiddenBy,
    hiddenReason = hiddenReason
)

fun BetEventRow.toDomain(): BetEvent = BetEvent(
    id = id,
    betId = betId,
    actorId = actorId,
    eventType = eventType,
    outcome = outcome,
    evidenceText = evidenceText,
    evidenceRef = evidenceRef,
    decisionNote = decisionNote,
    createdAt = Instant.parse(createdAt)
)

fun BetEvent.toRow(): BetEventRow = BetEventRow(
    id = id,
    betId = betId,
    actorId = actorId,
    eventType = eventType,
    outcome = outcome,
    evidenceText = evidenceText,
    evidenceRef = evidenceRef,
    decisionNote = decisionNote,
    createdAt = createdAt.toString()
)

fun BettingControlsRow.toDomain(): BettingControls = BettingControls(
    userId = userId,
    dailyLimitCup = dailyLimitCup,
    selfExcludedUntil = selfExcludedUntil?.let(Instant::parse),
    updatedAt = Instant.parse(updatedAt)
)

fun BettingControls.toRow(): BettingControlsRow = BettingControlsRow(
    userId = userId,
    dailyLimitCup = dailyLimitCup,
    selfExcludedUntil = selfExcludedUntil?.toString(),
    updatedAt = updatedAt.toString()
)
