package com.lamadriguera.app.domain.model

import java.time.Instant
import java.math.BigDecimal

/** Domain model; money uses exact decimal arithmetic, dates use java.time. */
data class Bet(
    val id: String,
    val groupId: String,
    val creatorId: String,
    val acceptorId: String? = null,
    val description: String,
    val stakeAmount: BigDecimal,
    val stakeCurrency: String = "CUP",
    val odds: BigDecimal = BigDecimal.ONE,
    val status: BetStatus = BetStatus.OPEN,
    val settledBy: String? = null,
    val settledAt: Instant? = null,
    val evidenceUrl: String? = null,
    val createdAt: Instant
)
