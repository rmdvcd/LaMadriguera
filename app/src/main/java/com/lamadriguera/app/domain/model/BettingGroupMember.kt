package com.lamadriguera.app.domain.model

import java.time.Instant

/** Domain model; money uses exact decimal arithmetic, dates use java.time. */
data class BettingGroupMember(
    val id: String,
    val groupId: String,
    val userId: String,
    val createdAt: Instant
)
