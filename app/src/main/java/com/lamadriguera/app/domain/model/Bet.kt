package com.lamadriguera.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Bet(
    val id: String,
    val creatorId: String,
    val opponentId: String? = null,
    val title: String,
    val description: String,
    val stake: Double,
    val currency: String = "CUP",
    val odds: Double? = null,
    val status: BetStatus = BetStatus.OPEN
)

enum class BetStatus { OPEN, MATCHED, SETTLED, DISPUTED, CANCELLED }
